# ADR-011: Tanbon and Technical Performance Contract

- Status: Accepted
- Date: 2026-11-08
- Increment: I5 «Остальные дисциплины» (`docs/ROADMAP.md`)
- Requirements: `TAN-001`-`TAN-005`, `TEC-001`-`TEC-014`, `NFR-011`; client `CLI-021`, `CLI-031`, `CLI-042`, `CLI-044`,
  `CLI-045`, `CLI-048`-`CLI-053`
- Additive to: [ADR-009](ADR-009-kerugi-bout-contract.md) (every rule of it holds unless this ADR names an addition);
  numbering and the court panel of [ADR-008](ADR-008-courts-numbering-protocols.md) §6, §9

## Context

ADR-009 §13 reserved `tanbon` and the technical disciplines: Tanbon reuses the bout pipeline with `tanbon_score_command`,
technical disciplines send one final per-judge vector with `technical_score_send_command` and carry a discipline-specific
score. The client still sends Tanbon taps as the generic `command` (rejected `unsupported_command` since I2a) and its
technical screens only save local drafts. The application template has no Tanbon sheet (ADR-005), and ADR-008 numbers
technical performances in the same sequence as bouts but nothing starts them yet.

## Decision

### 1. Capabilities

`GET /v1/metadata` adds `"tanbon_bout": true` and `"technical_performance": true`; an I5 client requires both together
with `kerugi_bout`. Everything else in ADR-009 §1 (strict client messages, every field present, canonical UUIDs, cursor,
request/push routing, ordering) applies to the messages below.

### 2. One court, one current session

A court has one current session: the latest started bout or performance. «Начать» on the court panel starts the next
item: a bout (Kerugi or Tanbon) or a technical performance. Starting is rejected with `bout_in_progress` while the current
session is not `completed` or `cancelled`. Snapshots, resyncs and pushes describe the current session whatever its
discipline; `cursor` is the journal sequence of the latest session event of either kind.

### 3. Tanbon

- **Source.** Tanbon bouts are bouts of «Весовая категория» brackets. At «Начать» the operator chooses Kerugi (default) or
  Tanbon. The first started bout of a category fixes its discipline: a later bout of that category with the other one is
  rejected with `discipline_mismatch` on the desktop.
- **Pipeline.** The bout aggregate of ADR-009 with roster (2-3 seats), quorum, coincidence window, seat hold, states,
  `clientTimestamp` windows, the running-period rule, corrections, the reset, the result by final score and
  `judge_attention_command` (`TAN-001`). Journal events: `tanbon_bout_started` (payload as `kerugi_bout_started`) and
  `tanbon_score_candidate`; all other events are the shared session and bout events.
- **Scoring.** `HEAD = 2`, `BODY = 1` (`TAN-002`, `TAN-003`). `CROSS` is journaled, acknowledged and shown in the
  history as audit-only; it never changes `score`, cannot be corrected and is not compensated by a reset (`TAN-004`).
- **Operator.** Tanbon defines only `HEAD`, `BODY` and `CROSS`; throws, spin bonuses, Gamjeom and the disqualification are
  Kerugi rules and are rejected with `discipline_mismatch` for a Tanbon bout (open question below).
- **Snapshot.** ADR-009 §3 unchanged with `"discipline": "tanbon"`; `score.gamjeom` is `0`/`0`, `warnings` is empty and
  `disqualified` is `null`.
- **Command.** `tanbon_score_command` is the ADR-009 §6 envelope and audit context with `competitor` and `area`:

```json
{
  "type": "tanbon_score_command",
  "eventId": "4a7c2e19-6b3d-4f8a-9c1e-5d2b7f3a8e60",
  "sequence": 3,
  "clientTimestamp": "2026-11-08T11:02:14.250Z",
  "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
  "peerId": "00000000-0000-4000-8000-000000000001",
  "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
  "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b61",
  "judgeId": "2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e71",
  "deviceId": "android-contract",
  "competitor": null,
  "area": "CROSS"
}
```

  `area` is `HEAD`, `BODY` or `CROSS`. `HEAD` and `BODY` need `competitor` `BLUE` or `RED`; `CROSS` needs `competitor`
  `null`; anything else is `invalid_command`.
