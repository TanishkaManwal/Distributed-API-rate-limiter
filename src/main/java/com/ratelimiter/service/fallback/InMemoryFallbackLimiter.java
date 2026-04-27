package com.ratelimiter.service.fallback;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitRequest;
import com.ratelimiter.model.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fallback rate limiter used when Redis is unavailable.
 *
 * Uses a simple token bucket implemented with AtomicLong counters.
 * Not distributed — each node has its own state — but prevents
 * total outage when Redis connectivity is lost.
 */
@Component
public class InMemoryFallbackLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryFallbackLimiter.class);

    private static final long FALLBACK_LIMIT_PER_MINUTE = 30L;
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitResult isAllowed(RateLimitRequest request) {
        log.warn("Using in-memory fallback limiter for key={}", request.getRedisKey());

        String key = request.getRedisKey();
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());

        long now = System.currentTimeMillis();
        boolean allowed = counter.tryConsume(now, FALLBACK_LIMIT_PER_MINUTE, WINDOW_MS);
        long remaining = Math.max(0, FALLBACK_LIMIT_PER_MINUTE - counter.getCount());

        return RateLimitResult.builder()
                .allowed(allowed)
                .remainingTokens(remaining)
                .resetAfterSeconds(60)
                .retryAfterSeconds(allowed ? 0 : 60)
                .reason(allowed ? null : "[Fallback] In-memory rate limit exceeded")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build();
    }

    /** Periodically evict stale counters to prevent memory leaks. */
    @Scheduled(fixedDelay = 120_000)
    public void evictStaleCounters() {
        long now = System.currentTimeMillis();
        int before = counters.size();
        counters.entrySet().removeIf(e -> e.getValue().isExpired(now, WINDOW_MS));
        int removed = before - counters.size();
        if (removed > 0) {
            log.debug("Evicted {} stale fallback counters", removed);
        }
    }

    // ---------------------------------------------------------
    // Inner class: sliding fixed-window counter
    // ---------------------------------------------------------
    private static class WindowCounter {
        private final AtomicLong count = new AtomicLong(0);
        private volatile long windowStart = System.currentTimeMillis();

        synchronized boolean tryConsume(long now, long limit, long windowMs) {
            if (now - windowStart >= windowMs) {
                count.set(0);
                windowStart = now;
            }
            long current = count.incrementAndGet();
            return current <= limit;
        }

        long getCount() {
            return count.get();
        }

        boolean isExpired(long now, long windowMs) {
            return (now - windowStart) > windowMs * 2;
        }
    }
}