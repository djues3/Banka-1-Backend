package com.banka1.banking.services;

import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.models.Account;
import com.banka1.banking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final AccountService accountService;
    private final BankAccountUtils bankAccountUtils;
    private final AccountRepository accountRepository;
    private final TransferService transferService;

    @Transactional
    public Double executeOrder(String direction, Long userId, Long accountId, Double amount, Double fee) {
        Account account = accountService.findById(accountId);
        Account bankAccount = bankAccountUtils.getBankAccountForCurrency(account.getCurrencyType());

        if (!Objects.equals(account.getOwnerID(), userId)) {
            throw new RuntimeException("Korisnik nije vlasnik računa");
        }

        if (direction.equalsIgnoreCase("buy") && account.getBalance() < amount + fee) {
            throw new IllegalArgumentException("Nedovoljno sredstava na računu za iznos + proviziju");
        }

        boolean sameCurrency = account.getCurrencyType().equals(bankAccount.getCurrencyType());
        System.out.printf("[ORDER EXEC] account=%s, bank=%s, direction=%s, devizni=%s%n",
                account.getCurrencyType(), bankAccount.getCurrencyType(), direction, !sameCurrency);

        if (Objects.equals(account.getId(), bankAccount.getId())) {
            if (direction.equalsIgnoreCase("buy")) {
                account.setBalance(account.getBalance() - amount - fee);
            } else if (direction.equalsIgnoreCase("sell")) {
                account.setBalance(account.getBalance() + amount);
            } else {
                throw new IllegalArgumentException("Nepoznata direkcija");
            }

            accountRepository.save(account);
        } else {
            // Glavni transfer
            MoneyTransferDTO dto = new MoneyTransferDTO();
            dto.setFromAccountNumber(direction.equalsIgnoreCase("buy") ? account.getAccountNumber() : bankAccount.getAccountNumber());
            dto.setRecipientAccount(direction.equalsIgnoreCase("buy") ? bankAccount.getAccountNumber() : account.getAccountNumber());
            dto.setAmount(amount);
            dto.setReceiver("Order Execution");
            dto.setAdress("System");
            dto.setPayementCode("999");
            dto.setPayementReference("Auto");
            dto.setPayementDescription("Realizacija naloga");

            transferService.createMoneyTransfer(dto);

            // Transfer za fee banci
            if (fee != null && fee > 0) {
                MoneyTransferDTO feeDto = new MoneyTransferDTO();
                feeDto.setFromAccountNumber(account.getAccountNumber());
                feeDto.setRecipientAccount(bankAccount.getAccountNumber());
                feeDto.setAmount(fee);
                feeDto.setReceiver("Bank Fee");
                feeDto.setAdress("System");
                feeDto.setPayementCode("999");
                feeDto.setPayementReference("Fee");
                feeDto.setPayementDescription("Provizija za realizaciju naloga");

                transferService.createMoneyTransfer(feeDto);
            }
        }

        return amount;
    }
}
