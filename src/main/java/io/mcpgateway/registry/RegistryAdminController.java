package io.mcpgateway.registry;

import tools.jackson.databind.JsonNode;
import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.registry.RegistryService.ToolManifest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.URL;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for MCP server registration and inspection (FR-REG-1, FR-ADMIN-1).
 *
 * <p>Endpoints are authenticated by the edge chain; the scope rules (who may register shared vs
 * tenant-private) live in {@link RegistryService} so they hold for every caller of the service,
 * not just this controller.
 */
@RestController
@RequestMapping("/admin/servers")
public class RegistryAdminController {

    private final RegistryService registry;

    public RegistryAdminController(RegistryService registry) {
        this.registry = registry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerResponse register(GatewayAuthenticationToken auth,
                                   @Valid @RequestBody RegisterServerRequest request) {
        List<ToolManifest> manifest = request.tools().stream()
                .map(t -> new ToolManifest(t.name(), t.description(), t.inputSchema().toString(),
                        t.sensitivityTier() == null ? Tool.SensitivityTier.INTERNAL : t.sensitivityTier()))
                .toList();
        McpServer server = registry.register(auth.actor(), request.shared(), request.name(),
                request.baseUrl(), request.description(), manifest);
        return ServerResponse.from(server);
    }

    @GetMapping
    public List<ServerResponse> list(GatewayAuthenticationToken auth) {
        return registry.visibleServers(auth.actor()).stream().map(ServerResponse::from).toList();
    }

    /**
     * Kill switch (FR-REG-6): one call disables or restores a server. Ownership rules are
     * enforced in the service; both directions produce audit events.
     */
    @PatchMapping("/{serverId}/enabled")
    public void setEnabled(GatewayAuthenticationToken auth, @PathVariable UUID serverId,
                           @Valid @RequestBody KillSwitchRequest request) {
        registry.setServerEnabled(auth.actor(), serverId, request.enabled());
    }

    /** Kill-switch payload: {@code enabled=false} disables, {@code true} restores. */
    public record KillSwitchRequest(@NotNull Boolean enabled) {
    }

    /** Registration payload; {@code shared} requires the platform-admin role. */
    public record RegisterServerRequest(
            @NotBlank String name,
            @NotBlank @URL String baseUrl,
            String description,
            boolean shared,
            @NotEmpty List<@Valid ToolRequest> tools) {
    }

    /** A tool definition inside a registration payload. */
    public record ToolRequest(
            @NotBlank String name,
            String description,
            @NotNull JsonNode inputSchema,
            Tool.SensitivityTier sensitivityTier) {
    }

    /** Public view of a registered server; never exposes backend auth details. */
    public record ServerResponse(UUID id, String name, String baseUrl, String description,
                                 boolean shared, boolean enabled, Instant createdAt,
                                 List<ToolResponse> tools) {

        static ServerResponse from(McpServer server) {
            return new ServerResponse(server.getId(), server.getName(), server.getBaseUrl(),
                    server.getDescription(), server.isPlatformShared(), server.isEnabled(),
                    server.getCreatedAt(),
                    server.getTools().stream().map(ToolResponse::from).toList());
        }
    }

    /** Public view of a registered tool, including its pinned manifest hash. */
    public record ToolResponse(UUID id, String name, String description,
                               Tool.SensitivityTier sensitivityTier, Tool.Status status,
                               String manifestHash) {

        static ToolResponse from(Tool tool) {
            return new ToolResponse(tool.getId(), tool.getName(), tool.getDescription(),
                    tool.getSensitivityTier(), tool.getStatus(), tool.getManifestHash());
        }
    }
}
