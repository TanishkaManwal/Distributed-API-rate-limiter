package com.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Test controller exposing demo endpoints with different rate limit configurations:
 *
 * - /api/login   → Fixed Window, strict (5 req/min)
 * - /api/search  → Sliding Window Log, high throughput (500 req/min)
 * - /api/payment → Token Bucket, moderate (20 req/min)
 * - /api/data    → Token Bucket (tier-based)
 * - /api/health  → unrestricted health check
 *
 * All /api/* requests are intercepted by RateLimitFilter before
 * reaching these controller methods.
 */
@RestController
@RequestMapping("/api")
public class TestController {

    private final Random random = new Random();

    // ----------------------------------------------------------
    // /api/login - strict Fixed Window limit
    // ----------------------------------------------------------
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody(required = false) Map<String, String> body) {

        String username = body != null ? body.getOrDefault("username", "demo") : "demo";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success",   true);
        response.put("message",   "Login successful");
        response.put("username",  username);
        response.put("token",     "jwt-" + Long.toHexString(System.nanoTime()));
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/login")
    public ResponseEntity<Map<String, Object>> loginGet() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",   "/api/login");
        response.put("algorithm",  "FIXED_WINDOW");
        response.put("limit",      "5 requests per 60 seconds");
        response.put("note",       "Send POST with {\"username\":\"test\"} to test login");
        response.put("timestamp",  Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // /api/search - high-throughput Sliding Window Log
    // ----------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(defaultValue = "spring boot") String q) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query",    q);
        response.put("results",  generateMockResults(q));
        response.put("total",    random.nextInt(1000) + 50);
        response.put("page",     1);
        response.put("algorithm","SLIDING_WINDOW_LOG");
        response.put("timestamp",Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // /api/payment - moderate Token Bucket
    // ----------------------------------------------------------
    @PostMapping("/payment")
    public ResponseEntity<Map<String, Object>> payment(
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success",       true);
        response.put("transactionId", "TXN-" + Long.toHexString(System.nanoTime()).toUpperCase());
        response.put("amount",        body != null ? body.getOrDefault("amount", 0) : 0);
        response.put("currency",      body != null ? body.getOrDefault("currency", "USD") : "USD");
        response.put("status",        "PROCESSED");
        response.put("algorithm",     "TOKEN_BUCKET");
        response.put("timestamp",     Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/payment")
    public ResponseEntity<Map<String, Object>> paymentInfo() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",  "/api/payment");
        response.put("algorithm", "TOKEN_BUCKET");
        response.put("limit",     "20 requests per 60 seconds");
        response.put("burst",     "Supports burst up to capacity");
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // /api/data - tier-based Token Bucket
    // ----------------------------------------------------------
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> data() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data",      Map.of("items", random.nextInt(100), "page", 1));
        response.put("note",      "Rate limited based on API key tier (FREE/PRO/PREMIUM)");
        response.put("tiers",     Map.of(
                "FREE",    "10 req/min",
                "PRO",     "100 req/min",
                "PREMIUM", "1000 req/min"
        ));
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // /api/echo - echo request info for debugging
    // ----------------------------------------------------------
    @GetMapping("/echo")
    public ResponseEntity<Map<String, Object>> echo(
            @RequestHeader(value = "X-API-Key",    required = false) String apiKey,
            @RequestHeader(value = "X-Real-IP",    required = false) String realIp,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiKey",      apiKey != null ? apiKey : "(none - using IP)");
        response.put("realIp",      realIp);
        response.put("forwardedFor",forwardedFor);
        response.put("tip",         "Pass X-API-Key header to use key-based rate limiting");
        response.put("demoKeys", Map.of(
                "FREE",    new String[]{"free-key-001", "free-key-002"},
                "PRO",     new String[]{"pro-key-001",  "pro-key-002"},
                "PREMIUM", new String[]{"premium-key-001", "premium-key-002"}
        ));
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // Helper
    // ----------------------------------------------------------
    private java.util.List<Map<String, Object>> generateMockResults(String query) {
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        String[] titles = {
                "Introduction to " + query,
                "Advanced " + query + " techniques",
                "Best practices for " + query,
                query + " performance guide"
        };
        for (int i = 0; i < titles.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",    i + 1);
            item.put("title", titles[i]);
            item.put("score", Math.round((random.nextDouble() * 0.5 + 0.5) * 100.0) / 100.0);
            results.add(item);
        }
        return results;
    }
}