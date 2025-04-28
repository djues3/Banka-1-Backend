package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDTO;
import com.banka1.banking.dto.MoneyTransferDTO;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.VoteReasonDTO;
import com.banka1.banking.dto.interbank.committx.CommitTransactionDTO;
import com.banka1.banking.dto.interbank.internal.PremiumPaymentDTO;
import com.banka1.banking.dto.interbank.newtx.ForeignBankIdDTO;
import com.banka1.banking.dto.interbank.newtx.InterbankTransactionDTO;
import com.banka1.banking.dto.interbank.newtx.PostingDTO;
import com.banka1.banking.dto.interbank.newtx.TxAccountDTO;
import com.banka1.banking.dto.interbank.newtx.assets.CurrencyAsset;
import com.banka1.banking.dto.interbank.newtx.assets.MonetaryAssetDTO;
import com.banka1.banking.dto.interbank.rollbacktx.RollbackTransactionDTO;
import com.banka1.banking.models.*;
import com.banka1.banking.models.helper.CurrencyType;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.CurrencyRepository;
import com.banka1.banking.repository.EventDeliveryRepository;
import com.banka1.banking.services.requests.RequestBuilder;
import com.banka1.banking.services.requests.RequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class InterbankService implements InterbankOperationService {

    private final EventService eventService;
    private final EventDeliveryRepository eventDeliveryRepository;
    private final EventExecutorService eventExecutorService;
    private final ObjectMapper objectMapper;
    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final CurrencyRepository currencyRepository;
    private final InterbankConfig config;
    private final RequestService requestService;

    public InterbankService(
            EventService eventService,
            EventDeliveryRepository eventDeliveryRepository,
            EventExecutorService eventExecutorService,
            ObjectMapper objectMapper,
            @Lazy TransferService transferService,
            AccountRepository accountRepository,
            CurrencyRepository currencyRepository,
            InterbankConfig config,
            RequestService requestService) {
        this.eventService = eventService;
        this.eventDeliveryRepository = eventDeliveryRepository;
        this.eventExecutorService = eventExecutorService;
        this.objectMapper = objectMapper;
        this.transferService = transferService;
        this.accountRepository = accountRepository;
        this.currencyRepository = currencyRepository;
        this.config = config;
        this.requestService = requestService;
    }

    public void sendInterbankMessage(InterbankMessageDTO<?> messageDto, String targetUrl) {

        System.out.println("####################");
        System.out.println(
                "Sending interbank message: "
                        + messageDto.getMessageType()
                        + " "
                        + messageDto.getIdempotenceKey().getLocallyGeneratedKey());

        Event event;
        try {

            validateMessageByType(messageDto);
            log.info("Sending message: {}", messageDto);
            String payloadJson = objectMapper.writeValueAsString(messageDto);
            System.out.println("trying to send interbank message: " + payloadJson);
            event =
                    eventService.createEvent(
                            new CreateEventDTO(messageDto, payloadJson, targetUrl));

            System.out.println("Attempting to send event: " + event.getId());

        } catch (Exception ex) {
            throw new RuntimeException("Failed to send interbank message", ex);
        }

        try {
            eventExecutorService.attemptEventAsync(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send interbank message", e);
        }
    }

    public void sendNewTXMessage(Transfer transfer) {
        InterbankMessageDTO<InterbankTransactionDTO> transaction = new InterbankMessageDTO<>();
        transaction.setMessageType(InterbankMessageType.NEW_TX);

        // Set up the idempotence key
        IdempotenceKey idempotenceKey =
                new IdempotenceKey(config.getRoutingNumber(), transfer.getId().toString());
        transaction.setIdempotenceKey(idempotenceKey);

        ForeignBankIdDTO txId =
                new ForeignBankIdDTO(
                        idempotenceKey.getRoutingNumber(), idempotenceKey.getLocallyGeneratedKey());

        InterbankTransactionDTO message = new InterbankTransactionDTO();

        /*
          Set idempotence key as id for the transaction, will be used to extract
        */
        message.setTransactionId(txId);

        // Set up timestamp
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        message.setTimestamp(ZonedDateTime.now().format(formatter));

        // Set up the transaction details
        message.setPostings(
                List.of(
                        new PostingDTO(
                                new TxAccountDTO(
                                        "ACCOUNT",
                                        null,
                                        transfer.getFromAccountId().getAccountNumber()),
                                -transfer.getAmount(),
                                new MonetaryAssetDTO(
                                        new CurrencyAsset(
                                                transfer.getToCurrency().getCode().toString()))),
                        new PostingDTO(
                                new TxAccountDTO("ACCOUNT", null, transfer.getNote()),
                                transfer.getAmount(),
                                new MonetaryAssetDTO(
                                        new CurrencyAsset(
                                                transfer.getFromCurrency()
                                                        .getCode()
                                                        .toString())))));

        message.setMessage(transfer.getPaymentDescription());

        transaction.setMessage(message);

        // Send the message
        // execute after five seconds for testing purposes
        System.out.println("Sending interbank message: " + transaction);

        try {
            Thread.sleep(1000);
            sendInterbankMessage(transaction, config.getInterbankTargetUrl());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private IdempotenceKey generateIdempotenceKey(InterbankMessageDTO<?> messageDto) {
        IdempotenceKey idempotenceKey = new IdempotenceKey();
        idempotenceKey.setRoutingNumber(config.getRoutingNumber());
        idempotenceKey.setLocallyGeneratedKey(UUID.randomUUID().toString());
        return idempotenceKey;
    }

    public VoteDTO webhook(InterbankMessageDTO<?> messageDto, String rawPayload, String sourceUrl) {
        //        eventService.receiveEvent(messageDto, rawPayload, sourceUrl);
        if (eventService.existsByIdempotenceKey(messageDto.getIdempotenceKey(), messageDto.getMessageType())) {
            var event = eventService.findEventByIdempotenceKey(messageDto.getIdempotenceKey());
            // Since events get saved before the request reaches this point in the interceptor,
            // there needs to be a way to ignore them.
            // This is that way.
            if (event.getCreatedAt().plusMillis(100).isBefore(Instant.now())) {
                log.info(
                        "Replaying event with idempotence key: {}", messageDto.getIdempotenceKey());
                return replayEvent(messageDto.getIdempotenceKey());
            }

        }

        VoteDTO response = new VoteDTO();

        System.out.println("==========================");
        System.out.println(
                "Received interbank message: "
                        + messageDto.getMessageType()
                        + " "
                        + messageDto.getIdempotenceKey().getLocallyGeneratedKey());

        switch (messageDto.getMessageType()) {
            case NEW_TX:
                System.out.println("Received NEW_TX message: " + messageDto.getMessage());
                InterbankMessageDTO<InterbankTransactionDTO> properNewTx =
                        objectMapper.convertValue(
                                messageDto,
                                objectMapper
                                        .getTypeFactory()
                                        .constructParametricType(
                                                InterbankMessageDTO.class,
                                                InterbankTransactionDTO.class));
                response = handleNewTXRequest(properNewTx);
                break;
            case COMMIT_TX:
                System.out.println("Received COMMIT_TX message: " + messageDto.getMessage());

                InterbankMessageDTO<CommitTransactionDTO> properCommitTx =
                        objectMapper.convertValue(
                                messageDto,
                                objectMapper
                                        .getTypeFactory()
                                        .constructParametricType(
                                                InterbankMessageDTO.class,
                                                CommitTransactionDTO.class));
                try {
                    handleCommitTXRequest(properCommitTx);
                } catch (Exception e) {
                    e.printStackTrace();
                    response.setVote("NO");
                    response.setReasons(List.of(new VoteReasonDTO("COMMIT_TX_FAILED", null)));
                    return response;
                }
                response.setVote("YES");
                break;
            case ROLLBACK_TX:
                response.setVote("YES");
                break;
            default:
                throw new IllegalArgumentException("Unknown message type");
        }

        return response;
    }

    private VoteDTO replayEvent(IdempotenceKey idempotenceKey) {
        var event = eventService.findEventByIdempotenceKey(idempotenceKey);
        var delivery = eventDeliveryRepository.findFirstEventDeliveryByEvent(event).orElseThrow(() -> new IllegalArgumentException("Event delivery not found"));
	    try {
		    return objectMapper.readValue(delivery.getResponseBody(), VoteDTO.class);
	    } catch (JsonProcessingException e) {
		    throw new RuntimeException(e);
	    }


    }

    public void handleCommitTXRequest(InterbankMessageDTO<CommitTransactionDTO> messageDto) {
        log.info("Commit tx request: {}", messageDto);
        Event event =
                eventService.findEventByTransactionId(messageDto.getMessage().getTransactionId());
        if (event == null) {
            throw new IllegalArgumentException(
                    "Event not found for idempotence key: " + messageDto.getIdempotenceKey());
        }

        InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage;
        try {
            originalNewTxMessage =
                    objectMapper.readValue(
                            event.getPayload(),
                            objectMapper
                                    .getTypeFactory()
                                    .constructParametricType(
                                            InterbankMessageDTO.class,
                                            InterbankTransactionDTO.class));
        } catch (Exception e) {
            log.error("Failed to parse original NEW_TX message: {}", e.getMessage());
            throw new RuntimeException("Failed to parse NEW_TX payload: " + e.getMessage());
        }

        System.out.println("Handling COMMIT_TX message: " + event.getPayload());
        InterbankTransactionDTO originalMessage = originalNewTxMessage.getMessage();

        if (messageDto.getMessage().getTransactionId().getId().startsWith("premium-") || messageDto.getMessage().getTransactionId().getId().startsWith("tx-")) {
            forwardCommitOriginal(messageDto);
        }

        if (originalMessage.getPostings().size() == 2) {
            handle2PostingCommit(messageDto, originalMessage, originalNewTxMessage);
        }

        if (originalMessage.getPostings().size() == 4) {
            handle4PostingCommit(messageDto, originalMessage, originalNewTxMessage);
        }
    }

    public void handle2PostingCommit(InterbankMessageDTO<CommitTransactionDTO> messageDto, InterbankTransactionDTO originalMessage, InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage) {
        Account localAccount = null;
        Currency localCurrency = null;
        double amount = 0.0;
        String localAccountId = null;

        for (PostingDTO posting : originalMessage.getPostings()) {
            if (!(posting.getAsset() instanceof MonetaryAssetDTO)) {
                forwardCommit(originalNewTxMessage);
                return;
            }

            TxAccountDTO account = posting.getAccount();
            if ("ACCOUNT".equals(account.getType())) {
                String accountId = account.getNum();
                if (accountId != null && accountId.startsWith(config.getRoutingNumber())) {
                    localAccountId = accountId;
                    amount = posting.getAmount();
                    System.out.println("AMOUNT: " + amount);

                    Optional<Account> localAccountOpt = accountRepository.findByAccountNumber(localAccountId);
                    if (localAccountOpt.isPresent()) {
                        localAccount = localAccountOpt.get();
                    } else {
                        throw new IllegalArgumentException("Local account not found: " + localAccountId);
                    }

                    if (posting.getAsset() instanceof MonetaryAssetDTO asset) {
                        if (asset.getAsset() != null) {
                            CurrencyType currencyType = CurrencyType.fromString(asset.getAsset().getCurrency());
                            Optional<Currency> currencyOpt = currencyRepository.findByCode(currencyType);
                            if (currencyOpt.isPresent()) {
                                localCurrency = currencyOpt.get();
                            } else {
                                throw new IllegalArgumentException("Currency not found: " + currencyType);
                            }
                        } else {
                            throw new IllegalArgumentException("Invalid asset type");
                        }
                    } else {
                        throw new IllegalArgumentException("Invalid asset type");
                    }
                }
            }
        }

        if (localAccount == null) {
            throw new IllegalArgumentException("Local account not found");
        }

        Transfer transfer =
                transferService.receiveForeignBankTransfer(
                        localAccount.getAccountNumber(),
                        amount,
                        originalMessage.getMessage(),
                        "Banka 4",
                        localCurrency);

        if (transfer == null) {
            throw new IllegalArgumentException("Failed to create transfer");
        }
    }

    public void handle4PostingCommit(InterbankMessageDTO<CommitTransactionDTO> messageDto, InterbankTransactionDTO originalMessage, InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage) {
//        try {
//            String jsonString = objectMapper.writeValueAsString(originalMessage.getPostings());
//
//            HttpResponse<String> response = requestService.send(
//                    new RequestBuilder()
//                            .method("POST")
//                            .url(config.getTradingServiceUrl() + "/validate-postings")
//                            .body(jsonString)
//                            .addHeader("Content-Type", "application/json")
//            );
//
//            if (response.statusCode() != 200) {
//                throw new RuntimeException("Trading servis nije prihvatio postings. Status: " + response.statusCode());
//            }
//        } catch (Exception e) {
//            throw new RuntimeException("Greška prilikom komunikacije sa Trading servisom: " + e.getMessage(), e);
//        }

        boolean foundLocalMonas = false;

        for (PostingDTO posting : originalMessage.getPostings()) {
            if (!(posting.getAsset() instanceof MonetaryAssetDTO asset)) {
                continue;
            }

            TxAccountDTO account = posting.getAccount();
            boolean isLocal = false;
            String localAccountNumber = null;

            if ("ACCOUNT".equals(account.getType())) {
                if (account.getNum() != null && account.getNum().startsWith(config.getRoutingNumber())) {
                    isLocal = true;
                    localAccountNumber = account.getNum();
                }
            } else if ("PERSON".equals(account.getType())) {
                if (account.getId() != null && config.getRoutingNumber().equals(account.getId().getRoutingNumber())) {
                    isLocal = true;
                    localAccountNumber = account.getId().getId();
                }
            }

            if (isLocal) {
                foundLocalMonas = true;

                Optional<Account> localAccountOpt = accountRepository.findByAccountNumber(localAccountNumber);
                if (localAccountOpt.isEmpty()) {
                    throw new IllegalArgumentException("Local account not found: " + localAccountNumber);
                }
                Account localAccount = localAccountOpt.get();

                CurrencyType currencyType = CurrencyType.fromString(asset.getAsset().getCurrency());
                Optional<Currency> currencyOpt = currencyRepository.findByCode(currencyType);
                if (currencyOpt.isEmpty()) {
                    throw new IllegalArgumentException("Currency not found: " + currencyType);
                }
                Currency localCurrency = currencyOpt.get();

                Transfer transfer = transferService.receiveForeignBankTransfer(
                        localAccount.getAccountNumber(),
                        posting.getAmount(),
                        originalMessage.getMessage(),
                        "Banka 4",
                        localCurrency
                );

                if (transfer == null) {
                    throw new RuntimeException("Failed to create transfer for account: " + localAccountNumber);
                }
            }
        }

        if (!foundLocalMonas) {
            System.out.println("Found no local MONAS postings");
        }
    }



    public VoteDTO handleNewTXRequest(InterbankMessageDTO<InterbankTransactionDTO> messageDto) {
        // check if all postings are monetary
        VoteDTO response = new VoteDTO();
        try {
            InterbankTransactionDTO message = messageDto.getMessage();
            List<PostingDTO> postings = message.getPostings();

            String currencyCode = null;

            if (postings == null || postings.size() != 2) {
                response.setVote("NO");
                response.setReasons(List.of(new VoteReasonDTO("NO_POSTINGS", null)));
                return response;
            }

            String localAcountId = null;

            boolean forwardToTradingService = false;

            for (PostingDTO posting : postings) {
                if (posting.getAsset() instanceof MonetaryAssetDTO asset) {
                    if (asset.getAsset().getCurrency() == null) {
                        response.setVote("NO");
                        response.setReasons(List.of(new VoteReasonDTO("NO_SUCH_ASSET", posting)));
                        System.out.println("No currency code for asset");
                        return response;
                    }

                    if (currencyCode == null) {
                        currencyCode = asset.getAsset().getCurrency();
                    } else if (!currencyCode.equals(asset.getAsset().getCurrency())) {
                        response.setVote("NO");
                        response.setReasons(List.of(new VoteReasonDTO("NO_SUCH_ASSET", posting)));
                        System.out.println("Different currency codes for assets");
                        return response;
                    }

                    TxAccountDTO account = posting.getAccount();

                    switch (account.getType()) {
                        case "PERSON", "OPTION" -> {
                            if (account.getId() == null
                                    || account.getId().getId() == null
                                    || account.getId().getId().isEmpty()) {
                                response.setVote("NO");
                                response.setReasons(
                                        List.of(
                                                new VoteReasonDTO(
                                                        "INVALID_POSTING_FORMAT", posting)));
                                return response;
                            }
                        }
                        case "ACCOUNT" -> {
                            String accountId = account.getNum();
                            if (accountId == null) {
                                response.setVote("NO");
                                response.setReasons(
                                        List.of(new VoteReasonDTO("NO_SUCH_ACCOUNT", posting)));
                                System.out.println("No account number for posting");
                                return response;
                            }
                            if (!accountId.startsWith(config.getRoutingNumber())) {
                                continue;
                            }
                            localAcountId = accountId;
                        }
                    }
                } else {
                    forwardToTradingService = true;
                }
            }
            log.info("Local account id: {}", localAcountId);
            // if any of the assets is not monetary, forward to trading service and wait for
            // response
            if (forwardToTradingService) {
                response = forwardNewTX(messageDto);
                return response;
            }

            // check if the local account exists
            Optional<Account> localAccountOpt =
                    accountRepository.findByAccountNumber(localAcountId);
            if (localAccountOpt.isEmpty()) {
                System.out.println("No such account: " + localAcountId);
                response.setVote("NO");
                response.setReasons(List.of(new VoteReasonDTO("NO_SUCH_ACCOUNT", null)));
                return response;
            }

            response.setVote("YES");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to handle new transaction request: " + e.getMessage());
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public void validateMessageByType(InterbankMessageDTO<?> dto) {
        InterbankMessageType type = dto.getMessageType();
        Object message = dto.getMessage();

        switch (type) {
            case NEW_TX -> {
                if (!(message instanceof InterbankTransactionDTO)) {
                    throw new IllegalArgumentException(
                            "Expected InterbankTransactionDTO for NEW_TX");
                }
            }
            case COMMIT_TX -> {
                if (!(message instanceof CommitTransactionDTO)) {
                    throw new IllegalArgumentException(
                            "Expected CommitTransactionDTO for COMMIT_TX");
                }
            }
            case ROLLBACK_TX -> {
                if (!(message instanceof RollbackTransactionDTO)) {
                    throw new IllegalArgumentException(
                            "Expected RollbackTransactionDTO for ROLLBACK_TX");
                }
            }
            default -> throw new IllegalArgumentException("Unknown message type");
        }
    }

    @Override
    public void sendCommit(Event event) {
        var originalMessage = event.getPayload();
        try {
            InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage =
                    objectMapper.readValue(
                            event.getPayload(),
                            objectMapper
                                    .getTypeFactory()
                                    .constructParametricType(
                                            InterbankMessageDTO.class,
                                            InterbankTransactionDTO.class));
            log.info("Original message: {}", originalMessage);
            System.out.println("Sending commit for event: " + event.getId());

            InterbankMessageDTO<CommitTransactionDTO> message = new InterbankMessageDTO<>();
            message.setMessageType(InterbankMessageType.COMMIT_TX);
            IdempotenceKey idempotenceKey = generateIdempotenceKey(message);
            message.setIdempotenceKey(idempotenceKey);

            CommitTransactionDTO commitTransactionDTO = new CommitTransactionDTO();
            commitTransactionDTO.setTransactionId(
                    originalNewTxMessage.getMessage().getTransactionId());

            message.setMessage(commitTransactionDTO);

            transferService.commitForeignBankTransfer(event.getIdempotenceKey());

            sendInterbankMessage(message, config.getInterbankTargetUrl());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sendRollback(Event event) {
        System.out.println("Sending rollback for event: " + event.getId());

        InterbankMessageDTO<RollbackTransactionDTO> message = new InterbankMessageDTO<>();
        message.setMessageType(InterbankMessageType.ROLLBACK_TX);
        IdempotenceKey idempotenceKey = generateIdempotenceKey(message);
        message.setIdempotenceKey(idempotenceKey);

        RollbackTransactionDTO rollbackTransactionDTO = new RollbackTransactionDTO();
        rollbackTransactionDTO.setTransactionId(
                new ForeignBankIdDTO(
                        event.getIdempotenceKey().getRoutingNumber(),
                        event.getIdempotenceKey().getLocallyGeneratedKey()));

        message.setMessage(rollbackTransactionDTO);

        try {
            Thread.sleep(1000);
            sendInterbankMessage(message, config.getInterbankTargetUrl());

            transferService.rollbackForeignBankTransfer(event.getIdempotenceKey());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private VoteDTO forwardNewTX(InterbankMessageDTO<InterbankTransactionDTO> messageDto) {
        String jsonString;
        try {
            jsonString = objectMapper.writeValueAsString(messageDto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert message to JSON: " + e.getMessage());
        }

        try {
            HttpResponse<String> response =
                    requestService.send(
                            new RequestBuilder()
                                    .method("POST")
                                    .url(config.getTradingServiceUrl())
                                    .body(jsonString)
                                    .addHeader("Content-Type", "application/json"));

            VoteDTO voteDTO = objectMapper.readValue(response.body(), VoteDTO.class);
            if (voteDTO == null) {
                throw new RuntimeException("Failed to parse response from trading service");
            }

            if (voteDTO.getVote() == null || voteDTO.getVote().isEmpty()) {
                throw new RuntimeException("Invalid response from trading service");
            }

            return voteDTO;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to forward message to trading service: " + e.getMessage());
        }
    }

    private VoteDTO forwardCommit(InterbankMessageDTO<InterbankTransactionDTO> messageDto) {
        messageDto.setMessageType(InterbankMessageType.COMMIT_TX);
        String jsonString;
        try {
            jsonString = objectMapper.writeValueAsString(messageDto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert message to JSON: " + e.getMessage());
        }

        try {
            HttpResponse<String> response =
                    requestService.send(
                            new RequestBuilder()
                                    .method("POST")
                                    .url(config.getTradingServiceUrl())
                                    .body(jsonString)
                                    .addHeader("Content-Type", "application/json"));

            VoteDTO voteDTO = objectMapper.readValue(response.body(), VoteDTO.class);
            if (voteDTO == null) {
                throw new RuntimeException("Failed to parse response from trading service");
            }

            if (voteDTO.getVote() == null || voteDTO.getVote().isEmpty()) {
                throw new RuntimeException("Invalid response from trading service");
            }

            return voteDTO;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to forward message to trading service: " + e.getMessage());
        }
    }

    private VoteDTO forwardCommitOriginal(InterbankMessageDTO<CommitTransactionDTO> messageDto) {
        String jsonString;
        try {
            jsonString = objectMapper.writeValueAsString(messageDto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert message to JSON: " + e.getMessage());
        }

        try {
            HttpResponse<String> response =
                    requestService.send(
                            new RequestBuilder()
                                    .method("POST")
                                    .url(config.getTradingServiceUrl())
                                    .body(jsonString)
                                    .addHeader("Content-Type", "application/json"));

            VoteDTO voteDTO = objectMapper.readValue(response.body(), VoteDTO.class);
            if (voteDTO == null) {
                throw new RuntimeException("Failed to parse response from trading service");
            }

            if (voteDTO.getVote() == null || voteDTO.getVote().isEmpty()) {
                throw new RuntimeException("Invalid response from trading service");
            }

            return voteDTO;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to forward message to trading service: " + e.getMessage());
        }
    }

    public void internal(InterbankMessageDTO<?> message) {
        // this function receives message from our other service and should be just forwarded to the foreign bank

        // check if idempotence key already exists
        if (eventService.existsByIdempotenceKey(message.getIdempotenceKey(), message.getMessageType())) {
            return;
        }

        // forward the message to the foreign bank
        String jsonString;
        try {
            jsonString = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert message to JSON: " + e.getMessage());
        }

        try {
            HttpResponse<String> response =
                    requestService.send(
                            new RequestBuilder()
                                    .method("POST")
                                    .url(config.getInterbankTargetUrl())
                                    .body(jsonString)
                                    .addHeader("Content-Type", "application/json"));

            VoteDTO voteDTO = objectMapper.readValue(response.body(), VoteDTO.class);
            if (voteDTO == null) {
                throw new RuntimeException("Failed to parse response from foreign bank");
            }

            if (voteDTO.getVote() == null || voteDTO.getVote().isEmpty()) {
                throw new RuntimeException("Invalid response from foreign bank");
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to forward message to foreign bank: " + e.getMessage());
        }
    }
}
