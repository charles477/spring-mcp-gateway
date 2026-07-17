package io.mcpgateway.ratelimit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiter implemented as one atomic Redis Lua script (FR-RATE-1). State lives
 * only in Redis, so every gateway node enforces the same bucket for the same key — two nodes
 * cannot each grant a full quota. The script refills lazily on access (elapsed-time × rate),
 * which needs no background scheduler and no per-bucket clock state beyond one timestamp.
 */
@Component
public class RedisTokenBucket {

    private static final DefaultRedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local tokens_key = KEYS[1]
            local stamp_key = KEYS[2]
            local capacity = tonumber(ARGV[1])
            local refill_per_sec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('get', tokens_key) or capacity)
            local stamp = tonumber(redis.call('get', stamp_key) or now)
            tokens = math.min(capacity, tokens + (now - stamp) * refill_per_sec)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            local ttl = math.ceil(capacity / refill_per_sec) * 2
            redis.call('set', tokens_key, tokens, 'EX', ttl)
            redis.call('set', stamp_key, now, 'EX', ttl)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Atomically takes one token from the named bucket.
     *
     * @return true if a token was available; false means the caller is over its limit
     */
    public boolean tryConsume(String bucketKey, int capacity, double refillPerSecond) {
        Long allowed = redis.execute(TAKE,
                List.of("rl:{" + bucketKey + "}:tokens", "rl:{" + bucketKey + "}:ts"),
                String.valueOf(capacity), String.valueOf(refillPerSecond),
                String.valueOf(Instant.now().getEpochSecond()));
        return allowed != null && allowed == 1L;
    }
}
