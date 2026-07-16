package io.mcpgateway.registry;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-node route cache keyed by (tenant, server name). The request path reads from here so a
 * routing decision — including a kill-switch deny — needs no database round trip (FR-REG-6);
 * cross-node coherence comes from pub/sub invalidation, not TTLs (FR-REG-3).
 */
@Component
public class RoutingCache {

    private static final Logger log = LoggerFactory.getLogger(RoutingCache.class);

    private final Map<String, RouteSnapshot> routes = new ConcurrentHashMap<>();

    public RouteSnapshot get(UUID tenantId, String serverName) {
        return routes.get(key(tenantId, serverName));
    }

    public void put(UUID tenantId, RouteSnapshot snapshot) {
        routes.put(key(tenantId, snapshot.serverName()), snapshot);
    }

    /** Drops every tenant's entry for the server — visibility scoping is re-derived on reload. */
    public void invalidateServer(String serverName) {
        int before = routes.size();
        routes.keySet().removeIf(key -> key.endsWith("|" + serverName));
        if (routes.size() != before) {
            log.debug("invalidated {} cached route(s) for server '{}'", before - routes.size(), serverName);
        }
    }

    private static String key(UUID tenantId, String serverName) {
        return tenantId + "|" + serverName;
    }
}
