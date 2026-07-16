package io.mcpgateway.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One security-relevant event, written once and never updated — the audit trail is the source
 * of truth for every allow/deny/quarantine/kill decision the gateway makes (FR-AUDIT-1).
 */
@Entity
@Table(name = "audit_log")
public class AuditRecord {

    /** Outcome classification, deliberately coarse so dashboards can aggregate on it. */
    public enum Decision { ALLOWED, DENIED, QUARANTINED, ERROR }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "tenant_slug", nullable = false)
    private String tenantSlug;

    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    /** What was attempted, e.g. {@code tools/call}, {@code registry/kill}. */
    @Column(nullable = false)
    private String action;

    /** Qualified tool reference ({@code server.tool}) when the action targets a tool. */
    @Column(name = "tool_ref")
    private String toolRef;

    @Column(nullable = false)
    private String decision;

    /** Human-readable reason; never contains payloads, tokens, or secrets. */
    private String detail;

    @Column(name = "latency_ms")
    private Long latencyMs;

    protected AuditRecord() {
        // JPA
    }

    public AuditRecord(String tenantSlug, String actorSubject, String actorName, String action,
                       String toolRef, Decision decision, String detail, Long latencyMs) {
        this.tenantSlug = tenantSlug;
        this.actorSubject = actorSubject;
        this.actorName = actorName;
        this.action = action;
        this.toolRef = toolRef;
        this.decision = decision.name();
        this.detail = detail;
        this.latencyMs = latencyMs;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorName() {
        return actorName;
    }

    public String getAction() {
        return action;
    }

    public String getToolRef() {
        return toolRef;
    }

    public String getDecision() {
        return decision;
    }

    public String getDetail() {
        return detail;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }
}
