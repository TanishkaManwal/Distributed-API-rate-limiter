package com.ratelimiter.repository;

import com.ratelimiter.model.ApiKey;
import com.ratelimiter.model.UserTier;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory API key store. In production, this would be backed by a
 * database (PostgreSQL, DynamoDB, etc.). Pre-seeded with demo keys.
 */
@Repository
public class ApiKeyRepository {

    private final Map<String, ApiKey> store = new ConcurrentHashMap<>();

    public ApiKeyRepository() {
        // Seed demo API keys
        register(new ApiKey("free-key-001",     "user-001", UserTier.FREE,    true));
        register(new ApiKey("free-key-002",     "user-002", UserTier.FREE,    true));
        register(new ApiKey("pro-key-001",      "user-101", UserTier.PRO,     true));
        register(new ApiKey("pro-key-002",      "user-102", UserTier.PRO,     true));
        register(new ApiKey("premium-key-001",  "user-201", UserTier.PREMIUM, true));
        register(new ApiKey("premium-key-002",  "user-202", UserTier.PREMIUM, true));
        register(new ApiKey("inactive-key-001", "user-999", UserTier.FREE,    false));
    }

    public void register(ApiKey apiKey) {
        store.put(apiKey.getKey(), apiKey);
    }

    public Optional<ApiKey> findByKey(String key) {
        return Optional.ofNullable(store.get(key));
    }

    public boolean exists(String key) {
        return store.containsKey(key);
    }

    public void deactivate(String key) {
        ApiKey existing = store.get(key);
        if (existing != null) {
            store.put(key, new ApiKey(existing.getKey(), existing.getUserId(), existing.getTier(), false));
        }
    }
}