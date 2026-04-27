package com.ratelimiter.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers custom Prometheus/Micrometer metrics for the rate limiter.
 *
 * Metrics exposed:
 * - rate_limiter.redis.connected  : 1 if Redis is reachable, 0 otherwise
 * - rate_limiter.keys.active      : estimated number of active rate limit keys
 */
@Component
public class RateLimiterMetrics {

    private final RedisTemplate<String, String> redisTemplate;
    private final AtomicLong redisConnected = new AtomicLong(0);
    private final AtomicLong activeKeys     = new AtomicLong(0);

    public RateLimiterMetrics(RedisTemplate<String, String> redisTemplate,
                              MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;

        Gauge.builder("rate_limiter.redis.connected", redisConnected, AtomicLong::doubleValue)
                .description("1 if Redis is reachable, 0 otherwise")
                .register(meterRegistry);

        Gauge.builder("rate_limiter.keys.active", activeKeys, AtomicLong::doubleValue)
                .description("Approximate number of active rate limit keys in Redis")
                .register(meterRegistry);

        // Initial probe
        probeRedis();
    }

    /**
     * Called periodically by the health check to update gauge values.
     */
    public void probeRedis() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisConnected.set(1);

            Long keyCount = redisTemplate.execute(connection ->
                    connection.keyCommands().keys(("rl:*").getBytes()) == null ? 0L :
                            (long) connection.keyCommands().keys(("rl:*").getBytes()).size(), true);
            activeKeys.set(keyCount != null ? keyCount : 0L);
        } catch (Exception e) {
            redisConnected.set(0);
        }
    }
}
