package com.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.RateLimiterService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servlet filter that enforces rate limits on all /api/* requests.
 *
 * Extraction priority:
 * 1. X-API-Key header
 * 2. api_key query parameter
 * 3. Client IP address (fallback for unauthenticated requests)
 *
 * On rejection, writes a structured JSON 429 response with
 * Retry-After and X-RateLimit-* headers.
 */
@Component
@WebFilter(urlPatterns = "/api/*")
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String HEADER_API_KEY        = "X-API-Key";
    private static final String PARAM_API_KEY         = "api_key";
    private static final String HEADER_RETRY_AFTER    = "Retry-After";
    private static final String HEADER_REMAINING      = "X-RateLimit-Remaining";
    private static final String HEADER_RESET          = "X-RateLimit-Reset";
    private static final String HEADER_LIMIT_POLICY   = "X-RateLimit-Policy";
    private static final String HEADER_REQUEST_ID     = "X-Request-ID";

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("RateLimitFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String identifier = extractIdentifier(request);
        String endpoint   = request.getRequestURI();

        RateLimitResult result = rateLimiterService.checkLimit(identifier, endpoint);

        // Always set informational headers
        setRateLimitHeaders(response, result, endpoint);

        if (result.isAllowed()) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded: identifier={} endpoint={} reason={}",
                    identifier, endpoint, result.getReason());
            writeTooManyRequestsResponse(response, result, identifier);
        }
    }

    @Override
    public void destroy() {
        log.info("RateLimitFilter destroyed");
    }

    // -------------------------------------------------------
    // Identifier extraction
    // -------------------------------------------------------

    private String extractIdentifier(HttpServletRequest request) {
        // 1. Check X-API-Key header
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        // 2. Check query parameter
        apiKey = request.getParameter(PARAM_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        // 3. Fallback to IP address
        return extractClientIp(request);
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            // X-Forwarded-For can contain a chain: "client, proxy1, proxy2"
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    // -------------------------------------------------------
    // Response helpers
    // -------------------------------------------------------

    private void setRateLimitHeaders(HttpServletResponse response, RateLimitResult result, String endpoint) {
        response.setHeader(HEADER_REMAINING,    String.valueOf(Math.max(0, result.getRemainingTokens())));
        response.setHeader(HEADER_RESET,        String.valueOf(Instant.now().getEpochSecond() + result.getResetAfterSeconds()));
        response.setHeader(HEADER_LIMIT_POLICY, endpoint + ";algorithm=" + result.getAlgorithm());

        if (!result.isAllowed()) {
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.getRetryAfterSeconds()));
        }
    }

    private void writeTooManyRequestsResponse(HttpServletResponse response,
                                              RateLimitResult result,
                                              String identifier) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",     429);
        body.put("error",      "Too Many Requests");
        body.put("message",    result.getReason());
        body.put("retryAfter", result.getRetryAfterSeconds());
        body.put("algorithm",  result.getAlgorithm() != null ? result.getAlgorithm().name() : "UNKNOWN");
        body.put("timestamp",  Instant.now().toString());

        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}