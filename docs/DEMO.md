# Demo script

Everything below was executed against the real stack during development; each step maps to a
requirement in [REQUIREMENTS.md](REQUIREMENTS.md). Prereqs: `docker compose up -d`, gateway
running (`./mvnw spring-boot:run`), and a stub MCP backend on :9090 (any JSON-RPC server
answering `tools/list` / `tools/call`).

Get tokens (demo realm users, password `<user>123`):

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/mcp-gateway/protocol/openid-connect/token \
  -d "grant_type=password&client_id=mcp-gateway-client&username=alice&password=alice123" | jq -r .access_token)
```

| # | Scene | What to run / expect |
|---|---|---|
| 1 | Deny-by-default edge (FR-AUTHN-4) | `curl -i localhost:8080/api/me` → 401; with bearer → your identity incl. tenant + roles |
| 2 | Registration scopes (FR-REG-4) | `alice` POST `/admin/servers` → 403; `bob` (tenant-admin) → 201 private; `bob` with `"shared":true` → 403; `admin` → 201 shared |
| 3 | Policy deny-by-default (FR-AUTHZ-2) | With no policies: `tools/list` → `[]`, any call → `Denied by policy` |
| 4 | Dry-run (FR-AUTHZ-6) | Draft an ALLOW, POST `/admin/policies/simulate` with `includeDrafts` → `current: false, withDrafts: true`; live traffic still denied until activate |
| 5 | Explicit deny wins (FR-AUTHZ-3) | Activate a DENY over an existing ALLOW → call denied, tool gone from listing; audit row names the deciding policy (FR-AUTHZ-5) |
| 6 | Rug pull (FR-REG-5) | Mutate the stub's tool description → next call `-32001` quarantined; restore description → still quarantined; `bob` POST `/admin/tools/{id}/reapprove` → calls resume |
| 7 | Kill switch (FR-REG-6) | `bob` PATCH `/admin/servers/{id}/enabled {"enabled":false}` → calls answer *identically to unknown tools*; with two gateway nodes, flip via node 1, denial on node 2 within ~1s (FR-REG-3) |
| 8 | Approval (FR-APPR-1/2) | Call a RESTRICTED tool → `-32004` + ticket id; `alice` approving herself → 403; `bob` approves; re-call with `approvalId` executes; same ticket again → denied (one-shot, argument-hash bound) |
| 9 | Guardrails (FR-GUARD-1/2) | Arguments violating the pinned schema → `-32602` with the offending path; backend response containing an email + AWS key arrives as `[REDACTED:email-address]` / `[REDACTED:aws-access-key]` |
| 10 | Rate limit (FR-RATE-1) | Start with `--gateway.ratelimit.capacity=3 --gateway.ratelimit.refill-per-minute=1` → 3 calls pass, 4th `-32003` |
| 11 | Tenant isolation (FR-TENANT-1) | In psql: `SET ROLE gateway_reader; SET app.current_tenant='someone-else';` → 0 audit rows, 0 private servers |
| 12 | Observability (FR-OBS-2) | `/actuator/prometheus` metric families; Prometheus target `up` at :9091; Grafana at :3000; OpenAPI at `/v3/api-docs` (authenticated) |

The audit trail after a full run reads like a story — query it:

```sql
SELECT occurred_at, actor_name, action, tool_ref, decision, detail
FROM audit_log ORDER BY occurred_at DESC LIMIT 20;
```
