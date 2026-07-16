package io.mcpgateway.registry;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for tools; server-scoped lookups go through {@link McpServerRepository}. */
public interface ToolRepository extends JpaRepository<Tool, UUID> {
}
