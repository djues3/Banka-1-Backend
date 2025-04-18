package com.banka1.banking.controllers;

import com.banka1.banking.services.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {
    private final OrderService orderService;

    @PostMapping("/execute/{token}")
    public ResponseEntity<?> executeOrder(@PathVariable String token) {
//        Claims claims = authService.parseToken(token);
//
//        try {
//            String direction = claims.get("direction", String.class);
//            Long accountId = Long.valueOf(claims.get("accountId", Integer.class));
//            Long userId = Long.valueOf(claims.get("userId", Integer.class));
//            Double amount = Double.valueOf(claims.get("amount", String.class));
//            Double fee = Double.parseDouble((String) claims.get("fee"));
//
//            if(direction == null)
//                throw new Exception();
//
//            Double finalAmount = orderService.executeOrder(direction, userId, accountId, amount, fee);
//
//            return ResponseTemplate.create(ResponseEntity.status(HttpStatus.OK), true, Map.of("finalAmount", finalAmount), null);
//        } catch (IllegalArgumentException e) {
//            return ResponseTemplate.create(ResponseEntity.status(HttpStatus.FORBIDDEN), false, null, "Nedovoljna sredstva");
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseTemplate.create(ResponseEntity.status(HttpStatus.BAD_REQUEST), false, null, "Nevalidni podaci");
//        }
	    return null;
    }
}
