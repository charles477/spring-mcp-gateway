package io.mcpgateway.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single invocable capability of a registered MCP server.
 *
 * <p>The tool's definition (name, description, input schema) is content-hashed at registration
 * ({@code manifestHash}). If the backend's live definition ever stops matching, the tool is
 * {@link Status#QUARANTINED} rather than trusted — a backend silently rewriting an approved
 * description is the MCP "rug-pull" attack, not an update (FR-REG-5).
 */
@Entity
@Table(name = "tools")
public class Tool {

    /** Drives approval-workflow requirements: {@code RESTRICTED} tools pause for human approval. */
    public enum SensitivityTier { PUBLIC, INTERNAL, RESTRICTED }

    public enum Status {
        /** Routable. */
        ACTIVE,
        /** Blocked pending admin re-approval after manifest drift (FR-REG-5). */
        QUARANTINED,
        /** Kill-switched by an admin (FR-REG-6). */
        DISABLED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private McpServer server;

    @Column(nullable = false)
    private String name;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false)
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(name = "sensitivity_tier", nullable = false)
    private SensitivityTier sensitivityTier = SensitivityTier.INTERNAL;

    @Column(name = "manifest_hash", nullable = false)
    private String manifestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Tool() {
        // JPA
    }

    public Tool(String name, String description, String inputSchema,
                SensitivityTier sensitivityTier, String manifestHash) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.sensitivityTier = sensitivityTier;
        this.manifestHash = manifestHash;
    }

    void attachTo(McpServer server) {
        this.server = server;
    }

    /** Quarantines this tool after manifest drift; only an explicit re-approval reactivates it. */
    public void quarantine() {
        this.status = Status.QUARANTINED;
    }

    /** Re-approves a quarantined/disabled tool under a newly pinned manifest hash. */
    public void reapprove(String newManifestHash) {
        this.manifestHash = newManifestHash;
        this.status = Status.ACTIVE;
    }

    public boolean isCallable() {
        return status == Status.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public McpServer getServer() {
        return server;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public SensitivityTier getSensitivityTier() {
        return sensitivityTier;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
