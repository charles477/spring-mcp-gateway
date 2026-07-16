# spring-mcp-gateway — Requirements Specification (v1.0.0 target)

Status: **Draft, locking scope before further implementation.**
This document formalizes the scope agreed in the project plan into testable requirements. Every functional requirement (FR) has an ID and acceptance criteria; every ID should eventually be traceable to a test.

## 1. Overview

spring-mcp-gateway is a domain-agnostic control plane between AI agents/LLM clients and MCP servers. It centralizes authentication, authorization, dynamic tool registration, rate limiting, guardrails, approval workflows, and audit logging — none of which the MCP spec itself defines.

**Positioning**: this is a from-scratch, Java/Spring-native implementation of an architecture pattern already validated by Kong AI/MCP Gateway, Docker MCP Gateway, Stacklok/ToolHive, IBM ContextForge, Microsoft's MCP Gateway, and mcp-governance-sdk — none of which target the Spring ecosystem. It is built as a portfolio/demonstration project, not a commercial competitor; prior art is cited openly in the README.

## 2. Goals

- G1: Any unmodified MCP client can point at the gateway's single endpoint and get standard `tools/list`/`tools/call` behavior.
- G2: Any organization can add or remove backend MCP servers/tools via API, with zero gateway code changes or redeploys.
- G3: Every tool invocation is authenticated, authorized, optionally rate-limited, optionally guardrail-scanned, and audited — with no bypass path.
- G4: The system runs identically via Docker Compose (local/demo), and is documented (not necessarily fully implemented) for Kubernetes and on-prem.
- G5: The codebase demonstrates hexagonal architecture, a custom-built PDP/PEP, and Zero Trust/deny-by-default principles clearly enough to defend in a technical interview.

## 3. Non-Goals (v1)

- NG1: Not a commercial product; no billing, multi-region failover, or SLA guarantees.
- NG2: Not a replacement for a real IdP — the gateway is an OIDC **resource server**, never an authorization server.
- NG3: Not attempting feature parity with Kong/Stacklok/ContextForge — v1 proves the core pattern end-to-end, not every enterprise feature (see §6, Out of Scope).
- NG4: No proprietary cloud SDK integration (AWS/GCP/Azure-specific secrets managers etc.) in v1 — only protocol-standard integration points (OIDC, generic secrets interface).

## 4. Glossary

| Term | Meaning |
|---|---|
| Agent | An AI/LLM client speaking MCP JSON-RPC to the gateway |
| MCP Server | A backend tool provider registered behind the gateway |
| Tool | A single invocable capability exposed by an MCP server, with a JSON Schema for its arguments |
| PDP | Policy Decision Point — evaluates whether a request is allowed |
| PEP | Policy Enforcement Point — the pipeline stage that calls the PDP and enforces its decision |
| Tenant | An isolated organization/customer using the gateway |
| Sensitivity tier | A tag on a tool (`public` / `internal` / `restricted`) driving approval-workflow requirements |

## 5. Functional Requirements

### 5.1 AuthN (FR-AUTHN)
- **FR-AUTHN-1**: The gateway validates OIDC-issued JWT bearer tokens against a configured issuer's JWKS endpoint. *Acceptance*: a valid Keycloak-issued token is accepted; an expired or wrong-issuer token is rejected with 401.
- **FR-AUTHN-2**: The IdP is swappable via configuration only (issuer URL, JWKS URI) — no code changes to support Okta/Azure AD/Auth0 in place of Keycloak. *Acceptance*: documented config mapping for at least one alternate IdP, verified against Keycloak’s OIDC-standard behavior.
- **FR-AUTHN-3**: Service-account agents may authenticate via a gateway-issued API key instead of a user JWT. *Acceptance*: a request with a valid API key resolves to a principal with an identity and tenant, same as a JWT would.
- **FR-AUTHN-4**: Unauthenticated requests to any MCP or Admin endpoint are rejected by default (deny-by-default at the edge). *Acceptance*: a request with no token returns 401 on every non-health endpoint.

