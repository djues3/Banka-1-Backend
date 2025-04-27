package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CustomerDTO;
import com.banka1.banking.dto.InternalTransferDTO;
import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.dto.NotificationDTO;
import com.banka1.banking.models.*;
import com.banka1.banking.models.Currency;
import com.banka1.banking.models.helper.CurrencyType;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.models.helper.TransferStatus;
import com.banka1.banking.models.helper.TransferType;
import com.banka1.banking.repository.*;
import com.banka1.common.listener.MessageHelper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class TransferService {

    private final AccountRepository accountRepository;

    private final TransferRepository transferRepository;

    private final CurrencyRepository currencyRepository;

    private final TransactionRepository transactionRepository;

    private final JmsTemplate jmsTemplate;

    private final MessageHelper messageHelper;

    private final String destinationEmail;

    private final UserServiceCustomer userServiceCustomer;

    private final ExchangeService exchangeService;

    private final OtpTokenService otpTokenService;

    private final BankAccountUtils bankAccountUtils;
    private final ReceiverService receiverService;

    private final InterbankService interbankService;
    private final InterbankConfig config;


    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository, TransactionRepository transactionRepository, CurrencyRepository currencyRepository, JmsTemplate jmsTemplate, MessageHelper messageHelper, @Value("${destination.email}") String destinationEmail, UserServiceCustomer userServiceCustomer, ExchangeService exchangeService, OtpTokenService otpTokenService, BankAccountUtils bankAccountUtils, ReceiverService receiverService, InterbankService interbankService, InterbankConfig config) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.transactionRepository = transactionRepository;
        this.currencyRepository = currencyRepository;
        this.jmsTemplate = jmsTemplate;
        this.messageHelper = messageHelper;
        this.destinationEmail = destinationEmail;
        this.userServiceCustomer = userServiceCustomer;
        this.exchangeService = exchangeService;
        this.otpTokenService = otpTokenService;
        this.bankAccountUtils = bankAccountUtils;
        this.receiverService = receiverService;
        this.interbankService = interbankService;
        this.config = config;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public String processTransfer(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        System.out.println("Transfer type: " + transfer.getType());
        return switch (transfer.getType()) {
            case FOREIGN_BANK -> processForeignBankTransfer(transferId);
            case INTERNAL, EXCHANGE -> processInternalTransfer(transferId);
            case EXTERNAL, FOREIGN -> processExternalTransfer(transferId);
        };
    }

    /**
     * Completes a currency exchange transfer between two accounts with different currencies.
     * This method is called after the initial amount has been deducted from the source account.
     *
     * @param transfer    The transfer details
     * @param fromAccount The source account
     * @param toAccount   The destination account
     * @return Map containing exchange details
     */
    @Transactional
    protected Map<String, Object> performCurrencyExchangeTransfer(
            Transfer transfer,
            Account fromAccount,
            Account toAccount
    ) {
        // Validate inputs
        if (transfer.getAmount() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        CurrencyType fromCurrencyType = fromAccount.getCurrencyType();
        CurrencyType toCurrencyType = toAccount.getCurrencyType();

        if (fromCurrencyType == CurrencyType.RSD) {
            log.debug("Performing RSD to Foreign exchange");
            return performRsdToForeign(transfer.getAmount(), fromAccount, toAccount);
        } else if (toCurrencyType == CurrencyType.RSD) {
            log.debug("Performing Foreign to RSD exchange");
            return performForeignToRsd(transfer.getAmount(), fromAccount, toAccount);
        }
        log.debug("Performing Foreign to Foreign exchange");
        return performForeignToForeignExchange(transfer, fromAccount, toAccount);
    }

    /**
     * Performs a foreign-to-foreign currency exchange via RSD as an intermediate currency.
     * Example: Customer exchanges 100 EUR for USD
     * 1) Transfer 100 EUR from customer's account to bank's EUR account
     * 2) Convert EUR to RSD (applying exchange fee)
     * 3) Convert RSD to USD (applying exchange fee)
     * 4) Transfer resulting USD from bank's USD account to customer's account
     * 5) "Spawn" exchange fees on bank's RSD account
     */
    private Map<String, Object> performForeignToForeignExchange(
            Transfer transfer,
            Account fromAccount,
            Account toAccount
    ) {
        Currency rsd = currencyRepository.getByCode(CurrencyType.RSD);
        Currency fromCurrency = currencyRepository.getByCode(fromAccount.getCurrencyType());
        Currency toCurrency = currencyRepository.getByCode(toAccount.getCurrencyType());
        CustomerDTO receiver = userServiceCustomer.getCustomerById(toAccount.getOwnerID());

        Account fromCurrencyBankAccount = bankAccountUtils.getBankAccountForCurrency(fromAccount.getCurrencyType());
        Account rsdBankAccount = bankAccountUtils.getBankAccountForCurrency(CurrencyType.RSD);
        Account toCurrencyBankAccount = bankAccountUtils.getBankAccountForCurrency(toAccount.getCurrencyType());

        Map<String, Object> firstExchange = exchangeService.calculatePreviewExchangeAutomatic(
                fromAccount.getCurrencyType().toString(),
                "RSD",
                transfer.getAmount()
        );
        Double firstExchangedAmount = (Double) firstExchange.get("finalAmount");
        Double firstExchangeProvision = (Double) firstExchange.get("provision");
        Double firstExchangeRate = (Double) firstExchange.get("exchangeRate");

        Map<String, Object> secondExchange = exchangeService.calculatePreviewExchangeAutomatic(
                "RSD",
                toAccount.getCurrencyType().toString(),
                firstExchangedAmount
        );
        Double secondExchangedAmount = (Double) secondExchange.get("finalAmount");
        Double secondExchangeProvision = (Double) secondExchange.get("provision");
        Double secondExchangeTotalFee = (Double) secondExchange.get("totalFee");

        //skidamo korisniku EUR
        fromAccount.setBalance(fromAccount.getBalance() - transfer.getAmount());
        // i stavljamo direktno na racun banke za tu valutu
        fromCurrencyBankAccount.setBalance(fromCurrencyBankAccount.getBalance());
        // dodajemo konvertovanu svotu iz evra na racun dinara
        rsdBankAccount.setBalance(rsdBankAccount.getBalance() + firstExchangeProvision);
        //balance  - total + provision (finalAmount)
        toCurrencyBankAccount.setBalance(toCurrencyBankAccount.getBalance() + secondExchangeProvision);
        //dodajemo pare na devizni racun korisnika
        toAccount.setBalance(toAccount.getBalance() + secondExchangedAmount);

        //from user -> bank (foreign currency)
        Transaction firstTransaction = createTransaction(
                true,
                fromAccount,
                fromCurrencyBankAccount,
                transfer.getAmount(),
                fromCurrency,
                0.0,
                "Exchange: Foreign to RSD",
                transfer
        );

        //from bank(foreign currency) -> RSD bank account
        Transaction secondTransaction = createTransaction(
                true,
                fromCurrencyBankAccount,
                rsdBankAccount,
                transfer.getAmount() * firstExchangeRate ,
                rsd,
                0.0,
                "Provision: Foreign to RSD",
                transfer
        );

//        from RSD bank account -> foreign currency
        Transaction thirdTransaction = createTransaction(
                true,
                rsdBankAccount,
                toCurrencyBankAccount,
                firstExchangedAmount,
                rsd,
                0.0,
                "Provision: Foreign to RSD",
                transfer
        );
//         1% ostaje, 99 ide na korisnika
//        from foreign bank account -> to account
        Transaction fourthTransaction = createTransaction(
                true,
                toCurrencyBankAccount,
                toAccount,
                secondExchangedAmount,
                toCurrency,
                0.0,
                "Exchange: payment to customer",
                transfer
        );


        Set<Transaction> transactions = new HashSet<>(List.of(firstTransaction, secondTransaction, thirdTransaction, fourthTransaction));
        transactionRepository.saveAll(transactions);
        transferRepository.save(transfer);


        return exchangeService.calculatePreviewExchangeAutomatic(fromAccount.getCurrencyType().toString(),
                toAccount.getCurrencyType().toString(),
                transfer.getAmount());
    }

    /**
     * Performs an exchange from RSD to a foreign currency.
     */
    public Map<String, Object> performRsdToForeign(Double amount, Account fromAccount, Account toAccount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccount.getCurrencyType() != CurrencyType.RSD || toAccount.getCurrencyType() == CurrencyType.RSD) {
            throw new IllegalArgumentException("Invalid account currency types");
        }

        Currency rsd = currencyRepository.getByCode(CurrencyType.RSD);
        Currency toCurrency = currencyRepository.getByCode(toAccount.getCurrencyType());
        Account rsdBankAccount = bankAccountUtils.getBankAccountForCurrency(rsd.getCode());
        Account foreignBankAccount = bankAccountUtils.getBankAccountForCurrency(toAccount.getCurrencyType());
        CustomerDTO receiver = userServiceCustomer.getCustomerById(toAccount.getOwnerID());

        Map<String, Object> exchange = exchangeService.calculatePreviewExchangeAutomatic(
                "RSD",
                toAccount.getCurrencyType().toString(),
                amount
        );

        Double finalAmount = (Double) exchange.get("finalAmount");
        Double provision = (Double) exchange.get("provision");
        Double convertedAmount = (Double) exchange.get("convertedAmount");


        fromAccount.setBalance(fromAccount.getBalance() - amount);
        foreignBankAccount.setBalance(foreignBankAccount.getBalance() + provision);
        toAccount.setBalance(toAccount.getBalance() + finalAmount);

        Transfer transferToBank = createTransfer(
                fromAccount,
                rsdBankAccount,
                amount,
                "Promena valute",
                rsdBankAccount.getCompany().getName(),
                rsd,
                rsd,
                null
        );

        Transfer transferFromBank = createTransfer(
                foreignBankAccount,
                toAccount,
                finalAmount,
                "Promena valute",
                receiver.getFirstName() + " " + receiver.getLastName(),
                toCurrency,
                toCurrency,
                null
        );

        Transaction transactionToBank = createTransaction(
                true,
                fromAccount,
                rsdBankAccount,
                amount,
                rsd,
                0.0,
                "Exchange transaction",
                transferToBank
        );

        Transaction transactionRsdToForeign = createTransaction(
                true,
                rsdBankAccount,
                foreignBankAccount,
                convertedAmount,
                toCurrency,
                0.0,
                "Exchange transaction",
                transferToBank
        );

        Transaction transactionFromBank = createTransaction(
                true,
                foreignBankAccount,
                toAccount,
                finalAmount,
                toCurrency,
                0.0,
                "Exchange transaction",
                transferFromBank
        );

        saveTransfersAndTransactions(
                List.of(transferToBank, transferFromBank),
                List.of(transactionToBank, transactionRsdToForeign, transactionFromBank),
                List.of(rsdBankAccount, foreignBankAccount)
        );

        return exchange;
    }
    // base -> foreign -> 1 prov (foreign)
    // foreigh -> foreigh -> 2 prov ( rsd i foreigh)
    /**
     * Performs an exchange from a foreign currency to RSD.
     */
    public Map<String, Object> performForeignToRsd(Double amount, Account fromAccount, Account toAccount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccount.getCurrencyType() == CurrencyType.RSD || toAccount.getCurrencyType() != CurrencyType.RSD) {
            throw new IllegalArgumentException("Invalid account currency types");
        }

        Currency rsd = currencyRepository.getByCode(CurrencyType.RSD);
        Currency fromCurrency = currencyRepository.getByCode(fromAccount.getCurrencyType());

        Map<String, Object> exchange = exchangeService.calculatePreviewExchangeAutomatic(
                fromAccount.getCurrencyType().toString(),
                "RSD",
                amount
        );

        Double finalAmount = (Double) exchange.get("finalAmount");
        Double provision = (Double) exchange.get("provision");
        Double convertedAmount = (Double) exchange.get("convertedAmount");

        CustomerDTO receiver = userServiceCustomer.getCustomerById(toAccount.getOwnerID());

        Account rsdBankAccount = bankAccountUtils.getBankAccountForCurrency(rsd.getCode());
        Account foreignBankAccount = bankAccountUtils.getBankAccountForCurrency(fromAccount.getCurrencyType());

        fromAccount.setBalance(fromAccount.getBalance() - amount);
        rsdBankAccount.setBalance(rsdBankAccount.getBalance() + provision);
        toAccount.setBalance(toAccount.getBalance() + finalAmount);

        Transfer transferToBank = createTransfer(
                fromAccount,
                foreignBankAccount,
                amount,
                "Promena valute",
                rsdBankAccount.getCompany().getName(),
                fromCurrency,
                rsd,
                null
        );

        Transfer transferFromBank = createTransfer(
                rsdBankAccount,
                toAccount,
                finalAmount,
                "Promena valute",
                receiver.getFirstName() + " " + receiver.getLastName(),
                rsd,
                rsd,
                null
        );

        Transaction transactionToBank = createTransaction(
                true,
                fromAccount,
                foreignBankAccount,
                amount,
                fromCurrency,
                0.0,
                "Exchange transaction",
                transferToBank
        );
        Transaction transactionForeignToRsd = createTransaction(
                true,
                foreignBankAccount,
                rsdBankAccount,
                convertedAmount,
                rsd,
                0.0,
                "Exchange transaction",
                transferToBank
        );

        Transaction transactionFromBank = createTransaction(
                true,
                rsdBankAccount,
                toAccount,
                finalAmount,
                rsd,
                0.0,
                "Exchange transaction",
                transferFromBank
        );

        saveTransfersAndTransactions(
                List.of(transferToBank, transferFromBank),
                List.of(transactionToBank, transactionForeignToRsd, transactionFromBank),
                List.of(rsdBankAccount, foreignBankAccount)
        );

        return exchange;
    }

    /**
     * Helper method to create a Transfer object.
     */
    private Transfer createTransfer(
            Account fromAccount,
            Account toAccount,
            Double amount,
            String description,
            String receiver,
            Currency fromCurrency,
            Currency toCurrency,
            Long savedReceiverId
    ) {
        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount);
        transfer.setToAccountId(toAccount);
        transfer.setAmount(amount);
        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setType(TransferType.EXTERNAL);
        transfer.setPaymentDescription(description);
        transfer.setReceiver(receiver);
        transfer.setFromCurrency(fromCurrency);
        transfer.setToCurrency(toCurrency);
        transfer.setCreatedAt(System.currentTimeMillis());
        transfer.setSavedReceiverId(savedReceiverId);
        return transfer;
    }

    /**
     * Helper method to create a Transaction object.
     */
    private Transaction createTransaction(
            boolean bankOnly,
            Account fromAccount,
            Account toAccount,
            Double amount,
            Currency currency,
            Double fee,
            String description,
            Transfer transfer
    ) {
        Transaction transaction = new Transaction();
        transaction.setBankOnly(bankOnly);
        transaction.setFromAccountId(fromAccount);
        transaction.setToAccountId(toAccount);
        transaction.setAmount(amount);
        transaction.setCurrency(currency);
        transaction.setFinalAmount(amount - fee);
        transaction.setFee(fee);
        transaction.setTimestamp(System.currentTimeMillis());
        LocalDateTime now = LocalDateTime.now();
        String date = now.toLocalDate().toString();
        date = date.substring(8, 10) + "-" + date.substring(5, 7) + "-" + date.substring(0, 4);
        transaction.setDate(date);

        String time = now.toLocalTime().toString();
        time = time.substring(0, 5);
        transaction.setTime(time);
        transaction.setDescription(description);
        transaction.setTransfer(transfer);
        return transaction;
    }

    /**
     * Helper method to create a fee transaction on the bank's account.
     */
    private Transaction createFeeTransaction(
            Account bankAccount,
            Account customerFromAccount,
            Double feeAmount,
            Currency currency,
            String description,
            Transfer transfer
    ) {
        Transaction feeTransaction = new Transaction();
        feeTransaction.setBankOnly(false);
        feeTransaction.setFromAccountId(customerFromAccount); // No source account as this is just a fee
        feeTransaction.setToAccountId(bankAccount);
        feeTransaction.setAmount(feeAmount);
        feeTransaction.setCurrency(currency);
        feeTransaction.setFinalAmount(feeAmount);
        feeTransaction.setFee(0.0);
        feeTransaction.setTimestamp(System.currentTimeMillis());
        feeTransaction.setDescription(description);
        feeTransaction.setTransfer(transfer);
        return feeTransaction;
    }


    /**
     * Helper method to save multiple transfers, transactions, and accounts.
     */
    private void saveTransfersAndTransactions(
            List<Transfer> transfers,
            List<Transaction> transactions,
            List<Account> accounts
    ) {
        transferRepository.saveAll(transfers);
        transactionRepository.saveAll(transactions);
        accountRepository.saveAll(accounts);
    }


    @Transactional
    public String processForeignBankTransfer(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (!transfer.getStatus().equals(TransferStatus.RESERVED)) {
            throw new RuntimeException("Transfer is not in pending state");
        }

        if (!transfer.getType().equals(TransferType.FOREIGN_BANK)) {
            throw new RuntimeException("Invalid transfer type for this process");
        }

        Account fromAccount = transfer.getFromAccountId();
        Double amount = transfer.getAmount();

        if (fromAccount.getBalance() < amount) {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setNote("Insufficient balance");
            transferRepository.save(transfer);
            throw new RuntimeException("Insufficient balance for transfer");
        }

        try {
            System.out.println(fromAccount.getBalance() + " - " + amount);
            fromAccount.setBalance(fromAccount.getBalance() - amount);
            fromAccount.setReservedBalance(fromAccount.getReservedBalance() + amount);

            accountRepository.save(fromAccount);

            interbankService.sendNewTXMessage(transfer);

            transfer.setStatus(TransferStatus.RESERVED);
            transfer.setCompletedAt(Instant.now().toEpochMilli());
            transferRepository.save(transfer);

            //Inkrementiranje transakcije za fast payment opciju
            if(transfer.getSavedReceiverId() != null)
                receiverService.incrementUsage(transfer.getSavedReceiverId());

            return "Transfer reserved successfully";
        } catch (Exception e) {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setNote("Error during processing: " + e.getMessage());
            log.error(e.getMessage());
            transferRepository.save(transfer);
            throw new RuntimeException("Transfer processing failed", e);
        }
    }


    @Transactional
    public String processInternalTransfer(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));

        // Provera statusa i tipa transfera
        if (!transfer.getStatus().equals(TransferStatus.PENDING)) {
            throw new RuntimeException("Transfer is not in pending state");
        }

        if (!transfer.getType().equals(TransferType.INTERNAL) && !transfer.getType().equals(TransferType.EXCHANGE)) {
            throw new RuntimeException("Invalid transfer type for this process");
        }

        Account fromAccount = transfer.getFromAccountId();
        Account toAccount = transfer.getToAccountId();

        //Ukoliko na racunu ne postoji dovoljno sredstava za izvrsenje
        if (fromAccount.getBalance() < transfer.getAmount()) {
            transfer.setStatus(TransferStatus.FAILED);
            transferRepository.save(transfer);
            throw new RuntimeException("Insufficient funds");
        }

        try{
            // Azuriranje balansa
            Map<String, Object> exchangeMap = null;

            if(transfer.getType().equals(TransferType.INTERNAL)) {
                fromAccount.setBalance(fromAccount.getBalance() - transfer.getAmount());
                toAccount.setBalance(toAccount.getBalance() + transfer.getAmount());
            }
            else {
                exchangeMap = performCurrencyExchangeTransfer(transfer, fromAccount, toAccount);
            }

            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);

            // Kreiranje transakcija
            Transaction debitTransaction = new Transaction();
            debitTransaction.setFromAccountId(fromAccount);
            debitTransaction.setToAccountId(toAccount);
            debitTransaction.setAmount(transfer.getAmount());
            debitTransaction.setCurrency(transfer.getFromCurrency());
            if(exchangeMap != null) {
                debitTransaction.setFee((Double) exchangeMap.get("fee"));
                debitTransaction.setFinalAmount((Double) exchangeMap.get("finalAmount"));
            } else {
                debitTransaction.setFee(0.0);
                debitTransaction.setFinalAmount(transfer.getAmount());
            }
            debitTransaction.setTimestamp(System.currentTimeMillis());
            debitTransaction.setDescription("Debit transaction for transfer " + transferId);
            debitTransaction.setTransfer(transfer);
            LocalDateTime now = LocalDateTime.now();
            String date = now.toLocalDate().toString();
            date = date.substring(8, 10) + "-" + date.substring(5, 7) + "-" + date.substring(0, 4);
            debitTransaction.setDate(date);

            String time = now.toLocalTime().toString();
            time = time.substring(0, 5);
            debitTransaction.setTime(time);

            transactionRepository.save(debitTransaction);

            transfer.setStatus(TransferStatus.COMPLETED);
            transfer.setCompletedAt(System.currentTimeMillis());
            transferRepository.save(transfer);

            //Inkrementiranje transakcije za fast payment opciju
            if(transfer.getSavedReceiverId() != null)
                receiverService.incrementUsage(transfer.getSavedReceiverId());

            return "Transfer completed successfully";
        }catch (Exception e) {
            log.error("Error processing transfer:", e);
            throw new RuntimeException("Transaction failed, rollback initiated", e);
        }

    }

    @Transactional
    public String processExternalTransfer(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (!transfer.getStatus().equals(TransferStatus.PENDING)) {
            throw new RuntimeException("Transfer is not in pending state");
        }

        if (!transfer.getType().equals(TransferType.EXTERNAL) && !transfer.getType().equals(TransferType.FOREIGN)) {
            throw new RuntimeException("Invalid transfer type for this process");
        }

        Account fromAccount = transfer.getFromAccountId();
        Account toAccount = transfer.getToAccountId();
        Double amount = transfer.getAmount();

        if (fromAccount.getBalance() < amount) {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setNote("Insufficient balance");
            transferRepository.save(transfer);
            throw new RuntimeException("Insufficient balance for transfer");
        }

        try {
            fromAccount.setBalance(fromAccount.getBalance() - amount);
            Map<String, Object> exchangeMap = null;

            if(transfer.getType().equals(TransferType.EXTERNAL))
                toAccount.setBalance(toAccount.getBalance() + transfer.getAmount());
            else {
                exchangeMap = performCurrencyExchangeTransfer(transfer, fromAccount, toAccount);
            }

            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);

            Transaction debitTransaction = new Transaction();
            debitTransaction.setFromAccountId(fromAccount);
            debitTransaction.setToAccountId(toAccount);
            debitTransaction.setAmount(amount);
            debitTransaction.setCurrency(transfer.getFromCurrency());
            if(exchangeMap != null) {
                log.info(exchangeMap.toString());
                debitTransaction.setFee((Double) exchangeMap.get("fee"));
                debitTransaction.setFinalAmount((Double) exchangeMap.get("finalAmount"));
            } else {
                debitTransaction.setFee(0.0);
                debitTransaction.setFinalAmount(transfer.getAmount());
            }
            debitTransaction.setTimestamp(Instant.now().toEpochMilli());
            LocalDateTime now = LocalDateTime.now();
            String date = now.toLocalDate().toString();
            date = date.substring(8, 10) + "-" + date.substring(5, 7) + "-" + date.substring(0, 4);
            debitTransaction.setDate(date);

            String time = now.toLocalTime().toString();
            time = time.substring(0, 5);
            debitTransaction.setTime(time);
            debitTransaction.setDescription("Debit transaction for transfer " + transfer.getId());
            debitTransaction.setTransfer(transfer);
            transactionRepository.save(debitTransaction);

            transfer.setStatus(TransferStatus.COMPLETED);
            transfer.setCompletedAt(Instant.now().toEpochMilli());
            transferRepository.save(transfer);

            //Inkrementiranje transakcije za fast payment opciju
            if(transfer.getSavedReceiverId() != null)
                receiverService.incrementUsage(transfer.getSavedReceiverId());

            return "Transfer completed successfully";
        } catch (Exception e) {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setNote("Error during processing: " + e.getMessage());
            log.error(e.getMessage());
            transferRepository.save(transfer);
            throw new RuntimeException("Transfer processing failed", e);
        }
    }

    public boolean validateInternalTransfer(InternalTransferDTO transferDTO){

        Optional<Account> fromAccountInternal = accountRepository.findById(transferDTO.getFromAccountId());
        Optional<Account> toAccountInternal = accountRepository.findById(transferDTO.getToAccountId());

        if(fromAccountInternal.isEmpty() || toAccountInternal.isEmpty()){
            return false;
        }

        Account fromAccount = fromAccountInternal.get();
        Account toAccount = toAccountInternal.get();

        if(!fromAccount.getCurrencyType().equals(toAccount.getCurrencyType())){
            return false;
        }
        return fromAccount.getOwnerID().equals(toAccount.getOwnerID());
    }

    public boolean validateMoneyTransfer(MoneyTransferDTO transferDTO){

        Optional<Account> fromAccountOtp = accountRepository.findByAccountNumber(transferDTO.getFromAccountNumber());
        Optional<Account> toAccountOtp = accountRepository.findByAccountNumber(transferDTO.getRecipientAccount());

        // receiver account is null and should not be checked if it is not in our bank
        if(fromAccountOtp.isEmpty() || (toAccountOtp.isEmpty() && transferDTO.getRecipientAccount().startsWith(config.getRoutingNumber()))){
            return false;
        }

        Account fromAccount = fromAccountOtp.get();

        if(transferDTO.getAmount() <= 0){
            return false;
        }

        // PROVERA ZA RECEIVERA
        if (transferDTO.getSavedReceiverId() != null) {
            Receiver receiverExists = receiverService.findById(transferDTO.getSavedReceiverId());

            if (receiverExists == null) {
                return false;
            }
        }

        if (toAccountOtp.isPresent()) {
            Account toAccount = toAccountOtp.get();
            return !fromAccount.getOwnerID().equals(toAccount.getOwnerID());
        }

        return true;
    }

    public Transfer createInternalTransferEntity(Account fromAccount, Account toAccount,InternalTransferDTO internalTransferDTO, CustomerDTO customerData, String description) {
        Currency fromCurrency = currencyRepository.findByCode(fromAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException("Greska"));

        Currency toCurrency = currencyRepository.findByCode(toAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException("Greska"));

        String firstName = customerData.getFirstName();
        String lastName = customerData.getLastName();

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount);
        transfer.setToAccountId(toAccount);
        transfer.setAmount(internalTransferDTO.getAmount());
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setType(TransferType.INTERNAL);
        transfer.setPaymentDescription(description);
        transfer.setReceiver(firstName + " " + lastName);
        transfer.setFromCurrency(fromCurrency);
        transfer.setToCurrency(toCurrency);
        transfer.setCreatedAt(System.currentTimeMillis());

        return transferRepository.saveAndFlush(transfer);
    }

    public UUID createInternalTransfer(InternalTransferDTO internalTransferDTO){

        Optional<Account> fromAccountOtp = accountRepository.findById(internalTransferDTO.getFromAccountId());
        Optional<Account> toAccountOtp = accountRepository.findById(internalTransferDTO.getToAccountId());

        if (fromAccountOtp.isPresent() && toAccountOtp.isPresent()){

            Account fromAccount = fromAccountOtp.get();
            Account toAccount = toAccountOtp.get();

            Long customerId = fromAccount.getOwnerID();
            CustomerDTO customerData = userServiceCustomer.getCustomerById(customerId);

            if (customerData == null ) {
                throw new IllegalArgumentException("Korisnik nije pronađen");
            }

            String email = customerData.getEmail();
            String firstName = customerData.getFirstName();
            String lastName = customerData.getLastName();

            var transfer = createInternalTransferEntity(fromAccount, toAccount, internalTransferDTO, customerData, "Interni prenos");

            String otpCode = otpTokenService.generateOtp(transfer.getId());
            transfer.setOtp(otpCode);
            transferRepository.save(transfer);

            NotificationDTO emailDto = new NotificationDTO();
            emailDto.setSubject("Verifikacija");
            emailDto.setEmail(email);
            emailDto.setMessage("Vaš verifikacioni kod je: " + otpCode);
            emailDto.setFirstName(firstName);
            emailDto.setLastName(lastName);
            emailDto.setType("email");

            NotificationDTO pushNotification = new NotificationDTO();
            pushNotification.setSubject("Verifikacija");
            pushNotification.setMessage("Kliknite kako biste verifikovali transfer");
            pushNotification.setFirstName(firstName);
            pushNotification.setLastName(lastName);
            pushNotification.setType("firebase");
            pushNotification.setEmail(email);
            Map<String, String> data = Map.of("transferId", transfer.getId().toString(), "otp", otpCode);
            pushNotification.setAdditionalData(data);

            jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(emailDto));
            jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(pushNotification));

            return transfer.getId();
        }
        return null;
    }

    public Transfer createMoneyTransferEntity(Account fromAccount, Account toAccount, MoneyTransferDTO moneyTransferDTO) {
        Currency fromCurrency = currencyRepository.findByCode(fromAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException("Greska"));

        Currency toCurrency = currencyRepository.findByCode(toAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException("Greska"));

        Long customerId = fromAccount.getOwnerID();
        CustomerDTO customerData = userServiceCustomer.getCustomerById(customerId);

        if (customerData == null ) {
            throw new IllegalArgumentException("Korisnik nije pronađen");
        }

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount);
        transfer.setToAccountId(toAccount);
        transfer.setAmount(moneyTransferDTO.getAmount());
        transfer.setReceiver(moneyTransferDTO.getReceiver());
        transfer.setAdress(moneyTransferDTO.getAdress() != null ? moneyTransferDTO.getAdress() : "N/A");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setType(fromCurrency.equals(toCurrency) ? TransferType.EXTERNAL : TransferType.FOREIGN);
        transfer.setFromCurrency(fromCurrency);
        transfer.setToCurrency(toCurrency);
        transfer.setPaymentCode(moneyTransferDTO.getPayementCode());
        transfer.setPaymentReference(moneyTransferDTO.getPayementReference() != null ? moneyTransferDTO.getPayementReference() : "N/A");
        transfer.setPaymentDescription(moneyTransferDTO.getPayementDescription());
        transfer.setCreatedAt(System.currentTimeMillis());

        transfer.setSavedReceiverId(moneyTransferDTO.getSavedReceiverId());

        return transferRepository.saveAndFlush(transfer);
    }

    public Transfer createForeignBankMoneyTransferEntity(Account fromAccount, String foreignBankAccount, MoneyTransferDTO moneyTransferDTO) {
        Currency fromCurrency = currencyRepository.findByCode(fromAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException("Greska"));

        Long customerId = fromAccount.getOwnerID();
        CustomerDTO customerData = userServiceCustomer.getCustomerById(customerId);

        if (customerData == null ) {
            throw new IllegalArgumentException("Korisnik nije pronađen");
        }

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount);
        transfer.setToAccountId(null);
        transfer.setAmount(moneyTransferDTO.getAmount());
        transfer.setReceiver(moneyTransferDTO.getReceiver());
        transfer.setAdress(moneyTransferDTO.getAdress() != null ? moneyTransferDTO.getAdress() : "N/A");
        transfer.setStatus(TransferStatus.RESERVED);
        transfer.setType(TransferType.FOREIGN_BANK);
        transfer.setFromCurrency(fromCurrency);
        transfer.setToCurrency(fromCurrency);
        transfer.setPaymentCode(moneyTransferDTO.getPayementCode());
        transfer.setPaymentReference(moneyTransferDTO.getPayementReference() != null ? moneyTransferDTO.getPayementReference() : "N/A");
        transfer.setPaymentDescription(moneyTransferDTO.getPayementDescription());
        transfer.setCreatedAt(System.currentTimeMillis());
        transfer.setNote(foreignBankAccount);

        transfer.setSavedReceiverId(moneyTransferDTO.getSavedReceiverId());

        return transferRepository.saveAndFlush(transfer);
    }

    public UUID createMoneyTransfer(MoneyTransferDTO moneyTransferDTO){

        System.out.println("----------------------------------------");
        System.out.println("Creating money transfer");
        System.out.println("From account: " + moneyTransferDTO.getFromAccountNumber());
        System.out.println("To account: " + moneyTransferDTO.getRecipientAccount());
        System.out.println(config.getForeignBankRoutingNumber());
        if (moneyTransferDTO.getRecipientAccount().startsWith(config.getForeignBankRoutingNumber())) {
            System.out.println("Creating foreign bank transfer");
            return createForeignBankTransfer(moneyTransferDTO);
        }

        Optional<Account> fromAccountOtp = accountRepository.findByAccountNumber(moneyTransferDTO.getFromAccountNumber());
        Optional<Account> toAccountOtp = accountRepository.findByAccountNumber(moneyTransferDTO.getRecipientAccount());

        if (fromAccountOtp.isPresent() && toAccountOtp.isPresent()){

            Account fromAccount = fromAccountOtp.get();
            Account toAccount = toAccountOtp.get();

            Long customerId = fromAccount.getOwnerID();
            CustomerDTO customerData = userServiceCustomer.getCustomerById(customerId);

            if (customerData == null ) {
                throw new IllegalArgumentException("Korisnik nije pronađen");
            }

            String email = customerData.getEmail();
            String firstName = customerData.getFirstName();
            String lastName = customerData.getLastName();

            var transfer = createMoneyTransferEntity(fromAccount, toAccount, moneyTransferDTO);

            String otpCode = otpTokenService.generateOtp(transfer.getId());
            transfer.setOtp(otpCode);
            transferRepository.save(transfer);

            NotificationDTO emailDto = new NotificationDTO();
            emailDto.setSubject("Verifikacija");
            emailDto.setEmail(email);
            emailDto.setMessage("Vaš verifikacioni kod je: " + otpCode);
            emailDto.setFirstName(firstName);
            emailDto.setLastName(lastName);
            emailDto.setType("email");

            NotificationDTO pushNotification = new NotificationDTO();
            pushNotification.setSubject("Verifikacija");
            pushNotification.setMessage("Kliknite kako biste verifikovali transfer");
            pushNotification.setFirstName(firstName);
            pushNotification.setLastName(lastName);
            pushNotification.setEmail(email);
            pushNotification.setType("firebase");
            Map<String, String> data = Map.of("transferId", transfer.getId().toString(), "otp", otpCode);
            pushNotification.setAdditionalData(data);

            jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(emailDto));
            jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(pushNotification));

            return transfer.getId();

        }
        return null;
    }

    public UUID createForeignBankTransfer(MoneyTransferDTO moneyTransferDTO) {
        Optional<Account> fromAccountOtp = accountRepository.findByAccountNumber(moneyTransferDTO.getFromAccountNumber());

        if (!fromAccountOtp.isPresent()){
            throw new IllegalArgumentException("Račun nije pronađen");
        }

        Account fromAccount = fromAccountOtp.get();

        Long customerId = fromAccount.getOwnerID();
        CustomerDTO customerData = userServiceCustomer.getCustomerById(customerId);

        if (customerData == null ) {
            throw new IllegalArgumentException("Korisnik nije pronađen");
        }

        String email = customerData.getEmail();
        String firstName = customerData.getFirstName();
        String lastName = customerData.getLastName();

        var transfer = createForeignBankMoneyTransferEntity(fromAccount, moneyTransferDTO.getRecipientAccount(), moneyTransferDTO);

        String otpCode = otpTokenService.generateOtp(transfer.getId());
        transfer.setOtp(otpCode);
        transferRepository.save(transfer);

        System.out.println("OTP code: " + otpCode);

        NotificationDTO emailDto = new NotificationDTO();
        emailDto.setSubject("Verifikacija");
        emailDto.setEmail(email);
        emailDto.setMessage("Vaš verifikacioni kod je: " + otpCode);
        emailDto.setFirstName(firstName);
        emailDto.setLastName(lastName);
        emailDto.setType("email");

        NotificationDTO pushNotification = new NotificationDTO();
        pushNotification.setSubject("Verifikacija");
        pushNotification.setMessage("Kliknite kako biste verifikovali transfer");
        pushNotification.setFirstName(firstName);
        pushNotification.setLastName(lastName);
        pushNotification.setEmail(email);
        pushNotification.setType("firebase");
        Map<String, String> data = Map.of("transferId", transfer.getId().toString(), "otp", otpCode);
        pushNotification.setAdditionalData(data);

        jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(emailDto));
        jmsTemplate.convertAndSend(destinationEmail,messageHelper.createTextMessage(pushNotification));

        return transfer.getId();

    }

    @Scheduled(fixedRate = 10000)
    public void cancelExpiredTransfers(){

        long expirationTime = System.currentTimeMillis() - (5*60*1000);

        List<Transfer> expiredTransfers = transferRepository.findAllByStatusAndCreatedAtBefore(TransferStatus.PENDING,expirationTime);

        for (Transfer transfer : expiredTransfers){
            transfer.setStatus(TransferStatus.CANCELLED);
            transferRepository.save(transfer);
        }

    }

    public Transfer findById(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer sa ID-jem " + transferId + " nije pronađen"));
    }

    public List<Transfer> getAllTransfersStartedByUser(Long userId) {
        return transferRepository.findAllByFromAccountId_OwnerID(userId);
    }

    public Transfer commitForeignBankTransfer(IdempotenceKey idempotenceKey) {
        UUID transferID = UUID.fromString(idempotenceKey.getLocallyGeneratedKey());
        Transfer transfer = transferRepository.findById(transferID)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.RESERVED) {
            throw new RuntimeException("Transfer is not in reserved state");
        }

        if (transfer.getStatus() == TransferStatus.COMPLETED) {
            throw new RuntimeException("Transfer has already been processed");
        }

        try {

            // create transaction for the transfer
            Transaction transaction = new Transaction();
            transaction.setFromAccountId(transfer.getFromAccountId());
            transaction.setToAccountId(null); // No destination account for foreign bank transfer
            transaction.setAmount(transfer.getAmount());
            transaction.setCurrency(transfer.getFromCurrency());
            transaction.setFinalAmount(transfer.getAmount());
            transaction.setFee(0.0);
            transaction.setTimestamp(System.currentTimeMillis());
            transaction.setDescription("Foreign bank transfer");
            transaction.setTransfer(transfer);

            LocalDateTime now = LocalDateTime.now();
            String date = now.toLocalDate().toString();
            date = date.substring(8, 10) + "-" + date.substring(5, 7) + "-" + date.substring(0, 4);
            transaction.setDate(date);
            String time = now.toLocalTime().toString();
            time = time.substring(0, 5);
            transaction.setTime(time);

            transactionRepository.save(transaction);

            Account fromAccount = transfer.getFromAccountId();
            fromAccount.setReservedBalance(fromAccount.getReservedBalance() - transfer.getAmount());

            transfer.setStatus(TransferStatus.COMPLETED);
            transfer.setCompletedAt(System.currentTimeMillis());
            transferRepository.save(transfer);
            return transfer;
        } catch (Exception e) {
            throw new RuntimeException("Error processing transfer", e);
        }
    }

    public Transfer rollbackForeignBankTransfer(IdempotenceKey idempotenceKey) {
        UUID transferID = UUID.fromString(idempotenceKey.getLocallyGeneratedKey());
        Transfer transfer = transferRepository.findById(transferID)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.RESERVED) {
            throw new RuntimeException("Transfer is not in reserved state");
        }

        if (transfer.getStatus() == TransferStatus.CANCELLED) {
            throw new RuntimeException("Transfer has already been cancelled");
        }

        try {
            Account fromAccount = transfer.getFromAccountId();
            fromAccount.setReservedBalance(fromAccount.getReservedBalance() - transfer.getAmount());
            fromAccount.setBalance(fromAccount.getBalance() + transfer.getAmount());

            transfer.setStatus(TransferStatus.CANCELLED);
            transferRepository.save(transfer);
            return transfer;
        } catch (Exception e) {
            throw new RuntimeException("Error processing transfer", e);
        }
    }

    @Transactional
    public Transfer receiveForeignBankTransfer(String accountNumber, double amount, String description, String senderName, Currency currency) {
        Account toAccount = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Destination account not found: " + accountNumber));

        toAccount.setBalance(toAccount.getBalance() + amount);
        accountRepository.save(toAccount);

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(null);
        transfer.setToAccountId(toAccount);
        transfer.setAmount(amount);
        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setType(TransferType.FOREIGN_BANK);
        transfer.setPaymentDescription(description);
        transfer.setReceiver(senderName);
        transfer.setFromCurrency(currency);
        transfer.setToCurrency(currency);
        transfer.setCreatedAt(System.currentTimeMillis());

        transfer = transferRepository.save(transfer);

        Transaction transaction = new Transaction();
        transaction.setFromAccountId(null);
        transaction.setToAccountId(toAccount);
        transaction.setAmount(amount);
        transaction.setCurrency(currency);
        transaction.setFinalAmount(amount);
        transaction.setFee(0.0);
        transaction.setTimestamp(System.currentTimeMillis());
        transaction.setDescription("Received transfer from foreign bank");
        transaction.setTransfer(transfer);

        LocalDateTime now = LocalDateTime.now();
        String date = now.toLocalDate().toString();
        date = date.substring(8, 10) + "-" + date.substring(5, 7) + "-" + date.substring(0, 4);
        transaction.setDate(date);
        String time = now.toLocalTime().toString();
        time = time.substring(0, 5);
        transaction.setTime(time);



        transactionRepository.save(transaction);

        return transfer;
    }

    

}

