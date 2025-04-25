package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDeliveryDTO;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.committx.CommitTransactionDTO;
import com.banka1.banking.dto.interbank.newtx.InterbankTransactionDTO;
import com.banka1.banking.dto.interbank.rollbacktx.RollbackTransactionDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.EventDelivery;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@Service
public class EventExecutorService {

    private final EventService eventService;
    private final InterbankOperationService interbankService;
    private final InterbankConfig config;

    private final TaskScheduler taskScheduler = new ConcurrentTaskScheduler();

    private static final int MAX_RETRIES = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(20);

    public EventExecutorService(EventService eventService, @Lazy InterbankOperationService interbankService,
                                 InterbankConfig config) {
        this.eventService = eventService;
        this.interbankService = interbankService;
        this.config = config;
    }

    @Async
    public void attemptEventAsync(Event event) {
        attemptDelivery(event, 1);
    }

    private RestTemplate getTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }

            @Override
            public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {

            }
        });

        return restTemplate;
    }

    private void attemptDelivery(Event event, int attempt) {
        System.out.println("Attempting delivery for event: " + event.getId() + ", attempt: " + attempt);
        Instant start = Instant.now();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Api-Key", config.getForeignBankApiKey());

        HttpEntity<String> entity = new HttpEntity<>(event.getPayload(), headers);

        // add body to request

        String responseBody = null;
        int httpStatus = 0;
        DeliveryStatus status;

        try {
            System.out.println("Sending request to: " + event.getUrl());
            ResponseEntity<String> response = getTemplate().postForEntity(event.getUrl(), entity, String.class);
            System.out.println("Response: " + response.getBody());
            responseBody = response.getBody() != null ? new ObjectMapper().writeValueAsString(response.getBody()) : "";

            httpStatus = response.getStatusCodeValue();

            status = response.getStatusCode().is2xxSuccessful() ? DeliveryStatus.SUCCESS : DeliveryStatus.FAILED;

        } catch (Exception ex) {
            System.out.println(ex.getStackTrace());
            status = DeliveryStatus.FAILED;
            httpStatus = -1;
            responseBody = ex.getMessage();
        }

        if (status == DeliveryStatus.FAILED && attempt < MAX_RETRIES) {
            eventService.changeEventStatus(event, DeliveryStatus.RETRYING);
            taskScheduler.schedule(() -> attemptDelivery(event, attempt + 1), Instant.now().plus(RETRY_DELAY));
        } else if (status == DeliveryStatus.SUCCESS) {
            eventService.changeEventStatus(event, DeliveryStatus.SUCCESS);
            if (event.getMessageType() == InterbankMessageType.NEW_TX) {
                handleNewTxSuccess(event, responseBody);
            }
        } else if (attempt >= MAX_RETRIES) {
            eventService.changeEventStatus(event, DeliveryStatus.CANCELED);
            rollbackTransaction(event);
        }

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();

        CreateEventDeliveryDTO createEventDeliveryDTO = new CreateEventDeliveryDTO();
        createEventDeliveryDTO.setEvent(event);
        createEventDeliveryDTO.setDurationMs(durationMs);
        createEventDeliveryDTO.setHttpStatus(httpStatus);
        createEventDeliveryDTO.setResponseBody(responseBody);
        createEventDeliveryDTO.setStatus(status);

        EventDelivery eventDelivery = eventService.createEventDelivery(createEventDeliveryDTO);
    }


    public void handleNewTxSuccess(Event event, String responseBody) {
        System.out.println("Handling new transaction success for event: " + event.getId());
        try {
            System.out.println("Handling new transaction success for event: " + event.getId());
            String actualJson = new ObjectMapper().readValue(responseBody, String.class);
            VoteDTO vote = new ObjectMapper().readValue(actualJson, VoteDTO.class);

            if (vote.getVote().equalsIgnoreCase("yes")) {
                interbankService.sendCommit(event);
            } else {
                interbankService.sendRollback(event);
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to handle new transaction success: " + e.getMessage());
        }
    }

    public void rollbackTransaction(Event event) {
        try {
            interbankService.sendRollback(event);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to handle rollback transaction: " + e.getMessage());
        }
    }
}
