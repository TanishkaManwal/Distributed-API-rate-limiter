package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitRequest;
import com.ratelimiter.model.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sliding Window Log algorithm.
 *
 * Records each request as a timestamped entry in a Redis sorted set.
 * Counts only requests within the rolling window. Accurate but memory-intensive.
 * Used for /api/search (high-throughput, accurate limiting).
 */
@Component("slidingWindowLogLimiter")
public class SlidingWindowLogRateLimiter implements RateLimiterAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLogRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> script;

    public SlidingWindowLogRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("slidingWindowLogScript") DefaultRedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitRequest request) {
        String key = request.getRedisKey() + ":swl";
        long limit = request.getConfig().getCapacity();
        long windowSeconds = request.getConfig().getWindowSeconds();
        long nowMs = System.currentTimeMillis();
        // Unique member prevents sorted-set key collision on same millisecond
        String member = nowMs + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try {
            List result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds),
                    String.valueOf(nowMs),
                    member
            );

            if (result == null || result.size() < 3) {
                log.warn("Unexpected Lua result for key={}", key);
                return deny(windowSeconds);
            }

            boolean allowed      = ((Number) result.get(0)).intValue() == 1;
            long remaining       = ((Number) result.get(1)).longValue();
            long oldestAgeMs     = ((Number) result.get(2)).longValue();
            long retryAfterSecs  = allowed ? 0 : Math.max(1, (oldestAgeMs + 999) / 1000);

            log.debug("SlidingWindowLog key={} allowed={} remaining={}", key, allowed, remaining);

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .remainingTokens(remaining)
                    .resetAfterSeconds(windowSeconds)
                    .retryAfterSeconds(retryAfterSecs)
                    .reason(allowed ? null : "Sliding window limit exceeded. Retry after " + retryAfterSecs + "s")
                    .algorithm(RateLimitAlgorithm.SLIDING_WINDOW_LOG)
                    .build();

        } catch (Exception e) {
            log.error("Redis error in SlidingWindowLog for key={}: {}", key, e.getMessage());
            throw e;
        }
    }

    private RateLimitResult deny(long retryAfter) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingTokens(0)
                .retryAfterSeconds(retryAfter)
                .reason("Rate limit exceeded")
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW_LOG)
                .build();
    }
}
