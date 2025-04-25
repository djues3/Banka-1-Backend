package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.InternalTransferDTO;
import com.banka1.banking.models.*;
import com.banka1.banking.models.helper.*;
import com.banka1.banking.repository.*;
import com.banka1.common.listener.MessageHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.jms.core.JmsTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the heavy-weight process* methods.
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class TransferServiceProcessTest {

    /* ===== mocked collaborators ===== */
    @Mock AccountRepository accountRepo;
    @Mock TransferRepository transferRepo;
    @Mock CurrencyRepository currencyRepo;
    @Mock TransactionRepository txRepo;

    @Mock JmsTemplate jms;
    @Mock MessageHelper msgHelper;

    @Mock UserServiceCustomer userService;
    @Mock ExchangeService exchangeService;
    @Mock OtpTokenService otp;
    @Mock BankAccountUtils bankUtils;
    @Mock ReceiverService receiverService;
    @Mock InterbankService interbankService;
    @Mock InterbankConfig cfg;

    @InjectMocks
    private TransferService service;

    @BeforeEach
    void init() {
        // destinationEmail is irrelevant for the tests – just pass a dummy value
        service = new TransferService(
                accountRepo, transferRepo, txRepo, currencyRepo,
                jms, msgHelper, "dummy-queue",
                userService, exchangeService, otp, bankUtils,
                receiverService, interbankService, cfg
        );
    }

    @Test
    void processForeignBankTransfer_happyPath() {
        /* ---------- arrange ---------- */
        Account acc = new Account();
        acc.setId(1L);
        acc.setBalance(1_000.0);
        acc.setReservedBalance(0.0);
        acc.setCurrencyType(CurrencyType.RSD);

        Transfer t = new Transfer();
        t.setId(42L);
        t.setAmount(100.0);
        t.setStatus(TransferStatus.RESERVED);
        t.setType(TransferType.FOREIGN_BANK);
        t.setFromAccountId(acc);

        when(transferRepo.findById(42L)).thenReturn(Optional.of(t));
        when(accountRepo.save(any(Account.class))).thenReturn(acc);
        when(transferRepo.save(any(Transfer.class))).thenAnswer(i -> i.getArgument(0));

        /* ---------- act ---------- */
        String result = service.processTransfer(42L);

        /* ---------- assert ---------- */
        assertEquals("Transfer reserved successfully", result);
        verify(interbankService).sendNewTXMessage(t);
    }

    @Test
    void processInternalTransfer_insufficientFunds_throws() {
        /* ---------- arrange ---------- */
        Account from = new Account();
        from.setId(10L);
        from.setOwnerID(77L);
        from.setBalance(10.0);                   // not enough!
        from.setCurrencyType(CurrencyType.RSD);

        Account to = new Account();
        to.setId(11L);
        to.setOwnerID(77L);
        to.setBalance(0.0);
        to.setCurrencyType(CurrencyType.RSD);

        Currency rsd = mock(Currency.class);

        Transfer t = new Transfer();
        t.setId(99L);
        t.setAmount(100.0);
        t.setStatus(TransferStatus.PENDING);
        t.setType(TransferType.INTERNAL);
        t.setFromAccountId(from);
        t.setToAccountId(to);
        t.setFromCurrency(rsd);

        when(transferRepo.findById(99L)).thenReturn(Optional.of(t));
        when(transferRepo.save(any(Transfer.class))).thenAnswer(i -> i.getArgument(0));

        /* ---------- act / assert ---------- */
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.processTransfer(99L));

        assertTrue(ex.getMessage().toLowerCase().contains("insufficient"));
    }
}
