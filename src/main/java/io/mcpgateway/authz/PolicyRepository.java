package io.mcpgateway.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for policy documents. */
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    /** The enforceable set for a tenant: its own ACTIVE policies plus platform-global ones. */
    @Query("select p from Policy p where p.status = 'ACTIVE' "
            + "and (p.tenantId = :tenantId or p.tenantId is null)")
    List<Policy> findActiveFor(@Param("tenantId") UUID tenantId);

    List<Policy> findByTenantIdOrderByNameAscVersionDesc(UUID tenantId);

    List<Policy> findByTenantIdAndNameAndStatus(UUID tenantId, String name, Policy.Status status);
}
