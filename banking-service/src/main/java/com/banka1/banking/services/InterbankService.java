package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDTO;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.VoteReasonDTO;
import com.banka1.banking.dto.interbank.committx.CommitTransactionDTO;
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
import com.banka1.banking.models.helper.TransferStatus;
import com.banka1.banking.models.helper.TransferType;
import com.banka1.banking.repository.AccountRepository;
import com.banka1.banking.repository.CurrencyRepository;
import com.banka1.banking.repository.EventDeliveryRepository;
import com.banka1.banking.repository.TransferRepository;
import com.banka1.banking.services.requests.RequestBuilder;
import com.banka1.banking.services.requests.RequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
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
    private final TransferRepository transferRepository;

    public InterbankService(
            EventService eventService,
            EventDeliveryRepository eventDeliveryRepository,
            EventExecutorService eventExecutorService,
            ObjectMapper objectMapper,
            @Lazy TransferService transferService,
            AccountRepository accountRepository,
            CurrencyRepository currencyRepository,
            InterbankConfig config,
            RequestService requestService,
            TransferRepository transferRepository) {
        this.eventService = eventService;
        this.eventDeliveryRepository = eventDeliveryRepository;
        this.eventExecutorService = eventExecutorService;
        this.objectMapper = objectMapper;
        this.transferService = transferService;
        this.accountRepository = accountRepository;
        this.currencyRepository = currencyRepository;
        this.config = config;
        this.requestService = requestService;
        this.transferRepository = transferRepository;
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
            if (!eventService.existsByIdempotenceKey(
                    messageDto.getIdempotenceKey(), messageDto.getMessageType()))
                event =
                        eventService.createEvent(
                                new CreateEventDTO(messageDto, payloadJson, targetUrl));
            else event = eventService.findEventByIdempotenceKey(messageDto.getIdempotenceKey());
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
                new IdempotenceKey(
                        Integer.valueOf(config.getRoutingNumber()), transfer.getId().toString());
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
                                BigDecimal.valueOf(-transfer.getAmount()),
                                new MonetaryAssetDTO(
                                        new CurrencyAsset(
                                                transfer.getToCurrency().getCode().toString()))),
                        new PostingDTO(
                                new TxAccountDTO("ACCOUNT", null, transfer.getNote()),
                                BigDecimal.valueOf(transfer.getAmount()),
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

        sendInterbankMessage(transaction, config.getInterbankTargetUrl());
    }

    private IdempotenceKey generateIdempotenceKey(InterbankMessageDTO<?> messageDto) {
        IdempotenceKey idempotenceKey = new IdempotenceKey();
        idempotenceKey.setRoutingNumber(Integer.valueOf(config.getRoutingNumber()));
        idempotenceKey.setLocallyGeneratedKey(UUID.randomUUID().toString());
        return idempotenceKey;
    }

    public VoteDTO webhook(InterbankMessageDTO<?> messageDto, String rawPayload, String sourceUrl) {
        //        eventService.receiveEvent(messageDto, rawPayload, sourceUrl);
        if (eventService.shouldReplay(
                messageDto.getIdempotenceKey(), messageDto.getMessageType())) {
            return replayEvent(messageDto.getIdempotenceKey());
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
        var delivery =
                eventDeliveryRepository
                        .findFirstEventDeliveryByEvent(event)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Event delivery not found"));
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

        if (originalMessage.getPostings().size() == 2) {
            handle2PostingCommit(messageDto, originalMessage, originalNewTxMessage);
        }

        if (originalMessage.getPostings().size() == 4) {
            handle4PostingCommit(messageDto, originalMessage, originalNewTxMessage);
        }
    }

    public void handle2PostingCommit(
            InterbankMessageDTO<CommitTransactionDTO> messageDto,
            InterbankTransactionDTO originalMessage,
            InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage) {
        Account localAccount = null;
        Currency localCurrency = null;
        BigDecimal amount = BigDecimal.ZERO;
        String localAccountId;

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

                    Optional<Account> localAccountOpt =
                            accountRepository.findByAccountNumber(localAccountId);
                    if (localAccountOpt.isPresent()) {
                        localAccount = localAccountOpt.get();
                    } else {
                        throw new IllegalArgumentException(
                                "Local account not found: " + localAccountId);
                    }

                    if (posting.getAsset() instanceof MonetaryAssetDTO asset) {
                        if (asset.getAsset() != null) {
                            CurrencyType currencyType =
                                    CurrencyType.fromString(asset.getAsset().getCurrency());
                            Optional<Currency> currencyOpt =
                                    currencyRepository.findByCode(currencyType);
                            if (currencyOpt.isPresent()) {
                                localCurrency = currencyOpt.get();
                            } else {
                                throw new IllegalArgumentException(
                                        "Currency not found: " + currencyType);
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
                        amount.doubleValue(),
                        originalMessage.getMessage(),
                        "Banka 4",
                        localCurrency);

        if (transfer == null) {
            throw new IllegalArgumentException("Failed to create transfer");
        }
    }

    public void handle4PostingCommit(
            InterbankMessageDTO<CommitTransactionDTO> messageDto,
            InterbankTransactionDTO originalMessage,
            InterbankMessageDTO<InterbankTransactionDTO> originalNewTxMessage) {

        boolean foundLocalMonas = false;
        forwardCommit(originalNewTxMessage);

        for (PostingDTO posting : originalMessage.getPostings()) {
            if (!(posting.getAsset() instanceof MonetaryAssetDTO asset)) {
                continue;
            }

            TxAccountDTO account = posting.getAccount();
            boolean isLocal = false;
            String localAccountNumber = null;

            if ("ACCOUNT".equals(account.getType())) {
                if (account.getNum() != null
                        && account.getNum().startsWith(config.getRoutingNumber())) {
                    isLocal = true;
                    localAccountNumber = account.getNum();
                }
            } else if ("PERSON".equals(account.getType())) {
                if (account.getId() != null
                        && config.getRoutingNumber().equals(account.getId().getRoutingNumber())) {
                    isLocal = true;
                    localAccountNumber = account.getId().getId();
                }
            }

            if (isLocal) {
                foundLocalMonas = true;

                Optional<Account> localAccountOpt =
                        accountRepository.findByAccountNumber(localAccountNumber);
                if (localAccountOpt.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Local account not found: " + localAccountNumber);
                }
                Account localAccount = localAccountOpt.get();

                CurrencyType currencyType = CurrencyType.fromString(asset.getAsset().getCurrency());
                Optional<Currency> currencyOpt = currencyRepository.findByCode(currencyType);
                if (currencyOpt.isEmpty()) {
                    throw new IllegalArgumentException("Currency not found: " + currencyType);
                }
                Currency localCurrency = currencyOpt.get();

                Transfer transfer =
                        transferService.receiveForeignBankTransfer(
                                localAccount.getAccountNumber(),
                                posting.getAmount().doubleValue(),
                                originalMessage.getMessage(),
                                "Banka 4",
                                localCurrency);

                if (transfer == null) {
                    throw new RuntimeException(
                            "Failed to create transfer for account: " + localAccountNumber);
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

            if (postings == null || (postings.size() != 2 && postings.size() != 4)) {
                response.setVote("NO");
                response.setReasons(List.of(new VoteReasonDTO("NO_POSTINGS", null)));
                return response;
            }

            String localAccountId = null;

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
                        case "OPTION" -> {
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
                            localAccountId = accountId;
                        }
                        case "PERSON" -> {
                            if (!account.getId()
                                    .getRoutingNumber()
                                    .toString()
                                    .equals(config.getRoutingNumber())) {
                                continue;
                            }
                            var userId = account.getId().getId();
                            if (userId == null || userId.isEmpty()) {
                                response.setVote("NO");
                                response.setReasons(
                                        List.of(new VoteReasonDTO("NO_SUCH_USER", posting)));
                                log.info("No user id for posting");
                                return response;
                            }
                            List<Account> accounts =
                                    accountRepository.findByOwnerID(Long.parseLong(userId)).stream()
                                            .filter(
                                                    acc ->
                                                            acc.getCurrencyType()
                                                                    .equals(CurrencyType.USD))
                                            .toList();
                            if (accounts.isEmpty()) {
                                response.setVote("NO");
                                response.setReasons(
                                        List.of(new VoteReasonDTO("NO_SUCH_ACCOUNT", posting)));
                                log.info("No USD account for user id: {}", userId);
                                return response;
                            }
                            localAccountId = accounts.get(0).getAccountNumber();
                        }
                    }
                } else {
                    forwardToTradingService = true;
                }
            }
            log.info("Local account id: {}", localAccountId);
            // if any of the assets is not monetary, forward to trading service and wait for
            // response
            if (forwardToTradingService) {
                log.info("Forwarding to trading service: {}", messageDto);
                response = forwardNewTX(messageDto);
                return response;
            }

            // check if the local account exists
            Optional<Account> localAccountOpt =
                    accountRepository.findByAccountNumber(localAccountId);
            if (localAccountOpt.isEmpty()) {
                System.out.println("No such account: " + localAccountId);
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
            if (originalNewTxMessage.getMessage().getPostings().size() == 4) {
                forwardCommitOriginal(message);
            }
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
            log.info("Trading service responded with: {}", voteDTO);
            return voteDTO;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to forward message to trading service: " + e.getMessage());
        }
    }

    private VoteDTO forwardCommit(InterbankMessageDTO<InterbankTransactionDTO> messageDto) {
        messageDto.setMessageType(InterbankMessageType.COMMIT_TX);
        log.info("Forwarding commit message to trading service: {}", messageDto);
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

    public void internal(InterbankMessageDTO<InterbankTransactionDTO> message) {
        if (eventService.shouldReplay(message.getIdempotenceKey(), message.getMessageType())) {
            log.info("Idempotence key already exists: {}", message.getIdempotenceKey());
            return;
        }

        try {
            // Extract the transaction data from the message
            InterbankTransactionDTO transactionData = message.getMessage();

            // Find our account in the postings
            PostingDTO ourPosting = null;
            String localAccountNumber = null;

            for (PostingDTO posting : transactionData.getPostings()) {
                TxAccountDTO account = posting.getAccount();

                if ("ACCOUNT".equals(account.getType())) {
                    if (account.getNum() != null
                            && account.getNum().startsWith(config.getRoutingNumber())) {
                        ourPosting = posting;
                        localAccountNumber = account.getNum();
                        break;
                    }
                }
            }

            if (ourPosting == null || localAccountNumber == null) {
                throw new RuntimeException("No matching local account found in transaction");
            }

            // Check if the asset is a monetary asset
            if (!(ourPosting.getAsset() instanceof MonetaryAssetDTO monetaryAsset)) {
                throw new RuntimeException("Posting does not contain a monetary asset");
            }

            // Get the account
            String finalLocalAccountNumber = localAccountNumber;
            Account fromAccount =
                    accountRepository
                            .findByAccountNumber(localAccountNumber)
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "Source account not found: "
                                                            + finalLocalAccountNumber));

            // Get amount (note: might be negative, so we use the absolute value for the transfer)
            BigDecimal amount = ourPosting.getAmount().abs();

            // Validate that the account has sufficient funds
            if (fromAccount.getBalance() < amount.doubleValue()) {
                log.error("Insufficient funds in account: {}", fromAccount.getAccountNumber());
                throw new RuntimeException("Insufficient funds for transfer");
            }

            // Find or create currency
            CurrencyType currencyType =
                    CurrencyType.fromString(monetaryAsset.getAsset().getCurrency());
            Currency currency =
                    currencyRepository
                            .findByCode(currencyType)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Currency not found: " + currencyType));

            // Create transfer entity
            Transfer transfer = new Transfer();
            transfer.setId(UUID.fromString(message.getIdempotenceKey().getLocallyGeneratedKey()));
            transfer.setFromAccountId(fromAccount);
            transfer.setToAccountId(null); // External transfer to another bank
            transfer.setAmount(amount.doubleValue());
            transfer.setStatus(TransferStatus.RESERVED);
            transfer.setType(TransferType.FOREIGN_BANK);
            transfer.setFromCurrency(currency);
            transfer.setToCurrency(currency);
            transfer.setPaymentDescription(transactionData.getMessage());
            transfer.setCreatedAt(System.currentTimeMillis());

            // Set recipient information from other postings if available
            for (PostingDTO posting : transactionData.getPostings()) {
                // Look for the counterparty posting (opposite sign amount)
                if (posting != ourPosting
                        && posting.getAmount().signum() != ourPosting.getAmount().signum()
                        && posting.getAccount().getType().equals("PERSON")) {

                    // Set recipient info
                    transfer.setReceiver("External Bank Customer");
                    if (posting.getAccount().getId() != null) {
                        transfer.setNote(
                                posting.getAccount().getId().getRoutingNumber()
                                        + ":"
                                        + posting.getAccount().getId().getId());
                    }
                    break;
                }
            }
            transferRepository.insertTransferWithId(
                    transfer.getId(),
                    transfer.getFromAccountId().getId(),
                    transfer.getToAccountId() != null ? transfer.getToAccountId().getId() : null,
                    transfer.getAmount(),
                    "External Bank Customer",
                    "Premium for option contract",
                    currency.getId(),
                    currency.getId(),
                    transfer.getCreatedAt(),
                    transfer.getType().toString(),
                    transfer.getStatus().toString(),
                    transfer.getPaymentDescription());

            fromAccount.setBalance(fromAccount.getBalance() - amount.doubleValue());
            fromAccount.setReservedBalance(fromAccount.getReservedBalance() + amount.doubleValue());
            accountRepository.save(fromAccount);

            // Forward the message to the foreign bank
            sendInterbankMessage(message, config.getInterbankTargetUrl());

        } catch (Exception e) {
            log.error("Failed to process interbank transfer: ", e);
            throw new RuntimeException("Failed to process interbank transfer: " + e.getMessage());
        }
    }
}
