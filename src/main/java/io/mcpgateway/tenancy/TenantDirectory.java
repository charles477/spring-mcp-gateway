package io.mcpgateway.tenancy;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps the tenant slug carried in JWT claims to the internal tenant UUID.
 *
 * <p>Tenants are provisioned just-in-time on first sight: the IdP is the source of truth for who
 * belongs to which tenant, so a slug arriving in a validated token is by definition a legitimate
 * tenant. Requiring manual pre-registration here would only create a second, drift-prone copy of
 * that truth.
 */
@Service
public class TenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);

    private final TenantRepository repository;

    public TenantDirectory(TenantRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves the internal tenant id for a slug, creating the tenant on first sight.
     * Safe under concurrent first requests: the loser of the unique-constraint race re-reads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID resolveOrProvision(String slug) {
        return repository.findBySlug(slug)
                .map(Tenant::getId)
                .orElseGet(() -> provision(slug));
    }

    private UUID provision(String slug) {
        try {
            UUID id = repository.saveAndFlush(new Tenant(slug, slug)).getId();
            log.info("provisioned tenant '{}' on first sight", slug);
            return id;
        } catch (DataIntegrityViolationException raced) {
            return repository.findBySlug(slug).orElseThrow().getId();
        }
    }
}
