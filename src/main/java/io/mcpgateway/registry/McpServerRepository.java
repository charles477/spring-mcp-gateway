package io.mcpgateway.registry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for registered MCP servers, visibility-scoped per FR-REG-4. */
public interface McpServerRepository extends JpaRepository<McpServer, UUID> {

    /**
     * Servers a tenant may see: platform-shared plus its own tenant-private registrations.
     * Tools are fetch-joined because callers map them to DTOs after the transaction closes.
     */
    @Query("select distinct s from McpServer s left join fetch s.tools "
            + "where s.ownerTenantId is null or s.ownerTenantId = :tenantId")
    List<McpServer> findAllVisibleTo(@Param("tenantId") UUID tenantId);

    Optional<McpServer> findByOwnerTenantIdIsNullAndName(String name);

    Optional<McpServer> findByOwnerTenantIdAndName(UUID ownerTenantId, String name);
}
