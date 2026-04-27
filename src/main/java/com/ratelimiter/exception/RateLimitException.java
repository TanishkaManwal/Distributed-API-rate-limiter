package com.ratelimiter.exception;

/**
 * Thrown when a rate limit is exceeded programmatically
 * (e.g., from within service layer, not filter).
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}