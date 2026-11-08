# ADR-009: Kerugi Bout Realtime Contract

- Status: Accepted
- Date: 2026-11-08
- Increment: I2a «Kerugi: бой по баллам» (`docs/ROADMAP.md`)
- Requirements: `KER-001`-`KER-009`, `KER-015`, `SES-001`, `SES-002`, `SES-004`-`SES-006`, `UI-002`, `UI-008`,
  `NET-001`, `NET-003`, `AUD-002`, `DEV-008`; client `CLI-022`-`CLI-025`, `CLI-030`, `CLI-032`-`CLI-038`,
  `CLI-060`-`CLI-062`, `CLI-064`-`CLI-066`, `CLI-102`
- Amends by reference: [ADR-004](ADR-004-http-websocket-contract.md) (realtime message families, resync, generic command);
  keeps the envelope rules of [ADR-001](ADR-001-event-envelope.md) and the court panel of
  [ADR-008](ADR-008-courts-numbering-protocols.md) §9-§10

## Context

ADR-004 fixed the realtime families (handshake, session snapshot, command, ACK, rejection, heartbeat, resync), but the
server never sends a snapshot, the client sends a Kerugi tap as a generic `command` with payload `kerugi_score` that the
server acknowledges without scoring, and `kerugi_score_command` requires competition, bracket, session, judge and device
IDs the client cannot know. Seven `Realtime*Commands` classes accept operator commands (lifecycle, Gamjeom, corrections,
timer, result) from any paired device. `resync_request` replays opaque generic commands by event ID instead of
restoring the bout.

I2a judges a bout of an imported, drawn bracket end to end. The server and the client are built in parallel, so this ADR
pins the wire contract before either side changes.

## Decision

### 1. Conventions

1. Messages are JSON objects with a string `type`, one per WebSocket text frame, over the authenticated `/v1/realtime`
   socket of ADR-004. Protocol version stays `1.0`; `GET /v1/metadata` adds the capability `"kerugi_bout": true`, which
   an I2a client requires.
2. Client messages are at most 4,096 characters (unchanged) and decoded strictly: a missing, extra or mistyped field is
   `invalid_command`. Server messages may gain fields and new `type` values; the client ignores unknown fields and
   unknown types.
3. Every field listed here is always present; a nullable field is sent as `null`, never omitted.
4. Domain IDs (`competitionId`, `peerId`, `courtId`, `bracketId`, `sessionId`, `judgeId`, `eventId`, `requestId`) are
   canonical lowercase UUIDs. `deviceId` is the opaque device ID from pairing. Enums of the scoring domain are
   upper case (`BLUE`, `RED`, `HEAD`, `BODY`); states and codes are lower snake case. Timestamps are ISO-8601 UTC with
   `Z`.
5. **Requests and pushes.** Before the first `resync_response` on a socket the server sends only replies, so the frame
   after a request is its reply. After it the socket is subscribed and the server also pushes `session_snapshot`,
   `session_state_updated` and `kerugi_score_updated` at any time. From then on the client reads all frames in one loop
   and routes them: command replies by `eventId`, resync replies by `requestId`, `heartbeat_ack` and
   `clock_sync_response` by type (one outstanding each), everything else as a push.
6. **Ordering.** On one socket the server sends frames in journal order. The reply to a command precedes every push
   that the command caused.
7. **Cursor.** `cursor` is a JSON integer: the per-peer journal sequence (ADR-001, single `domain_events` journal) of
   the latest event the message reflects. It survives a restart and only grows. Cursors skip values (other events share
   the sequence); a gap is not an error.

### 2. Bout, session and judge seats

- «Начать» on the court panel (ADR-008 §9) starts the selected bout: it creates a new `sessionId` in state `prepared`
  and journals one `kerugi_bout_started` event with the bout number, the sides, the settings and the roster. The bout
  number is frozen from then on (ADR-008 §6), and the first start of a category locks its draw (ADR-008 §10).
- **Roster.** The operator forms it from the devices connected at that moment: 2 or 3 seats, default all connected
  devices in seat order 1..n; fewer than 2 or more than 3 rejects the start. Each seat gets a `judgeId`. Quorum (default
  `2`) and coincidence window (default `1000` ms) are set before the start. Roster and settings are fixed for the whole
  bout (`KER-001`-`KER-003`).
