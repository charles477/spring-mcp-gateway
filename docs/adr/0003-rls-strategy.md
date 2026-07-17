# ADR-0003: Row-level security as defense in depth, not the app's primary scoping

**Status**: accepted · 2026-07-17

## Context
FR-TENANT-1 demands database-enforced tenant isolation. Postgres RLS can apply to the
application's own connection (`FORCE ROW LEVEL SECURITY` + `SET LOCAL app.current_tenant` per
transaction) or to designated roles only. Forcing it on the app connection requires wiring a
tenant-setting interceptor into every pooled transaction and complicates platform-scoped
operations (kill switch, platform-shared registrations) that legitimately cross tenants.

## Decision
Two layers:
1. **Application scoping** (primary): every query path resolves the tenant from the validated
   JWT and filters in code — this is what the request pipeline enforces and tests.
2. **RLS policies** (defense in depth): the `gateway_reader` role sees only
   `app.current_tenant`-matching rows on approval_requests, audit_log, mcp_servers, policies.
   All reporting/integration access goes through this role; a crafted query cannot cross
   tenants (verified: foreign tenant session sees 0 rows).

## Consequences
- The app connection remains RLS-exempt; a bug in application scoping is not caught by the DB
  for gateway traffic. Mitigated by tests on every scoped query path.
- Roadmap: schema-per-tenant for deployments needing hard physical isolation, and FORCE RLS
  with a transaction-scoped tenant interceptor as an opt-in hardening mode.
