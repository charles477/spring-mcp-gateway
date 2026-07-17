-- Human-in-the-loop approvals for RESTRICTED-tier tools (FR-APPR-1/2).
-- args_hash pins the exact arguments the approver saw: an approval is a one-shot ticket for
-- that specific invocation, not a standing grant — re-running with different arguments (or a
-- second time) requires a fresh approval.

CREATE TABLE approval_requests (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_slug    TEXT NOT NULL,
    tool_ref       TEXT NOT NULL,
    requested_by   TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    arguments      JSONB NOT NULL,
    args_hash      TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED')),
    decided_by     TEXT,
    decided_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_approvals_tenant_status ON approval_requests (tenant_slug, status);
