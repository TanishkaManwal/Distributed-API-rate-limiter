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
 * Token Bucket algorithm (PRIMARY).
 *
 * Tokens accumulate at a steady refill rate up to a max capacity.
 * Each request consumes one token. Supports burst traffic (up to capacity).
 * State is stored atomically in Redis via Lua script.
 */
@Component("tokenBucketLimiter")
public class TokenBucketRateLimiter implements RateLimiterAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> script;

    public TokenBucketRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("tokenBucketScript") DefaultRedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitRequest request) {
        String key = request.getRedisKey() + ":tb";
        long capacity   = request.getConfig().getCapacity();
        long refillRate = request.getConfig().getRefillRate();   // tokens per window
        long windowSec  = request.getConfig().getWindowSeconds();

        // Convert to tokens-per-second for the Lua script
        double tokensPerSecond = (double) refillRate / windowSec;
        long nowMs = System.currentTimeMillis();

        try {
            List result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(tokensPerSecond),
                    "1",                      // consume 1 token
                    String.valueOf(nowMs)
            );

            if (result == null || result.size() < 3) {
                log.warn("Unexpected Lua result for key={}", key);
                return allowedByDefault();
            }

            boolean allowed        = ((Number) result.get(0)).intValue() == 1;
            long remaining         = ((Number) result.get(1)).longValue();
            long retryAfterMs      = ((Number) result.get(2)).longValue();
            long retryAfterSeconds = (retryAfterMs + 999) / 1000;

            log.debug("TokenBucket key={} allowed={} remaining={}", key, allowed, remaining);

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .remainingTokens(remaining)
                    .retryAfterSeconds(retryAfterSeconds)
                    .resetAfterSeconds(windowSec)
                    .reason(allowed ? null : "Token bucket exhausted. Retry after " + retryAfterSeconds + "s")
                    .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                    .build();

        } catch (Exception e) {
            log.error("Redis error in TokenBucket for key={}: {}", key, e.getMessage());
            throw e;  // propagate so fallback kicks in
        }
    }

    private RateLimitResult allowedByDefault() {
        return RateLimitResult.builder()
                .allowed(true)
                .remainingTokens(-1)
                .retryAfterSeconds(0)
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .build();
    }
}