package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDTO;
import com.banka1.banking.dto.CreateEventDeliveryDTO;
import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.EventDelivery;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.models.interbank.EventDirection;
import com.banka1.banking.repository.EventDeliveryRepository;
import com.banka1.banking.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventServiceTest {

    @InjectMocks
    private EventService eventService;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventDeliveryRepository eventDeliveryRepository;

    @Mock
    private InterbankConfig interbankConfig;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void attemptCount_ShouldReturnCorrectCount() {
        Event event = new Event();
        EventDelivery delivery1 = new EventDelivery();
        EventDelivery delivery2 = new EventDelivery();
        event.setDeliveries(Arrays.asList(delivery1, delivery2));

        int result = eventService.attemptCount(event);
        assertEquals(2, result);
    }

    @Test
    void changeEventStatus_ShouldSetStatusAndSave() {
        Event event = new Event();
        when(eventRepository.save(event)).thenReturn(event);

        eventService.changeEventStatus(event, DeliveryStatus.SUCCESS);

        assertEquals(DeliveryStatus.SUCCESS, event.getStatus());
        verify(eventRepository).save(event);
    }

    @Test
    void receiveEvent_ShouldReturnSavedEvent_WhenValid() {
        IdempotenceKey idKey = new IdempotenceKey(123, "abc");
        InterbankMessageDTO<String> dto = new InterbankMessageDTO<>();
        dto.setIdempotenceKey(idKey);
        dto.setMessageType(InterbankMessageType.NEW_TX);

        when(eventRepository.existsByIdempotenceKeyAndMessageType(eq(idKey), any())).thenReturn(false);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        Event result = eventService.receiveEvent(dto, "raw-json", "http://source");

        assertNotNull(result);
        assertEquals("raw-json", result.getPayload());
        assertEquals(EventDirection.INCOMING, result.getDirection());
        verify(eventRepository).save(result);
    }

    @Test
    void receiveEvent_ShouldThrow_WhenEventExists() {
        IdempotenceKey key = new IdempotenceKey(130, "key");
        InterbankMessageDTO<String> dto = new InterbankMessageDTO<>();
        dto.setIdempotenceKey(key);
        dto.setMessageType(InterbankMessageType.NEW_TX);

        when(eventRepository.existsByIdempotenceKeyAndMessageType(eq(key), any())).thenReturn(true);

        assertThrows(RuntimeException.class, () -> eventService.receiveEvent(dto, "payload", "url"));
    }

    @Test
    void createEvent_ShouldSaveAndReturnEvent() {
        CreateEventDTO dto = new CreateEventDTO();
        InterbankMessageDTO<String> message = new InterbankMessageDTO<>();
        IdempotenceKey key = new IdempotenceKey(13000, "ll");
        message.setIdempotenceKey(key);
        message.setMessageType(InterbankMessageType.NEW_TX);

        dto.setMessage(message);
        dto.setPayload("payload");
        dto.setUrl("http://url");

        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        Event result = eventService.createEvent(dto);
        assertEquals("payload", result.getPayload());
        assertEquals(EventDirection.OUTGOING, result.getDirection());
    }

//    @Test
//    void createEventDelivery_ShouldSaveAndReturnDelivery() {
//        CreateEventDeliveryDTO dto = new CreateEventDeliveryDTO();
//        Event event = new Event();
//        dto.setEvent(event);
//        dto.setStatus(DeliveryStatus.PENDING);
//        dto.setHttpStatus(200);
//        dto.setDurationMs(100L);
//        var vote = new VoteDTO();
//        vote.setVote("OK");
//        dto.setResponseBody(vote);
//
//        when(eventDeliveryRepository.save(any(EventDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
//
//        EventDelivery result = eventService.createEventDelivery(dto);
//        assertEquals("OK", result.getResponseBody());
//    }

    @Test
    void getEventDeliveriesForEvent_ShouldReturnDeliveries() {
        Event event = new Event();
        event.setId(1L);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        List<EventDelivery> deliveryList = List.of(new EventDelivery());
        when(eventDeliveryRepository.findByEvent(event)).thenReturn(deliveryList);

        List<EventDelivery> result = eventService.getEventDeliveriesForEvent(1L);
        assertEquals(1, result.size());
    }

    @Test
    void findEventByIdempotenceKey_ShouldReturnEvent() {
        IdempotenceKey key = new IdempotenceKey(183091, "k");
        Event event = new Event();
        when(eventRepository.findByIdempotenceKey(key)).thenReturn(Optional.of(event));

        Event result = eventService.findEventByIdempotenceKey(key);
        assertNotNull(result);
    }

    @Test
    void findEventByIdempotenceKey_ShouldThrowIfNotFound() {
        IdempotenceKey key = new IdempotenceKey(-112, "k");
        when(eventRepository.findByIdempotenceKey(key)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> eventService.findEventByIdempotenceKey(key));
    }
}