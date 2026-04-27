package com.ratelimiter.controller;

import com.ratelimiter.model.ApiKey;
import com.ratelimiter.model.UserTier;
import com.ratelimiter.repository.ApiKeyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Management controller for API keys.
 * In production this would be secured (e.g. admin JWT).
 */
@RestController
@RequestMapping("/admin/keys")
public class ApiKeyController {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyController(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getKey(@PathVariable String key) {
        Optional<ApiKey> apiKey = apiKeyRepository.findByKey(key);
        if (apiKey.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ApiKey k = apiKey.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key",    k.getKey());
        response.put("userId", k.getUserId());
        response.put("tier",   k.getTier());
        response.put("active", k.isActive());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createKey(@RequestBody Map<String, String> body) {
        String key    = body.get("key");
        String userId = body.get("userId");
        String tierStr = body.getOrDefault("tier", "FREE");

        if (key == null || userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "key and userId are required"));
        }

        UserTier tier;
        try {
            tier = UserTier.valueOf(tierStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tier: " + tierStr));
        }

        ApiKey newKey = new ApiKey(key, userId, tier, true);
        apiKeyRepository.register(newKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", true);
        response.put("key",     key);
        response.put("userId",  userId);
        response.put("tier",    tier);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deactivateKey(@PathVariable String key) {
        if (!apiKeyRepository.exists(key)) {
            return ResponseEntity.notFound().build();
        }
        apiKeyRepository.deactivate(key);
        return ResponseEntity.ok(Map.of("deactivated", true, "key", key));
    }
}