package io.mcpgateway.authz;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Policy Decision Point: pure evaluation logic with no framework or persistence
 * dependencies, so its behavior is exhaustively unit-testable.
 *
 * <p>Evaluation semantics (AWS-IAM-inspired):
 * <ol>
 *   <li><b>Deny by default</b> (FR-AUTHZ-2): with no matching policy, the answer is deny —
 *       absence of policy is a deny, not an error.</li>
 *   <li><b>Explicit deny wins</b> (FR-AUTHZ-3): one matching DENY overrides any number of
 *       matching ALLOWs.</li>
 * </ol>
 *
 * <p>A policy matches when its subjects match the actor AND at least one resource entry matches
 * the target AND every condition holds (conditions are ANDed). Every decision carries the full
 * list of matched policies so it can be explained, not just enforced (FR-AUTHZ-5).
 */
public final class PolicyDecisionPoint {

    /** What the caller wants to do, described attribute-by-attribute for ABAC conditions. */
    public record EvaluationRequest(String actorSubject, List<String> actorRoles,
                                    String qualifiedToolName, String sensitivityTier,
                                    LocalTime timeOfDay) {
    }

    /** One evaluatable policy, already parsed from its stored JSON document. */
    public record PolicyView(String id, String name, Effect effect, List<Subject> subjects,
                             List<Resource> resources, List<Condition> conditions) {

        public enum Effect { ALLOW, DENY }

        public record Subject(String type, String value) {
        }

        /** Either or both fields may be present; present fields must all match. */
        public record Resource(String pattern, String tier) {
        }

        public record Condition(String attribute, String operator, List<String> values) {
        }
    }

    /** The verdict plus everything needed to explain it (FR-AUTHZ-5). */
    public record Decision(boolean allowed, List<MatchedPolicy> matched, String explanation) {

        public record MatchedPolicy(String id, String name, PolicyView.Effect effect) {
        }
    }

    /** Evaluates the request against the given policy set. */
    public Decision evaluate(List<PolicyView> policies, EvaluationRequest request) {
        List<Decision.MatchedPolicy> matched = new ArrayList<>();
        boolean denied = false;
        boolean allowed = false;
        for (PolicyView policy : policies) {
            if (matches(policy, request)) {
                matched.add(new Decision.MatchedPolicy(policy.id(), policy.name(), policy.effect()));
                if (policy.effect() == PolicyView.Effect.DENY) {
                    denied = true;
                } else {
                    allowed = true;
                }
            }
        }
        if (denied) {
            String denier = matched.stream()
                    .filter(m -> m.effect() == PolicyView.Effect.DENY)
                    .findFirst().map(Decision.MatchedPolicy::name).orElse("?");
            return new Decision(false, matched,
                    "Denied: explicit deny from policy '" + denier + "' (explicit deny overrides allows)");
        }
        if (allowed) {
            String granter = matched.stream()
                    .filter(m -> m.effect() == PolicyView.Effect.ALLOW)
                    .findFirst().map(Decision.MatchedPolicy::name).orElse("?");
            return new Decision(true, matched, "Allowed by policy '" + granter + "'");
        }
        return new Decision(false, matched, "Denied: no matching allow policy (default deny)");
    }

    private boolean matches(PolicyView policy, EvaluationRequest request) {
        return subjectMatches(policy.subjects(), request)
                && resourceMatches(policy.resources(), request)
                && conditionsHold(policy.conditions(), request);
    }

    private boolean subjectMatches(List<PolicyView.Subject> subjects, EvaluationRequest request) {
        for (PolicyView.Subject subject : subjects) {
            boolean hit = switch (subject.type()) {
                case "role" -> request.actorRoles().contains(subject.value());
                case "user" -> request.actorSubject().equals(subject.value());
                default -> false;
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private boolean resourceMatches(List<PolicyView.Resource> resources, EvaluationRequest request) {
        for (PolicyView.Resource resource : resources) {
            boolean patternOk = resource.pattern() == null
                    || globMatches(resource.pattern(), request.qualifiedToolName());
            boolean tierOk = resource.tier() == null
                    || resource.tier().equalsIgnoreCase(request.sensitivityTier());
            if (patternOk && tierOk && (resource.pattern() != null || resource.tier() != null)) {
                return true;
            }
        }
        return false;
    }

    private boolean conditionsHold(List<PolicyView.Condition> conditions, EvaluationRequest request) {
        if (conditions == null) {
            return true;
        }
        for (PolicyView.Condition condition : conditions) {
            if (!conditionHolds(condition, request)) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionHolds(PolicyView.Condition condition, EvaluationRequest request) {
        String actual = attributeValue(condition.attribute(), request);
        if (actual == null) {
            // Unknown attribute: the condition cannot be proven, so the policy must not apply.
            return false;
        }
        return switch (condition.operator()) {
            case "equals" -> condition.values().size() == 1 && condition.values().get(0).equals(actual);
            case "not_equals" -> condition.values().size() == 1 && !condition.values().get(0).equals(actual);
            case "in" -> condition.values().contains(actual);
            case "time_between" -> condition.values().size() == 2 && timeBetween(
                    request.timeOfDay(), condition.values().get(0), condition.values().get(1));
            default -> false;
        };
    }

    private String attributeValue(String attribute, EvaluationRequest request) {
        return switch (attribute) {
            case "tool" -> request.qualifiedToolName();
            case "tier" -> request.sensitivityTier();
            case "subject" -> request.actorSubject();
            case "timeOfDay" -> request.timeOfDay() == null ? null : request.timeOfDay().toString();
            default -> null;
        };
    }

    private static boolean timeBetween(LocalTime now, String from, String to) {
        if (now == null) {
            return false;
        }
        LocalTime start = LocalTime.parse(from);
        LocalTime end = LocalTime.parse(to);
        // Inverted ranges span midnight (e.g. 22:00–06:00).
        return start.isBefore(end) || start.equals(end)
                ? !now.isBefore(start) && !now.isAfter(end)
                : !now.isBefore(start) || !now.isAfter(end);
    }

    /** Glob where {@code *} matches any run of characters; everything else is literal. */
    private static boolean globMatches(String glob, String value) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            regex.append(Pattern.quote(part)).append(".*");
        }
        regex.setLength(regex.length() - 2);
        return value.matches(regex.toString());
    }
}
