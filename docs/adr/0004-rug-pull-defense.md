# ADR-0004: Manifest pinning with per-call verification and quarantine

**Status**: accepted · 2026-07-16

## Context
The MCP "rug-pull" attack: a backend changes an approved tool's description or schema after
registration; the new text reaches the LLM as trusted instructions. Defenses range from
"verify at registration only" (cheap, blind afterwards) to "verify on every call" (safe,
doubles backend traffic).

## Decision
- Pin a length-prefixed SHA-256 over (name, description, inputSchema) at registration.
  Length-prefixing prevents field-boundary forgeries (`("ab","c")` vs `("a","bc")`).
- On every `tools/call`, fetch the backend's live `tools/list` and compare. Mismatch or a
  missing tool → automatic quarantine + audit event; recovery only via an explicit admin
  re-approval that re-pins from the live definition.
- An **unreachable** backend denies the call (fail closed) but does *not* quarantine: a network
  blip is not an attack and must not force a human re-approval cycle.

## Consequences
- One extra backend round trip per call. Accepted for v1 correctness-first; roadmap documents
  a short-TTL verification cache as the optimization (verify at most once per N seconds per
  tool), which keeps the drift-detection window bounded and configurable.
- Arguments-schema validation intentionally uses the *pinned* schema, never the live one — the
  reviewed manifest is the contract.
