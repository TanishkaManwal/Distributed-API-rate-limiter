package com.ratelimiter.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handler for clean JSON error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException ex) {
        log.warn("RateLimitException: {}", ex.getMessage());
        return buildError(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ex.getRetryAfterSeconds());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), 0);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred", 0);
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message, long retryAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",     status.value());
        body.put("error",      status.getReasonPhrase());
        body.put("message",    message);
        body.put("timestamp",  Instant.now().toString());
        if (retryAfter > 0) {
            body.put("retryAfter", retryAfter);
        }
        return ResponseEntity.status(status).body(body);
    }
}