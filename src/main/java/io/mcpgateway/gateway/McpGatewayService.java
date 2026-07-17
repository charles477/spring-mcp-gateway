package io.mcpgateway.gateway;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.audit.AuditService;
import io.mcpgateway.authz.PolicyDecisionPoint;
import io.mcpgateway.authz.PolicyDecisionPoint.EvaluationRequest;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView;
import io.mcpgateway.authz.PolicyService;
import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.gateway.JsonRpc.Request;
import io.mcpgateway.gateway.JsonRpc.Response;
import io.mcpgateway.registry.McpServer;
import io.mcpgateway.registry.RegistryService;
import io.mcpgateway.registry.RouteSnapshot;
import io.mcpgateway.registry.RouteSnapshot.ToolRoute;
import io.mcpgateway.registry.RoutingService;
import io.mcpgateway.registry.Tool;
import io.mcpgateway.registry.ToolManifestHasher;
import java.time.LocalTime;
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
 * The MCP request pipeline (FR-GW-1): resolves the qualified tool through the routing cache,
 * applies registry-level enforcement (visibility, kill switch, quarantine, manifest
 * verification), proxies to the backend, and audits every request exactly once (FR-AUDIT-1).
 *
 * <p>Tools are exposed to agents as {@code <server>.<tool>} — the gateway aggregates many
 * backends behind one endpoint, so names must carry their routing scope.
 */
