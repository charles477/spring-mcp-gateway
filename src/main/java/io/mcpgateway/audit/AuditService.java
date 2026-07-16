package io.mcpgateway.audit;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.common.AuthenticatedActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes audit records off the request thread (FR-AUDIT-2): the caller's response must never
 * be delayed or failed by audit persistence, so writes are async and failures are logged as
 * ERROR (an operator problem) instead of propagating.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Async
    public void record(AuthenticatedActor actor, String action, String toolRef,
                       Decision decision, String detail, long latencyMs) {
        try {
            repository.save(new AuditRecord(actor.tenantId(), actor.subject(), actor.username(),
                    action, toolRef, decision, detail, latencyMs));
        } catch (RuntimeException e) {
            log.error("audit write failed for action {} tool {} decision {}", action, toolRef, decision, e);
        }
    }
}
