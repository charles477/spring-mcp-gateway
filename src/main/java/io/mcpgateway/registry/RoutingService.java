package io.mcpgateway.registry;

import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Cache-first route resolution for the request path (FR-REG-3): hit serves from node-local
 * memory; miss loads from the database and populates the cache. Invalidation — not expiry —
 * keeps nodes coherent, so a kill switch takes effect within one pub/sub hop.
 */
@Service
public class RoutingService {

    private final RoutingCache cache;
    private final McpServerRepository servers;
    private final TenantDirectory tenants;

    public RoutingService(RoutingCache cache, McpServerRepository servers, TenantDirectory tenants) {
        this.cache = cache;
        this.servers = servers;
        this.tenants = tenants;
    }

    /** Resolves a server route visible to the actor's tenant (FR-REG-4 scoping included). */
    public Optional<RouteSnapshot> route(AuthenticatedActor actor, String serverName) {
        UUID tenantId = tenants.resolveOrProvision(actor.tenantId());
        RouteSnapshot cached = cache.get(tenantId, serverName);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<RouteSnapshot> loaded = servers.findVisibleByName(tenantId, serverName)
                .map(RouteSnapshot::from);
        loaded.ifPresent(snapshot -> cache.put(tenantId, snapshot));
        return loaded;
    }
}
