package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.InternalTransferDTO;
import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.models.*;
import com.banka1.banking.models.helper.CurrencyType;
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
 * Focuses on the quick-running validation helpers.
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class TransferServiceValidationTest {

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

    private TransferService service;

    @BeforeEach
    void setUp() {
        when(cfg.getRoutingNumber()).thenReturn("111");          // anything is fine

        service = new TransferService(
                accountRepo, transferRepo, txRepo, currencyRepo,
                jms, msgHelper, "dummy-queue",
                userService, exchangeService, otp, bankUtils,
                receiverService, interbankService, cfg
        );
    }

    @Test
    void validateMoneyTransfer_negativeAmount_returnsFalse() {
        Account src = new Account();
        src.setOwnerID(55L);

        when(accountRepo.findByAccountNumber("111-222")).thenReturn(Optional.of(src));

        MoneyTransferDTO dto = new MoneyTransferDTO();
        dto.setFromAccountNumber("111-222");
        dto.setRecipientAccount("123-456");
        dto.setAmount(-50.0);

        assertFalse(service.validateMoneyTransfer(dto));
    }
}
