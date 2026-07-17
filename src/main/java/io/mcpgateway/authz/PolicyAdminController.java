package io.mcpgateway.authz;

import io.mcpgateway.authn.GatewayJwtAuthenticationConverter.GatewayAuthenticationToken;
import io.mcpgateway.authz.PolicyDecisionPoint.Decision;
import io.mcpgateway.authz.PolicyDecisionPoint.EvaluationRequest;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Policy administration (FR-AUTHZ-1), decision explanation (FR-AUTHZ-5), and dry-run
 * simulation (FR-AUTHZ-6). Scope rules live in {@link PolicyService}.
 */
@RestController
@RequestMapping("/admin/policies")
public class PolicyAdminController {

    private final PolicyService policyService;
    private final PolicyDecisionPoint pdp;

    public PolicyAdminController(PolicyService policyService, PolicyDecisionPoint pdp) {
        this.policyService = policyService;
        this.pdp = pdp;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse createDraft(GatewayAuthenticationToken auth,
                                      @Valid @RequestBody CreatePolicyRequest request) {
        Policy draft = policyService.createDraft(auth.actor(), request.platformGlobal(),
                request.name(), request.effect(), request.description(),
                request.subjects().toString(), request.resources().toString(),
                request.conditions() == null ? null : request.conditions().toString());
        return PolicyResponse.from(draft);
    }

    @PostMapping("/{policyId}/activate")
    public void activate(GatewayAuthenticationToken auth, @PathVariable UUID policyId) {
        policyService.activate(auth.actor(), policyId);
    }

    @GetMapping
    public List<PolicyResponse> list(GatewayAuthenticationToken auth) {
        return policyService.managedPolicies(auth.actor()).stream()
                .map(PolicyResponse::from).toList();
    }

    /**
     * Explains what the ACTIVE policy set decides for a hypothetical request (FR-AUTHZ-5),
     * optionally with extra draft policies overlaid to preview activation (FR-AUTHZ-6) —
     * nothing here mutates state or affects live traffic.
     */
    @PostMapping("/simulate")
    public SimulationResponse simulate(GatewayAuthenticationToken auth,
                                       @Valid @RequestBody SimulateRequest request) {
        List<PolicyView> active = policyService.activePoliciesFor(auth.actor());
        EvaluationRequest evaluation = new EvaluationRequest(
                request.actorSubject(), request.actorRoles(), request.tool(),
                request.tier() == null ? "INTERNAL" : request.tier(),
                request.timeOfDay() == null ? LocalTime.now() : LocalTime.parse(request.timeOfDay()));

        Decision current = pdp.evaluate(active, evaluation);
        Decision withDrafts = current;
        if (request.includeDrafts() != null && !request.includeDrafts().isEmpty()) {
            List<PolicyView> overlaid = new ArrayList<>(active);
            for (UUID draftId : request.includeDrafts()) {
                overlaid.add(policyService.viewOf(draftId));
            }
            withDrafts = pdp.evaluate(overlaid, evaluation);
        }
        return new SimulationResponse(outcome(current), outcome(withDrafts));
    }

    private DecisionOutcome outcome(Decision decision) {
        return new DecisionOutcome(decision.allowed(), decision.explanation(),
                decision.matched().stream()
                        .map(m -> m.name() + " (" + m.effect() + ")").toList());
    }

    /** Draft creation payload; subject/resource/condition shapes match the PDP document model. */
    public record CreatePolicyRequest(
            @NotBlank String name,
            @NotNull Policy.Effect effect,
            String description,
            boolean platformGlobal,
            @NotNull JsonNode subjects,
            @NotNull JsonNode resources,
            JsonNode conditions) {
    }

    /** A hypothetical request to evaluate; {@code includeDrafts} previews inactive policies. */
    public record SimulateRequest(
            @NotBlank String actorSubject,
            @NotEmpty List<String> actorRoles,
            @NotBlank String tool,
            String tier,
            String timeOfDay,
            List<UUID> includeDrafts) {
    }

    /** Live-set decision vs the decision if the listed drafts were activated. */
    public record SimulationResponse(DecisionOutcome current, DecisionOutcome withDrafts) {
    }

    /** One explained decision: verdict, reason, and every policy that matched (FR-AUTHZ-5). */
    public record DecisionOutcome(boolean allowed, String explanation, List<String> matchedPolicies) {
    }

    /** Public view of a stored policy version. */
    public record PolicyResponse(UUID id, String name, int version, Policy.Status status,
                                 Policy.Effect effect, String description, boolean platformGlobal,
                                 JsonNode subjects, JsonNode resources, JsonNode conditions,
                                 String createdBy, Instant createdAt) {

        static PolicyResponse from(Policy policy) {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.json.JsonMapper();
            return new PolicyResponse(policy.getId(), policy.getName(), policy.getVersion(),
                    policy.getStatus(), policy.getEffect(), policy.getDescription(),
                    policy.getTenantId() == null,
                    mapper.readTree(policy.getSubjects()),
                    mapper.readTree(policy.getResources()),
                    policy.getConditions() == null ? null : mapper.readTree(policy.getConditions()),
                    policy.getCreatedBy(), policy.getCreatedAt());
        }
    }
}