- **Seat hold.** A seat belongs to the paired `deviceId`, not to a socket. A disconnect keeps it; on reconnect the
  device's snapshot shows the same seat and `judgeId`, so the judge returns to the seat. A revoked device cannot write;
  its seat stays empty until the bout ends. A connected device without a seat receives the bout read-only (`seat: null`).
- States follow `SES-001`: `prepared`, `running`, `paused`, `completed`, `cancelled` (the names «ready» and «finished»
  in planning notes are `prepared` and `completed`/`cancelled`). Only the operator changes them, on the desktop.

### 3. Snapshot

`session_snapshot` (push) and `resync_response` (reply) carry the same body: `cursor` and `session`. `session` is `null`
when the court has no bout yet. It is personalised: `seat` is the receiving device's seat.

```json
{
  "type": "session_snapshot",
  "cursor": 118,
  "session": {
    "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
    "discipline": "kerugi",
    "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
    "peerId": "00000000-0000-4000-8000-000000000001",
    "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
    "courtNumber": 1,
    "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40",
    "categoryTitle": "Юноши 12-14 лет, 45 кг",
    "boutNumber": 7,
    "state": "running",
    "blue": {
      "entryId": "entry-12",
      "label": "Иванов И. И. (Москва)",
      "athletes": [{ "fullName": "Иванов Иван Иванович", "city": "Москва" }]
    },
    "red": {
      "entryId": "entry-31",
      "label": "Петров П. П. (Казань)",
      "athletes": [{ "fullName": "Петров Пётр Петрович", "city": "Казань" }]
    },
    "settings": { "judgeCount": 3, "quorum": 2, "coincidenceWindowMillis": 1000 },
    "seat": {
      "number": 2,
      "judgeId": "2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e70",
      "deviceId": "android-contract"
    },
    "score": {
      "blue": 3,
      "red": 1,
      "gamjeom": { "blue": 0, "red": 1 },
      "warnings": [],
      "disqualified": null
    },
    "result": null
  }
}
```

```json
{ "type": "session_snapshot", "cursor": 57, "session": null }
```

- Blue is the upper slot of the bracket bout and red the lower one (ADR-008 §7). `label` is the display text the server
  formats; the client shows it as is and never builds a bout number or label itself (`DEV-008`, `CLI-023`, `CLI-024`).
- `score` is the authoritative projection: `blue`/`red` totals, effective Gamjeom per side, `warnings` and the confirmed
  disqualification (`"BLUE"`, `"RED"` or `null`). The client displays it and never computes quorum or totals
  (`CLI-034`).
- `warnings`: `{ "code": "disqualification_pending", "competitor": "RED", "gamjeomCount": 10 }` while 10 effective
  Gamjeom wait for the operator's confirmation (`KER-015`).
- `result`: `null`, or `{ "winner": "BLUE", "reason": "final_score" }` once the bout is `completed` (see the changes below
  for `disqualification`).
- The server pushes a new `session_snapshot` when a bout starts on the court (new `sessionId`) or the device's seat
  changes; the client replaces its whole session state with it.

### 4. `resync_request` / `resync_response` / `resync_rejected`

```json
{ "type": "resync_request", "requestId": "1e2d3c4b-5a69-4788-9a0b-c1d2e3f4a5b6", "cursor": 112 }
```

```json
{ "type": "resync_response", "requestId": "1e2d3c4b-5a69-4788-9a0b-c1d2e3f4a5b6", "cursor": 118, "session": { "...": "as in section 3" } }
```

```json
{ "type": "resync_rejected", "requestId": "1e2d3c4b-5a69-4788-9a0b-c1d2e3f4a5b6", "code": "journal_unavailable" }
```

- `cursor` in the request is the cursor of the last snapshot or update the client applied, or `null` after an app
  start. The response always carries the complete current snapshot, whatever the cursor: an older, equal, unknown or
  `null` cursor is not an error. The client replaces its state with the response even when its cursor is lower (journal
  restored from backup).
- After an applied snapshot the client drops a push whose `cursor` is not greater than the cursor it holds, and a
  `session_state_updated` or `kerugi_score_updated` for another `sessionId`; on such a mismatch it sends a new
  `resync_request`.
- The first `resync_response` subscribes the socket (section 1.5); a later one on the same socket only refreshes it.
- Codes: `invalid_resync_request` (malformed; `requestId` is `null` when unreadable), `journal_unavailable`
  (transient: retry with backoff, controls stay disabled).
