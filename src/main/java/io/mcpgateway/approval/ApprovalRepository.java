package io.mcpgateway.approval;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for approval requests. */
public interface ApprovalRepository extends JpaRepository<ApprovalRequest, UUID> {

    List<ApprovalRequest> findByTenantSlugAndStatus(String tenantSlug, ApprovalRequest.Status status);
}
