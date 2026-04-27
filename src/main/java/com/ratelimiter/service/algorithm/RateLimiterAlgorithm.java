package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.RateLimitRequest;
import com.ratelimiter.model.RateLimitResult;

public interface RateLimiterAlgorithm {

    /**
     * Checks whether the incoming request is within the rate limit.
     *
     * @param request encapsulates the key, endpoint, tier, and config
     * @return RateLimitResult with allow/deny decision and metadata
     */
    RateLimitResult isAllowed(RateLimitRequest request);
}