package io.mcpgateway.audit;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.common.AuthenticatedActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the audit trail (FR-AUDIT-3). Tenant-admins see their own tenant's records;
 * platform-admins see everything. Tool-users see nothing — the audit trail is an operator
 * surface, not an agent one.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditQueryController {

    private final AuditRepository audit;

    public AuditQueryController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditView> recent(GatewayAuthenticationToken auth,
                                  @RequestParam(defaultValue = "50") int limit) {
        AuthenticatedActor actor = auth.actor();
        int capped = Math.min(Math.max(limit, 1), 500);
        List<AuditRecord> records;
        if (actor.hasRole("platform-admin")) {
            records = audit.findAllByOrderByOccurredAtDesc(PageRequest.of(0, capped));
        } else if (actor.hasRole("tenant-admin") || actor.hasRole("tool-approver")) {
            records = audit.findByTenantSlugOrderByOccurredAtDesc(actor.tenantId(), PageRequest.of(0, capped));
        } else {
            throw new AccessDeniedException("Audit access requires an admin or approver role");
        }
        return records.stream().map(AuditView::from).toList();
    }

    /** Public view of one audit record. */
    public record AuditView(UUID id, Instant occurredAt, String tenantSlug, String actorName,
                            String action, String toolRef, String decision, String detail,
                            Long latencyMs) {

        static AuditView from(AuditRecord r) {
            return new AuditView(r.getId(), r.getOccurredAt(), r.getTenantSlug(), r.getActorName(),
                    r.getAction(), r.getToolRef(), r.getDecision(), r.getDetail(), r.getLatencyMs());
        }
    }
}
