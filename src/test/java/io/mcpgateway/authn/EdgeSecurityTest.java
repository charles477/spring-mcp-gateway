package io.mcpgateway.authn;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the deny-by-default edge (FR-AUTHN-4): no token means 401 on any endpoint, and a valid
 * token round-trips through claim mapping to the identity echo endpoint (FR-AUTHN-1).
 */
@WebMvcTest(MeController.class)
@Import(SecurityConfig.class)
class EdgeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    /** Satisfies the resource-server wiring; never invoked because tests inject authentication directly. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestSeesItsResolvedIdentity() throws Exception {
        mockMvc.perform(get("/api/me").with(authentication(aliceToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.tenantId").value("tenant-acme"))
                .andExpect(jsonPath("$.roles[0]").value("tool-user"));
    }

    /** Builds the token exactly as production does: through the converter, not hand-assembled. */
    private static AbstractAuthenticationToken aliceToken() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("subject-alice")
                .claim("tenant_id", "tenant-acme")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("tool-user")))
                .build();
        return new GatewayJwtAuthenticationConverter(
                new AuthnProperties("tenant_id", "realm_access.roles")).convert(jwt);
    }
}
