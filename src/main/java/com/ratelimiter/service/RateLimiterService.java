package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.model.*;
import com.ratelimiter.repository.ApiKeyRepository;
import com.ratelimiter.service.algorithm.*;
import com.ratelimiter.service.fallback.InMemoryFallbackLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Central rate limiter service.
 *
 * Responsibilities:
 * 1. Resolve the API key → user/tier
 * 2. Determine per-endpoint config (overrides tier config)
 * 3. Select the appropriate algorithm
 * 4. Execute the check (Redis primary, in-memory fallback)
 * 5. Record observability metrics
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiterProperties properties;
    private final TokenBucketRateLimiter tokenBucketLimiter;
    private final FixedWindowRateLimiter fixedWindowLimiter;
    private final SlidingWindowLogRateLimiter slidingWindowLogLimiter;
    private final LeakyBucketRateLimiter leakyBucketLimiter;
    private final InMemoryFallbackLimiter fallbackLimiter;
    private final MeterRegistry meterRegistry;

    // Metrics
    private final Counter allowedRequests;
    private final Counter deniedRequests;
    private final Counter fallbackRequests;

    public RateLimiterService(
            ApiKeyRepository apiKeyRepository,
            RateLimiterProperties properties,
            TokenBucketRateLimiter tokenBucketLimiter,
            FixedWindowRateLimiter fixedWindowLimiter,
            SlidingWindowLogRateLimiter slidingWindowLogLimiter,
            LeakyBucketRateLimiter leakyBucketLimiter,
            InMemoryFallbackLimiter fallbackLimiter,
            MeterRegistry meterRegistry) {

        this.apiKeyRepository      = apiKeyRepository;
        this.properties            = properties;
        this.tokenBucketLimiter    = tokenBucketLimiter;
        this.fixedWindowLimiter    = fixedWindowLimiter;
        this.slidingWindowLogLimiter = slidingWindowLogLimiter;
        this.leakyBucketLimiter    = leakyBucketLimiter;
        this.fallbackLimiter       = fallbackLimiter;
        this.meterRegistry         = meterRegistry;

        this.allowedRequests  = Counter.builder("rate_limiter.requests.allowed")
                .description("Total requests allowed").register(meterRegistry);
        this.deniedRequests   = Counter.builder("rate_limiter.requests.denied")
                .description("Total requests denied (429)").register(meterRegistry);
        this.fallbackRequests = Counter.builder("rate_limiter.requests.fallback")
                .description("Requests handled by fallback limiter").register(meterRegistry);
    }
    public RateLimitResult checkLimit(String identifier, String endpoint) {
        // 1. Resolve API key and tier
        Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKey(identifier);

        if (apiKeyOpt.isPresent() && !apiKeyOpt.get().isActive()) {
            log.warn("Inactive API key used: {}", identifier);
            return deny("API key is inactive or revoked");
        }

        UserTier tier = apiKeyOpt.map(ApiKey::getTier).orElse(UserTier.FREE);
        String userId = apiKeyOpt.map(ApiKey::getUserId).orElse("anon:" + identifier);

        // 2. Resolve config: endpoint-specific overrides tier config
        Map<String, RateLimitConfig> endpointConfigs = properties.getEndpointConfigs();
        RateLimitConfig config = endpointConfigs.getOrDefault(
                normalizeEndpoint(endpoint),
                properties.getTierConfig(tier)
        );

        RateLimitRequest request = new RateLimitRequest(userId, endpoint, tier, config);

        // 3. Execute rate limit check
        RateLimitResult result = executeWithFallback(request);

        // 4. Record metrics
        recordMetrics(result, tier, endpoint);

        log.debug("RateLimit check: identifier={} endpoint={} tier={} allowed={}",
                identifier, endpoint, tier, result.isAllowed());

        return result;
    }

    // -------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------

    private RateLimitResult executeWithFallback(RateLimitRequest request) {
        try {
            RateLimiterAlgorithm algorithm = resolveAlgorithm(request.getConfig().getAlgorithm());
            return algorithm.isAllowed(request);
        } catch (Exception redisException) {
            log.error("Redis unavailable, switching to fallback: {}", redisException.getMessage());
            if (properties.isFallbackEnabled()) {
                fallbackRequests.increment();
                return fallbackLimiter.isAllowed(request);
            }
            // If fallback is disabled, fail open (allow the request)
            log.warn("Fallback disabled, failing open for key={}", request.getRedisKey());
            return RateLimitResult.builder()
                    .allowed(true)
                    .remainingTokens(-1)
                    .retryAfterSeconds(0)
                    .reason("Redis unavailable, fail-open policy active")
                    .algorithm(request.getConfig().getAlgorithm())
                    .build();
        }
    }

    private RateLimiterAlgorithm resolveAlgorithm(RateLimitAlgorithm algorithm) {
        return switch (algorithm) {
            case TOKEN_BUCKET      -> tokenBucketLimiter;
            case FIXED_WINDOW      -> fixedWindowLimiter;
            case SLIDING_WINDOW_LOG -> slidingWindowLogLimiter;
            case LEAKY_BUCKET      -> leakyBucketLimiter;
        };
    }

    private String normalizeEndpoint(String endpoint) {
        // Normalize to base path: /api/login/extra → /api/login
        if (endpoint.startsWith("/api/login"))   return "/api/login";
        if (endpoint.startsWith("/api/search"))  return "/api/search";
        if (endpoint.startsWith("/api/payment")) return "/api/payment";
        return endpoint;
    }

    private RateLimitResult deny(String reason) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingTokens(0)
                .retryAfterSeconds(60)
                .reason(reason)
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .build();
    }

    private void recordMetrics(RateLimitResult result, UserTier tier, String endpoint) {
        if (result.isAllowed()) {
            allowedRequests.increment();
            meterRegistry.counter("rate_limiter.allowed", "tier", tier.name(), "endpoint", endpoint).increment();
        } else {
            deniedRequests.increment();
            meterRegistry.counter("rate_limiter.denied", "tier", tier.name(), "endpoint", endpoint).increment();
        }
    }
}