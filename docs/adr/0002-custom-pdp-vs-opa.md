# ADR-0002: Custom Java policy engine instead of embedding OPA

**Status**: accepted · 2026-07-16

## Context
Authorization needs RBAC + ABAC with deny-by-default, explicit-deny-wins, explanations, and
dry-run. Open Policy Agent (Rego) is the industry default and what Stacklok uses; embedding it
would be faster and production-proven.

## Decision
Build the PDP in plain Java (`PolicyDecisionPoint`): versioned JSON policy documents, glob
resources, ANDed conditions, AWS-IAM-style combining. No framework dependencies in the
evaluator, so its semantics are exhaustively unit-tested (10 tests map to FR-AUTHZ-2/3/5).

## Rationale
- This project's purpose is demonstrating design ability; an owned evaluator is defensible
  line-by-line, an embedded engine is configuration.
- Explanations and draft-overlay simulation (FR-AUTHZ-5/6) fall out of owning the evaluation
  loop; with OPA they require extra tooling around `decision logs`.
- The PEP calls a narrow interface; an OPA-backed implementation could replace the PDP behind
  it if a deployment demands Rego compatibility (documented migration path, not built).

## Consequences
- We own correctness: mitigated by the test suite and deliberately small operator set.
- No Rego ecosystem (bundles, partial eval). Accepted for v1 scope.
