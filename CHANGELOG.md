# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and versions follow
[Semantic Versioning](https://semver.org/).

## [0.7.0] - 2026-07-19

### Added
- React + TypeScript admin console (`ui/`): Dashboard, Registry, Policies with
  AI copilot and dry-run activation, Approvals, Audit — dark mission-console theme
- `GET /admin/audit` query endpoint, tenant-scoped by role (FR-AUDIT-3)
- `/admin/ai/draft-policy` and `/admin/ai/digest`: server-side Claude API calls
  (key in gateway env only); graceful 503 when unconfigured; copilot output is
  draft-only and passes the standard simulate-then-activate gate
- CI job typechecking and building the console UI

## [0.6.0] - 2026-07-17

### Added
- Prometheus metrics at `/actuator/prometheus`; Prometheus + Grafana in Docker Compose (FR-OBS-2)
- OpenAPI documentation for Admin and MCP APIs via springdoc (FR-ADMIN-1)
- ADRs 0001–0004 (modular monolith, custom PDP vs OPA, RLS strategy, rug-pull defense)
- `docs/DEMO.md` — 12-scene demo script mapped to requirements

## [0.5.0] - 2026-07-17

### Added
- Redis Lua token-bucket rate limiting per (tenant, user, tool) (FR-RATE-1)
- Input validation against the manifest-pinned JSON Schema (FR-GUARD-1)
- Guardrail chain redacting secrets/PII from tool responses (FR-GUARD-2)
- Approval workflow for RESTRICTED tools: one-shot, argument-hash-bound tickets (FR-APPR-1/2)
- Postgres row-level security for tenant isolation via `gateway_reader` role (FR-TENANT-1)

## [0.4.0] - 2026-07-17

### Added
- Custom RBAC+ABAC policy engine: framework-free PDP with versioned policy
  documents, role/user subjects, glob resources, sensitivity tiers, and ANDed
  ABAC conditions incl. overnight time ranges (FR-AUTHZ-1)
- Deny-by-default and explicit-deny-wins evaluation (FR-AUTHZ-2, FR-AUTHZ-3)
- PEP embedded in the gateway pipeline: `tools/call` checked before proxying,
  `tools/list` hides denied tools (FR-AUTHZ-4)
- Decision explanations naming every matched and deciding policy, surfaced to
  audit records and admins — never to the denied agent (FR-AUTHZ-5)
- Policy lifecycle (DRAFT/ACTIVE/ARCHIVED, immutable history) with dry-run
  simulation overlaying drafts on the live set (FR-AUTHZ-6)

## [0.3.0] - 2026-07-16

### Added
- Dynamic MCP server/tool registration with platform-shared vs tenant-private
  ownership scopes (FR-REG-1, FR-REG-4); just-in-time tenant provisioning from JWT
- `POST /mcp` JSON-RPC 2.0 endpoint (`tools/list`, `tools/call`) exposing tools as
  `<server>.<tool>` across aggregated backends (FR-GW-1)
- Rug-pull defense (FR-REG-5): tool definitions content-hashed at registration;
  live-definition drift auto-quarantines; `POST /admin/tools/{id}/reapprove` re-pins
- Kill switch (FR-REG-6): `PATCH /admin/servers/{id}/enabled`, enforced from the
  node-local cache; killed servers indistinguishable from unknown ones
- Append-only async audit trail — exactly one record per request (FR-AUDIT-1/2)
- Node-local routing cache with Redis pub/sub invalidation across nodes (FR-REG-3)
- Circuit breaker per backend with connect/read timeouts (FR-GW-2)

## [0.2.0] - 2026-07-16

### Added
- OIDC resource-server authentication with deny-by-default edge: only health
  probes are anonymous, everything else requires a validated JWT (FR-AUTHN-1, FR-AUTHN-4)
- Config-driven claim mapping (`gateway.auth.tenant-claim`, `gateway.auth.roles-claim`)
  so the IdP is swappable without code changes (FR-AUTHN-2)
- Tenant-required token rule: JWTs without a tenant claim are rejected at
  authentication (FR-TENANT-2)
- `AuthenticatedActor` carried on the `Authentication` for downstream pipeline stages
- Flyway V1 baseline identity schema (tenants, users, groups, roles, bindings)
- `/api/me` identity echo endpoint for end-to-end verification
- GitHub Actions CI (`mvnw verify` on push and pull request)

### Fixed
- Keycloak realm import: declared user profile with unmanaged attributes enabled
  so `tenant_id` survives import; complete demo-user profiles so direct grants work
- Keycloak healthcheck now targets the management port (9000) with health enabled
- Maven wrapper executable bit for Linux CI runners

## [0.1.0] - 2026-07-16

### Added
- Maven/Spring Boot project skeleton with hexagonal module layout
  (gateway, authn, authz, registry, audit, guardrail, ratelimit, secrets, approval, tenancy)
- Docker Compose local stack: Postgres 16, Redis 7, Keycloak 26.6 with pre-seeded demo realm
- Requirements specification with per-requirement acceptance criteria (`docs/REQUIREMENTS.md`)
- README with architecture and prior-art comparison; contribution conventions (`CONTRIBUTING.md`)

[0.7.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.7.0
[0.6.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.6.0
[0.5.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.5.0
[0.4.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.4.0
[0.3.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.3.0
[0.2.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.2.0
[0.1.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.1.0