- **Push.** `tanbon_score_updated` has the body of ADR-009 §9 (`type`, `sessionId`, `cursor`, `score`) and is sent when the
  score of the current Tanbon bout changes.
- **Discipline guard (`TAN-005`).** `kerugi_score_command` for a Tanbon bout, `tanbon_score_command` for a Kerugi bout and
  either of them for a technical performance are rejected with `discipline_mismatch`. The client enables Tanbon buttons
  under ADR-009 §10.5 with `discipline` `tanbon`.

### 4. Technical disciplines and criteria (`TEC-001`-`TEC-005`)

| Application sheet (ADR-005) | Weapon | `discipline` | Technique criteria | Priority (`TEC-013`) | Arbiter bonus |
|------------------------------|--------|--------------|--------------------|----------------------|---------------|
| Приёмы самообороны | | `hosinsool` | 4 (Младшие юноши, Юноши) or 6 (Юниоры, Мужчины) | technique | no |
| Поединок постановочный - пара | | `pair_freestyle` | 4 or 6, as Hosinsool | presentation | no |
| Поединок постановочный - группа | | `group_freestyle` | 6 | presentation | no |
| Комплекс свободный | МЕЧ | `sword` | 6 | presentation | no |
| Комплекс свободный | ШЕСТ | `pole` | 6 | presentation | no |
| Комплекс свободный | ПАРНЫЕ ДВОЙНЫЕ ЦЕПЫ | `paired_nunchaku` | 6 | presentation | `+0.2` once |
| Комплекс свободный | ПАРНЫЕ ВЕЕРА | `paired_fans` | 6 | presentation | `+0.2` once |

Presentation always has 4 criteria (`TEC-004`). The vector order is the FHR order of the criteria:

- Hosinsool and Pair technique: wrist hold, clothes hold/grabs/chokes, fist punch, leg kick, then for 6: knife, hapkido
  weapon. Hosinsool presentation: realism, power/speed/kihap, balance/fall, harmony/timing.
- Group technique: offence/defence, breaking, kicks, weapon, dynamics/movement, acrobatics.
- Weapon technique: weapon strikes/blocks, jump kicks, spinning kicks, weapon manipulation, stances/movement, acrobatics.
- Freestyle presentation (pair, group, weapon): realism/creativity, power/speed/kihap, balance, music/choreography.

All values are integers in tenths on the wire and in the journal: a criterion is `1..10` (`0.1..1.0`, `TEC-002`), so
`0.0` and fractions below `0.1` cannot be expressed.

### 5. Performance session

- «Начать» for a performance item of the court panel journals one `technical_performance_started` with the category,
  court number, performance number (ADR-008 §6), place in the performance order, the entry, `discipline`, the criteria
  counts, the roster and the aggregation formula. Roster: 1-7 connected devices in seat order (FHR names five side
  judges; the pilot may run fewer). Formula: `trimmed_mean` (default, needs at least 3 seats) or `mean`. Roster and
  formula are fixed for the session before the first score (`TEC-010`).
- States: `prepared` → `running` (operator «Старт»: judges may send) → `completed` or `cancelled` (with a reason). There
  is no pause. Seat hold and the read-only view of an unseated device are as ADR-009 §2.
- A completed performance leaves the court queue; a cancelled one can be started again.

### 6. Technical snapshot

The session body for a technical discipline (push `session_snapshot`, reply `resync_response`):

