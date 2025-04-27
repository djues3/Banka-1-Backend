package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.newtx.InterbankTransactionDTO;
import com.banka1.banking.models.Account;
import com.banka1.banking.models.Currency;
import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.CurrencyType;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.CurrencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.weaver.TypeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class InterbankServiceTest {

    @Mock
    private EventService eventService;
    @Mock
    private EventExecutorService eventExecutorService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TransferService transferService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private InterbankConfig config;

    @InjectMocks
    private InterbankService interbankService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    void testSendNewTXMessage_shouldSendMessage() {
        InterbankService spyService = Mockito.spy(interbankService);

        // Mock Transfer
        Transfer mockTransfer = mock(Transfer.class);
        Account mockAccount = mock(Account.class);
        Currency mockCurrency = mock(Currency.class);

        when(mockTransfer.getId()).thenReturn(UUID.randomUUID());
        when(mockTransfer.getFromAccountId()).thenReturn(mockAccount);
        when(mockTransfer.getAmount()).thenReturn(100.0);
        when(mockTransfer.getPaymentDescription()).thenReturn("Test Description");
        when(mockTransfer.getFromCurrency()).thenReturn(mockCurrency);
        when(mockTransfer.getToCurrency()).thenReturn(mockCurrency);

        when(mockAccount.getAccountNumber()).thenReturn("111-222-333");
        when(mockCurrency.getCode()).thenReturn(CurrencyType.USD);

        when(config.getRoutingNumber()).thenReturn("123456");
        when(config.getForeignBankRoutingNumber()).thenReturn("654321");
        when(config.getInterbankTargetUrl()).thenReturn("http://localhost:8080/interbank");

        doNothing().when(spyService).sendInterbankMessage(any(InterbankMessageDTO.class), anyString());

        spyService.sendNewTXMessage(mockTransfer);


//        verify(spyService).sendInterbankMessage(any());
    }


    // Add more tests for COMMIT_TX, ROLLBACK_TX, handleCommitTXRequest, etc.
}
