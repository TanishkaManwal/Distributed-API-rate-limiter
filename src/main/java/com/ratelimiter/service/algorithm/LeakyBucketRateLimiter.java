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
 * Leaky Bucket algorithm.
 *
 * Requests fill a queue that drains at a constant rate.
 * Provides a smooth, constant outflow regardless of burst arrival.
 * Rejects requests when the queue (bucket) is full.
 */
@Component("leakyBucketLimiter")
public class LeakyBucketRateLimiter implements RateLimiterAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(LeakyBucketRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> script;

    public LeakyBucketRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("leakyBucketScript") DefaultRedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitRequest request) {
        String key = request.getRedisKey() + ":lb";
        long capacity  = request.getConfig().getCapacity();
        long leakRate  = request.getConfig().getRefillRate();     // requests per window
        long windowSec = request.getConfig().getWindowSeconds();
        double leakPerSecond = (double) leakRate / windowSec;
        long nowMs = System.currentTimeMillis();

        try {
            List result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(leakPerSecond),
                    String.valueOf(nowMs)
            );

            if (result == null || result.size() < 3) {
                log.warn("Unexpected Lua result for key={}", key);
                return deny(windowSec);
            }

            boolean allowed       = ((Number) result.get(0)).intValue() == 1;
            long queueSize        = ((Number) result.get(1)).longValue();
            long retryAfterMs     = ((Number) result.get(2)).longValue();
            long retryAfterSec    = (retryAfterMs + 999) / 1000;
            long remaining        = Math.max(0, capacity - queueSize);

            log.debug("LeakyBucket key={} allowed={} queue={}", key, allowed, queueSize);

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .remainingTokens(remaining)
                    .resetAfterSeconds(windowSec)
                    .retryAfterSeconds(allowed ? 0 : retryAfterSec)
                    .reason(allowed ? null : "Leaky bucket full. Retry after " + retryAfterSec + "s")
                    .algorithm(RateLimitAlgorithm.LEAKY_BUCKET)
                    .build();

        } catch (Exception e) {
            log.error("Redis error in LeakyBucket for key={}: {}", key, e.getMessage());
            throw e;
        }
    }

    private RateLimitResult deny(long retryAfter) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingTokens(0)
                .retryAfterSeconds(retryAfter)
                .reason("Rate limit exceeded")
                .algorithm(RateLimitAlgorithm.LEAKY_BUCKET)
                .build();
    }
}