package com.ratelimiter.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Boot Actuator health indicator for Redis.
 * Reports status at /actuator/health with Redis connectivity details.
 */
@Component("redisRateLimiterHealth")
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterMetrics metrics;
    private volatile Health lastHealth = Health.unknown().build();

    public RedisHealthIndicator(RedisTemplate<String, String> redisTemplate,
                                RateLimiterMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics       = metrics;
    }

    @Override
    public Health health() {
        return lastHealth;
    }

    @Scheduled(fixedDelay = 30_000)
    public void checkHealth() {
        try {
            String pong = redisTemplate.execute(connection -> {
                connection.ping();
                return "PONG";
            }, true);

            metrics.probeRedis();

            lastHealth = Health.up()
                    .withDetail("redis",  "connected")
                    .withDetail("ping",   pong)
                    .withDetail("host",   redisTemplate.getConnectionFactory()
                            .getConnection().getNativeConnection().toString())
                    .build();
        } catch (Exception e) {
            lastHealth = Health.down()
                    .withDetail("redis",  "unreachable")
                    .withDetail("error",  e.getMessage())
                    .withDetail("fallback", "in-memory limiter active")
                    .build();
        }
    }
}