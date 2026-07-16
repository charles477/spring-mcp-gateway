package io.mcpgateway.authn;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claim-mapping configuration that makes the IdP swappable without code changes (FR-AUTHN-2):
 * Keycloak, Okta, and Azure AD each place tenant and role information in different claims,
 * so both locations are configurable rather than hardcoded.
 *
 * @param tenantClaim name of the JWT claim carrying the tenant identifier
 * @param rolesClaim  dot-separated path to the roles list inside the JWT
 *                    (e.g. Keycloak's {@code realm_access.roles})
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthnProperties(String tenantClaim, String rolesClaim) {
}
