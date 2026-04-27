package com.ratelimiter.model;

public class RateLimitResult {

    private final boolean allowed;
    private final long remainingTokens;
    private final long resetAfterSeconds;
    private final long retryAfterSeconds;
    private final String reason;
    private final RateLimitAlgorithm algorithm;

    private RateLimitResult(Builder builder) {
        this.allowed = builder.allowed;
        this.remainingTokens = builder.remainingTokens;
        this.resetAfterSeconds = builder.resetAfterSeconds;
        this.retryAfterSeconds = builder.retryAfterSeconds;
        this.reason = builder.reason;
        this.algorithm = builder.algorithm;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getRemainingTokens() {
        return remainingTokens;
    }

    public long getResetAfterSeconds() {
        return resetAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getReason() {
        return reason;
    }

    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean allowed;
        private long remainingTokens;
        private long resetAfterSeconds;
        private long retryAfterSeconds;
        private String reason;
        private RateLimitAlgorithm algorithm;

        public Builder allowed(boolean allowed) {
            this.allowed = allowed;
            return this;
        }

        public Builder remainingTokens(long remainingTokens) {
            this.remainingTokens = remainingTokens;
            return this;
        }

        public Builder resetAfterSeconds(long resetAfterSeconds) {
            this.resetAfterSeconds = resetAfterSeconds;
            return this;
        }

        public Builder retryAfterSeconds(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder algorithm(RateLimitAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public RateLimitResult build() {
            return new RateLimitResult(this);
        }
    }
}