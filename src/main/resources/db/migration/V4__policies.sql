-- Versioned policy documents for the custom PDP (FR-AUTHZ-1).
-- tenant_id NULL means a platform-global policy applying to every tenant (only a
-- platform-admin may manage those). Activating a new version archives the old one;
-- history is never deleted, so every past authorization decision stays explainable.

CREATE TABLE policies (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenants (id),
    name        TEXT NOT NULL,
    version     INT NOT NULL DEFAULT 1,
    status      TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    effect      TEXT NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    description TEXT,
    -- [{"type": "role"|"user", "value": "..."}] — who the policy applies to
    subjects    JSONB NOT NULL,
    -- [{"pattern": "server.tool-glob"} and/or {"tier": "PUBLIC|INTERNAL|RESTRICTED"}]
    resources   JSONB NOT NULL,
    -- [{"attribute": "...", "operator": "equals|not_equals|in|time_between", "values": [...]}]
    conditions  JSONB,
    created_by  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policies_tenant_status ON policies (tenant_id, status);
CREATE UNIQUE INDEX uq_policies_tenant_name_version ON policies (tenant_id, name, version)
    WHERE tenant_id IS NOT NULL;
CREATE UNIQUE INDEX uq_policies_platform_name_version ON policies (name, version)
    WHERE tenant_id IS NULL;