### 5.2 Registry (FR-REG)
- **FR-REG-1**: Admins can register an MCP server (base URL, auth config, sensitivity tags) via API without restarting the gateway. *Acceptance*: a newly registered server's tools are callable within one cache-invalidation cycle, no redeploy.
- **FR-REG-2**: The registry stores each tool's name, version, input JSON Schema, and sensitivity tier. *Acceptance*: `tools/list` returns schema-accurate tool definitions sourced from the registry, not hardcoded.
- **FR-REG-3**: Registry changes propagate to all gateway nodes via pub/sub cache invalidation, not polling-only. *Acceptance*: a registration made against one node is visible via another node's `tools/list` within the invalidation window.
- **FR-REG-4**: Every registered server has an ownership scope: **platform-shared** (registered by a platform-admin, visible to all tenants) or **tenant-private** (registered by a tenant-admin, visible and callable only within the owning tenant). Shared servers remain governed per-tenant — each tenant's own policies, rate limits, and audit records apply to its usage of a shared tool. *Acceptance*: a tenant-private server never appears in another tenant's `tools/list` and returns 403/404 on cross-tenant `tools/call`; two tenants calling the same platform-shared tool produce separately scoped audit rows and rate-limit buckets.
- **FR-REG-5 (manifest pinning / rug-pull defense)**: Each tool's definition (name, description, input schema) is content-hashed at registration time. The gateway verifies the backend's live definition against the pinned hash; on mismatch the tool is auto-quarantined (removed from `tools/list`, `tools/call` denied) and an admin alert audit event is emitted, until an admin explicitly re-approves the new definition. This defends against the known MCP "rug-pull" attack, where a backend silently changes an approved tool's description to smuggle injected instructions to the LLM. *Acceptance*: changing a stub backend's tool description after registration causes the next call to be denied with a quarantine reason and produces a quarantine audit event; re-approval via Admin API restores the tool with a new pinned hash.
- **FR-REG-6 (kill switch)**: A platform-admin (or tenant-admin, within their tenant) can immediately disable a tool, an entire server, or a whole tenant via a single Admin API call. The disable is enforced from the in-memory cache after pub/sub invalidation — it does not require a database read on the request path, so it holds even under database degradation. *Acceptance*: after a kill-switch call, `tools/call` to the target is denied on all gateway nodes within the invalidation window; re-enabling restores service; both actions produce audit events.

### 5.3 Policy Engine / AuthZ (FR-AUTHZ)
- **FR-AUTHZ-1**: Policies are versioned documents with subject (user/group/role), resource (tool name/tag pattern), effect (allow/deny), and optional ABAC conditions. *Acceptance*: a policy CRUD round-trip via Admin API persists and re-evaluates correctly.
- **FR-AUTHZ-2**: Evaluation is deny-by-default: a request with no matching allow policy is denied. *Acceptance*: unit test — empty policy set → every request denied.
- **FR-AUTHZ-3**: Explicit deny beats any allow (AWS-IAM-style evaluation). *Acceptance*: unit test — conflicting allow+deny policies on the same subject/resource → deny wins.
- **FR-AUTHZ-4**: The PEP is invoked on every `tools/call` and `tools/list` (list is filtered to only visible tools) — no path bypasses it. *Acceptance*: a denied tool does not appear in `tools/list` and returns 403 on direct `tools/call`.
- **FR-AUTHZ-5 (decision explanations)**: Every PDP decision records *which* policies matched and why (matched policy IDs/versions, effect each contributed, which condition failed for near-misses). The explanation is attached to the audit record and retrievable via an admin `explain` endpoint. *Acceptance*: for a denied request, the explain output names the deciding policy (or states "no matching allow — default deny"); for an allowed request, it names the granting policy.
- **FR-AUTHZ-6 (policy dry-run / simulation)**: An admin can evaluate a draft (inactive) policy against a hypothetical request — or replay recent audit entries — and see what the decision *would* be, without activating the policy or affecting live traffic. *Acceptance*: a draft deny policy simulated against a request that live policy allows reports "would deny" while the live request still succeeds; activating the draft then flips the live outcome.

