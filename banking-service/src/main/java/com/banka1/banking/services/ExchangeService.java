package com.banka1.banking.services;

import com.banka1.banking.dto.CustomerDTO;
import com.banka1.banking.dto.ExchangeMoneyTransferDTO;
import com.banka1.banking.dto.NotificationDTO;
import com.banka1.banking.models.Account;
import com.banka1.banking.models.Currency;
import com.banka1.banking.models.ExchangePair;
import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.*;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.CurrencyRepository;
import com.banka1.banking.repository.ExchangePairRepository;
import com.banka1.banking.repository.TransferRepository;
import com.banka1.banking.utils.ExcludeFromGeneratedJacocoReport;
import com.banka1.common.listener.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ExchangeService {

    private final AccountRepository accountRepository;

    private final CurrencyRepository currencyRepository;

    private final TransferRepository transferRepository;

    private final JmsTemplate jmsTemplate;

    private final MessageHelper messageHelper;

    private final String destinationEmail;

    private final UserServiceCustomer userServiceCustomer;

    private final OtpTokenService otpTokenService;

    private final ExchangePairRepository exchangePairRepository;

    public ExchangeService(AccountRepository accountRepository, CurrencyRepository currencyRepository, TransferRepository transferRepository, JmsTemplate jmsTemplate, MessageHelper messageHelper, @Value("send-email") String destinationEmail, UserServiceCustomer userServiceCustomer, OtpTokenService otpTokenService, ExchangePairRepository exchangePairRepository) {
        this.accountRepository = accountRepository;
        this.currencyRepository = currencyRepository;
        this.transferRepository = transferRepository;
        this.jmsTemplate = jmsTemplate;
        this.messageHelper = messageHelper;
        this.destinationEmail = destinationEmail;
        this.userServiceCustomer = userServiceCustomer;
        this.otpTokenService = otpTokenService;
        this.exchangePairRepository = exchangePairRepository;
    }

    public boolean validateExchangeTransfer(ExchangeMoneyTransferDTO exchangeMoneyTransferDTO){

        Optional<Account> fromAccountOtp = accountRepository.findById(exchangeMoneyTransferDTO.getAccountFrom());
        Optional<Account> toAccountOtp = accountRepository.findById(exchangeMoneyTransferDTO.getAccountTo());

        if (fromAccountOtp.isEmpty() || toAccountOtp.isEmpty()){
            return false;
        }

        Account fromAccount = fromAccountOtp.get();
        Account toAccount = toAccountOtp.get();

        if (fromAccount.getStatus() != AccountStatus.ACTIVE ||
                toAccount.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }

        if (fromAccount.getCurrencyType().equals(toAccount.getCurrencyType())){
            return false;
        }

        if(exchangeMoneyTransferDTO.getAmount() <= 0){
            return false;
        }
        return fromAccount.getOwnerID().equals(toAccount.getOwnerID());
    }

    public UUID createExchangeTransfer(ExchangeMoneyTransferDTO exchangeMoneyTransferDTO) {
        Account fromAccount = accountRepository.findById(exchangeMoneyTransferDTO.getAccountFrom())
                .orElseThrow(() -> new IllegalArgumentException("Račun nije pronađen"));

        Account toAccount = accountRepository.findById(exchangeMoneyTransferDTO.getAccountTo())
                .orElseThrow(() -> new IllegalArgumentException("Račun nije pronađen"));

        if (!fromAccount.getOwnerID().equals(toAccount.getOwnerID())) {
            throw new IllegalArgumentException("Transfer je moguc samo između računa istog korisnika");
        }

        Currency fromCurrency = currencyRepository.findByCode(fromAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Greska" + fromAccount.getAccountNumber()));

        Currency toCurrency = currencyRepository.findByCode(toAccount.getCurrencyType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Greska" + toAccount.getAccountNumber()));

        CustomerDTO customerData = Optional.ofNullable(userServiceCustomer.getCustomerById(fromAccount.getOwnerID()))
                .orElseThrow(() -> new IllegalArgumentException("Korisnik nije pronađen"));

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount);
        transfer.setToAccountId(toAccount);
        transfer.setAmount(exchangeMoneyTransferDTO.getAmount());
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setType(TransferType.EXCHANGE);
        transfer.setFromCurrency(fromCurrency);
        transfer.setToCurrency(toCurrency);
        transfer.setCreatedAt(System.currentTimeMillis());

        try {
            transferRepository.saveAndFlush(transfer);
            String otpCode = otpTokenService.generateOtp(transfer.getId());
            transfer.setOtp(otpCode);
            transfer = transferRepository.save(transfer);

            NotificationDTO emailDto = new NotificationDTO();
            emailDto.setSubject("Verifikacija");
            emailDto.setEmail(customerData.getEmail());
            emailDto.setMessage("Vaš verifikacioni kod je: " + otpCode);
            emailDto.setFirstName(customerData.getFirstName());
            emailDto.setLastName(customerData.getLastName());
            emailDto.setType("email");

            NotificationDTO pushNotification = new NotificationDTO();
            pushNotification.setSubject("Verifikacija");
            pushNotification.setMessage("Kliknite kako biste verifikovali transfer");
            pushNotification.setFirstName(customerData.getFirstName());
            pushNotification.setLastName(customerData.getLastName());
            pushNotification.setType("firebase");
            pushNotification.setEmail(customerData.getEmail());
            pushNotification.setAdditionalData(Map.of(
                    "transferId", transfer.getId().toString(),
                    "otp", otpCode
            ));

            jmsTemplate.convertAndSend(destinationEmail, messageHelper.createTextMessage(emailDto));
            jmsTemplate.convertAndSend(destinationEmail, messageHelper.createTextMessage(pushNotification));

            return transfer.getId();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create exchange transfer", e);
        }
    }


    private CurrencyType parseCurrency(String currency) {
        try {
            return CurrencyType.valueOf(currency.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Nepoznata valuta: " + currency);
        }
    }

    @ExcludeFromGeneratedJacocoReport("Wrapper method")
    public Map<String, Object> calculatePreviewExchangeAutomatic(String fromCurrency, String toCurrency, Double amount) {
        if(fromCurrency.equals("RSD") || toCurrency.equals("RSD")){
            return calculatePreviewExchange(fromCurrency, toCurrency, amount);
            }
        else
            return calculatePreviewExchangeForeign(fromCurrency, toCurrency, amount);
    }

    public Map<String, Object> calculatePreviewExchange(String fromCurrency, String toCurrency, Double amount) {
        boolean isToRSD = toCurrency.equalsIgnoreCase("RSD");
        boolean isFromRSD = fromCurrency.equalsIgnoreCase("RSD");

        if (!isToRSD && !isFromRSD) {
            throw new RuntimeException("Ova funkcija podržava samo konverzije između RSD i druge valute.");
        }

        CurrencyType base = parseCurrency(isToRSD ? fromCurrency : "RSD");
        CurrencyType target = parseCurrency(isToRSD ? "RSD" : toCurrency);

        Optional<ExchangePair> exchangePairOpt = exchangePairRepository
                .findByBaseCurrencyCodeAndTargetCurrencyCode(base, target);

        double exchangeRate;
        if (exchangePairOpt.isPresent()) {
            exchangeRate = exchangePairOpt.get().getExchangeRate();
        } else {
            Optional<ExchangePair> reverseOpt = exchangePairRepository
                    .findByBaseCurrencyCodeAndTargetCurrencyCode(target, base);

            if (reverseOpt.isEmpty()) {
                throw new RuntimeException("Kurs nije pronađen za traženu konverziju.");
            }
            exchangeRate = 1 / reverseOpt.get().getExchangeRate();
        }

        double convertedAmount = amount * exchangeRate;
        double fee = (fromCurrency.equalsIgnoreCase("RSD") && toCurrency.equalsIgnoreCase("RSD")) ? 0.0 : convertedAmount * 0.01;
        double finalAmount = convertedAmount - fee;


        if (fromCurrency.equalsIgnoreCase("RSD")) {
            exchangeRate = 1 / exchangeRate;
        }

        return Map.of(
                "exchangeRate", exchangeRate,
                "convertedAmount", convertedAmount,
                "fee", fee,
                "provision", fee,
                "finalAmount", finalAmount
        );
    }


    public Map<String, Object> calculatePreviewExchangeForeign(String fromCurrency, String toCurrency, Double amount) {
        log.warn("Ova funkcija ne radi kako treba");
        if (fromCurrency.equalsIgnoreCase("RSD") || toCurrency.equalsIgnoreCase("RSD")) {
            throw new RuntimeException("Ova metoda je samo za konverziju strane valute u stranu valutu.");
        }
        CurrencyType from = parseCurrency(fromCurrency);
        CurrencyType to = parseCurrency(toCurrency);
        CurrencyType rsd = CurrencyType.RSD;

        Optional<ExchangePair> firstExchangeOpt = exchangePairRepository
                .findByBaseCurrencyCodeAndTargetCurrencyCode(from, rsd);

        double firstExchangeRate;
        if (firstExchangeOpt.isPresent()) {
            firstExchangeRate = firstExchangeOpt.get().getExchangeRate();
        } else {
            Optional<ExchangePair> reverseFirstOpt = exchangePairRepository
                    .findByBaseCurrencyCodeAndTargetCurrencyCode(rsd, from);

            if (reverseFirstOpt.isEmpty()) {
                throw new RuntimeException("Kurs za " + fromCurrency + " prema RSD nije pronađen.");
            }
            firstExchangeRate = 1 / reverseFirstOpt.get().getExchangeRate();
        }

        double amountInRSD = amount * firstExchangeRate;
        double firstFee = amountInRSD * 0.01;
        double remainingRSD = amountInRSD - firstFee;

        Optional<ExchangePair> secondExchangeOpt = exchangePairRepository
                .findByBaseCurrencyCodeAndTargetCurrencyCode(rsd, to);

        double secondExchangeRate;
        if (secondExchangeOpt.isPresent()) {
            secondExchangeRate = secondExchangeOpt.get().getExchangeRate();
        } else {
            Optional<ExchangePair> reverseSecondOpt = exchangePairRepository
                    .findByBaseCurrencyCodeAndTargetCurrencyCode(to, rsd);

            if (reverseSecondOpt.isEmpty()) {
                throw new RuntimeException("Kurs za RSD prema " + toCurrency + " nije pronađen.");
            }
            secondExchangeRate = 1 / reverseSecondOpt.get().getExchangeRate();
        }

        double amountInTargetCurrency = remainingRSD * secondExchangeRate;
        double secondFee = amountInTargetCurrency * 0.01;
        double finalAmount = amountInTargetCurrency - secondFee;
        double totalFee = firstFee * secondExchangeRate + secondFee;

        // Prikazujemo obrnut kurs za prikaz klijentu
        double displayedSecondExchangeRate = 1 / secondExchangeRate;

        return Map.of(
                "firstExchangeRate", firstExchangeRate,
                "secondExchangeRate", displayedSecondExchangeRate,
                "totalFee", totalFee,
                "provision", totalFee,
                "fee", totalFee,
                "finalAmount", finalAmount
        );
    }

}
