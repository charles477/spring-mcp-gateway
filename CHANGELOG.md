# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and versions follow
[Semantic Versioning](https://semver.org/).

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

[0.3.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.3.0
[0.2.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.2.0
[0.1.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.1.0
