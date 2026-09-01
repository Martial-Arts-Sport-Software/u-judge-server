# ADR-004: HTTP/WebSocket Contract and Version Negotiation

- Status: Accepted
- Date: 2026-08-31
- Requirements: `DEV-003`--`DEV-010`, `SES-004`, `NET-001`--`NET-006`, `NFR-006`, `NFR-007`, `NFR-012`,
  `KER-003`, `KER-005`, `KER-009`, `CLI-005`, `CLI-014`--`CLI-018`, `CLI-060`--`CLI-068`

## Context

Selecting an mDNS service must not grant online access. A mobile client must validate server metadata and protocol
compatibility, complete a pairing flow, and receive an explicit session before it can send judging events. The client must
persist outgoing events before transmission, preserve their IDs across retry, and receive a terminal ACK or rejection.

The current server mDNS publisher and client discovery UI are not a Pilot protocol. The current `POST /score` is a stub and
must not become the production write endpoint.

## Non-Negotiable Constraints

- The protocol rejects anonymous state-changing requests.
- HTTP metadata validation, protocol version/capabilities, pairing, and realtime connection state are distinct steps.
- Every realtime event has stable event ID, client sequence, client timestamp, session ID, and typed payload.
- Retries preserve the original event ID; server-side application is idempotent.
- Reconnect resynchronizes state before new judging actions become available.
- Errors are typed and localizable; payload sizes and schemas are limited before business logic.
- Coincidence evaluation uses client event timestamps corrected to server time, never WebSocket arrival order.

## Options

| Decision | Option A | Option B | Pilot recommendation |
| --- | --- | --- | --- |
| Metadata | HTTP `GET /v1/metadata` returning protocol/capabilities and court identity. | Put metadata only in the WebSocket greeting. | A: fail incompatibility before a socket is opened. |
| Pairing | HTTP request plus realtime status update. | WebSocket-only pairing messages. | A: simple pending/approve/reject lifecycle with a clear polling fallback. |
| Realtime transport | One authenticated WebSocket per paired client. | Repeated HTTP polling. | WebSocket: required for timely session state, ACK, heartbeat, and reconnect. |
| Compatibility | Major protocol equality plus required capability set. | Exact client/server build equality. | Major version plus capabilities; supports compatible patch releases. |
| ACK | Typed ACK/rejection envelope with event ID and reason code. | Infer acceptance from a later score snapshot. | Typed ACK; snapshots remain the source for displayed score. |

## Decision

The client validates `GET /v1/metadata` before online state. It returns the protocol version, capabilities, desktop peer and
court IDs, server name, pairing policy, and server time. Compatibility requires the same protocol major version and all
required capabilities.

Every new mobile device requires explicit operator approval. Approval issues a reconnect credential stored in platform secure
storage; it remains valid until revocation or rotation. The realtime protocol uses one authenticated WebSocket per paired
client and typed messages for handshake, pairing status, session snapshot, command/event, ACK, rejection, heartbeat,
resync request/response, and server notice. TLS uses the local certificate/trust flow selected in ADR-002.

The current server slice keeps approval in a transport-agnostic local operator application service. It transitions a pending
request to accepted idempotently, issues an opaque reconnect credential and revokes that credential idempotently, without
exposing an anonymous LAN approval endpoint. `/v1/realtime` accepts a versioned WebSocket handshake only for an active
credential and emits a typed rejection for unknown, revoked and incompatible-version requests. Credential delivery to
Android/iOS secure storage, persistent device state, reconnect/resync and heartbeat remain unimplemented.

The authenticated connection accepts a bounded typed command envelope with an event ID, sequence, client timestamp, session
ID and typed payload. It rejects malformed or oversized payloads and rechecks a credential before every command so
post-handshake revocation blocks writes. Without a journal, its in-memory receipt store returns the original typed ACK when
the identical event ID is retried, retains at most 1,024 receipts, and rejects new IDs at that limit while continuing to
acknowledge known retries. When configured with `JdbcPeerJournal`, a validated command is appended before its ACK, using the
client event ID as the journal ID. A recreated command handler returns an ACK for an identical persisted envelope, rejects a
different envelope with the same ID, and returns a typed rejection if persistence fails. This optional adapter does not wire
a desktop datasource, authorize or apply scoring.

An authenticated connection also accepts `clock_sync` with an ISO-8601 UTC client send timestamp. Its typed response echoes
that timestamp with server receive and send timestamps in UTC, giving the client the timestamps required for its own
four-timestamp offset calculation. An invalid client timestamp receives a typed rejection and does not close the connection.
This exchange does not implement heartbeat, telemetry, scoring, or coincidence evaluation.

An event receives a terminal ACK only after durable journal commit. After reconnect, cursor-based resync completes and the
active session snapshot is current before scoring controls re-enable. A four-timestamp exchange estimates the client/server
clock offset and round-trip time.

For Kerugi, the default coincidence window is `1000 ms`. For the same participant, all valid score candidates in one window
resolve deterministically to the minimum candidate score, regardless of arrival order. For example, `1`-point and `2`-point
candidates resolve to `1` point. All source events and the resolution remain available to audit. The clock-quality threshold
is a telemetry-validated implementation setting; it must not alter the domain coincidence window.

## Required Acceptance Evidence

ADR-004 can be accepted only after contract/integration tests prove:

- incompatible protocol version and missing required capability are rejected before online state;
- unpaired and revoked clients cannot write events;
- pairing pending, accepted, rejected, and reconnect states are observable;
- duplicate event retry returns the original terminal outcome without a second application;
- disconnect/reconnect performs resync before controls re-enable;
- malformed or oversized HTTP/WebSocket payloads are rejected with typed error codes.
- clock sync echoes a valid client send timestamp with UTC server receive/send timestamps, and an invalid timestamp is rejected
  without preventing a later valid command on the authenticated connection.
- score candidates for the same participant in either arrival order and within `1000 ms` resolve to their minimum score,
  with all candidates and the resolution retained for audit; this includes a `1`-point plus `2`-point conflict resolving to
  `1` point.

## Decision Record

| Field | Approved value |
| --- | --- |
| Metadata endpoint and fields | `GET /v1/metadata`: protocol version, capabilities, desktop peer/court ID, server name, pairing policy, and server time. |
| Version/capability rule | Same protocol major version plus all required capabilities. |
| Pairing and revocation policy | Explicit operator approval for every new device; revoked devices cannot write. |
| Credential lifecycle | Reconnect credential in platform secure storage, valid until revocation or rotation. |
| Realtime message families | Handshake, pairing status, session snapshot, command/event, ACK, rejection, clock sync request/response/rejection, heartbeat, resync request/response, server notice. |
| Durable ACK rule | Terminal ACK after durable journal commit only. |
| Reconnect/resync rule | Cursor-based resync and current active-session snapshot before scoring controls re-enable. |
| Clock-offset method and bound | `clock_sync` echoes the ISO-8601 UTC client send timestamp with UTC server receive/send timestamps; the client calculates the four-timestamp offset/round-trip estimate. The telemetry-validated quality threshold does not change the `1000 ms` coincidence window. |
| Kerugi coincidence conflict | Same-participant score candidates in one `1000 ms` window resolve to the minimum score, regardless of arrival order; retain all candidates and the resolution for audit. |
| Transport security | TLS with locally managed certificate and trust flow. |
