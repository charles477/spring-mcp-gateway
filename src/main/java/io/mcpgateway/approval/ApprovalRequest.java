package io.mcpgateway.approval;

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
 * One pending human decision about one specific restricted-tool invocation (FR-APPR-1). The
 * arguments hash makes an approval a one-shot ticket for exactly what the approver reviewed —
 * approve-then-swap-arguments is structurally impossible.
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    public enum Status { PENDING, APPROVED, REJECTED, EXECUTED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_slug", nullable = false)
    private String tenantSlug;

    @Column(name = "tool_ref", nullable = false)
    private String toolRef;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "requester_name", nullable = false)
    private String requesterName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String arguments;

    @Column(name = "args_hash", nullable = false)
    private String argsHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRequest() {
        // JPA
    }

    public ApprovalRequest(String tenantSlug, String toolRef, String requestedBy,
                           String requesterName, String arguments, String argsHash) {
        this.tenantSlug = tenantSlug;
        this.toolRef = toolRef;
        this.requestedBy = requestedBy;
        this.requesterName = requesterName;
        this.arguments = arguments;
        this.argsHash = argsHash;
    }

    public void decide(boolean approved, String decider) {
        this.status = approved ? Status.APPROVED : Status.REJECTED;
        this.decidedBy = decider;
        this.decidedAt = Instant.now();
    }

    public void markExecuted() {
        this.status = Status.EXECUTED;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public String getToolRef() {
        return toolRef;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public String getArguments() {
        return arguments;
    }

    public String getArgsHash() {
        return argsHash;
    }

    public Status getStatus() {
        return status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
