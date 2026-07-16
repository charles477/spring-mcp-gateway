package io.mcpgateway.common;

import java.util.Set;

/**
 * The identity every pipeline stage (authz, rate limiting, audit) works with, resolved once
 * at the edge from the validated credential. Immutable so it can be passed across stages
 * without any stage being able to escalate it.
 *
 * @param subject  stable IdP subject identifier (OIDC {@code sub}), never null
 * @param username human-readable name for logs and audit records, never null
 * @param tenantId tenant the actor belongs to, never null — requests without a resolvable
 *                 tenant are rejected during authentication (FR-TENANT-2)
 * @param roles    role names as issued by the IdP, without any {@code ROLE_} prefix
 */
public record AuthenticatedActor(String subject, String username, String tenantId, Set<String> roles) {

    /** @return true if the actor holds the given role (exact, prefix-free match). */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
