---
name: u-judge-domain
description: Use when changing U'Judge server domain models, scoring, sessions, events, audit, brackets, or exports.
---

# U'Judge Server Domain

Read `docs/REQUIREMENTS.md`, `docs/PROJECT.md`, and `docs/ROADMAP.md` before changing domain behavior.

## Non-negotiable invariants

- An append-only event journal is the source of truth. Do not mutate or delete historical scoring data.
- Event IDs are globally unique. Re-delivery must be idempotent and must not alter projections twice.
- Persist raw scoring inputs, author, source, competition, bracket, session, and timestamp; never persist only a calculated total.
- Projections must be deterministic and rebuildable from the journal.
- A bracket has exactly one immutable owning peer after assignment. Do not add offline transfer or ownership conflict resolution.
- Validate commands before appending an event. Rejected commands must not change state.

## Scoring rules

- Kerugi and Tanbon: `HEAD = 2`, `BODY = 1`; Tanbon `CROSS` is audit-only and does not change score.
- Kerugi quorum and coincidence window are session settings; default window is `1000 ms`.
- Technical criteria are `0.1..1.0` in `0.1` steps. Keep calculation precision at `0.1`.
- For technical scores, `Send` is final for a judge and cannot be overwritten for the same session.
- Server computes and validates every total independently from client-provided values.

## Implementation workflow

1. Map the change to requirement IDs and add or update acceptance tests.
2. Define command, event, projection, and rejection behavior before UI or transport wiring.
3. Keep application services transport-agnostic.
4. Add focused unit tests for valid, invalid, duplicate, and out-of-order inputs.

Do not invent rules absent from the requirements. Mark unresolved policy as an ADR decision.
