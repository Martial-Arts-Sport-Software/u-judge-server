# ADR-002: P2P Discovery, Join, and Anti-Entropy

- Status: Accepted
- Date: 2026-08-31
- Requirements: `CMP-001`--`CMP-007`, `P2P-001`--`P2P-012`, `NFR-003`, `NFR-005`, `NFR-006`, `NFR-013`

## Context

U'Judge v1 Pilot requires up to eight equal peers in an isolated LAN. Every peer must retain the full event journal, continue
its locally owned brackets during a partition, and converge after reconnect without a central coordinator. Each peer may
also serve as a consensus node for ownership claims. ADR-001 has
defined the Stage 1 event envelope and its idempotency/sequence rules, but it deliberately does not choose peer discovery,
competition join, transport, or anti-entropy.

The current in-memory spike proves three-peer convergence, duplicate delivery, restart recovery, and sequence-gap detection.
It does not prove a network protocol, durable cursors, peer authorization, or bracket-owner validation.

## Non-Negotiable Constraints

- A central coordinator is not permitted for Pilot.
- The peer backbone and court client networks are separate logical networks. A peer must have access to both its local
  court network and the shared peer network.
- The preferred physical layout is Ethernet plus Wi-Fi, or two Wi-Fi interfaces. A Wi-Fi-only peer backbone is supported
  as a lower-reliability deployment option.
- A bracket has at most one owner while it is `IN_PROGRESS`; a non-owner must not change it. Ownership is released only by
  an explicit state transition authorized by the consensus protocol.
- Replication is at-least-once and must deduplicate by stable event ID.
- Missing per-owner sequences are requested and remain unapplied until the gap closes.
- Joining requires a competition code. Anonymous peers must not receive competition or personal data.
- The solution must work without WAN access on Windows and macOS.

## Options

| Option | Description | Advantages | Risks | Pilot fit |
| --- | --- | --- | --- | --- |
| A. Direct LAN mesh with leader claims | Peers discover a bootstrap peer through mDNS, join with a competition code, then keep direct authenticated WebSocket connections to known peers. A stable elected leader atomically confirms bracket claims; anti-entropy exchanges per-owner cursors and requests missing ranges. | No single coordinator; simple at 8 peers; matches ADR-001. | Leader election and claim recovery need care. | Recommended baseline. |
| B. Bootstrap-only relay | One peer accepts all replication traffic and relays it to others. | Simplest initial transport. | Acts as a coordinator and becomes an availability dependency. | Rejected by `P2P-001`. |
| C. Database replication | PostgreSQL replicates peer data directly. | Database-level synchronization. | Does not model ownership, command validation, or event conflict semantics; difficult offline lifecycle. | Rejected for Pilot. |

## Decision

Pilot uses two logical network planes. The shared peer network connects desktop peers for discovery, consensus, replication,
and operator traffic. Each peer separately exposes its court network to the mobile clients assigned to that court. The
preferred physical deployment is Ethernet plus Wi-Fi or two Wi-Fi interfaces; a Wi-Fi-only peer backbone is allowed but is
less reliable.

Peers use direct authenticated WebSocket mesh replication. Desktop peers discover bootstrap peers through mDNS on the peer
network, with a manual host/IP entry fallback. A peer joins only with an operator-created competition code and explicit
operator approval.

Each desktop instance has a generated stable UUID for replication identity and a per-competition credential. During
competition setup, the operator assigns the instance a display name and the responsible referee's name/surname. This
assignment is competition metadata and an audit-journal record, not the technical peer ID. Every peer stores the same
bracket list and state (`AVAILABLE`, `IN_PROGRESS`, or `DONE`).

The peers elect a stable leader independently of bracket ownership. Pilot uses deterministic election: among the peers that
can communicate in the current peer-network membership view, the peer with the lowest stable UUID is the candidate leader.
The candidate becomes leader only after receiving acknowledgements from a majority quorum (`floor(peerCount / 2) + 1`) and
publishes a monotonically increasing leader term. A claim must include the current term and quorum-backed membership view;
claims without a valid leader quorum are rejected or remain pending and must not be applied.

The leader is a consensus node, not a competition coordinator: it atomically confirms `TAKE` claims and does not process or
own all competition events. A successful claim sets `ownerPeerId`; the owning peer performs the bracket work and replicates
its events to all peers. Leader election is not re-run for every claim, and a peer may be leader whether or not it owns an
active bracket. If the leader is lost, existing owners continue their brackets, while new claims wait until a new majority
quorum elects the next deterministic leader.

Peers exchange per-owner high-water cursors and replay only explicit missing sequence ranges. TLS with a locally managed
certificate/trust flow protects all peer traffic. Revoking a peer immediately terminates its connections and rejects future

## Acceptance Evidence

ADR-002 can be accepted only when a multi-process test or prototype proves:

- join rejection for a missing or invalid competition code;
- three peers discover, join, and exchange events without a permanent coordinator;
- a partition followed by reconnect converges all event IDs and projections;
- duplicate delivery and missing-range replay are safe;
- a non-owner event is rejected without changing the bracket projection.
- a leader confirms at most one concurrent `TAKE`, leader loss is handled without split-brain ownership, and existing owners
  can continue their brackets while new claims are unavailable.
- deterministic UUID election chooses the same leader for a membership view, majority quorum is enforced, and stale leader
  terms cannot confirm a claim.

## Decision Record

| Field | Approved value |
| --- | --- |
| Peer identity | Generated stable desktop-peer UUID and per-competition credential; competition-scoped operator assignment of display name and referee name/surname. |
| Join and approval flow | Operator-created competition code plus explicit operator approval for every new peer. |
| Discovery mechanism | mDNS bootstrap discovery with manual host/IP fallback. |
| Network planes | Shared peer network for peer-to-peer traffic; separate court network for clients of each court. |
| Physical network preference | Ethernet plus Wi-Fi or two Wi-Fi interfaces; Wi-Fi-only peer backbone is supported with lower reliability. |
| Replication topology | Direct authenticated WebSocket peer-network mesh, maximum eight peers; all peers retain the full state. |
| Leader and claims | Lowest stable UUID wins deterministic election after majority quorum (`floor(n / 2) + 1`); monotonically increasing term is required for atomic `TAKE`; leader is not a central competition coordinator and is not re-elected per claim. |
| Anti-entropy protocol | Per-owner high-water cursors, explicit missing-range request, and at-least-once range replay. |
| Transport security | TLS with locally managed certificate and trust flow. |
| Peer revocation behavior | Immediately disconnect and reject future events; retain local historical copy without further replication authority. |