- This replaces the ADR-004 resync that returned journaled generic commands after an event-ID cursor; its codes
  `invalid_resync_cursor` and `resync_unavailable` are gone.

### 5. `session_state_updated`

```json
{
  "type": "session_state_updated",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "cursor": 119,
  "state": "completed",
  "result": { "winner": "BLUE", "reason": "final_score" }
}
```

Sent to every subscribed socket after each operator transition (`SES-004`). `result` is `null` except for `completed`.

### 6. `kerugi_score_command`

One physical tap of a seated judge (`KER-004`, `CLI-030`):

```json
{
  "type": "kerugi_score_command",
  "eventId": "6f1c8a52-3c1e-4c55-9a3e-2b9f0d6e4a10",
  "sequence": 0,
  "clientTimestamp": "2026-11-08T10:15:30.120Z",
  "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
  "peerId": "00000000-0000-4000-8000-000000000001",
  "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
  "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "judgeId": "2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e70",
  "deviceId": "android-contract",
  "competitor": "BLUE",
  "area": "HEAD"
}
```

- `eventId`, `sequence` (client outbox sequence, `>= 0`) and `clientTimestamp` (tap time corrected by the `clock_sync`
  offset) are created once, before the outbox write, and never change on retry (`NET-001`, `NET-003`, `CLI-032`,
  `CLI-033`, `CLI-061`).
- **Audit context.** `competitionId`, `peerId`, `courtId`, `bracketId`, `sessionId`, `judgeId` and `deviceId` are copied
  from the snapshot that was current at the tap and stored with the outbox entry; a replay after the bout changed carries
  the old bout and is rejected instead of being attributed to the new one. The server checks every ID against the
  authenticated device's seat; it never trusts them. Source (`judge_device`), author and receive time are set by the
  server.
- The coincidence window uses `clientTimestamp`, never arrival order (ADR-004). The candidate is accepted while the bout
  is `running` or `paused`, so a tap just before «Пауза» that arrives after it is kept; a candidate whose timestamp lies
  outside a running period is journaled for audit but does not score (`KER-009`).

### 7. `judge_attention_command`

The judge's warning/attention signal, separate from scoring (`CLI-037`, `UI-006`); same envelope and audit context, no
scoring fields. Accepted in `prepared`, `running` and `paused`; journaled with judge, device, session and time; never
changes the score. The operator sees it on the desktop.

```json
{
  "type": "judge_attention_command",
  "eventId": "8b3e5d7f-1a2c-4e4b-9d6f-3c5e7a9b1d20",
  "sequence": 1,
  "clientTimestamp": "2026-11-08T10:15:41.500Z",
  "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
  "peerId": "00000000-0000-4000-8000-000000000001",
  "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
  "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "judgeId": "2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e70",
  "deviceId": "android-contract"
}
```

### 8. ACK and rejection

Every device command gets exactly one reply, in the existing shapes of ADR-004:

```json
{ "type": "command_ack", "eventId": "6f1c8a52-3c1e-4c55-9a3e-2b9f0d6e4a10" }
```

```json
{ "type": "command_rejected", "code": "session_closed", "eventId": "6f1c8a52-3c1e-4c55-9a3e-2b9f0d6e4a10" }
```

- `command_ack` is sent only after the event is committed to the journal. It means «recorded», not «scored»: whether
  the tap formed a point is visible only in `score` and the server audit. An identical retry of an acknowledged event
  gets the same `command_ack` and changes nothing (`NET-003`).
- `eventId` in `command_rejected` echoes the command when it can be read, else `null` (sent explicitly, section 1.3).

| Code | Meaning | Client |
|------|---------|--------|
| `invalid_command` | Malformed: missing/extra/mistyped field, non-canonical UUID, unknown enum, bad timestamp, negative sequence | Terminal |
| `command_too_large` | Over 4,096 characters (`eventId` is `null`) | Terminal |
| `unsupported_command` | Unknown `type`, including the deprecated generic `command` | Terminal |
| `command_not_permitted` | An operator command type sent by a judge device | Terminal |
| `event_id_conflict` | The `eventId` is journaled with different content | Terminal |
| `session_not_current` | `sessionId` is unknown, or the audit context does not match the court's current bout | Terminal |
| `session_not_started` | The bout is `prepared` | Terminal |
| `session_closed` | The bout is `completed` or `cancelled` | Terminal |
| `judge_not_assigned` | `deviceId` is not the authenticated device, or `judgeId` is not its seat in this bout | Terminal |
| `invalid_client_timestamp` | `clientTimestamp` is more than 5 s later than the server receive time | Terminal |
| `invalid_reconnect_credential` | The credential was revoked (ADR-004) | Terminal |
| `journal_unavailable` | The journal write failed | Transient: keep pending, retry the same `eventId` with backoff |

