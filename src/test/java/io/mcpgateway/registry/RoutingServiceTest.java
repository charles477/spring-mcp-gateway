package io.mcpgateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cache-first routing (FR-REG-3): repeat lookups are served from memory, and invalidation —
 * the pub/sub path — forces the next lookup back to the database.
 */
class RoutingServiceTest {

    private static final UUID ACME_ID = UUID.randomUUID();
    private static final AuthenticatedActor ALICE =
            new AuthenticatedActor("sub", "alice", "tenant-acme", Set.of("tool-user"));

    private McpServerRepository servers;
    private RoutingCache cache;
    private RoutingService service;

    @BeforeEach
    void setUp() {
        servers = mock(McpServerRepository.class);
        TenantDirectory tenants = mock(TenantDirectory.class);
        when(tenants.resolveOrProvision(anyString())).thenReturn(ACME_ID);
        cache = new RoutingCache();
        service = new RoutingService(cache, servers, tenants);
    }

    @Test
    void secondLookupIsServedFromCacheWithoutTouchingTheDatabase() {
        when(servers.findVisibleByName(eq(ACME_ID), eq("crm")))
                .thenReturn(Optional.of(new McpServer(ACME_ID, "crm", "http://crm", null)));

        service.route(ALICE, "crm");
        service.route(ALICE, "crm");

        verify(servers, times(1)).findVisibleByName(any(), anyString());
    }

    @Test
    void invalidationForcesReloadFromTheDatabase() {
        when(servers.findVisibleByName(eq(ACME_ID), eq("crm")))
                .thenReturn(Optional.of(new McpServer(ACME_ID, "crm", "http://crm", null)));

        service.route(ALICE, "crm");
        cache.invalidateServer("crm");
        service.route(ALICE, "crm");

        verify(servers, times(2)).findVisibleByName(any(), anyString());
    }

    @Test
    void missStaysAMissAndIsNotNegativelyCached() {
        when(servers.findVisibleByName(eq(ACME_ID), eq("ghost"))).thenReturn(Optional.empty());

        assertThat(service.route(ALICE, "ghost")).isEmpty();
        assertThat(service.route(ALICE, "ghost")).isEmpty();

        // Both lookups hit the DB: a not-yet-registered server must appear as soon as it exists.
        verify(servers, times(2)).findVisibleByName(any(), anyString());
    }
}