### 5.4 Gateway / Proxy (FR-GW)
- **FR-GW-1**: The gateway exposes a single MCP-JSON-RPC-compliant endpoint implementing `tools/list` and `tools/call` per the MCP spec, unmodified from a client's perspective. *Acceptance*: an off-the-shelf MCP client library can complete a full list+call cycle against the gateway.
- **FR-GW-2**: Backend server calls use connection pooling and a circuit breaker; a failing backend does not cascade to unrelated tool calls. *Acceptance*: forcing one stubbed backend to fail does not affect calls routed to a second, healthy stub.

### 5.5 Audit Logging (FR-AUDIT)
- **FR-AUDIT-1**: Every request that reaches the PEP produces exactly one structured audit record (actor, tenant, tool, decision, latency, outcome), regardless of allow/deny/error outcome. *Acceptance*: integration test asserts one audit row per request across allow, deny, and backend-error paths.
- **FR-AUDIT-2**: Audit writes are asynchronous and never block or fail the request path. *Acceptance*: audit persistence failure (simulated) does not change the response returned to the caller.
- **FR-AUDIT-3**: Audit records are queryable via Admin API, filterable by tenant/actor/tool/time range. *Acceptance*: a seeded set of audit rows is correctly filtered by each supported query parameter.

### 5.6 Rate Limiting (FR-RATE)
- **FR-RATE-1**: Requests are token-bucket limited per (user, tenant, tool) triple, backed by Redis so limits hold across gateway nodes. *Acceptance*: exceeding the configured bucket returns 429; two gateway node instances sharing Redis enforce the same limit.

### 5.7 Admin API (FR-ADMIN)
- **FR-ADMIN-1**: CRUD endpoints exist for tenants, users, groups, roles, policies, and server registrations, documented via OpenAPI. *Acceptance*: OpenAPI spec is generated and every documented endpoint is reachable and authorization-checked (platform-admin/tenant-admin only).
- **FR-ADMIN-2**: Admin API access is itself policy-governed — an admin endpoint is just another PEP-protected resource, not a separate trust path. *Acceptance*: a `tool-user`-role token is rejected (403) from all `/admin/*` endpoints.

### 5.8 Guardrails (FR-GUARD)
- **FR-GUARD-1**: Inbound tool-call arguments are validated against the tool's registered JSON Schema before proxying. *Acceptance*: a malformed argument payload is rejected with 400 before reaching the backend server.
- **FR-GUARD-2**: At least one real detector (regex/entropy-based PII & secret scanner) runs on outbound tool responses via a Chain-of-Responsibility pipeline, and the pipeline is extensible (new detectors pluggable without touching call sites). *Acceptance*: a stubbed tool response containing a fake API-key-shaped string is flagged/redacted; adding a second detector requires no changes to the pipeline invocation code.

### 5.9 Approval Workflow (FR-APPR)
- **FR-APPR-1**: Tools tagged `restricted` create a pending `approval_requests` record instead of executing immediately. *Acceptance*: a restricted-tier tool call returns a "pending approval" response, not a result.
- **FR-APPR-2**: An authorized approver (tool-approver/tenant-admin role) can approve or reject via Admin API, which then allows or permanently blocks the original call. *Acceptance*: approving unblocks execution; rejecting returns a terminal denial, never silently retries.

