package io.mcpgateway.registry;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.audit.AuditService;
import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.List;
import java.util.Optional;
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
    private final ToolRepository tools;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final RegistryEvents events;

    public RegistryService(McpServerRepository servers, ToolRepository tools,
                           TenantDirectory tenants, AuditService audit, RegistryEvents events) {
        this.servers = servers;
        this.tools = tools;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
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
        events.serverChanged(name);
        log.info("registered {} server '{}' ({} tools) by {}",
                shared ? "platform-shared" : "tenant-private", name, manifest.size(), actor.username());
        return saved;
    }

    /** Servers visible to the actor's tenant: platform-shared plus tenant-private (FR-REG-4). */
    @Transactional(readOnly = true)
    public List<McpServer> visibleServers(AuthenticatedActor actor) {
        return servers.findAllVisibleTo(tenants.resolveOrProvision(actor.tenantId()));
    }

    /** Resolves one visible server by name for routing (FR-REG-4 scoping applied in the query). */
    @Transactional(readOnly = true)
    public Optional<McpServer> visibleServerByName(AuthenticatedActor actor, String serverName) {
        return servers.findVisibleByName(tenants.resolveOrProvision(actor.tenantId()), serverName);
    }

    /**
     * Kill switch at server granularity (FR-REG-6): platform-admin for any server, tenant-admin
     * only for servers their tenant owns. Both directions are audited.
     */
    @Transactional
    public void setServerEnabled(AuthenticatedActor actor, UUID serverId, boolean enabled) {
        McpServer server = servers.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown server " + serverId));
        requireAdminOver(actor, server);
        server.setEnabled(enabled);
        events.serverChanged(server.getName());
        log.warn("kill switch: server '{}' {} by {}", server.getName(),
                enabled ? "restored" : "disabled", actor.username());
        audit.record(actor, "registry/kill-switch", server.getName(), Decision.ALLOWED,
                enabled ? "server restored" : "server disabled", 0);
    }

    /**
     * Quarantines a tool after manifest drift (FR-REG-5). Called by the gateway pipeline, not an
     * admin — the tripwire must fire without human involvement.
     */
    @Transactional
    public void quarantineForDrift(AuthenticatedActor actor, UUID toolId, String qualifiedName) {
        tools.findById(toolId).ifPresent(tool -> {
            tool.quarantine();
            events.serverChanged(tool.getServer().getName());
            log.warn("quarantined tool {} after manifest drift, flagged during call by {}",
                    qualifiedName, actor.username());
            audit.record(actor, "registry/quarantine", qualifiedName, Decision.QUARANTINED,
                    "live tool definition no longer matches pinned manifest", 0);
        });
    }

    /**
     * Re-approves a quarantined tool under a freshly pinned manifest (FR-REG-5): an explicit
     * admin decision that the backend's current definition is trusted again.
     */
    @Transactional
    public void reapproveTool(AuthenticatedActor actor, UUID toolId, String name,
                              String description, String inputSchema) {
        Tool tool = tools.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool " + toolId));
        requireAdminOver(actor, tool.getServer());
        tool.reapprove(ToolManifestHasher.hash(name, description, inputSchema));
        events.serverChanged(tool.getServer().getName());
        log.info("tool {} re-approved with new manifest by {}", tool.getName(), actor.username());
        audit.record(actor, "registry/reapprove", tool.getName(), Decision.ALLOWED,
                "tool re-approved under new pinned manifest", 0);
    }

    /**
     * Resolves where a tool lives so re-approval can fetch the backend's live definition.
     * Runs the same admin check as the mutation it precedes, so location can't be probed.
     */
    @Transactional(readOnly = true)
    public ToolLocation locateToolForAdmin(AuthenticatedActor actor, UUID toolId) {
        Tool tool = tools.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool " + toolId));
        requireAdminOver(actor, tool.getServer());
        return new ToolLocation(tool.getId(), tool.getName(), tool.getServer().getBaseUrl());
    }

    /** Where a registered tool is served from. */
    public record ToolLocation(UUID toolId, String toolName, String baseUrl) {
    }

    private void requireAdminOver(AuthenticatedActor actor, McpServer server) {
        if (actor.hasRole("platform-admin")) {
            return;
        }
        boolean ownsIt = actor.hasRole("tenant-admin") && !server.isPlatformShared()
                && server.getOwnerTenantId().equals(tenants.resolveOrProvision(actor.tenantId()));
        if (!ownsIt) {
            throw new AccessDeniedException("Not an administrator of this server");
        }
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
