package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDTO;
import com.banka1.banking.dto.CreateEventDeliveryDTO;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.newtx.ForeignBankIdDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.EventDelivery;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.models.interbank.EventDirection;
import com.banka1.banking.repository.EventDeliveryRepository;
import com.banka1.banking.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EventDeliveryRepository eventDeliveryRepository;
    private final InterbankConfig config;
    private final ObjectMapper mapper;

    public int attemptCount(Event event) {
        return event.getDeliveries().size();
    }

    public void changeEventStatus(Event event, DeliveryStatus status) {
        event.setStatus(status);
        eventRepository.save(event);
    }

    public Event receiveEvent(InterbankMessageDTO<?> dto, String rawPayload, String sourceUrl) {

        Event event = new Event();

        if (dto == null) {
            return null;
        }

        if (eventRepository.existsByIdempotenceKeyAndMessageType(dto.getIdempotenceKey(), dto.getMessageType())) {
            log.info("Event already exists with idempotence key: " + dto.getIdempotenceKey());
            return eventRepository
                    .findByIdempotenceKey(dto.getIdempotenceKey())
                    .orElseThrow(
                            () -> new IllegalArgumentException("Event expected to be present"));
        }

        try {
            event.setMessageType(dto.getMessageType());
            event.setPayload(rawPayload);
            event.setUrl(sourceUrl);

            event.setIdempotenceKey(dto.getIdempotenceKey());
            event.setDirection(EventDirection.INCOMING);
            event.setStatus(DeliveryStatus.PENDING);
        } catch (Exception e) {
            event.setMessageType(null);
            event.setPayload(rawPayload);
            event.setUrl(sourceUrl);
            if (dto.getIdempotenceKey() != null && dto.getIdempotenceKey().getRoutingNumber() != null && dto.getIdempotenceKey().getLocallyGeneratedKey() != null) {
                event.setIdempotenceKey(dto.getIdempotenceKey());
            } else {
                IdempotenceKey idempotenceKey = new IdempotenceKey();
                idempotenceKey.setRoutingNumber(Integer.valueOf(config.getRoutingNumber()));
                idempotenceKey.setLocallyGeneratedKey(UUID.randomUUID().toString());
                event.setIdempotenceKey(idempotenceKey);
            }
            event.setDirection(EventDirection.INCOMING);
            event.setStatus(DeliveryStatus.FAILED);

            throw new RuntimeException("Failed to create event: " + e.getMessage());
        }


        System.out.println("Saving event with idempotence key: " + event.getIdempotenceKey().getRoutingNumber() + " - " + event.getIdempotenceKey().getLocallyGeneratedKey());
        return eventRepository.save(event);
    }

    @Transactional
    public Event createEvent(CreateEventDTO createEventDTO) {
        Event event = new Event();
        event.setPayload(createEventDTO.getPayload());
        event.setUrl(createEventDTO.getUrl());
        event.setMessageType(createEventDTO.getMessage().getMessageType());
        event.setDirection(EventDirection.OUTGOING);

        event.setIdempotenceKey(createEventDTO.getMessage().getIdempotenceKey());

	    return eventRepository.save(event);
    }

    public EventDelivery createEventDelivery(CreateEventDeliveryDTO createEventDeliveryDTO) {

        EventDelivery eventDelivery = new EventDelivery();
        eventDelivery.setEvent(createEventDeliveryDTO.getEvent());
        eventDelivery.setStatus(createEventDeliveryDTO.getStatus());
        eventDelivery.setHttpStatus(createEventDeliveryDTO.getHttpStatus());
        eventDelivery.setDurationMs(createEventDeliveryDTO.getDurationMs());
	    try {
		    eventDelivery.setResponseBody(mapper.writeValueAsString(createEventDeliveryDTO.getResponseBody()));
	    } catch (JsonProcessingException e) {
		    return null;
	    }

	    eventDelivery.setSentAt(Instant.now());

        return eventDeliveryRepository.save(eventDelivery);
    }

    public List<EventDelivery> getEventDeliveriesForEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));

        return eventDeliveryRepository.findByEvent(event);
    }

    public Event findEventByIdempotenceKey(IdempotenceKey idempotenceKey) {
        return eventRepository.findByIdempotenceKey(idempotenceKey)
                .orElseThrow(() -> new RuntimeException("Event not found"));
    }

    public Event findEventByTransactionId(ForeignBankIdDTO txId) {
        return eventRepository
                .findByTransactionIdInPayload(String.valueOf(txId.getRoutingNumber()), txId.getId())
                .orElseThrow(() -> new RuntimeException("Event not found: " + txId));
    }

    public boolean existsByIdempotenceKey(IdempotenceKey idempotenceKey, InterbankMessageType messageType) {
        return eventRepository.existsByIdempotenceKeyAndMessageType(idempotenceKey, messageType);
    }

    public boolean shouldReplay(IdempotenceKey idempotenceKey, InterbankMessageType messageType) {
        var eventOpt = eventRepository.findByIdempotenceKeyAndMessageTypeAndStatus(
                idempotenceKey,
                messageType,
                DeliveryStatus.SUCCESS);
        if (eventOpt.isEmpty()) {
            return false;
        }
        var event = eventOpt.get();
        // Since events get saved before the request reaches this point in the interceptor,
        // there needs to be a way to ignore them.
        // This is that way.
	    return event
			    .getCreatedAt()
			    .plusMillis(100)
			    .isBefore(Instant.now());
    }
}