### 5.10 Multi-Tenancy (FR-TENANT)
- **FR-TENANT-1**: All tenant-scoped tables enforce isolation via Postgres Row-Level Security keyed on `tenant_id`, not only application-layer filtering. *Acceptance*: a direct query executed under one tenant's RLS session cannot read another tenant's rows, even with a crafted query.
- **FR-TENANT-2**: Tenant is resolved from a JWT claim and propagated through the entire request pipeline (authz, guardrail, rate limit, audit). *Acceptance*: two tenants with identical role names but different policies get different authorization outcomes for the same tool.

### 5.11 Observability (FR-OBS)
- **FR-OBS-1**: Every pipeline stage (authn, authz, guardrail, rate limit, proxy, audit) emits an OpenTelemetry span, with one trace per request spanning all of them. *Acceptance*: a single `tools/call` produces one trace with a span per stage, visible in Jaeger.
- **FR-OBS-2**: Prometheus metrics expose request rate, latency percentiles, and policy-deny rate; at least two Grafana dashboards are pre-built. *Acceptance*: `/actuator/prometheus` exposes the named metrics; dashboards render non-empty panels after a demo run.

## 6. Out of Scope for v1 (documented roadmap, not implemented)

Each item below gets an ADR-level design note and, where marked (*), a thin proof-of-concept — but is explicitly **not** production-complete in v1:

- Schema-per-tenant isolation (alternative to RLS, for tenants needing hard physical separation)
- ML-based prompt-injection classifier (v1 ships regex/entropy heuristics only *)
- Approval workflow notification integrations — Slack/email (v1 ships the state machine + API only *)
- Kafka-based audit streaming to external SIEM (v1 ships async DB write + query API only)
- Policy-as-code GitOps CI validation
- Marketplace/catalog UI for discovering registered tools
- Usage-based billing/metering hooks
- SOC2/GDPR compliance report generation
- Production-hardened Helm chart / Kubernetes deployment (v1 ships Docker Compose + a Helm chart stub)
- Path to splitting the modular monolith into separate deployable services at real scale
- **Session taint tracking ("lethal trifecta" prevention)**: block an agent session that has both read private data (via a PII-tier tool) and ingested untrusted content from then calling an external-communication tool — the exfiltration risk is the *combination*, which per-tool policy cannot express. Requires session-scoped state; gets a dedicated ADR.
- **Agent-as-principal / on-behalf-of identity**: agents receive their own identity chained to the delegating user's (OAuth 2 token exchange, RFC 8693); effective permissions are the intersection of agent and user grants.
- **Per-user credential brokering**: exchange the caller's token for a user-scoped backend credential instead of a shared gateway-held service credential, so backends see the true end user.
- **Audit anomaly flags**: first-time tool use, off-hours activity, and per-tenant volume spikes flagged on the audit stream.

## 7. Non-Functional Requirements

- **NFR-1 (Security)**: No secret or backend credential is ever returned to, or logged in a way visible to, the calling agent.
- **NFR-2 (Portability)**: The application is a single Spring Boot artifact; all backing services (Postgres, Redis, IdP, secrets store) are externalized via standard protocols/config — no cloud-vendor SDK required to run it.
- **NFR-3 (Testability)**: Every FR above has at least one automated test; integration tests use Testcontainers against real Postgres/Redis/Keycloak, not mocks, for anything crossing a process boundary.
- **NFR-4 (Statelessness)**: Gateway application nodes hold no request-scoped state outside the request lifecycle — all shared state lives in Postgres/Redis, so nodes can scale horizontally behind a load balancer.

## 8. v1.0.0 Release Definition of Done

1. All FR acceptance criteria in §5 pass as automated tests.
2. `docker compose up` + the documented demo script (register a stub server, obtain a Keycloak token per role, exercise allow/deny/approval/audit paths) succeeds end-to-end on a clean checkout.
3. README includes the architecture diagram and the prior-art comparison table.
4. ADRs exist for: custom PDP vs. OPA, modular monolith vs. microservices, RLS vs. schema-per-tenant, Java/Spring positioning.
5. CI (`mvn verify`) is green on `main`.
