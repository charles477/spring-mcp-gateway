package io.mcpgateway.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for audit records. Append-only by convention: no delete/update callers exist. */
public interface AuditRepository extends JpaRepository<AuditRecord, UUID> {

    java.util.List<AuditRecord> findByTenantSlugOrderByOccurredAtDesc(
            String tenantSlug, org.springframework.data.domain.Pageable pageable);

    java.util.List<AuditRecord> findAllByOrderByOccurredAtDesc(
            org.springframework.data.domain.Pageable pageable);
}
