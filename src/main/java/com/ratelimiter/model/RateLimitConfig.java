package com.ratelimiter.model;

public class RateLimitConfig {

    private final long capacity;
    private final long refillRate;
    private final long windowSeconds;
    private final RateLimitAlgorithm algorithm;

    public RateLimitConfig(long capacity, long refillRate, long windowSeconds, RateLimitAlgorithm algorithm) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.windowSeconds = windowSeconds;
        this.algorithm = algorithm;
    }

    public long getCapacity() {
        return capacity;
    }

    public long getRefillRate() {
        return refillRate;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }

    @Override
    public String toString() {
        return "RateLimitConfig{capacity=" + capacity + ", refillRate=" + refillRate
                + ", windowSeconds=" + windowSeconds + ", algorithm=" + algorithm + "}";
    }
}