# Contributing & Development Conventions

This document defines the git, versioning, and release conventions for this repository. They apply to all commits — including solo work — so the history stays readable and reviewable.

## Commit style: Conventional Commits

Every commit message follows [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <imperative, lower-case summary>

[optional body: what and why, wrapped at ~72 chars]
```

**Types**: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `perf`.

**Scopes** match the module packages: `authn`, `authz`, `registry`, `gateway`, `audit`, `guardrail`, `ratelimit`, `secrets`, `approval`, `tenancy`, plus `infra` (Docker/compose), `build` (Maven/CI), and `docs`.

Examples:

```
feat(authz): add deny-by-default policy evaluation
feat(registry): pin tool manifest hash at registration (FR-REG-5)
fix(gateway): propagate tenant claim to audit context
docs: add ADR-0002 custom PDP vs OPA
test(authz): cover explicit-deny-beats-allow (FR-AUTHZ-3)
```

Rules:
- One coherent, working unit of change per commit — the build must be green after every commit.
- Reference requirement IDs (`FR-…`) from `docs/REQUIREMENTS.md` in the summary or body when a commit implements or tests one.
- No "WIP", "misc fixes", or bundled unrelated changes.

## Branching: trunk-based with short-lived feature branches

- `main` is always releasable; direct commits to `main` are limited to `docs`/`chore` trivia.
- Each module or feature gets a short-lived branch: `feat/authn-oidc`, `feat/policy-engine`, `fix/audit-tenant-scope`.
- Branches merge to `main` via pull request. CI (`mvn verify`) must pass before merge. Squash only when the branch history is noisy; otherwise merge with history intact.

## Versioning: SemVer, tagged per phase

Semantic Versioning, starting at `0.1.0`. Planned tags:

| Tag | Milestone |
|---|---|
| `v0.1.0` | Skeleton + Docker Compose infra + locked requirements |
| `v0.2.0` | OIDC AuthN end-to-end |
| `v0.3.0` | Registry + MCP JSON-RPC proxy (incl. manifest pinning, kill switch) |
| `v0.4.0` | Custom policy engine (incl. explain, dry-run) |
| `v0.5.0` | Rate limiting, guardrails, approvals, tenancy RLS |
| `v0.6.0` | Observability + Admin API + integration tests |
| `v1.0.0` | Docs/ADRs complete; Definition of Done in `docs/REQUIREMENTS.md` §8 met |

## Changelog

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/): one section per tag, grouped under Added/Changed/Fixed/Removed. Updated manually as part of each release commit (`chore(release): v0.x.0`).

## Code style

- **Javadoc**: every public class and public/protected method gets Javadoc stating *what it does and why it exists* — its contract (params, return, thrown exceptions, invariants), not a restatement of the signature. Private helpers get Javadoc only when the logic isn't self-evident.
- **Inline comments**: only where the code can't speak for itself — a non-obvious constraint, a security consideration, a deliberate trade-off (e.g. "deny-by-default: absence of policy is a deny, not an error"). Never narrate what the next line does.
- **Method design**: small, single-responsibility methods with intention-revealing names; no boolean-flag parameters that change behavior; validate arguments at public boundaries.
- **Logging**: SLF4J (`private static final Logger log = LoggerFactory.getLogger(X.class)`), never `System.out`. Use parameterized messages (`log.info("policy {} denied tool {}", id, tool)`), not string concatenation. Levels:
  - `ERROR` — request failed for an unexpected reason (backend unreachable, audit write crashed)
  - `WARN` — suspicious but handled (quarantined tool called, rate limit exceeded, token rejected)
  - `INFO` — lifecycle and security-relevant decisions (server registered, kill switch flipped, policy activated)
  - `DEBUG` — per-request pipeline detail (policy evaluation trace, cache hits)
  - Never log secrets, tokens, or full tool payloads; log IDs and hashes instead.
- Security decisions (allow/deny/quarantine/kill) are *audited*, not merely logged — the audit record is the source of truth; the log line is operator convenience.

## History integrity

Commit timestamps and history are never rewritten to misrepresent when or how work happened. `git rebase` is fine for cleaning up an unmerged feature branch; rewriting published history on `main` is not.
