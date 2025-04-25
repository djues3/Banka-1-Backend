package com.banka1.user.config;

import com.banka1.user.utils.ResponseTemplate;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception: ", ex);

        return ResponseTemplate.create(
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR),
                false,
                Map.of(
                        "stacktrace", ex.getStackTrace()
                ),
                ex.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error -> {
            errors.put(error.getField(), error.getDefaultMessage());
        });

        System.out.println(errors);

        return ResponseTemplate.create(
                ResponseEntity.status(HttpStatus.BAD_REQUEST),
                false,
                errors,
                "Validation failed"
        );
    }
}
