# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and versions follow
[Semantic Versioning](https://semver.org/).

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

[0.2.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.2.0
[0.1.0]: https://github.com/charles477/spring-mcp-gateway/releases/tag/v0.1.0
