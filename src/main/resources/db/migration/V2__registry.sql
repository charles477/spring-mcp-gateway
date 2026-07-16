-- MCP server/tool registry (FR-REG-1..6).
-- owner_tenant_id NULL means platform-shared: visible to all tenants, registered only by a
-- platform-admin. Non-null means tenant-private: visible and callable only within that tenant.

CREATE TABLE mcp_servers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_tenant_id UUID REFERENCES tenants (id),
    name            TEXT NOT NULL,
    base_url        TEXT NOT NULL,
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Server names must be unique within their visibility scope. Postgres treats NULLs as
-- distinct in unique constraints, so platform-shared uniqueness needs its own partial index.
CREATE UNIQUE INDEX uq_mcp_servers_tenant_name
    ON mcp_servers (owner_tenant_id, name) WHERE owner_tenant_id IS NOT NULL;
CREATE UNIQUE INDEX uq_mcp_servers_shared_name
    ON mcp_servers (name) WHERE owner_tenant_id IS NULL;

CREATE TABLE tools (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id        UUID NOT NULL REFERENCES mcp_servers (id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    description      TEXT,
    input_schema     JSONB NOT NULL,
    -- Values match the Java enum names exactly (EnumType.STRING storage).
    sensitivity_tier TEXT NOT NULL DEFAULT 'INTERNAL'
                     CHECK (sensitivity_tier IN ('PUBLIC', 'INTERNAL', 'RESTRICTED')),
    -- SHA-256 over the canonical tool definition, pinned at registration. A backend whose
    -- live definition no longer matches is quarantined (rug-pull defense, FR-REG-5).
    manifest_hash    TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'QUARANTINED', 'DISABLED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (server_id, name)
);

CREATE INDEX idx_tools_server ON tools (server_id);
