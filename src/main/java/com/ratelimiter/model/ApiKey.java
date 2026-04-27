package com.ratelimiter.model;

public class ApiKey {

    private final String key;
    private final String userId;
    private final UserTier tier;
    private final boolean active;

    public ApiKey(String key, String userId, UserTier tier, boolean active) {
        this.key = key;
        this.userId = userId;
        this.tier = tier;
        this.active = active;
    }

    public String getKey() {
        return key;
    }

    public String getUserId() {
        return userId;
    }

    public UserTier getTier() {
        return tier;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "ApiKey{key='" + key + "', userId='" + userId + "', tier=" + tier + ", active=" + active + "}";
    }
}