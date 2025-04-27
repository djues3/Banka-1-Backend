package com.banka1.banking.config;

import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.VoteReasonDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.services.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class InterbankInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final EventService eventService;
    private final InterbankConfig interbankConfig;

    public InterbankInterceptor(ObjectMapper objectMapper, EventService eventService, InterbankConfig interbankConfig) {
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.interbankConfig = interbankConfig;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (request.getRequestURI().contains("/interbank") && "POST".equalsIgnoreCase(request.getMethod())) {
            System.out.println("Received webhook request");

            String apiKey = request.getHeader("X-Api-Key");
            if (apiKey == null || apiKey.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                VoteDTO voteDTO = new VoteDTO();
                voteDTO.setVote("NO");
                voteDTO.setReasons(List.of(new VoteReasonDTO("UNBALANCED_TX", null)));
                return false;
            }

            if (!apiKey.equals(interbankConfig.getApiKey())) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                VoteDTO voteDTO = new VoteDTO();
                voteDTO.setVote("NO");
                voteDTO.setReasons(List.of(new VoteReasonDTO("UNBALANCED_TX", null)));
                return false;
            }

            String rawPayload = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));

            Event event;

            try {
                if (rawPayload.isEmpty()) {
                    event = eventService.receiveEvent(new InterbankMessageDTO<>(), "", request.getRemoteAddr());
                    request.setAttribute("event", event);
                    request.setAttribute("startTime", System.currentTimeMillis());
                } else {
                    InterbankMessageDTO<?> dto = objectMapper.readValue(rawPayload, InterbankMessageDTO.class);
                    event = eventService.receiveEvent(dto, rawPayload, request.getRemoteAddr());
                }
            } catch (IOException e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                return false;
            }

            request.setAttribute("event", event);
            request.setAttribute("startTime", System.currentTimeMillis());
        }
        return true;
    }
}

