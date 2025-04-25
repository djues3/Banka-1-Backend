package com.banka1.banking.services;

import com.banka1.banking.dto.TaxCollectionDTO;
import com.banka1.banking.models.Account;
import com.banka1.banking.models.Currency;
import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.CurrencyType;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.TransactionRepository;
import com.banka1.banking.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TaxServiceTest {
    @InjectMocks
    private TaxService taxService;

    @Mock
    private BankAccountUtils bankAccountUtils;

    @Mock
    private TransferService transferService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private ExchangeService exchangeService;

    @Test
    void testPayTax_Success() {
        TaxCollectionDTO dto = new TaxCollectionDTO();
        dto.setAccountId(1L);
        dto.setAmount(100.0);

        Account userAccount = new Account();
        Account bankAccount = new Account();
        userAccount.setCurrencyType(CurrencyType.USD);

        userAccount.setBalance(200.0);
        bankAccount.setBalance(100.0);
        bankAccount.setCurrencyType(CurrencyType.USD);

        Transfer transfer = new Transfer();
        transfer.setAmount(100.0);

        Currency usd = new Currency();
        usd.setCode(CurrencyType.USD);
        transfer.setFromCurrency(usd);

        when(accountRepository.findById(dto.getAccountId())).thenReturn(Optional.of(userAccount));
        when(bankAccountUtils.getBankAccountForCurrency(CurrencyType.USD)).thenReturn(bankAccount);
        when(transferService.createMoneyTransferEntity(any(), any(), any())).thenReturn(transfer);

        taxService.payTax(dto);

        verify(transferRepository, times(1)).save(transfer);
        assertEquals(100.0, userAccount.getBalance());
        assertEquals(200.0, bankAccount.getBalance());
    }
}
