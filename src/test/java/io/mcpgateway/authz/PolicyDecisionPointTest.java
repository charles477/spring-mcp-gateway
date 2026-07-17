package io.mcpgateway.authz;

import static org.assertj.core.api.Assertions.assertThat;

import io.mcpgateway.authz.PolicyDecisionPoint.Decision;
import io.mcpgateway.authz.PolicyDecisionPoint.EvaluationRequest;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView.Condition;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView.Effect;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView.Resource;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView.Subject;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Evaluation semantics, requirement by requirement: deny-by-default (FR-AUTHZ-2),
 * explicit-deny-wins (FR-AUTHZ-3), subject/resource/condition matching, and explanations
 * that name the deciding policy (FR-AUTHZ-5).
 */
class PolicyDecisionPointTest {

    private final PolicyDecisionPoint pdp = new PolicyDecisionPoint();

    // ---- FR-AUTHZ-2: deny by default ----

    @Test
    void emptyPolicySetDeniesEverything() {
        Decision decision = pdp.evaluate(List.of(), request("crm.read", "INTERNAL"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.matched()).isEmpty();
        assertThat(decision.explanation()).contains("default deny");
    }

    @Test
    void nonMatchingAllowStillDenies() {
        PolicyView allowOther = allow("allow-other", role("tool-user"), pattern("hr.*"));

        Decision decision = pdp.evaluate(List.of(allowOther), request("crm.read", "INTERNAL"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.matched()).isEmpty();
    }

    // ---- FR-AUTHZ-3: explicit deny wins ----

    @Test
    void explicitDenyOverridesAnyNumberOfAllows() {
        PolicyView allow1 = allow("allow-1", role("tool-user"), pattern("*"));
        PolicyView allow2 = allow("allow-2", user("sub-1"), pattern("crm.*"));
        PolicyView deny = deny("deny-crm", role("tool-user"), pattern("crm.*"));

        Decision decision = pdp.evaluate(List.of(allow1, allow2, deny), request("crm.read", "INTERNAL"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.matched()).hasSize(3);
        assertThat(decision.explanation()).contains("deny-crm").contains("explicit deny");
    }

    // ---- subject matching ----

    @Test
    void roleAndUserSubjectsMatchIndependently() {
        PolicyView byRole = allow("by-role", role("tool-user"), pattern("*"));
        PolicyView byUser = allow("by-user", user("sub-1"), pattern("*"));

        assertThat(pdp.evaluate(List.of(byRole), request("t.x", "INTERNAL")).allowed()).isTrue();
        assertThat(pdp.evaluate(List.of(byUser), request("t.x", "INTERNAL")).allowed()).isTrue();

        EvaluationRequest stranger = new EvaluationRequest("sub-2", List.of("other-role"),
                "t.x", "INTERNAL", LocalTime.NOON);
        assertThat(pdp.evaluate(List.of(byRole, byUser), stranger).allowed()).isFalse();
    }

    // ---- resource matching ----

    @Test
    void globPatternsMatchQualifiedToolNames() {
        assertThat(allowedFor(pattern("crm-tools.*"), "crm-tools.crm.read")).isTrue();
        assertThat(allowedFor(pattern("*.crm.read"), "crm-tools.crm.read")).isTrue();
        assertThat(allowedFor(pattern("crm-tools.crm.read"), "crm-tools.crm.read")).isTrue();
        assertThat(allowedFor(pattern("hr.*"), "crm-tools.crm.read")).isFalse();
        // Glob metacharacters other than * are literal: a dot cannot act as regex "any".
        assertThat(allowedFor(pattern("crm-toolsXcrm.read".replace('X', '.')), "crm-toolsAcrm.read"))
                .isFalse();
    }

    @Test
    void tierResourceMatchesSensitivity() {
        PolicyView allowPublicOnly = allow("public-only", role("tool-user"),
                new Resource(null, "PUBLIC"));

        assertThat(pdp.evaluate(List.of(allowPublicOnly), request("t.x", "PUBLIC")).allowed()).isTrue();
        assertThat(pdp.evaluate(List.of(allowPublicOnly), request("t.x", "RESTRICTED")).allowed()).isFalse();
    }

    @Test
    void emptyResourceEntryMatchesNothing() {
        PolicyView vacuous = allow("vacuous", role("tool-user"), new Resource(null, null));

        assertThat(pdp.evaluate(List.of(vacuous), request("t.x", "INTERNAL")).allowed()).isFalse();
    }

    // ---- ABAC conditions ----

    @Test
    void conditionsAreAndedAndUnknownAttributesFailClosed() {
        PolicyView conditioned = new PolicyView("p", "conditioned", Effect.ALLOW,
                List.of(role("tool-user")), List.of(pattern("*")),
                List.of(new Condition("tier", "in", List.of("PUBLIC", "INTERNAL")),
                        new Condition("nonsense-attribute", "equals", List.of("x"))));

        // The unknown attribute cannot be proven, so the whole policy must not apply.
        assertThat(pdp.evaluate(List.of(conditioned), request("t.x", "INTERNAL")).allowed()).isFalse();
    }

    @Test
    void timeBetweenSupportsOvernightRanges() {
        PolicyView nightShift = new PolicyView("p", "night-shift", Effect.ALLOW,
                List.of(role("tool-user")), List.of(pattern("*")),
                List.of(new Condition("timeOfDay", "time_between", List.of("22:00", "06:00"))));

        assertThat(pdp.evaluate(List.of(nightShift),
                requestAt("t.x", LocalTime.of(23, 30))).allowed()).isTrue();
        assertThat(pdp.evaluate(List.of(nightShift),
                requestAt("t.x", LocalTime.of(3, 0))).allowed()).isTrue();
        assertThat(pdp.evaluate(List.of(nightShift),
                requestAt("t.x", LocalTime.of(12, 0))).allowed()).isFalse();
    }

    // ---- FR-AUTHZ-5: explanations ----

    @Test
    void allowedDecisionNamesTheGrantingPolicy() {
        PolicyView granter = allow("crm-for-users", role("tool-user"), pattern("crm.*"));

        Decision decision = pdp.evaluate(List.of(granter), request("crm.read", "INTERNAL"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.explanation()).contains("crm-for-users");
        assertThat(decision.matched()).extracting(Decision.MatchedPolicy::name)
                .containsExactly("crm-for-users");
    }

    // ---- helpers ----

    private boolean allowedFor(Resource resource, String tool) {
        return pdp.evaluate(List.of(allow("p", role("tool-user"), resource)),
                request(tool, "INTERNAL")).allowed();
    }

    private static PolicyView allow(String name, Subject subject, Resource resource) {
        return new PolicyView("id-" + name, name, Effect.ALLOW, List.of(subject),
                List.of(resource), null);
    }

    private static PolicyView deny(String name, Subject subject, Resource resource) {
        return new PolicyView("id-" + name, name, Effect.DENY, List.of(subject),
                List.of(resource), null);
    }

    private static Subject role(String value) {
        return new Subject("role", value);
    }

    private static Subject user(String value) {
        return new Subject("user", value);
    }

    private static Resource pattern(String glob) {
        return new Resource(glob, null);
    }

    private static EvaluationRequest request(String tool, String tier) {
        return new EvaluationRequest("sub-1", List.of("tool-user"), tool, tier, LocalTime.NOON);
    }

    private static EvaluationRequest requestAt(String tool, LocalTime time) {
        return new EvaluationRequest("sub-1", List.of("tool-user"), tool, "INTERNAL", time);
    }
}
