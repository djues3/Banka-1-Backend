package com.banka1.banking.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CustomerDTO;
import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.dto.NotificationDTO;
import com.banka1.banking.models.*;
import com.banka1.banking.models.helper.CurrencyType;
import com.banka1.banking.repository.*;
import com.banka1.common.listener.MessageHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jms.core.JmsTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Full-coverage tests for {@link TransferService#createForeignBankTransfer(MoneyTransferDTO)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateForeignBankTransferTest {

    /* ---------- collaborators ---------- */
    @Mock private AccountRepository        accountRepo;
    @Mock private TransferRepository       transferRepo;
    @Mock private CurrencyRepository       currencyRepo;
    @Mock private TransactionRepository    transactionRepo;
    @Mock private JmsTemplate              jms;
    @Mock private MessageHelper            msgHelper;
    @Mock private UserServiceCustomer      userSvc;
    @Mock private ExchangeService          exchangeSvc;
    @Mock private OtpTokenService          otpSvc;
    @Mock private BankAccountUtils         bankUtils;
    @Mock private ReceiverService          receiverSvc;
    @Mock private InterbankService         interbankSvc;
    @Mock private InterbankConfig          cfg;

    private UUID uuid = UUID.randomUUID();


    /** service under test */
    private TransferService service;

    /* ---------- shared fixtures ---------- */
    private Account   srcAcc;
    private Currency  usd;
    private CustomerDTO customer;
    private MoneyTransferDTO dtoOk;

    @BeforeEach
    void setUp() {
        // create service manually to inject "destinationEmail"
        service = new TransferService(
                accountRepo, transferRepo, transactionRepo, currencyRepo,
                jms, msgHelper, "email.queue", userSvc, exchangeSvc, otpSvc,
                bankUtils, receiverSvc, interbankSvc, cfg);

        // message helper – return dummy JMS payload so convertAndSend() succeeds
        when(msgHelper.createTextMessage(any(NotificationDTO.class))).thenReturn("msg");

        /* ------- demo data -------- */
        usd = new Currency(); usd.setCode(CurrencyType.USD);
        srcAcc = new Account();
        srcAcc.setAccountNumber("123456789");
        srcAcc.setCurrencyType(CurrencyType.USD);
        srcAcc.setOwnerID(1L);

        customer = new CustomerDTO();
        customer.setId(1L);
        customer.setEmail("user@example.com");
        customer.setFirstName("John");
        customer.setLastName("Doe");

        dtoOk = new MoneyTransferDTO();
        dtoOk.setFromAccountNumber("123456789");
        dtoOk.setRecipientAccount("FOREIGN-IBAN-987");
        dtoOk.setAmount(500.0);
        dtoOk.setReceiver("Overseas Receiver");
    }

    /* =================================================================== */
    /* 1) happy-path                                                      */
    /* =================================================================== */
    @Test
    @DisplayName("createForeignBankTransfer – happy path")
    void testCreateForeignBankTransfer_success() {
        /* --- given --- */
        when(accountRepo.findByAccountNumber("123456789"))
                .thenReturn(Optional.of(srcAcc));

        when(currencyRepo.findByCode(CurrencyType.USD))
                .thenReturn(Optional.of(usd));                // used inside sub-method
        when(userSvc.getCustomerById(1L)).thenReturn(customer);

        // saveAndFlush inside createForeignBankMoneyTransferEntity must return entity w/ id
        Mockito.doAnswer(inv -> {
            Transfer t = inv.getArgument(0, Transfer.class);
            t.setId(uuid);      // pretend DB generated id = 77
            return t;
        }).when(transferRepo).saveAndFlush(any(Transfer.class));

        when(otpSvc.generateOtp(uuid)).thenReturn("OTP-CODE");
        when(transferRepo.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        /* --- when --- */
        UUID id = service.createForeignBankTransfer(dtoOk);

        /* --- then --- */
        assertEquals(uuid, id);

        // OTP generated & written back
        verify(otpSvc).generateOtp(uuid);
        verify(transferRepo).save(argThat(t -> "OTP-CODE".equals(t.getOtp())));


        // made sure e-mail DTO contains the otp string
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        assertFalse(captor.getAllValues().stream()
                         .anyMatch(msg -> msg.toString().contains("OTP-CODE")));
    }

    /* =================================================================== */
    /* 2) account not found                                               */
    /* =================================================================== */
    @Test
    @DisplayName("createForeignBankTransfer – account missing → exception")
    void testAccountMissing() {
        when(accountRepo.findByAccountNumber("123456789")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.createForeignBankTransfer(dtoOk));

        assertEquals("Račun nije pronađen", ex.getMessage());
        verifyNoInteractions(userSvc, otpSvc, jms);
    }

    /* =================================================================== */
    /* 3) customer not found                                              */
    /* =================================================================== */
    @Test
    @DisplayName("createForeignBankTransfer – customer missing → exception")
    void testCustomerMissing() {
        when(accountRepo.findByAccountNumber("123456789"))
                .thenReturn(Optional.of(srcAcc));
        when(userSvc.getCustomerById(1L)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.createForeignBankTransfer(dtoOk));

        assertEquals("Korisnik nije pronađen", ex.getMessage());
        verifyNoInteractions(otpSvc, jms);
    }
}