@Service
public class McpGatewayService {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayService.class);

    private final RegistryService registry;
    private final RoutingService routing;
    private final McpBackendClient backend;
    private final AuditService audit;
    private final PolicyService policies;
    private final PolicyDecisionPoint pdp;
    private final ObjectMapper objectMapper;

    public McpGatewayService(RegistryService registry, RoutingService routing,
                             McpBackendClient backend, AuditService audit, PolicyService policies,
                             PolicyDecisionPoint pdp, ObjectMapper objectMapper) {
        this.registry = registry;
        this.routing = routing;
        this.backend = backend;
        this.audit = audit;
        this.policies = policies;
        this.pdp = pdp;
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
     * (FR-REG-4) that are enabled (FR-REG-6), with only callable tools (FR-REG-5). Reads the
     * database directly rather than the cache so a fresh registration is listed immediately.
     */
    private Response listTools(AuthenticatedActor actor, Request request) {
        long started = System.currentTimeMillis();
        List<PolicyView> activePolicies = policies.activePoliciesFor(actor);
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpServer server : registry.visibleServers(actor)) {
            if (!server.isEnabled()) {
                continue;
            }
            for (Tool tool : server.getTools()) {
                String qualifiedName = server.getName() + "." + tool.getName();
                // PEP on the listing too (FR-AUTHZ-4): a tool the actor may not call
                // does not exist from their perspective.
                boolean permitted = pdp.evaluate(activePolicies, evaluationRequest(
                        actor, qualifiedName, tool.getSensitivityTier().name())).allowed();
                if (tool.isCallable() && permitted) {
                    toolList.add(Map.of(
                            "name", qualifiedName,
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

        Optional<RouteSnapshot> routeLookup = routing.route(actor, serverName);
        if (routeLookup.isEmpty() || !routeLookup.get().enabled()) {
            // Disabled and invisible servers answer identically, so probing can't distinguish
            // "exists but killed" from "not yours" (FR-REG-4, FR-REG-6).
            return deny(actor, request, qualifiedName, started, JsonRpc.DENIED,
                    "Unknown tool: " + qualifiedName);
        }
        RouteSnapshot route = routeLookup.get();
        Optional<ToolRoute> toolLookup = route.tool(toolName);
        if (toolLookup.isEmpty()) {
            return deny(actor, request, qualifiedName, started, JsonRpc.DENIED,
                    "Unknown tool: " + qualifiedName);
        }
        ToolRoute tool = toolLookup.get();
        // The PEP (FR-AUTHZ-4): the PDP's verdict is enforced before anything else is revealed
        // about the tool; the explanation lands in the audit record, not the agent response.
        PolicyDecisionPoint.Decision verdict = pdp.evaluate(policies.activePoliciesFor(actor),
                evaluationRequest(actor, qualifiedName, tool.sensitivityTier().name()));
        if (!verdict.allowed()) {
            return deny(actor, request, qualifiedName, started, JsonRpc.DENIED,
                    "Denied by policy", verdict.explanation());
        }
        if (!tool.callable()) {
            return deny(actor, request, qualifiedName, started, JsonRpc.TOOL_QUARANTINED,
                    "Tool is not callable (status " + tool.status() + ")");
        }
        return verifyManifestAndProxy(actor, request, route, tool, qualifiedName, started);
    }

    /**
     * Rug-pull tripwire (FR-REG-5), then the proxy. Verification distinguishes two failure
     * classes deliberately: an <em>unreachable</em> backend denies the call but leaves the tool
     * alone (a network blip is not an attack, and must not force an admin re-approval), while a
     * <em>successful listing</em> that no longer matches the pinned hash — or no longer contains
     * the tool — quarantines it.
     */
    private Response verifyManifestAndProxy(AuthenticatedActor actor, Request request,
                                            RouteSnapshot route, ToolRoute tool,
                                            String qualifiedName, long started) {
        Optional<JsonNode> liveTools;
        try {
            liveTools = backend.listTools(route.baseUrl());
        } catch (RuntimeException e) {
            log.warn("manifest verification: backend {} unreachable: {}", route.serverName(), e.getMessage());
            return deny(actor, request, qualifiedName, started, JsonRpc.UPSTREAM_ERROR,
                    "Cannot verify tool manifest: backend unreachable");
        }
        if (manifestDrifted(liveTools, tool)) {
            registry.quarantineForDrift(actor, tool.id(), qualifiedName);
            // The quarantine event above is the registry's record; this one is the request's
            // own audit row — every request gets exactly one (FR-AUDIT-1).
            return deny(actor, request, qualifiedName, started, JsonRpc.TOOL_QUARANTINED,
                    "Tool quarantined: live definition no longer matches its approved manifest");
        }
        return proxy(actor, request, route, tool, qualifiedName, started);
    }

    private boolean manifestDrifted(Optional<JsonNode> liveTools, ToolRoute tool) {
        if (liveTools.isEmpty()) {
            return true;
        }
        for (JsonNode live : liveTools.get()) {
            if (tool.name().equals(live.path("name").asString(""))) {
                String liveHash = ToolManifestHasher.hash(
                        live.path("name").asString(""),
                        live.path("description").isMissingNode() ? null : live.path("description").asString(),
                        live.path("inputSchema").toString());
                return !liveHash.equals(tool.manifestHash());
            }
        }
        return true;
    }

    private Response proxy(AuthenticatedActor actor, Request request, RouteSnapshot route,
                           ToolRoute tool, String qualifiedName, long started) {
        JsonNode upstream;
        try {
            upstream = backend.callTool(route.baseUrl(), tool.name(), request.params().path("arguments"));
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
        return deny(actor, request, toolRef, started, code, message, message);
    }

    /**
     * Denies with a caller-facing message distinct from the audited detail: policy explanations
     * belong to operators, not to the (possibly adversarial) agent being denied.
     */
    private Response deny(AuthenticatedActor actor, Request request, String toolRef, long started,
                          int code, String message, String auditDetail) {
        audit.record(actor, "tools/call", toolRef.isEmpty() ? null : toolRef, Decision.DENIED,
                auditDetail, System.currentTimeMillis() - started);
        return Response.error(request.id(), code, message);
    }

    private static EvaluationRequest evaluationRequest(AuthenticatedActor actor,
                                                       String qualifiedName, String tier) {
        return new EvaluationRequest(actor.subject(), List.copyOf(actor.roles()),
                qualifiedName, tier, LocalTime.now());
    }
}
