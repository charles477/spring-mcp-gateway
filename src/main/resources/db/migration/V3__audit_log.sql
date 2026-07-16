-- Append-only audit trail: one row per request that reaches the gateway pipeline,
-- regardless of outcome (FR-AUDIT-1). No UPDATE/DELETE is ever issued by the application.

CREATE TABLE audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_slug  TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    actor_name   TEXT NOT NULL,
    action       TEXT NOT NULL,
    tool_ref     TEXT,
    decision     TEXT NOT NULL,
    detail       TEXT,
    latency_ms   BIGINT
);

CREATE INDEX idx_audit_tenant_time ON audit_log (tenant_slug, occurred_at DESC);
CREATE INDEX idx_audit_tool_time ON audit_log (tool_ref, occurred_at DESC);
