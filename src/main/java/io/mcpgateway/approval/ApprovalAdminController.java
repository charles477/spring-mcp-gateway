package io.mcpgateway.approval;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Approver-facing API for pending restricted-tool calls (FR-APPR-2). */
@RestController
@RequestMapping("/admin/approvals")
public class ApprovalAdminController {

    private final ApprovalService approvals;

    public ApprovalAdminController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalView> pending(GatewayAuthenticationToken auth) {
        return approvals.pendingFor(auth.actor()).stream().map(ApprovalView::from).toList();
    }

    @PostMapping("/{id}/approve")
    public void approve(GatewayAuthenticationToken auth, @PathVariable UUID id) {
        approvals.decide(auth.actor(), id, true);
    }

    @PostMapping("/{id}/reject")
    public void reject(GatewayAuthenticationToken auth, @PathVariable UUID id) {
        approvals.decide(auth.actor(), id, false);
    }

    /** What an approver reviews: who wants to run what, with which exact arguments. */
    public record ApprovalView(UUID id, String toolRef, String requesterName, String arguments,
                               ApprovalRequest.Status status, Instant createdAt) {

        static ApprovalView from(ApprovalRequest request) {
            return new ApprovalView(request.getId(), request.getToolRef(),
                    request.getRequesterName(), request.getArguments(), request.getStatus(),
                    request.getCreatedAt());
        }
    }
}
