package com.banka1.banking.controllers;

import com.banka1.banking.dto.interbank.InterbankMessageDTO;
import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.dto.interbank.internal.PremiumPaymentDTO;
import com.banka1.banking.services.InterbankService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/interbank")
@RequiredArgsConstructor
public class InterbankController {

    private final InterbankService interbankService;

    @PostMapping
    public ResponseEntity<?> receiveWebhook(HttpServletRequest request) throws IOException {
        System.out.println("Received webhook request");
        String rawPayload =
                request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));

        ObjectMapper mapper = new ObjectMapper();
        InterbankMessageDTO<?> message = mapper.readValue(rawPayload, InterbankMessageDTO.class);
        try {
            VoteDTO response =
                    interbankService.webhook(message, rawPayload, request.getRemoteAddr());
            if (response.getVote().equalsIgnoreCase("NO")) {
                return ResponseEntity.status(500).body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing webhook:", e);
            VoteDTO response = new VoteDTO();
            response.setVote("NO");
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/internal")
    public ResponseEntity<?> internal(HttpServletRequest request) throws IOException {
        System.out.println("Received webhook request");
        String rawPayload =
                request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));

        ObjectMapper mapper = new ObjectMapper();
        InterbankMessageDTO<?> message = mapper.readValue(rawPayload, InterbankMessageDTO.class);
        try {

            interbankService.internal(message);

            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing webhook:", e);
            VoteDTO response = new VoteDTO();
            response.setVote("NO");
            return ResponseEntity.status(500).body(response);
        }
    }
}
