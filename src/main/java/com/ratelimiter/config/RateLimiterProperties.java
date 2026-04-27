package com.ratelimiter.config;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.model.UserTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RateLimiterProperties {

    @Value("${rate.limiter.fallback.enabled:true}")
    private boolean fallbackEnabled;

    // Tier capacities
    @Value("${rate.limiter.tier.free.capacity:10}")
    private long freeCapacity;
    @Value("${rate.limiter.tier.free.refill-rate:10}")
    private long freeRefillRate;
    @Value("${rate.limiter.tier.free.refill-interval-seconds:60}")
    private long freeRefillInterval;

    @Value("${rate.limiter.tier.pro.capacity:100}")
    private long proCapacity;
    @Value("${rate.limiter.tier.pro.refill-rate:100}")
    private long proRefillRate;
    @Value("${rate.limiter.tier.pro.refill-interval-seconds:60}")
    private long proRefillInterval;

    @Value("${rate.limiter.tier.premium.capacity:1000}")
    private long premiumCapacity;
    @Value("${rate.limiter.tier.premium.refill-rate:1000}")
    private long premiumRefillRate;
    @Value("${rate.limiter.tier.premium.refill-interval-seconds:60}")
    private long premiumRefillInterval;

    // Endpoint configs
    @Value("${rate.limiter.endpoint.login.limit:5}")
    private long loginLimit;
    @Value("${rate.limiter.endpoint.login.window-seconds:60}")
    private long loginWindow;

    @Value("${rate.limiter.endpoint.search.limit:500}")
    private long searchLimit;
    @Value("${rate.limiter.endpoint.search.window-seconds:60}")
    private long searchWindow;

    @Value("${rate.limiter.endpoint.payment.limit:20}")
    private long paymentLimit;
    @Value("${rate.limiter.endpoint.payment.window-seconds:60}")
    private long paymentWindow;

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public RateLimitConfig getTierConfig(UserTier tier) {
        return switch (tier) {
            case FREE -> new RateLimitConfig(freeCapacity, freeRefillRate, freeRefillInterval, RateLimitAlgorithm.TOKEN_BUCKET);
            case PRO -> new RateLimitConfig(proCapacity, proRefillRate, proRefillInterval, RateLimitAlgorithm.TOKEN_BUCKET);
            case PREMIUM -> new RateLimitConfig(premiumCapacity, premiumRefillRate, premiumRefillInterval, RateLimitAlgorithm.TOKEN_BUCKET);
        };
    }

    public Map<String, RateLimitConfig> getEndpointConfigs() {
        return Map.of(
                "/api/login",   new RateLimitConfig(loginLimit,   loginLimit,   loginWindow,   RateLimitAlgorithm.FIXED_WINDOW),
                "/api/search",  new RateLimitConfig(searchLimit,  searchLimit,  searchWindow,  RateLimitAlgorithm.SLIDING_WINDOW_LOG),
                "/api/payment", new RateLimitConfig(paymentLimit, paymentLimit, paymentWindow, RateLimitAlgorithm.TOKEN_BUCKET)
        );
    }
}