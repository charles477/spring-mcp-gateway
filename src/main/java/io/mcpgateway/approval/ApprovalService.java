package io.mcpgateway.approval;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.audit.AuditService;
import io.mcpgateway.common.AuthenticatedActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval state machine for restricted-tier tools (FR-APPR-1/2). An approval is a one-shot
 * execution ticket bound to (requester, tool, exact arguments): consuming it flips it to
 * EXECUTED; rejection is terminal — the gateway never retries a rejected call.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvals;
    private final AuditService audit;

    public ApprovalService(ApprovalRepository approvals, AuditService audit) {
        this.approvals = approvals;
        this.audit = audit;
    }

    /** Files a new pending request for the given invocation and returns its id (FR-APPR-1). */
    @Transactional
    public UUID filePending(AuthenticatedActor actor, String toolRef, String argumentsJson) {
        ApprovalRequest request = approvals.save(new ApprovalRequest(actor.tenantId(), toolRef,
                actor.subject(), actor.username(), argumentsJson, hash(argumentsJson)));
        log.info("approval requested for {} by {}", toolRef, actor.username());
        audit.record(actor, "approval/file", toolRef, Decision.ALLOWED,
                "pending approval " + request.getId(), 0);
        return request.getId();
    }

    /** Pending requests for the approver's tenant. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingFor(AuthenticatedActor actor) {
        requireApprover(actor);
        return approvals.findByTenantSlugAndStatus(actor.tenantId(), ApprovalRequest.Status.PENDING);
    }

    /** Approves or rejects a pending request (FR-APPR-2). Approvers cannot decide their own. */
    @Transactional
    public void decide(AuthenticatedActor actor, UUID requestId, boolean approved) {
        requireApprover(actor);
        ApprovalRequest request = require(requestId);
        if (!request.getTenantSlug().equals(actor.tenantId()) && !actor.hasRole("platform-admin")) {
            throw new AccessDeniedException("Approval belongs to another tenant");
        }
        if (request.getStatus() != ApprovalRequest.Status.PENDING) {
            throw new IllegalArgumentException("Approval is not pending");
        }
        if (request.getRequestedBy().equals(actor.subject())) {
            throw new AccessDeniedException("Requesters cannot approve their own calls");
        }
        request.decide(approved, actor.username());
        log.info("approval {} {} by {}", requestId, approved ? "granted" : "rejected", actor.username());
        audit.record(actor, "approval/decide", request.getToolRef(),
                approved ? Decision.ALLOWED : Decision.DENIED,
                (approved ? "approved" : "rejected") + " request " + requestId, 0);
    }

    /**
     * Consumes an APPROVED ticket for exactly this (requester, tool, arguments) triple.
     *
     * @return true when the ticket was valid and is now spent; false denies the call
     */
    @Transactional
    public boolean consume(AuthenticatedActor actor, UUID requestId, String toolRef,
                           String argumentsJson) {
        ApprovalRequest request = approvals.findById(requestId).orElse(null);
        boolean valid = request != null
                && request.getStatus() == ApprovalRequest.Status.APPROVED
                && request.getRequestedBy().equals(actor.subject())
                && request.getToolRef().equals(toolRef)
                && request.getArgsHash().equals(hash(argumentsJson));
        if (valid) {
            request.markExecuted();
        }
        return valid;
    }

    private ApprovalRequest require(UUID id) {
        return approvals.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown approval " + id));
    }

    private static void requireApprover(AuthenticatedActor actor) {
        if (!actor.hasRole("tool-approver") && !actor.hasRole("tenant-admin")
                && !actor.hasRole("platform-admin")) {
            throw new AccessDeniedException("Approval handling requires an approver role");
        }
    }

    private static String hash(String argumentsJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(argumentsJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
