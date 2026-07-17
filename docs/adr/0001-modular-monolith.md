# ADR-0001: Modular monolith with hexagonal boundaries, not microservices

**Status**: accepted · 2026-07-16

## Context
The gateway has ~10 concerns (authn, authz, registry, gateway/proxy, audit, guardrail,
ratelimit, approval, tenancy, secrets). Comparable products (Kong, Stacklok) ship as a single
deployable. A solo-built portfolio project must optimize for demonstrable design quality per
hour, not for organizational scaling problems it doesn't have.

## Decision
One Spring Boot deployable, internally split into packages with hexagonal (ports & adapters)
boundaries: each module owns its domain logic; infrastructure (Postgres, Redis, Keycloak, HTTP)
sits behind interfaces like `RegistryEvents`. Cross-module calls go through service classes,
never repositories of another module.

## Consequences
- One process to run, test, and demo; module boundaries still show the same design maturity.
- The seams are already service interfaces + Redis pub/sub, so extracting a module (e.g. the
  policy engine as its own PDP service) later is a packaging change, not a redesign.
- Trade-off: no independent scaling per module. Acceptable — the stateless app scales
  horizontally as a whole (NFR-4), which is how the comparable gateways scale too.
