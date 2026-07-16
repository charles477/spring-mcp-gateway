# spring-mcp-gateway

A Spring-native security and governance control plane for the Model Context Protocol (MCP) — it sits between AI agents/LLM clients and MCP tool servers, so authentication, authorization, auditing, rate limiting, and safety checks are enforced centrally instead of being reinvented (or skipped) inside every individual MCP server.

> **Status: 🚧 early, active development.** The architecture, infra, and requirements below are locked; the request pipeline is being built module by module. See [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) for exact scope and [Implementation status](#implementation-status) for what runs today.

## The problem

The MCP spec standardizes *how* an agent discovers and calls tools. It says almost nothing about *who* is allowed to, under *what conditions*, or *with what oversight*. In practice, every team that adopts MCP ends up reinventing auth (or skipping it) per server: no shared identity, no cross-server policy, no audit trail, no safe way to register a new tool without redeploying code, and no defense against a tool response smuggling a prompt injection back into the agent's context.

This project builds that missing layer: a domain-agnostic gateway that any organization can put in front of any MCP server, without modifying the agent or the server.

## How this compares

This is **not** an unclaimed problem — by 2026 several serious gateways already exist. Disclosing that up front, rather than pretending otherwise:

| Project | Stack | Deployment target | Policy engine | Niche |
|---|---|---|---|---|
| Kong AI/MCP Gateway | Lua/Go | Any (Kong ecosystem) | OAuth2 plugin, DLP | API-gateway incumbent extending into MCP |
| Docker MCP Gateway | Go | Developer laptop / containers | Container isolation | Local dev, signed images, secrets mgmt |
| Stacklok / ToolHive | Go | Kubernetes-native | Cedar / OPA-Rego | K8s operator, CRD-based server lifecycle |
| IBM ContextForge | Python/Rust | Any | Plugin framework | Federates MCP + A2A + REST/gRPC |
| mcp-governance-sdk | TypeScript | SDK, embedded in your server | Custom RBAC | Library, not a standalone gateway |
| **spring-mcp-gateway (this project)** | **Java/Spring** | Docker Compose, Helm/K8s (documented) | **Custom Java PDP/PEP** | **The only Spring-native option — built for enterprise Java/Spring shops (banks, insurers, gov, large regulated orgs) that would rather adopt a Spring Security-idiomatic gateway than a Go binary or Python service** |

This is a portfolio/demonstration project (built to prove the pattern, deeply, in an underserved stack) — not a claim to compete commercially with the projects above.

## Architecture

```
Agent/LLM
   │  MCP JSON-RPC (tools/list, tools/call) + Bearer JWT
   ▼
[Gateway: MCP-spec-compliant endpoint]
   │
   ├─ AuthN        — validate JWT (OIDC resource server) against Keycloak/Okta/Azure AD (config-only)
   ├─ AuthZ (PEP)  — custom Policy Decision Point: RBAC + ABAC, deny-by-default, explicit-deny-wins
   ├─ Approval gate — sensitive-tier tools pause for admin approval
   ├─ Guardrail(in)— validate tool-call args against JSON Schema; scan for injection patterns
   ├─ Rate limiter — token bucket per user/tenant/tool (Redis-backed)
   ├─ Secrets      — inject backend tool credentials; never expose to agent
   ▼
[Registry/Router] → proxies to the correct backend MCP server (dynamically registered, no redeploy)
   ▼
Backend MCP Server(s)
   │
   ▼ response
[Guardrail(out)]  — scan tool output for PII/secret leakage before it re-enters agent context
   │
   ├─ Audit log (async, append-only)
   └─ OpenTelemetry spans/metrics across every hop above
   ▼
Agent/LLM receives response
```

One deployable Spring Boot application, internally organized as a **modular monolith with hexagonal (ports & adapters) boundaries** — each module (`authn`, `authz`, `registry`, `gateway`, `audit`, `guardrail`, `ratelimit`, `secrets`, `approval`, `tenancy`) owns its domain model and swaps adapters (Postgres, Redis, Keycloak) without leaking implementation details across module boundaries. See `docs/adr/` for why this was chosen over microservices.

## Core capabilities

| Capability | What it does |
|---|---|
| OIDC AuthN | Validates JWTs from any standard IdP; swapping Keycloak → Okta/Azure AD is config-only |
| Dynamic registry | Register/remove MCP servers and tools via API — zero gateway redeploys |
| Custom policy engine | RBAC + ABAC, versioned policies, deny-by-default, explicit-deny-wins evaluation |
| MCP-spec proxy | Unmodified `tools/list`/`tools/call` endpoint any MCP client can use as-is |
| Guardrails | Pluggable pipeline scanning tool args/responses for PII, secrets, and injection patterns |
| Rate limiting | Redis-backed token bucket per user/tenant/tool |
| Approval workflow | Restricted-tier tools pause for human approval before executing |
| Multi-tenancy | Postgres Row-Level Security keyed on tenant, resolved from the JWT |
| Audit logging | One structured, async, queryable record per request — allow, deny, or error |
| Observability | OpenTelemetry traces + Prometheus metrics across the full request lifecycle |
| Rug-pull defense | Tool definitions are content-hash pinned at registration; silent backend changes auto-quarantine the tool |
| Kill switch | One API call disables a tool/server/tenant fleet-wide, enforced from cache even under DB degradation |
| Policy explain & dry-run | Every decision names the policies that produced it; draft policies can be simulated before activation |

Full acceptance criteria for each capability: [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md).

## Getting started

Prerequisites: Docker Desktop, JDK 17+ (a Maven Wrapper is included, no local Maven install needed).

```bash
git clone <this-repo>
cd spring-mcp-gateway
docker compose up -d        # Postgres (5433), Redis (6380), Keycloak (8081) with a pre-seeded demo realm
./mvnw spring-boot:run       # starts the gateway (once the AuthN module lands, see status below)
```

The Keycloak demo realm (`mcp-gateway`) ships with three users you can use to obtain tokens once AuthN is wired up: `admin` (platform-admin), `bob` (tenant-admin/tool-approver), `alice` (tool-user) — see `docker/keycloak/mcp-gateway-realm.json`.

## Implementation status

- [x] Project skeleton (Maven, hexagonal package layout)
- [x] Local infra (Docker Compose: Postgres, Redis, Keycloak + demo realm)
- [x] Requirements locked (`docs/REQUIREMENTS.md`)
- [ ] OIDC AuthN end-to-end
- [ ] MCP server/tool registry + JSON-RPC proxy
- [ ] Custom RBAC/ABAC policy engine
- [ ] Rate limiting, guardrail PoC, approval PoC, tenancy RLS
- [ ] Observability stack, Admin API, integration tests
- [ ] ADRs, demo script, v1.0.0 tag

## Documentation

- [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) — full functional/non-functional requirements and v1.0.0 Definition of Done
- `docs/adr/` — architecture decision records (coming with implementation)

## License

Apache License 2.0 (recommended default for security/infra OSS — matches Stacklok/ToolHive's licensing, includes an explicit patent grant). Not yet finalized — confirm before first public push.
