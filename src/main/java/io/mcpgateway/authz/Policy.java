package io.mcpgateway.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A versioned policy document (FR-AUTHZ-1). Documents are immutable once ACTIVE — changes create
 * a new version and archive the old one — so any historical decision can be traced to the exact
 * policy text that produced it. {@code tenantId == null} means platform-global.
 */
@Entity
@Table(name = "policies")
public class Policy {

    public enum Status { DRAFT, ACTIVE, ARCHIVED }

    public enum Effect { ALLOW, DENY }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Effect effect;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String subjects;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String resources;

    @JdbcTypeCode(SqlTypes.JSON)
    private String conditions;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Policy() {
        // JPA
    }

    public Policy(UUID tenantId, String name, int version, Effect effect, String description,
                  String subjects, String resources, String conditions, String createdBy) {
        this.tenantId = tenantId;
        this.name = name;
        this.version = version;
        this.effect = effect;
        this.description = description;
        this.subjects = subjects;
        this.resources = resources;
        this.conditions = conditions;
        this.createdBy = createdBy;
    }

    public void activate() {
        this.status = Status.ACTIVE;
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public Status getStatus() {
        return status;
    }

    public Effect getEffect() {
        return effect;
    }

    public String getDescription() {
        return description;
    }

    public String getSubjects() {
        return subjects;
    }

    public String getResources() {
        return resources;
    }

    public String getConditions() {
        return conditions;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
