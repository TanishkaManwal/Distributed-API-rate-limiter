package com.ratelimiter.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.List;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // -------------------------------------------------------
    // Connection Factory
    // -------------------------------------------------------

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWait(Duration.ofMillis(1000));

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(2000))
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(2000))
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    // -------------------------------------------------------
    // Lua Script: Token Bucket (PRIMARY)
    // Atomically checks and refills tokens.
    // KEYS[1] = bucket key
    // ARGV[1] = capacity, ARGV[2] = refill_rate (tokens/sec),
    // ARGV[3] = requested_tokens, ARGV[4] = now (epoch millis)
    // Returns: {allowed (0|1), remaining_tokens, retry_after_ms}
    // -------------------------------------------------------
    @Bean(name = "tokenBucketScript")
    public DefaultRedisScript<List> tokenBucketScript() {
        String script = """
                local key       = KEYS[1]
                local capacity  = tonumber(ARGV[1])
                local refill_rate = tonumber(ARGV[2])
                local requested = tonumber(ARGV[3])
                local now       = tonumber(ARGV[4])
                
                local data = redis.call('HMGET', key, 'tokens', 'last_refill')
                local tokens      = tonumber(data[1])
                local last_refill = tonumber(data[2])
                
                if tokens == nil then
                    tokens = capacity
                    last_refill = now
                end
                
                -- Refill tokens based on elapsed time
                local elapsed = math.max(0, now - last_refill)
                local refilled = math.floor(elapsed * refill_rate / 1000)
                tokens = math.min(capacity, tokens + refilled)
                
                local allowed = 0
                local retry_after = 0
                
                if tokens >= requested then
                    tokens = tokens - requested
                    allowed = 1
                else
                    -- Calculate wait time until enough tokens are available
                    local deficit = requested - tokens
                    retry_after = math.ceil(deficit * 1000 / refill_rate)
                end
                
                -- Persist updated state; expire after 2x window to clean up
                redis.call('HMSET', key,
                    'tokens', tokens,
                    'last_refill', now)
                redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / refill_rate) * 2 + 60000)
                
                return {allowed, tokens, retry_after}
                """;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(List.class);
        return redisScript;
    }

    // -------------------------------------------------------
    // Lua Script: Fixed Window
    // KEYS[1] = counter key
    // ARGV[1] = limit, ARGV[2] = window_seconds
    // Returns: {allowed (0|1), current_count, reset_after_seconds}
    // -------------------------------------------------------
    @Bean(name = "fixedWindowScript")
    public DefaultRedisScript<List> fixedWindowScript() {
        String script = """
                local key    = KEYS[1]
                local limit  = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                
                local count = redis.call('GET', key)
                count = tonumber(count) or 0
                
                local allowed = 0
                local ttl = redis.call('TTL', key)
                if ttl < 0 then ttl = window end
                
                if count < limit then
                    count = redis.call('INCR', key)
                    if count == 1 then
                        redis.call('EXPIRE', key, window)
                        ttl = window
                    end
                    allowed = 1
                end
                
                local remaining = math.max(0, limit - count)
                return {allowed, remaining, ttl}
                """;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(List.class);
        return redisScript;
    }

    // -------------------------------------------------------
    // Lua Script: Sliding Window Log
    // KEYS[1] = sorted-set key
    // ARGV[1] = limit, ARGV[2] = window_seconds,
    // ARGV[3] = now (epoch millis), ARGV[4] = unique member id
    // Returns: {allowed (0|1), current_count, oldest_entry_age_ms}
    // -------------------------------------------------------
    @Bean(name = "slidingWindowLogScript")
    public DefaultRedisScript<List> slidingWindowLogScript() {
        String script = """
                local key    = KEYS[1]
                local limit  = tonumber(ARGV[1])
                local window = tonumber(ARGV[2]) * 1000
                local now    = tonumber(ARGV[3])
                local member = ARGV[4]
                
                -- Remove entries outside the window
                redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
                
                local count = redis.call('ZCARD', key)
                local allowed = 0
                local oldest_age = 0
                
                if count < limit then
                    redis.call('ZADD', key, now, member)
                    count = count + 1
                    allowed = 1
                else
                    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                    if #oldest > 0 then
                        local oldest_ts = tonumber(oldest[2])
                        oldest_age = (oldest_ts + window - now)
                    end
                end
                
                redis.call('PEXPIRE', key, window + 1000)
                local remaining = math.max(0, limit - count)
                return {allowed, remaining, oldest_age}
                """;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(List.class);
        return redisScript;
    }

    // -------------------------------------------------------
    // Lua Script: Leaky Bucket
    // KEYS[1] = bucket key
    // ARGV[1] = capacity, ARGV[2] = leak_rate (reqs/sec),
    // ARGV[3] = now (epoch millis)
    // Returns: {allowed (0|1), queue_size, retry_after_ms}
    // -------------------------------------------------------
    @Bean(name = "leakyBucketScript")
    public DefaultRedisScript<List> leakyBucketScript() {
        String script = """
                local key       = KEYS[1]
                local capacity  = tonumber(ARGV[1])
                local leak_rate = tonumber(ARGV[2])
                local now       = tonumber(ARGV[3])
                
                local data = redis.call('HMGET', key, 'queue', 'last_leak')
                local queue     = tonumber(data[1]) or 0
                local last_leak = tonumber(data[2]) or now
                
                -- Leak requests based on elapsed time
                local elapsed = math.max(0, now - last_leak)
                local leaked  = math.floor(elapsed * leak_rate / 1000)
                queue = math.max(0, queue - leaked)
                
                local allowed = 0
                local retry_after = 0
                
                if queue < capacity then
                    queue = queue + 1
                    allowed = 1
                else
                    -- Retry after one slot leaks
                    retry_after = math.ceil(1000 / leak_rate)
                end
                
                redis.call('HMSET', key, 'queue', queue, 'last_leak', now)
                redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / leak_rate) * 2 + 60000)
                
                return {allowed, queue, retry_after}
                """;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(List.class);
        return redisScript;
    }
}
