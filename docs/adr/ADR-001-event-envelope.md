# ADR-001: Event Envelope, Sequence and Idempotency

- Status: Accepted for the Stage 1 P2P spike
- Date: 2026-08-30
- Requirements: `SYS-008`, `SYS-009`, `P2P-001`--`P2P-005`, `P2P-011`

## Context

The first P2P spike must prove that independent peers can exchange events at-least-once after a network partition without
duplicating a projection or silently applying an incomplete per-peer sequence. At this stage PostgreSQL, peer discovery,
authorization and the network transport are not selected.

## Decision

The spike uses an append-only event envelope with these fields:

| Field | Meaning |
|-------|---------|
| `id` | Globally unique UUID used for idempotent delivery |
| `ownerPeerId` | Stable globally unique peer identifier that owns the sequence |
| `sequence` | Positive, monotonically increasing sequence number within `ownerPeerId` |
| `payload` | Raw input retained by the journal; the spike treats it as an opaque string |

Each peer retains every accepted event ID. Re-delivery of an identical envelope is ignored; reuse of an ID or an
`ownerPeerId`/`sequence` pair with different data is rejected. Peers exchange their journal entries in both directions.

A projection only uses the contiguous prefix beginning at sequence `1` for each owner. Higher events remain in the journal,
Projection order is deterministic: owners sort lexicographically, then their event sequences sort ascending.

The spike accepts an existing event collection on construction to model journal recovery after restart. Production recovery
will load the same envelope from PostgreSQL rather than process memory.

## Consequences

- The test fixture proves three-peer convergence after reconnect, duplicate delivery, restart from a journal snapshot and
  sequence-gap detection without applying the out-of-order event.
- Anti-entropy currently exchanges the full in-memory journal. Range requests, durable cursors and a network protocol remain
  future work under `P2P-004`, `P2P-010` and the Stage 2 persistence scope.
- The envelope does not yet carry competition, bracket, session, author, timestamp or ownership validation fields. Those
  fields must be added before domain commands are persisted or projected, per `SYS-007`, `AUD-001` and `P2P-006`.
- This ADR does not choose mDNS discovery, transport, authentication or PostgreSQL packaging. Those choices remain in
  ADR-002 and ADR-003 after their respective spikes.
