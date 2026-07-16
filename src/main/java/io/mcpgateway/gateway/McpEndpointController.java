package io.mcpgateway.gateway;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.gateway.JsonRpc.Request;
import io.mcpgateway.gateway.JsonRpc.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single MCP endpoint agents connect to (FR-GW-1): JSON-RPC 2.0 over HTTP POST, exactly as
 * an unmodified MCP client speaks it. Authentication happened at the edge; everything else
 * happens in {@link McpGatewayService}.
 */
@RestController
public class McpEndpointController {

    private final McpGatewayService gateway;

    public McpEndpointController(McpGatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/mcp")
    public Response handle(GatewayAuthenticationToken auth, @RequestBody Request request) {
        return gateway.handle(auth.actor(), request);
    }
}