```json
{
  "sessionId": "1b2c3d4e-5f60-4718-8a9b-0c1d2e3f4a50",
  "discipline": "hosinsool",
  "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
  "peerId": "00000000-0000-4000-8000-000000000001",
  "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
  "courtNumber": 1,
  "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41",
  "categoryTitle": "Приёмы самообороны · 2013-2011 г.р. Юноши",
  "performanceNumber": 12,
  "state": "running",
  "entry": {
    "entryId": "entry-5",
    "label": "Иванов И. И. (Москва) / Петров П. П. (Москва)",
    "athletes": [{ "fullName": "Иванов Иван Иванович", "city": "Москва" }, { "fullName": "Петров Пётр Петрович", "city": "Москва" }]
  },
  "settings": { "judgeCount": 3, "formula": "trimmed_mean" },
  "criteria": { "technique": 4, "presentation": 4, "deductionStepTenths": 3 },
  "seat": { "number": 1, "judgeId": "6e5d4c3b-2a19-4807-9f6e-5d4c3b2a1900", "deviceId": "android-contract" },
  "sent": null,
  "score": { "submittedSeats": [], "result": null },
  "result": null
}
```

- The client decodes the body by `discipline`: `kerugi` and `tanbon` have the ADR-009 shape, the seven codes of section 4
  this one, and an unknown code leaves the controls disabled.
- `sent` is the device's own acknowledged Send in this session, else `null`:
  `{ "eventId", "techniqueTenths", "presentationTenths", "deductionTenths", "totalTenths" }`. A reinstalled or reconnected
  client shows it read-only (`CLI-051`).
- `score.submittedSeats` lists the seats that sent, in seat order; judges never see the other judges' values.
- `score.result` is `null` until `completed`, then
  `{ "totalTenths", "aggregateTenths", "adjustmentTenths", "divisor", "formula" }`.
- `result` and `session_state_updated.result` are always `null` for a technical session; its result is `score.result`.

### 7. `technical_score_send_command`

The final vector of one judge (`TEC-008`, `TEC-009`, `CLI-050`); the ADR-009 §6 envelope and audit context:

```json
{
  "type": "technical_score_send_command",
  "eventId": "c3d2e1f0-a9b8-4c7d-8e6f-5a4b3c2d1e00",
  "sequence": 7,
  "clientTimestamp": "2026-11-08T12:40:05.000Z",
  "competitionId": "3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60",
  "peerId": "00000000-0000-4000-8000-000000000001",
  "courtId": "5b7e2c10-1d3f-4a6b-8c9d-0e1f2a3b4c50",
  "bracketId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41",
  "sessionId": "1b2c3d4e-5f60-4718-8a9b-0c1d2e3f4a50",
  "judgeId": "6e5d4c3b-2a19-4807-9f6e-5d4c3b2a1900",
  "deviceId": "android-contract",
  "discipline": "hosinsool",
  "techniqueTenths": [8, 7, 9, 6],
  "presentationTenths": [7, 8, 8, 9],
  "deductionTenths": 3,
  "totalTenths": 59
}
```

- `deductionTenths` is the judge's own deduction (the client's negative «extra points»): `0` or a multiple of `3` not
  above the maximum of the vector. `totalTenths` is the client's local total; the server recomputes it.
- Checks after the ADR-009 §8 ones (malformed, context, seat, timestamp): `discipline` differs from the session →
  `discipline_mismatch`; the session is `prepared` → `session_not_started`; the vector has the wrong length, a criterion
  outside `1..10` or an invalid deduction → `invalid_score_vector`; `totalTenths` differs from the server's total →
  `total_mismatch`; the seat already sent another event → `score_already_sent`.
- **Send is final.** One accepted event per seat and session. An identical retry of the accepted `eventId` gets the same
  `command_ack`; the same `eventId` with other content is `event_id_conflict`; a second Send of the seat is
  `score_already_sent` (`TEC-008`, `CLI-051`). All new codes are terminal; `journal_unavailable` stays transient.
- **Client.** `Send` is enabled only while connected, synchronized, the session is the screen's technical discipline,
  `running`, seated, `sent` is `null` and no Send of this session is pending (`CLI-048`). It asks for confirmation
  (`CLI-049`), writes one outbox entry with `eventId`, `sequence` and `clientTimestamp` fixed before the write, and replays
  it after a disconnect or app kill (`CLI-052`). After `command_ack` the rating is read-only; a terminal rejection is shown
  with its code and the payload stays in the outbox journal (`CLI-053`).

### 8. Calculation (`TEC-006`, `TEC-007`, `TEC-011`-`TEC-013`)

