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

/**
 * Fixed Window Counter algorithm.
 *
 * Divides time into fixed windows. Counts requests per window.
 * Simple and fast, but susceptible to boundary bursts.
 * Used for /api/login (strict, simple enforcement).
 */
@Component("fixedWindowLimiter")
public class FixedWindowRateLimiter implements RateLimiterAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> script;

    public FixedWindowRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("fixedWindowScript") DefaultRedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitRequest request) {
        // Key includes window timestamp for fixed partitioning
        long windowSeconds = request.getConfig().getWindowSeconds();
        long windowId = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = request.getRedisKey() + ":fw:" + windowId;

        long limit = request.getConfig().getCapacity();

        try {
            List result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            if (result == null || result.size() < 3) {
                log.warn("Unexpected Lua result for key={}", key);
                return deny(windowSeconds);
            }

            boolean allowed       = ((Number) result.get(0)).intValue() == 1;
            long remaining        = ((Number) result.get(1)).longValue();
            long resetAfterSeconds = ((Number) result.get(2)).longValue();

            log.debug("FixedWindow key={} allowed={} remaining={}", key, allowed, remaining);

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .remainingTokens(remaining)
                    .resetAfterSeconds(resetAfterSeconds)
                    .retryAfterSeconds(allowed ? 0 : resetAfterSeconds)
                    .reason(allowed ? null : "Fixed window limit exceeded. Retry after " + resetAfterSeconds + "s")
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .build();

        } catch (Exception e) {
            log.error("Redis error in FixedWindow for key={}: {}", key, e.getMessage());
            throw e;
        }
    }

    private RateLimitResult deny(long retryAfter) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingTokens(0)
                .retryAfterSeconds(retryAfter)
                .reason("Rate limit exceeded")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build();
    }
}
