package io.mcpgateway.authz;

import io.mcpgateway.audit.AuditRecord.Decision;
import io.mcpgateway.audit.AuditService;
import io.mcpgateway.authz.PolicyDecisionPoint.PolicyView;
import io.mcpgateway.common.AuthenticatedActor;
import io.mcpgateway.tenancy.TenantDirectory;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lifecycle and retrieval of policy documents (FR-AUTHZ-1).
 *
 * <p>Authorization rules: a tenant-admin manages only their tenant's policies; platform-global
 * policies require platform-admin. Activation archives any previously ACTIVE version of the same
 * name, so exactly one version of a named policy is enforceable at a time.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policies;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public PolicyService(PolicyRepository policies, TenantDirectory tenants, AuditService audit,
                         ObjectMapper objectMapper) {
        this.policies = policies;
        this.tenants = tenants;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** Creates a DRAFT policy; it enforces nothing until explicitly activated (FR-AUTHZ-6). */
    @Transactional
    public Policy createDraft(AuthenticatedActor actor, boolean platformGlobal, String name,
                              Policy.Effect effect, String description, String subjectsJson,
                              String resourcesJson, String conditionsJson) {
        UUID tenantId = resolveScope(actor, platformGlobal);
        int nextVersion = 1 + policies.findByTenantIdOrderByNameAscVersionDesc(tenantId).stream()
                .filter(p -> p.getName().equals(name))
                .mapToInt(Policy::getVersion).max().orElse(0);
        Policy draft = policies.save(new Policy(tenantId, name, nextVersion, effect, description,
                subjectsJson, resourcesJson, conditionsJson, actor.username()));
        log.info("policy '{}' v{} drafted by {}", name, nextVersion, actor.username());
        return draft;
    }

    /** Activates a draft, archiving any previously ACTIVE version of the same name. */
    @Transactional
    public void activate(AuthenticatedActor actor, UUID policyId) {
        Policy policy = require(policyId);
        requireAdminOver(actor, policy.getTenantId());
        if (policy.getStatus() != Policy.Status.DRAFT) {
            throw new IllegalArgumentException("Only DRAFT policies can be activated");
        }
        policies.findByTenantIdAndNameAndStatus(policy.getTenantId(), policy.getName(),
                Policy.Status.ACTIVE).forEach(Policy::archive);
        policy.activate();
        log.info("policy '{}' v{} activated by {}", policy.getName(), policy.getVersion(),
                actor.username());
        audit.record(actor, "policy/activate", policy.getName(), Decision.ALLOWED,
                "version " + policy.getVersion() + " activated", 0);
    }

    /** The enforceable policy set for the actor's tenant, parsed and ready for the PDP. */
    @Transactional(readOnly = true)
    public List<PolicyView> activePoliciesFor(AuthenticatedActor actor) {
        return policies.findActiveFor(tenants.resolveOrProvision(actor.tenantId())).stream()
                .map(this::toView)
                .toList();
    }

    /** All policies the actor may administer, newest version first. */
    @Transactional(readOnly = true)
    public List<Policy> managedPolicies(AuthenticatedActor actor) {
        requireAnyAdmin(actor);
        UUID tenantId = tenants.resolveOrProvision(actor.tenantId());
        return policies.findByTenantIdOrderByNameAscVersionDesc(
                actor.hasRole("platform-admin") ? null : tenantId);
    }

    /** Parses one stored policy into its evaluatable form; also used for simulating drafts. */
    @Transactional(readOnly = true)
    public PolicyView viewOf(UUID policyId) {
        return toView(require(policyId));
    }

    private PolicyView toView(Policy policy) {
        return new PolicyView(
                policy.getId().toString(),
                policy.getName(),
                policy.getEffect() == Policy.Effect.ALLOW
                        ? PolicyView.Effect.ALLOW : PolicyView.Effect.DENY,
                objectMapper.readValue(policy.getSubjects(), new TypeReference<>() {}),
                objectMapper.readValue(policy.getResources(), new TypeReference<>() {}),
                policy.getConditions() == null ? null
                        : objectMapper.readValue(policy.getConditions(), new TypeReference<>() {}));
    }

    private Policy require(UUID policyId) {
        return policies.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy " + policyId));
    }

    private UUID resolveScope(AuthenticatedActor actor, boolean platformGlobal) {
        if (platformGlobal) {
            if (!actor.hasRole("platform-admin")) {
                throw new AccessDeniedException("Only platform-admin may manage platform-global policies");
            }
            return null;
        }
        requireAnyAdmin(actor);
        return tenants.resolveOrProvision(actor.tenantId());
    }

    private void requireAdminOver(AuthenticatedActor actor, UUID policyTenantId) {
        if (actor.hasRole("platform-admin")) {
            return;
        }
        boolean ownTenant = actor.hasRole("tenant-admin") && policyTenantId != null
                && policyTenantId.equals(tenants.resolveOrProvision(actor.tenantId()));
        if (!ownTenant) {
            throw new AccessDeniedException("Not an administrator of this policy's scope");
        }
    }

    private void requireAnyAdmin(AuthenticatedActor actor) {
        if (!actor.hasRole("tenant-admin") && !actor.hasRole("platform-admin")) {
            throw new AccessDeniedException("Policy administration requires an admin role");
        }
    }
}
