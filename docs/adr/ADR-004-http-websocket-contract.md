# ADR-004: HTTP/WebSocket Contract and Version Negotiation

- Status: Decision required
- Date: 2026-08-30
- Requirements: `DEV-003`--`DEV-010`, `SES-004`, `NET-001`--`NET-006`, `NFR-006`, `NFR-007`, `NFR-012`,
  `CLI-005`, `CLI-014`--`CLI-018`, `CLI-060`--`CLI-068`

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

## Options

| Decision | Option A | Option B | Pilot recommendation |
| --- | --- | --- | --- |
| Metadata | HTTP `GET /v1/metadata` returning protocol/capabilities and court identity. | Put metadata only in the WebSocket greeting. | A: fail incompatibility before a socket is opened. |
| Pairing | HTTP request plus realtime status update. | WebSocket-only pairing messages. | A: simple pending/approve/reject lifecycle with a clear polling fallback. |
| Realtime transport | One authenticated WebSocket per paired client. | Repeated HTTP polling. | WebSocket: required for timely session state, ACK, heartbeat, and reconnect. |
| Compatibility | Major protocol equality plus required capability set. | Exact client/server build equality. | Major version plus capabilities; supports compatible patch releases. |
| ACK | Typed ACK/rejection envelope with event ID and reason code. | Infer acceptance from a later score snapshot. | Typed ACK; snapshots remain the source for displayed score. |

## Product-Owner Decisions

1. **Protocol versioning:** approve semantic protocol versions with matching major version and negotiated capabilities, or
   specify a stricter compatibility rule.
2. **Required metadata:** approve the minimum response fields: protocol version, capability list, peer/court ID, server name,
   pairing policy, and server time. Add any privacy or operator-display fields required by Pilot.
3. **Pairing approval:** choose whether every device needs explicit operator approval, whether an approved device can reconnect
   without reapproval, and how operator revocation is surfaced to the judge.
4. **Identity and credentials:** choose the client credential lifetime and rotation behavior. Credentials must use platform
   secure storage and must not be logged or kept in plain preferences.
5. **Realtime envelope:** approve message families for handshake, pairing status, session snapshot, command/event, ACK,
   rejection, heartbeat, resync request/response, and server notice.
6. **Final ACK semantics:** choose whether a score event is terminally accepted only after durable journal commit (recommended)
   or after receipt by the transport. The latter is not acceptable for official scoring.
7. **Resync:** approve cursor-based resync after reconnect, including the rule that client controls remain disabled until the
   active session snapshot is current.
8. **Clock offset:** approve an HTTP/handshake time exchange that produces an offset estimate and its allowed error bound for
   the Kerugi coincidence window.
9. **Security transport:** align this with ADR-002: TLS with local trust management, or authenticated plaintext documented as
   an isolated-LAN Pilot limitation.

## Required Acceptance Evidence

ADR-004 can be accepted only after contract/integration tests prove:

- incompatible protocol version and missing required capability are rejected before online state;
- unpaired and revoked clients cannot write events;
- pairing pending, accepted, rejected, and reconnect states are observable;
- duplicate event retry returns the original terminal outcome without a second application;
- disconnect/reconnect performs resync before controls re-enable;
- malformed or oversized HTTP/WebSocket payloads are rejected with typed error codes.

## Decision Record

| Field | Approved value |
| --- | --- |
| Metadata endpoint and fields | _Pending product-owner decision_ |
| Version/capability rule | _Pending product-owner decision_ |
| Pairing and revocation policy | _Pending product-owner decision_ |
| Credential lifecycle | _Pending product-owner decision_ |
| Realtime message families | _Pending product-owner decision_ |
| Durable ACK rule | _Pending product-owner decision_ |
| Reconnect/resync rule | _Pending product-owner decision_ |
| Clock-offset method and bound | _Pending product-owner decision_ |
| Transport security | _Pending product-owner decision_ |
