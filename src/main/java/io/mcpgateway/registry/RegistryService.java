package io.mcpgateway.registry;

import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and lookup of backend MCP servers (FR-REG-1, FR-REG-4).
 *
 * <p>Authorization rules enforced here, not in the controller, so no alternative entry point can
 * skip them: platform-shared servers may only be registered by a {@code platform-admin};
 * tenant-private servers by a {@code tenant-admin} (or platform-admin), always under the
 * registrant's own tenant — there is no way to register into someone else's tenant.
 */
@Service
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

    private final McpServerRepository servers;
    private final TenantDirectory tenants;

    public RegistryService(McpServerRepository servers, TenantDirectory tenants) {
        this.servers = servers;
        this.tenants = tenants;
    }

    /**
     * Registers a server with its tool manifest, pinning each tool's content hash (FR-REG-5).
     *
     * @param shared true to register platform-shared (platform-admin only)
     * @throws AccessDeniedException if the actor's roles don't permit the requested scope
     */
    @Transactional
    public McpServer register(AuthenticatedActor actor, boolean shared, String name, String baseUrl,
                              String description, List<ToolManifest> manifest) {
        UUID ownerTenantId = resolveOwnerTenant(actor, shared);

        McpServer server = new McpServer(ownerTenantId, name, baseUrl, description);
        for (ToolManifest tool : manifest) {
            server.addTool(new Tool(tool.name(), tool.description(), tool.inputSchema(),
                    tool.sensitivityTier(),
                    ToolManifestHasher.hash(tool.name(), tool.description(), tool.inputSchema())));
        }
        McpServer saved = servers.save(server);
        log.info("registered {} server '{}' ({} tools) by {}",
                shared ? "platform-shared" : "tenant-private", name, manifest.size(), actor.username());
        return saved;
    }

    /** Servers visible to the actor's tenant: platform-shared plus tenant-private (FR-REG-4). */
    @Transactional(readOnly = true)
    public List<McpServer> visibleServers(AuthenticatedActor actor) {
        return servers.findAllVisibleTo(tenants.resolveOrProvision(actor.tenantId()));
    }

    private UUID resolveOwnerTenant(AuthenticatedActor actor, boolean shared) {
        if (shared) {
            if (!actor.hasRole("platform-admin")) {
                throw new AccessDeniedException("Only platform-admin may register platform-shared servers");
            }
            return null;
        }
        if (!actor.hasRole("tenant-admin") && !actor.hasRole("platform-admin")) {
            throw new AccessDeniedException("Only tenant-admin may register servers for their tenant");
        }
        return tenants.resolveOrProvision(actor.tenantId());
    }

    /** A tool definition as submitted at registration time. */
    public record ToolManifest(String name, String description, String inputSchema,
                               Tool.SensitivityTier sensitivityTier) {
    }
}
