package io.mcpgateway.gateway;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.registry.RegistryService;
import io.mcpgateway.registry.RegistryService.ToolLocation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Re-approves a quarantined tool (FR-REG-5): fetches the backend's <em>current</em> definition
 * and pins a fresh manifest hash from it — an explicit admin statement that the new definition
 * has been reviewed and is trusted. Lives in the gateway module because it needs the backend
 * client; the ownership check happens in the registry service.
 */
@RestController
public class ToolRecoveryAdminController {

    private final RegistryService registry;
    private final McpBackendClient backend;

    public ToolRecoveryAdminController(RegistryService registry, McpBackendClient backend) {
        this.registry = registry;
        this.backend = backend;
    }

    @PostMapping("/admin/tools/{toolId}/reapprove")
    public void reapprove(GatewayAuthenticationToken auth, @PathVariable UUID toolId) {
        ToolLocation location = registry.locateToolForAdmin(auth.actor(), toolId);
        JsonNode liveDefinition = backend.listTools(location.baseUrl())
                .map(tools -> findByName(tools, location.toolName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Backend no longer serves tool '" + location.toolName() + "'"));
        registry.reapproveTool(auth.actor(), toolId,
                liveDefinition.path("name").asString(""),
                liveDefinition.path("description").isMissingNode()
                        ? null : liveDefinition.path("description").asString(),
                liveDefinition.path("inputSchema").toString());
    }

    private static JsonNode findByName(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asString(""))) {
                return tool;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Backend no longer serves tool '" + name + "'");
    }
}
