package io.mcpgateway.registry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable, cache-safe view of one server's routing state. The request path works from
 * snapshots, never from JPA entities: entities carry open-session baggage and mutable state
 * that must not leak into a cache shared across requests.
 */
public record RouteSnapshot(UUID serverId, String serverName, String baseUrl, boolean enabled,
                            List<ToolRoute> tools) {

    /** One tool's routing/enforcement state within a snapshot. */
    public record ToolRoute(UUID id, String name, String description, String inputSchema,
                            Tool.SensitivityTier sensitivityTier, Tool.Status status,
                            String manifestHash) {

        public boolean callable() {
            return status == Tool.Status.ACTIVE;
        }
    }

    public Optional<ToolRoute> tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    static RouteSnapshot from(McpServer server) {
        return new RouteSnapshot(server.getId(), server.getName(), server.getBaseUrl(),
                server.isEnabled(),
                server.getTools().stream()
                        .map(t -> new ToolRoute(t.getId(), t.getName(), t.getDescription(),
                                t.getInputSchema(), t.getSensitivityTier(), t.getStatus(),
                                t.getManifestHash()))
                        .toList());
    }
}