Every number is an integer count of tenths; client and server use the same integer arithmetic, so the same payload gives
the same total (`TEC-007`).

1. **Judge total** `J = max(0, ΣT + ΣP − D)`.
2. **Aggregate.** `trimmed_mean`: sort the judge totals, drop one lowest and one highest, divisor `d = n − 2`; `mean`:
   divisor `d = n`. `A = round(ΣJ / d)` to tenths, half up: `A = (2·ΣJ + d) div (2·d)`. Five judges give the mean of the
   three remaining (`TEC-011`); any other count shows its divisor (`TEC-012`).
3. **Arbiter adjustments** are operator events with a reason and author, separate from the criteria (`TEC-006`): a
   penalty `−0.3` per episode (time, lost weapon, restart, conduct, delay, boundary) and the bonus `+0.2` for two items
   (`paired_nunchaku`, `paired_fans`, once). A withdrawal is a compensating event; the original stays.
4. **Final** `F = max(0, A + Σadjustments)`.
5. **Places.** Within a category by `F` descending; ties by `K1`, the aggregate of the judges' priority-part sums by the
   same formula (technique for Hosinsool, presentation for the others), then `K2`, the plain sum of those parts over all
   judges. Entries equal in `F`, `K1` and `K2` share the place and are marked for the panel vote, which is not automated.

The normative vectors are the fixture `technical_test_vectors`; the server and the client test against the same file. Gate
G5 still needs their confirmation by an expert judge.

### 9. Completion (`TEC-014`)

The operator sees every seat as submitted or pending. «Завершить» is accepted when every seat sent. With a missing seat it
needs an explicit reason and at least the scores the formula needs (`trimmed_mean` 3, `mean` 1); the completed event
records the missing seats, the reason, the divisor and the total. Codes: `scores_missing` (no reason), `not_enough_scores`.
A Send that arrives after completion is `session_closed`.

### 10. `technical_score_updated`

```json
{
  "type": "technical_score_updated",
  "sessionId": "1b2c3d4e-5f60-4718-8a9b-0c1d2e3f4a50",
  "cursor": 211,
  "score": { "submittedSeats": [1, 2, 3], "result": { "totalTenths": 62, "aggregateTenths": 62, "adjustmentTenths": 0, "divisor": 1, "formula": "trimmed_mean" } }
}
```

Pushed to every subscribed socket when the score object of the current performance changes: a Send, an adjustment, a
withdrawal or the completion. `session_state_updated` follows every operator transition with `result: null`.

### 11. Dispatcher

The single dispatcher of ADR-009 §11 decodes the three judge command types and routes each by its session: bout commands
to the bout aggregate, `technical_score_send_command` to the performance aggregate, a session of the other kind to
`discipline_mismatch`. An `eventId` journaled by either kind of session is checked against that event. `judge_attention_command`
stays a bout command. The operator's performance commands (start, «Старт», adjustment, withdrawal, completion,
cancellation) run in the desktop process through the same dispatcher and are not part of the device protocol.

## Contract fixtures

Added to `server/src/test/resources/contract/v1` and checked by `ContractFixturesTest`: `tanbon_session_snapshot`,
`tanbon_score_command`, `tanbon_score_updated`, `technical_session_snapshot`, `technical_score_send_command`,
`technical_score_updated`, `technical_command_rejected` (`total_mismatch`) and `technical_test_vectors` (data, not a
message). `metadata_response` gains the two capabilities. The client syncs them by bumping `contract/server-ref`.

## Consequences

- Tanbon needs no application sheet; the operator's choice at the first bout of a category is journaled with it.
- The client keeps one decoder per session shape and must not assume Kerugi fields in every snapshot.
- Equal sums rely on integer tenths on both sides; a `Float` total can no longer reach the wire.

## Open questions

- Whether Tanbon has penalties or other operator points (for now none; the rule owner decides).
- Expert confirmation of the normative vectors and of the tie-break reading of FHR §4.2.9, §4.3.8 (gate G5).
- Per-category result protocols and exports (I6).
