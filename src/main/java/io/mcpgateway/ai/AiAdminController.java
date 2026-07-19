package io.mcpgateway.ai;

import io.mcpgateway.audit.AuditRecord;
import io.mcpgateway.audit.AuditRepository;
import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.common.AuthenticatedActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI endpoints for the console. Hard security boundary, stated where it's enforced: the copilot
 * only ever produces a DRAFT policy document — activation still goes through the ordinary
 * simulate-then-activate flow, so nothing an LLM writes can touch live traffic by itself.
 */
@RestController
@RequestMapping("/admin/ai")
public class AiAdminController {

    private static final String POLICY_SYSTEM = """
            You translate an administrator's plain-English access intent into a policy document
            for an MCP gateway. Respond with ONLY a JSON object, no fences, with fields:
            name (kebab-case), effect ("ALLOW" or "DENY"),
            subjects (array of {"type":"role"|"user","value":string}),
            resources (array of {"pattern": glob over "<server>.<tool>" names} and/or {"tier":"PUBLIC"|"INTERNAL"|"RESTRICTED"}),
            conditions (array of {"attribute":"tool"|"tier"|"subject"|"timeOfDay","operator":"equals"|"not_equals"|"in"|"time_between","values":[...]}, or null),
            rationale (one sentence for the reviewing admin).
            Known roles: tool-user, tool-approver, tenant-admin, platform-admin.
            If the intent mixes an allow and a prohibition, encode the allow and put the
            prohibition in the rationale as a suggested companion DENY policy.""";

    private final AiService ai;
    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    public AiAdminController(AiService ai, AuditRepository audit, ObjectMapper objectMapper) {
        this.ai = ai;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** Drafts a policy document from plain English. Draft only — never activates anything. */
    @PostMapping("/draft-policy")
    public JsonNode draftPolicy(GatewayAuthenticationToken auth,
                                @Valid @RequestBody DraftRequest request) {
        requireAdmin(auth.actor());
        requireConfigured();
        String raw = ai.complete(POLICY_SYSTEM, request.intent(), 1024).trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```[a-z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI response was not a valid policy document — try rephrasing the intent");
        }
    }

    /** Plain-English digest of recent gateway activity for the dashboard. */
    @PostMapping("/digest")
    public Map<String, String> digest(GatewayAuthenticationToken auth) {
        requireAdmin(auth.actor());
        requireConfigured();
        List<AuditRecord> recent = audit.findByTenantSlugOrderByOccurredAtDesc(
                auth.actor().tenantId(), PageRequest.of(0, 40));
        String rows = recent.stream()
                .map(r -> String.join("|", r.getOccurredAt().toString(), r.getActorName(),
                        r.getAction(), String.valueOf(r.getToolRef()), r.getDecision(),
                        String.valueOf(r.getDetail())))
                .collect(Collectors.joining("\n"));
        String digest = ai.complete(
                "You are a security operations assistant for an MCP gateway. Given recent audit "
                        + "rows (time|actor|action|tool|decision|detail), write a 2-3 sentence "
                        + "digest for an administrator: notable denials, quarantines, spikes, or "
                        + "first-time behaviors. Plain prose, no preamble, no markdown.",
                rows.isEmpty() ? "No recent activity." : rows, 300);
        return Map.of("digest", digest);
    }

    private void requireAdmin(AuthenticatedActor actor) {
        if (!actor.hasRole("tenant-admin") && !actor.hasRole("platform-admin")) {
            throw new AccessDeniedException("AI features require an admin role");
        }
    }

    private void requireConfigured() {
        if (!ai.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI is not configured: set ANTHROPIC_API_KEY on the gateway");
        }
    }

    /** Plain-English intent to turn into a draft policy. */
    public record DraftRequest(@NotBlank String intent) {
    }
}
