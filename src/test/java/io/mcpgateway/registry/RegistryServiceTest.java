package io.mcpgateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.mcpgateway.audit.AuditService;
import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.registry.RegistryService.ToolManifest;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Registration scope rules (FR-REG-1, FR-REG-4): platform-shared needs platform-admin,
 * tenant-private needs tenant-admin, and registration always lands in the actor's own tenant.
 */
class RegistryServiceTest {

    private static final UUID ACME_ID = UUID.randomUUID();

    private McpServerRepository servers;
    private TenantDirectory tenants;
    private RegistryService service;

    @BeforeEach
    void setUp() {
        servers = mock(McpServerRepository.class);
        tenants = mock(TenantDirectory.class);
        when(servers.save(any(McpServer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenants.resolveOrProvision(anyString())).thenReturn(ACME_ID);
        service = new RegistryService(servers, mock(ToolRepository.class), tenants,
                mock(AuditService.class));
    }

    @Test
    void platformAdminRegistersSharedServerWithNoOwnerTenant() {
        McpServer server = service.register(actor("platform-admin"), true,
                "crm", "http://crm:8080", null, manifest());

        assertThat(server.isPlatformShared()).isTrue();
        assertThat(server.getTools()).hasSize(1);
    }

    @Test
    void tenantAdminCannotRegisterSharedServer() {
        assertThatThrownBy(() -> service.register(actor("tenant-admin"), true,
                "crm", "http://crm:8080", null, manifest()))
                .isInstanceOf(AccessDeniedException.class);
        verify(servers, never()).save(any());
    }

    @Test
    void tenantAdminRegistersPrivateServerUnderOwnTenant() {
        McpServer server = service.register(actor("tenant-admin"), false,
                "crm", "http://crm:8080", null, manifest());

        assertThat(server.getOwnerTenantId()).isEqualTo(ACME_ID);
        assertThat(server.visibleTo(ACME_ID)).isTrue();
        assertThat(server.visibleTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void toolUserCannotRegisterAnything() {
        assertThatThrownBy(() -> service.register(actor("tool-user"), false,
                "crm", "http://crm:8080", null, manifest()))
                .isInstanceOf(AccessDeniedException.class);
        verify(servers, never()).save(any());
    }

    @Test
    void registrationPinsManifestHashPerTool() {
        McpServer server = service.register(actor("platform-admin"), true,
                "crm", "http://crm:8080", null, manifest());

        Tool tool = server.getTools().get(0);
        assertThat(tool.getManifestHash())
                .isEqualTo(ToolManifestHasher.hash("crm.read", "Reads records", "{\"type\":\"object\"}"));
        assertThat(tool.isCallable()).isTrue();
    }

    private static AuthenticatedActor actor(String role) {
        return new AuthenticatedActor("sub-1", "someone", "tenant-acme", Set.of(role));
    }

    private static List<ToolManifest> manifest() {
        return List.of(new ToolManifest("crm.read", "Reads records", "{\"type\":\"object\"}",
                Tool.SensitivityTier.INTERNAL));
    }
}
