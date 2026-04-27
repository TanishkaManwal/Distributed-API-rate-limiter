package com.ratelimiter.model;

public class RateLimitRequest {

    private final String identifier;   // API key or IP
    private final String endpoint;
    private final UserTier tier;
    private final RateLimitConfig config;

    public RateLimitRequest(String identifier, String endpoint, UserTier tier, RateLimitConfig config) {
        this.identifier = identifier;
        this.endpoint = endpoint;
        this.tier = tier;
        this.config = config;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public UserTier getTier() {
        return tier;
    }

    public RateLimitConfig getConfig() {
        return config;
    }

    public String getRedisKey() {
        return "rl:" + endpoint.replaceAll("/", "_") + ":" + identifier;
    }
}