A terminal rejection is final for that event: the outbox marks it rejected, the client shows it and never resends it
(`CLI-066`). A rejected command changes no state; if it is resent anyway, the server evaluates it again.

### 9. `kerugi_score_updated`

```json
{
  "type": "kerugi_score_updated",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "cursor": 124,
  "score": {
    "blue": 5,
    "red": 1,
    "gamjeom": { "blue": 0, "red": 10 },
    "warnings": [{ "code": "disqualification_pending", "competitor": "RED", "gamjeomCount": 10 }],
    "disqualified": null
  }
}
```

Sent to every subscribed socket whenever the score projection of the current bout changes: judge candidates, operator
throws, spin bonuses and Gamjeom, corrections, the bout reset and the disqualification confirmation. `score` has the
shape of the snapshot's `score`.

### 10. Client behaviour on connect and reconnect

1. From the moment the socket is lost (or the app starts) combat controls are disabled.
2. Handshake, then `clock_sync` (unchanged, ADR-004).
3. Replay due outbox entries in sequence. No pushes arrive yet, so each frame after a command is its reply.
4. `resync_request` with the last applied cursor (or `null`); apply `resync_response`. The socket is now subscribed;
   switch to the routing loop of section 1.5.
5. Controls are enabled only while all hold: connected, a snapshot applied on this socket, `session` not `null`,
   `discipline` is `kerugi`, `state` is `running`, `seat` not `null` (`CLI-022`, `CLI-025`, `CLI-038`, `CLI-064`).
   They are disabled again on disconnect, a state other than `running`, a new session or `seat: null`.
6. Feedback per tap only from its reply: pending until `command_ack` or a terminal `command_rejected` for that `eventId`
   (`CLI-036`, `CLI-065`).

### 11. One dispatcher, two origins

The server has one command dispatcher. It reads `type`, checks that the origin may send it, decodes the typed command,
builds the domain command with server-derived source and author, applies it to the bout aggregate over the single
`domain_events` journal and sends the reply, then one publisher sends the pushes. A judge device (WebSocket) may send
`kerugi_score_command` and `judge_attention_command`, plus the non-command messages `clock_sync`, `heartbeat` and
`resync_request`. The operator acts in the desktop process through the same dispatcher: start bout, state transitions,
throw, spin bonus, Gamjeom, correction, bout reset with reason, disqualification confirmation and result; these are not
part of the device protocol.

### 12. Deprecated and removed device messages

| Message | Replacement |
|---------|-------------|
| Generic `command` (payloads `kerugi_score`, `tanbon_score`) and fixture `command_request` | `kerugi_score_command`; `unsupported_command` from I2a |
| `resync_request` with an event-ID cursor; `resync_response.events` | Section 4 |
| `session_lifecycle_command`, `kerugi_operator_action_command`, `kerugi_score_correction_command`, `kerugi_disqualification_command`, `kerugi_timer_command`, `kerugi_result_command` from a device | Desktop operator; `command_not_permitted` |
| `session_lifecycle_ack`/`_rejected`, `kerugi_score_ack`/`_rejected`, `kerugi_operator_action_ack`, `kerugi_score_correction_ack`, `kerugi_disqualification_ack`, `kerugi_timer_ack`/`_rejected`, `kerugi_result_ack`/`_rejected` | `command_ack` / `command_rejected` |
| `kerugi_score_updated` with `blueScore`, `redScore`, `disqualificationWarnings`, `disqualification` | Section 9 |
| `kerugi_timer_updated`, `kerugi_result_updated` | `session_state_updated.result`; the timer returns in I2b |

### 13. Room for I2b and I5

- **I2b (timer, bracket progression).** The session gains a `timer` object (period, running/paused, remaining time) and
  the push `kerugi_timer_updated`; `running`/`paused` follow the timer and the states keep their names. `result.reason`
  gains `golden_round`. The move to the next bout already arrives as a new `session_snapshot`; the snapshot may gain a
  `next` bout for the call screen.
