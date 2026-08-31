# ADR-002: P2P Discovery, Join, and Anti-Entropy

- Status: Decision required
- Date: 2026-08-30
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

## Product-Owner Decisions

Answer each item before ADR acceptance. The recommended baseline is provided only to make the choice concrete.

1. **Peer identity:** approve a generated stable peer UUID plus a locally stored per-competition credential, or require an
   alternative identity model? Recommended: generated UUID and credential; no user-account system in Pilot.
2. **Competition join:** approve a one-time/operator-created competition code for peer enrollment? Specify whether a code
   expires and whether an operator must approve every peer.
3. **Discovery:** approve mDNS only as bootstrap discovery in the selected LAN, with a visible manual retry flow but no manual
   IP entry? If manual IP is required, specify its Pilot UX and security constraints.
4. **Topology:** approve direct authenticated WebSocket mesh for up to eight peers? Connections may be initiated by either
   peer; no peer is special after join.
5. **Anti-entropy:** approve a per-owner high-water cursor summary plus explicit missing-range requests and at-least-once
   range replay? Full-journal exchange remains acceptable only for the spike, not the Pilot protocol.
6. **Transport security:** choose one of the following:
   - [ ] TLS with a locally managed certificate/trust flow.
   - [ ] Authenticated plaintext only in the isolated Pilot LAN, documented as a Pilot limitation.
   - [ ] Another model: _describe it_.
7. **Peer removal:** define whether revocation immediately terminates connections and whether a revoked peer retains its local
   historical copy. The system must reject its future events.

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
| Peer identity | _Pending product-owner decision_ |
| Join and approval flow | _Pending product-owner decision_ |
| Discovery mechanism | _Pending product-owner decision_ |
| Replication topology | _Pending product-owner decision_ |
| Anti-entropy protocol | _Pending product-owner decision_ |
| Transport security | _Pending product-owner decision_ |
| Peer revocation behavior | _Pending product-owner decision_ |
