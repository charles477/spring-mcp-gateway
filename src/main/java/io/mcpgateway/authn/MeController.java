package io.mcpgateway.authn;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.common.AuthenticatedActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity echo endpoint: returns the caller's resolved {@link AuthenticatedActor}.
 *
 * <p>Exists so that operators (and the demo script) can verify the full authentication chain —
 * IdP token, JWKS validation, claim mapping, tenant resolution — with a single call, before any
 * MCP traffic is involved (FR-AUTHN-1).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public AuthenticatedActor me(GatewayAuthenticationToken authentication) {
        return authentication.actor();
    }
}