- **I5 (Tanbon, technical disciplines).** `discipline` gets `tanbon` and the technical values. Tanbon reuses this
  pipeline with `tanbon_score_command` (same envelope and audit context; `area` `HEAD`, `BODY` or `CROSS`, where `CROSS`
  is journaled audit-only and never changes `score`). Technical disciplines send the final per-judge vector with a
  `Send` command (`technical_score_send_command`), and their snapshot carries a discipline-specific score object. A
  command for another discipline's session is rejected with `discipline_mismatch` (`TAN-005`).

## Contract fixtures

The server adds these to `server/src/test/resources/contract/v1`, with the JSON of this ADR, and `ContractFixturesTest`
checks them against the running module: `metadata_response` (`kerugi_bout` capability), `resync_request`,
`resync_response`, `resync_response_idle` (`session: null`), `resync_rejected`, `session_snapshot`,
`session_state_updated`, `kerugi_score_command`, `judge_attention_command`, `command_ack`, `command_rejected`,
`kerugi_score_updated`. `command_request` is removed. The client syncs them by bumping `contract/server-ref`.

## Consequences

- The client's request/response channel holds only until subscription; after it a routing reader is required.
- Pushes go only to subscribed sockets, so a device that never resyncs receives no pushes.
- An operator command can no longer be forged by a paired phone.
- Old clients that still send the generic `command` get terminal `unsupported_command` rejections for taps, which the
  server never scored anyway.

## Implementation (I2a)

- `RealtimeHub` is the single dispatcher and publisher: it decodes judge messages, applies them through
  `KerugiBoutService` with the judge origin and queues the reply and the pushes on each socket's ordered outbound queue.
  The desktop sends operator commands through the same hub (`CourtBouts`, `ServerRuntime.courtBouts`). The separate
  `Realtime*Commands`, their publishers, the generic `command` path and the per-type score, lifecycle and result journals
  are removed; the timer journal stays for I2b.
- `KerugiBout` is rebuilt from the session's events in `domain_events`: `kerugi_bout_started` (category, court and bout
  number, sides, seats with `judgeId`, paired device and surname, quorum, window), `session_started`/`_paused`/`_resumed`/
  `_completed` (with the result)/`_cancelled` (with the reason), `kerugi_score_candidate` (`clientTimestamp`, `sequence`),
  `kerugi_operator_action`, `kerugi_score_correction`, `kerugi_bout_reset` (reason and compensated event IDs),
  `kerugi_disqualification_confirmed`, `judge_attention_signalled`. Candidates outside a running period are journaled with
  the audit status «outside running period». The domain `deviceId` of a judge event is a name-based UUID of the paired
  device ID; the session ID and the judge IDs are name-based UUIDs of the start event ID, so an identical retry rebuilds
  the same event.
- «Начать» of the I4b court panel starts its next Kerugi bout: the roster must be connected now, the category's draw is
  locked on its first bout (`DrawEvent.Started`) after the start is validated, and the panel shows the bout as current and
  drops decided bouts from the queue (`CourtProgress`).
- Evidence: `KerugiBoutServiceTest`, `CourtBoutsTest`, `KerugiBoutScenarioTest` (three `JudgeSimulator` sockets over the
  production module and the JDBC journal), `ContractFixturesTest`, `ServerRuntimeTest`, `RealPostgresLifecycleTest`.

## Changes since the first version (d1ce2b4)

All are additive or clarify the first version; no field or code of it changed.

- `result.reason` can also be `disqualification`: finishing a bout after a confirmed disqualification gives the win to the
  opponent. A tied score cannot be finished in I2a (golden round comes with I2b).
- `resync_request.requestId` must be a canonical UUID and `cursor` must be present (a number or `null`); anything else is
  `resync_rejected` with `invalid_resync_request`. The cursor of a snapshot before any bout event is `0`.
- A `session_snapshot` pushed at «Начать» has state `prepared`; `session_state_updated` follows for every operator
  transition, and `kerugi_score_updated` is pushed only when the score object changes.
- `session.courtId` is the domain court UUID of the desktop; it is not the `courtId` of `GET /v1/metadata`, which stays a
  display identity.
- The fixture values differ from the examples above (two judges, generated IDs compared by presence); the shapes are the
  same.
