package com.banka1.banking.services;

import com.banka1.banking.config.InterbankConfig;
import com.banka1.banking.dto.CreateEventDeliveryDTO;
import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.EventDelivery;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

public class EventExecutorServiceTest {

    @Mock
    private EventService eventService;

    @Mock
    private InterbankOperationService interbankService;
    
    @Mock
    private InterbankConfig config;

    @InjectMocks
    private EventExecutorService eventExecutorService;

    @Captor
    private ArgumentCaptor<CreateEventDeliveryDTO> deliveryCaptor;
    
    @Mock
    private TaskScheduler mockTaskScheduler;

    private Event mockEvent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockEvent = new Event();
        mockEvent.setId(1L);
        mockEvent.setUrl("http://localhost:8080/test");
        mockEvent.setPayload("{\"test\":\"value\"}");
        mockEvent.setMessageType(InterbankMessageType.NEW_TX);
        
        when(config.getForeignBankApiKey()).thenReturn("test-api-key");
        
        // Setup event service
        when(eventService.createEventDelivery(any(CreateEventDeliveryDTO.class)))
            .thenAnswer(invocation -> {
                CreateEventDeliveryDTO dto = invocation.getArgument(0);
                EventDelivery delivery = new EventDelivery();
                delivery.setEvent(dto.getEvent());
                delivery.setStatus(dto.getStatus());
                delivery.setHttpStatus(dto.getHttpStatus());
                delivery.setResponseBody(dto.getResponseBody());
                delivery.setDurationMs(dto.getDurationMs());
                return delivery;
            });
    }

    @Test
    void testRollbackTransactionCallsInterbankService() {
        eventExecutorService.rollbackTransaction(mockEvent);
        verify(interbankService).sendRollback(mockEvent);
    }

    @Test
    void testHandleNewTxSuccess_voteYes_callsSendCommit() throws Exception {
        VoteDTO voteDTO = new VoteDTO();
        voteDTO.setVote("yes");

        String voteJson = new ObjectMapper().writeValueAsString(voteDTO);
        String wrappedJson = new ObjectMapper().writeValueAsString(voteJson);

        eventExecutorService.handleNewTxSuccess(mockEvent, wrappedJson);
        verify(interbankService).sendCommit(mockEvent);
    }

    @Test
    void testHandleNewTxSuccess_voteNo_callsSendRollback() throws Exception {
        VoteDTO voteDTO = new VoteDTO();
        voteDTO.setVote("no");

        String voteJson = new ObjectMapper().writeValueAsString(voteDTO);
        String wrappedJson = new ObjectMapper().writeValueAsString(voteJson);

        eventExecutorService.handleNewTxSuccess(mockEvent, wrappedJson);
        verify(interbankService).sendRollback(mockEvent);
    }
    
    @Test
    void testGetTemplate() throws Exception {
        // Use reflection to access private method
        Method getTemplateMethod = EventExecutorService.class.getDeclaredMethod("getTemplate");
        getTemplateMethod.setAccessible(true);
        
        RestTemplate template = (RestTemplate) getTemplateMethod.invoke(eventExecutorService);
        
        // Get the error handler from the RestTemplate
        Field errorHandlerField = RestTemplate.class.getDeclaredField("errorHandler");
        errorHandlerField.setAccessible(true);
        ResponseErrorHandler errorHandler = (ResponseErrorHandler) errorHandlerField.get(template);
        
        // Create a mock ClientHttpResponse for testing
        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
        
        // Test that hasError always returns false
        assertFalse(errorHandler.hasError(mockResponse));
        
        // Test that handleError doesn't throw exceptions
        URI mockUri = new URI("http://test.com");
        errorHandler.handleError(mockUri, HttpMethod.POST, mockResponse);
    }
    
    // Utility method to set private fields
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = EventExecutorService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}