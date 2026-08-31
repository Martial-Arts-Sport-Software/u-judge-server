# ADR-002: P2P Discovery, Join, and Anti-Entropy

- Status: Accepted
- Date: 2026-08-31
- Requirements: `CMP-001`--`CMP-007`, `P2P-001`--`P2P-011`, `NFR-003`, `NFR-005`, `NFR-006`, `NFR-013`

## Context

U'Judge v1 Pilot requires up to eight equal peers in an isolated LAN. Every peer must retain the full event journal, continue
its locally owned brackets during a partition, and converge after reconnect without a central coordinator. ADR-001 has
defined the Stage 1 event envelope and its idempotency/sequence rules, but it deliberately does not choose peer discovery,
competition join, transport, or anti-entropy.

The current in-memory spike proves three-peer convergence, duplicate delivery, restart recovery, and sequence-gap detection.
It does not prove a network protocol, durable cursors, peer authorization, or bracket-owner validation.

## Non-Negotiable Constraints

- A central coordinator is not permitted for Pilot.
- A competition bracket has one immutable owner after assignment; a non-owner must not change it.
- Replication is at-least-once and must deduplicate by stable event ID.
- Missing per-owner sequences are requested and remain unapplied until the gap closes.
- Joining requires a competition code. Anonymous peers must not receive competition or personal data.
- The solution must work without WAN access on Windows and macOS.

## Options

| Option | Description | Advantages | Risks | Pilot fit |
| --- | --- | --- | --- | --- |
| A. Direct LAN mesh | Peers discover a bootstrap peer through mDNS, join with a competition code, then keep direct authenticated WebSocket connections to known peers. Anti-entropy exchanges per-owner cursors and requests missing ranges. | No coordinator; simple at 8 peers; matches ADR-001. | Connection count grows with peer count; membership changes need care. | Recommended baseline. |
| B. Bootstrap-only relay | One peer accepts all replication traffic and relays it to others. | Simplest initial transport. | Acts as a coordinator and becomes an availability dependency. | Rejected by `P2P-001`. |
| C. Database replication | PostgreSQL replicates peer data directly. | Database-level synchronization. | Does not model ownership, command validation, or event conflict semantics; difficult offline lifecycle. | Rejected for Pilot. |

## Decision

Pilot uses direct authenticated WebSocket mesh replication. Desktop peers discover bootstrap peers through mDNS, with a
manual host/IP entry fallback. A peer joins only with an operator-created competition code and explicit operator approval.

Each desktop instance has a generated stable UUID for replication identity and a per-competition credential. During
competition setup, the operator assigns the instance a display name and the responsible referee's name/surname. This
assignment is competition metadata and an audit-journal record, not the technical peer ID.

Peers exchange per-owner high-water cursors and replay only explicit missing sequence ranges. TLS with a locally managed
certificate/trust flow protects all peer traffic. Revoking a peer immediately terminates its connections and rejects future

## Acceptance Evidence

ADR-002 can be accepted only when a multi-process test or prototype proves:

- join rejection for a missing or invalid competition code;
- three peers discover, join, and exchange events without a permanent coordinator;
- a partition followed by reconnect converges all event IDs and projections;
- duplicate delivery and missing-range replay are safe;
- a non-owner event is rejected without changing the bracket projection.

## Decision Record

| Field | Approved value |
| --- | --- |
| Peer identity | Generated stable desktop-peer UUID and per-competition credential; competition-scoped operator assignment of display name and referee name/surname. |
| Join and approval flow | Operator-created competition code plus explicit operator approval for every new peer. |
| Discovery mechanism | mDNS bootstrap discovery with manual host/IP fallback. |
| Replication topology | Direct authenticated WebSocket LAN mesh, maximum eight peers, no special peer after join. |
| Anti-entropy protocol | Per-owner high-water cursors, explicit missing-range request, and at-least-once range replay. |
| Transport security | TLS with locally managed certificate and trust flow. |
| Peer revocation behavior | Immediately disconnect and reject future events; retain local historical copy without further replication authority. |
