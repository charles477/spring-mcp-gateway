package io.mcpgateway.ratelimit;

import io.mcpgateway.common.AuthenticatedActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Applies the (tenant, user, tool) rate limit (FR-RATE-1). The key includes all three so one
 * noisy user cannot exhaust a tenant's quota for others, and one tool cannot starve the rest.
 * Limits are global config for now; per-tenant/per-tool overrides are a policy-document
 * extension documented in the roadmap.
 */
@Service
public class RateLimitService {

    private final RedisTokenBucket bucket;
    private final int capacity;
    private final double refillPerSecond;

    public RateLimitService(RedisTokenBucket bucket,
                            @Value("${gateway.ratelimit.capacity:20}") int capacity,
                            @Value("${gateway.ratelimit.refill-per-minute:60}") double refillPerMinute) {
        this.bucket = bucket;
        this.capacity = capacity;
        this.refillPerSecond = refillPerMinute / 60.0;
    }

    /** @return true when the call may proceed; false when the caller is over its limit */
    public boolean allow(AuthenticatedActor actor, String qualifiedToolName) {
        return bucket.tryConsume(
                actor.tenantId() + ":" + actor.subject() + ":" + qualifiedToolName,
                capacity, refillPerSecond);
    }
}
