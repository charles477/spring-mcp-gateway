package io.mcpgateway.registry;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A registered backend MCP server: where to route, and who may see it.
 *
 * <p>{@code ownerTenantId == null} means platform-shared — listed for every tenant, though each
 * tenant's own policies/limits still govern actual calls. Non-null means tenant-private: the
 * server does not exist at all from any other tenant's perspective (FR-REG-4).
 */
@Entity
@Table(name = "mcp_servers")
public class McpServer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_tenant_id")
    private UUID ownerTenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    private String description;

    /** Kill switch at server granularity (FR-REG-6): disabled servers vanish from routing. */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Tool> tools = new ArrayList<>();

    protected McpServer() {
        // JPA
    }

    public McpServer(UUID ownerTenantId, String name, String baseUrl, String description) {
        this.ownerTenantId = ownerTenantId;
        this.name = name;
        this.baseUrl = baseUrl;
        this.description = description;
    }

    /** Adds a tool and maintains both sides of the association. */
    public void addTool(Tool tool) {
        tool.attachTo(this);
        tools.add(tool);
    }

    public boolean isPlatformShared() {
        return ownerTenantId == null;
    }

    /** @return true when this server is listed for the given tenant (FR-REG-4 visibility rule). */
    public boolean visibleTo(UUID tenantId) {
        return isPlatformShared() || ownerTenantId.equals(tenantId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerTenantId() {
        return ownerTenantId;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Tool> getTools() {
        return List.copyOf(tools);
    }
}
