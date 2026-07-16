package io.mcpgateway.gateway;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.audit.AuditService;
import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.gateway.JsonRpc.Request;
import io.mcpgateway.gateway.JsonRpc.Response;
import io.mcpgateway.registry.McpServer;
import io.mcpgateway.registry.RegistryService;
import io.mcpgateway.registry.Tool;
import io.mcpgateway.registry.ToolManifestHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP request pipeline (FR-GW-1): resolves the qualified tool, applies registry-level
 * enforcement (visibility, kill switch, quarantine, manifest verification), proxies to the
 * backend, and audits every outcome exactly once (FR-AUDIT-1).
 *
 * <p>Tools are exposed to agents as {@code <server>.<tool>} — the gateway aggregates many
 * backends behind one endpoint, so names must carry their routing scope.
 */
@Service
public class McpGatewayService {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayService.class);

    private final RegistryService registry;
    private final McpBackendClient backend;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public McpGatewayService(RegistryService registry, McpBackendClient backend,
                             AuditService audit, ObjectMapper objectMapper) {
        this.registry = registry;
        this.backend = backend;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** Dispatches one JSON-RPC request from an authenticated agent. */
    public Response handle(AuthenticatedActor actor, Request request) {
        return switch (request.method()) {
            case "tools/list" -> listTools(actor, request);
            case "tools/call" -> callTool(actor, request);
            default -> Response.error(request.id(), JsonRpc.METHOD_NOT_FOUND,
                    "Unsupported method: " + request.method());
        };
    }

    /**
     * Aggregated tool list, already filtered to what this actor may see: visible servers
     * (FR-REG-4) that are enabled (FR-REG-6), with only callable tools (FR-REG-5).
     */
    private Response listTools(AuthenticatedActor actor, Request request) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpServer server : registry.visibleServers(actor)) {
            if (!server.isEnabled()) {
                continue;
            }
            for (Tool tool : server.getTools()) {
                if (tool.isCallable()) {
                    toolList.add(Map.of(
                            "name", server.getName() + "." + tool.getName(),
                            "description", tool.getDescription() == null ? "" : tool.getDescription(),
                            "inputSchema", objectMapper.readTree(tool.getInputSchema())));
                }
            }
        }
        audit.record(actor, "tools/list", null, Decision.ALLOWED,
                toolList.size() + " tools visible", System.currentTimeMillis() - started);
        return Response.result(request.id(),
                objectMapper.valueToTree(Map.of("tools", toolList)));
    }

    private Response callTool(AuthenticatedActor actor, Request request) {
        long started = System.currentTimeMillis();
        String qualifiedName = request.params() == null ? "" : request.params().path("name").asString("");
        int split = qualifiedName.indexOf('.');
        if (split <= 0) {
            return deny(actor, request, qualifiedName, started, JsonRpc.INVALID_PARAMS,
                    "Tool name must be qualified as <server>.<tool>");
        }
        String serverName = qualifiedName.substring(0, split);
        String toolName = qualifiedName.substring(split + 1);

        Optional<McpServer> serverLookup = registry.visibleServerByName(actor, serverName);
        if (serverLookup.isEmpty() || !serverLookup.get().isEnabled()) {
            // Disabled and invisible servers answer identically, so probing can't distinguish
            // "exists but killed" from "not yours" (FR-REG-4, FR-REG-6).
            return deny(actor, request, qualifiedName, started, JsonRpc.DENIED,
                    "Unknown tool: " + qualifiedName);
        }
        McpServer server = serverLookup.get();
        Optional<Tool> toolLookup = server.getTools().stream()
                .filter(t -> t.getName().equals(toolName)).findFirst();
        if (toolLookup.isEmpty()) {
            return deny(actor, request, qualifiedName, started, JsonRpc.DENIED,
                    "Unknown tool: " + qualifiedName);
        }
        Tool tool = toolLookup.get();
        if (!tool.isCallable()) {
            return deny(actor, request, qualifiedName, started, JsonRpc.TOOL_QUARANTINED,
                    "Tool is not callable (status " + tool.getStatus() + ")");
        }
        if (manifestDrifted(server, tool)) {
            registry.quarantineForDrift(actor, tool.getId(), qualifiedName);
            // The quarantine event above is the registry's record; this one is the request's
            // own audit row — every request gets exactly one (FR-AUDIT-1).
            return deny(actor, request, qualifiedName, started, JsonRpc.TOOL_QUARANTINED,
                    "Tool quarantined: live definition no longer matches its approved manifest");
        }
        return proxy(actor, request, server, tool, qualifiedName, started);
    }

    /**
     * Rug-pull tripwire (FR-REG-5): compares the backend's live definition against the hash
     * pinned at registration. A backend that is unreachable or no longer lists the tool counts
     * as drifted — absence of proof is not proof of innocence for a security check.
     */
    private boolean manifestDrifted(McpServer server, Tool tool) {
        Optional<JsonNode> liveTools;
        try {
            liveTools = backend.listTools(server.getBaseUrl());
        } catch (RuntimeException e) {
            log.warn("manifest verification: backend {} unreachable: {}", server.getName(), e.getMessage());
            return true;
        }
        if (liveTools.isEmpty()) {
            return true;
        }
        for (JsonNode live : liveTools.get()) {
            if (tool.getName().equals(live.path("name").asString(""))) {
                String liveHash = ToolManifestHasher.hash(
                        live.path("name").asString(""),
                        live.path("description").isMissingNode() ? null : live.path("description").asString(),
                        live.path("inputSchema").toString());
                return !liveHash.equals(tool.getManifestHash());
            }
        }
        return true;
    }

    private Response proxy(AuthenticatedActor actor, Request request, McpServer server, Tool tool,
                           String qualifiedName, long started) {
        JsonNode upstream;
        try {
            upstream = backend.callTool(server.getBaseUrl(), tool.getName(),
                    request.params().path("arguments"));
        } catch (RuntimeException e) {
            log.error("backend call failed for {}: {}", qualifiedName, e.getMessage());
            audit.record(actor, "tools/call", qualifiedName, Decision.ERROR,
                    "backend unreachable", System.currentTimeMillis() - started);
            return Response.error(request.id(), JsonRpc.UPSTREAM_ERROR, "Backend server unreachable");
        }
        if (upstream.has("error")) {
            audit.record(actor, "tools/call", qualifiedName, Decision.ERROR,
                    "backend returned error " + upstream.path("error").path("code").asInt(),
                    System.currentTimeMillis() - started);
            return Response.error(request.id(), JsonRpc.UPSTREAM_ERROR,
                    upstream.path("error").path("message").asString("Backend error"));
        }
        audit.record(actor, "tools/call", qualifiedName, Decision.ALLOWED, null,
                System.currentTimeMillis() - started);
        return Response.result(request.id(), upstream.path("result"));
    }

    private Response deny(AuthenticatedActor actor, Request request, String toolRef, long started,
                          int code, String message) {
        audit.record(actor, "tools/call", toolRef.isEmpty() ? null : toolRef, Decision.DENIED,
                message, System.currentTimeMillis() - started);
        return Response.error(request.id(), code, message);
    }
}
