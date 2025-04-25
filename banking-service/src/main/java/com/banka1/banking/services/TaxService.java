package com.banka1.banking.services;

import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.dto.TaxCollectionDTO;
import com.banka1.banking.models.Account;
import com.banka1.banking.models.Transaction;
import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.TransferStatus;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.TransactionRepository;
import com.banka1.banking.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaxService {
    private final BankAccountUtils bankAccountUtils;
    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransferRepository transferRepository;
    private final ExchangeService exchangeService;

    @Transactional
    public void payTax(TaxCollectionDTO dto) {
        Account account = accountRepository.findById(dto.getAccountId()).orElseThrow();

        if(account.getBalance() < dto.getAmount())
            throw new RuntimeException("Nedovoljno sredstava");

        Account bankAccount = bankAccountUtils.getBankAccountForCurrency(account.getCurrencyType());

        account.setBalance(account.getBalance() - dto.getAmount());
        Map<String, Object> exchangeMap = null;

        MoneyTransferDTO moneyTransferDTO = new MoneyTransferDTO();

        moneyTransferDTO.setAdress("");
        moneyTransferDTO.setAmount(dto.getAmount());
        moneyTransferDTO.setReceiver("Banka");
        moneyTransferDTO.setRecipientAccount(bankAccount.getAccountNumber());
        moneyTransferDTO.setFromAccountNumber(account.getAccountNumber());
        moneyTransferDTO.setPayementDescription("Porez");
        moneyTransferDTO.setPayementCode("253");
        moneyTransferDTO.setPayementReference(null);

        Transfer transfer = transferService.createMoneyTransferEntity(
                account,
                bankAccount,
                moneyTransferDTO
        );

        if(account.getCurrencyType() == bankAccount.getCurrencyType())
            bankAccount.setBalance(bankAccount.getBalance() + dto.getAmount());
        else {
            exchangeMap = exchangeService.calculatePreviewExchangeAutomatic(account.getCurrencyType().toString(), "RSD", dto.getAmount());
            bankAccount.setBalance(bankAccount.getBalance() + (Double) exchangeMap.get("finalAmount") + (Double) exchangeMap.get("fee"));
        }

        accountRepository.save(account);
        accountRepository.save(bankAccount);

        Transaction debitTransaction = new Transaction();
        debitTransaction.setFromAccountId(account);
        debitTransaction.setToAccountId(bankAccount);
        debitTransaction.setAmount(dto.getAmount());
        debitTransaction.setCurrency(transfer.getFromCurrency());
        debitTransaction.setFee(0.0);
        debitTransaction.setBankOnly(false);

        if(exchangeMap != null) {
            log.info(exchangeMap.toString());
            debitTransaction.setFinalAmount((Double) exchangeMap.get("finalAmount") + (Double) exchangeMap.get("fee"));
        } else {
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
        debitTransaction.setDescription("Porez");
        debitTransaction.setTransfer(transfer);
        transactionRepository.save(debitTransaction);

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(Instant.now().toEpochMilli());
        transferRepository.save(transfer);
    }
}
