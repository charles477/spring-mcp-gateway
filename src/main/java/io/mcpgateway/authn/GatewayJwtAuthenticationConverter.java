package io.mcpgateway.authn;

import io.mcpgateway.common.AuthenticatedActor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Converts a validated OIDC JWT into the gateway's authentication token, resolving roles and
 * tenant from the configured claim locations.
 *
 * <p>A token without a tenant claim is rejected outright rather than defaulted: every later
 * pipeline stage (authz, rate limiting, audit, RLS) keys on the tenant, and an unscoped request
 * slipping through would bypass tenant isolation (FR-TENANT-2).
 */
public class GatewayJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(GatewayJwtAuthenticationConverter.class);

    private final AuthnProperties properties;

    public GatewayJwtAuthenticationConverter(AuthnProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenantId = jwt.getClaimAsString(properties.tenantClaim());
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("rejected token for subject {}: missing tenant claim '{}'",
                    jwt.getSubject(), properties.tenantClaim());
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "invalid_token", "Token has no '" + properties.tenantClaim() + "' claim", null));
        }

        Set<String> roles = extractRoles(jwt);
        Collection<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toUnmodifiableSet());

        String username = jwt.hasClaim("preferred_username")
                ? jwt.getClaimAsString("preferred_username")
                : jwt.getSubject();

        AuthenticatedActor actor = new AuthenticatedActor(jwt.getSubject(), username, tenantId, roles);
        return new GatewayAuthenticationToken(jwt, authorities, actor);
    }

    /**
     * Walks the dot-separated {@code rolesClaim} path into the claim tree. A missing path yields
     * an empty role set (an authenticated actor with no roles), not an error — role absence is an
     * authorization concern, and deny-by-default handles it there (FR-AUTHZ-2).
     */
    private Set<String> extractRoles(Jwt jwt) {
        Object node = jwt.getClaims();
        for (String segment : properties.rolesClaim().split("\\.")) {
            if (!(node instanceof Map<?, ?> map) || (node = map.get(segment)) == null) {
                return Set.of();
            }
        }
        if (node instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    /**
     * A {@link JwtAuthenticationToken} that additionally carries the resolved
     * {@link AuthenticatedActor}, so downstream stages never re-parse claims.
     */
    public static final class GatewayAuthenticationToken extends JwtAuthenticationToken {

        private final transient AuthenticatedActor actor;

        private GatewayAuthenticationToken(Jwt jwt, Collection<GrantedAuthority> authorities,
                                           AuthenticatedActor actor) {
            super(jwt, authorities, actor.username());
            this.actor = actor;
        }

        public AuthenticatedActor actor() {
            return actor;
        }
    }
}
