package io.mcpgateway.authn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.common.AuthenticatedActor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Covers FR-AUTHN-1 (claim mapping) and the FR-TENANT-2 edge rule: a token without a tenant
 * claim must be rejected during authentication, never defaulted.
 */
class GatewayJwtAuthenticationConverterTest {

    private final GatewayJwtAuthenticationConverter converter = new GatewayJwtAuthenticationConverter(
            new AuthnProperties("tenant_id", "realm_access.roles"));

    @Test
    void mapsRolesTenantAndUsernameFromKeycloakShapedToken() {
        Jwt jwt = jwt(builder -> builder
                .claim("tenant_id", "tenant-acme")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("tool-user", "tool-approver"))));

        GatewayAuthenticationToken token = (GatewayAuthenticationToken) converter.convert(jwt);
        AuthenticatedActor actor = token.actor();

        assertThat(actor.tenantId()).isEqualTo("tenant-acme");
        assertThat(actor.username()).isEqualTo("alice");
        assertThat(actor.subject()).isEqualTo("subject-1");
        assertThat(actor.roles()).containsExactlyInAnyOrder("tool-user", "tool-approver");
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_tool-user", "ROLE_tool-approver");
    }

    @Test
    void rejectsTokenWithoutTenantClaim() {
        Jwt jwt = jwt(builder -> builder
                .claim("realm_access", Map.of("roles", List.of("tool-user"))));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void missingRolesPathYieldsAuthenticatedActorWithNoRoles() {
        Jwt jwt = jwt(builder -> builder.claim("tenant_id", "tenant-acme"));

        GatewayAuthenticationToken token = (GatewayAuthenticationToken) converter.convert(jwt);

        assertThat(token.actor().roles()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void fallsBackToSubjectWhenPreferredUsernameAbsent() {
        Jwt jwt = jwt(builder -> builder.claim("tenant_id", "tenant-acme"));

        GatewayAuthenticationToken token = (GatewayAuthenticationToken) converter.convert(jwt);

        assertThat(token.actor().username()).isEqualTo("subject-1");
    }

    private static Jwt jwt(java.util.function.Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("subject-1");
        claims.accept(builder);
        return builder.build();
    }
}
