# ADR-010: Kerugi Bout Time and Bracket Advancement

- Status: Accepted
- Date: 2026-11-08
- Increment: I2b «Kerugi: время боя и ход сетки» (`docs/ROADMAP.md`)
- Requirements: `KER-010`-`KER-013`, `UI-001`, `UI-003`-`UI-005`, `SES-007`, `BRK-003`, `BRK-004`; client `CLI-026`,
  `CLI-072`, `CLI-074`, `CLI-075`
- Extends: [ADR-009](ADR-009-kerugi-bout-contract.md) (additive only: no field, type or code of ADR-009 changes meaning);
  keeps the numbering and the bracket view of [ADR-008](ADR-008-courts-numbering-protocols.md) §6-§7, §9
- Source: FHR 2024 §4.1.2 (time), §4.1.13 (golden round), §4.1.14 (results), §3.3.3 (technical assistant fixes the time)

## Context

ADR-009 judges a bout by points: the operator starts, pauses and resumes the session and fixes the result by the final
score. There is no clock: the rounds, the break and the golden round of FHR 2024 §4.1.2 are missing, a candidate counts
whenever the session is `running`, and a tied bout cannot end. A result does not move the winner in the bracket, so the
court panel never offers a second-round bout, the bracket view keeps «Победитель» empty and judges have nothing after
the first bout. The separate `KerugiTimerJournal` of the earlier design is not connected to the bout and keeps its own
event types, which the single-journal rule forbids. ADR-009 §13 reserved a `timer` object, `kerugi_timer_updated`, the
`golden_round` reason and the move to the next bout as a new `session_snapshot`.

## Decision

### 1. Time by age class

The age class of the category (`AgeClass` of the application, ADR-005) fixes the durations at «Начать»; they are journaled
in `kerugi_bout_started.timing` and do not change for the bout.

| Age class | Round 1 and 2 (net time) | Break | Rest before the golden round | Golden round |
|-----------|--------------------------|-------|------------------------------|--------------|
| Младшие юноши и девушки (10-11) | 1:00 | 1:00 | 0:30 | 1:00 |
| Юноши и девушки (12-14) | 1:30 | 1:00 | 0:30 | 1:00 |
| Юниоры (15-17), мужчины и женщины (18+) | 2:00 | 1:00 | 0:30 | 1:00 |

A start event without `timing` (I2a journals) is read as the 15+ row.

### 2. Periods and the clock

A bout runs through the periods `round 1 → break → round 2 → [break → golden round]`. The rest and the golden round
exist only when the score is tied when round 2 ends. One clock counts down the current period.

| Operator action (desktop) | Allowed | Effect |
|---------------------------|---------|--------|
| «Бой» (`session_started`) | `prepared` | Round 1 runs |
| «Пауза» (`session_paused`) | a round runs | The clock stops |
| «Продолжить» (`session_resumed`) | a round is stopped with time left, or a break runs or has ended | The round runs; from a break the next round starts and the break ends at once |
| «Стоп периода» (`kerugi_period_ended`) | a round or a break has time left | The period ends now |
| «Время» (`kerugi_clock_adjusted`) | the clock is stopped in a round with time left | Sets the remaining time, `1 ms`..duration (§3.3.3: the technical assistant fixes the time) |

- **Expiry.** When the clock of a period reaches zero the server journals `kerugi_period_ended` itself (source
  `server`, author `timer`) with `occurredAt` equal to the exact deadline, so a replay gives the same clock. Any stop of a
  running clock at or after its deadline is treated as the end of the period at the deadline.
- **After a round.** The end of round 1 starts the break at the same instant. The end of round 2 starts the 0:30 rest
  when the score is tied at that moment, otherwise the bout waits for the result. The end of the golden round waits for
  the result.
- **Session state.** `running` exactly while a round clock runs; `paused` during a stopped round, a break and after the
  last period; `prepared` before «Бой»; `completed` and `cancelled` as in ADR-009. The state names do not change, so an
  ADR-009 client enables scoring only while a round runs.
- **Scoring time.** A judge candidate counts only when its `clientTimestamp` lies inside an interval in which a round
  clock ran, clipped at the deadline; a candidate in a break or after the time is journaled and audited as «вне времени
  боя» (`KER-009`). Operator throws, spins, Gamjeom and corrections stay allowed while `running` or `paused`.

### 3. Restart

The clock is never derived from the wall time after a restart (roadmap I2b decision):

- While a clock runs the server journals `kerugi_clock_saved` (empty payload) whenever 5 s passed since the latest event
  of the bout. Judge taps and operator actions save the time as well, so a busy bout writes fewer checkpoints.
- When the service is rebuilt and the current bout has a running clock, it journals `kerugi_clock_stopped` with
  `reason: "server_restart"` and `stoppedAt` = the `occurredAt` of the latest event of the bout. The clock stands at that
  saved time (at most 5 s behind the moment of the crash), a running round becomes `paused`, and the operator resumes it or
  corrects the time with «Время». A break restored this way ends; «Продолжить» starts the next round.

### 4. Results

`CompleteBout` gains an optional `winner`, used only for preference. Completion is allowed when:

| Situation | Winner | `result.reason` |
|-----------|--------|-----------------|
| A disqualification is confirmed (ADR-009, any time) | The opponent | `disqualification` |
| Round 2 has ended, the golden round has not started, the score is not tied | The leader | `final_score` |
| The golden round has started and the score is not tied: its first point decides (§4.1.13) | The leader | `golden_round` |
| The golden round has ended with a tied score | The operator's choice, required | `preference` |

Anything else is rejected: `bout_not_finished` before round 2 ends, `score_tied` for a tie outside the last row,
`winner_required` or `winner_not_allowed` for a missing or unexpected choice. `preference` is the end of a scoreless golden
round (§4.1.13); the other reasons of §4.1.14 (ПОР, РБ, СС, ВЗ, ОДС, ООСНП) remain `KER-014` (Should, after the pilot).
The completion stops the clock. The reason and the winner are journaled in `session_completed`.

### 5. Wire additions

**`session.timer`** (in `session_snapshot`, `resync_response`; always present for `kerugi`):

```json
{
  "period": "round",
  "round": 2,
  "goldenRound": false,
  "durationMillis": 90000,
  "remainingMillis": 61200,
  "running": true,
  "runningSince": "2026-11-08T10:17:02.000Z"
}
```

- `period` is `round` or `break`; `round` is the round being fought, or for a break the round that follows it (2 or 3);
  `goldenRound` is `true` for round 3 and the rest before it.
- `remainingMillis` is exact while the clock is stopped. While it runs, `runningSince` is the server time it started and
  `remainingMillis` the time left at that moment: the client shows
  `max(0, remainingMillis - (serverNow - runningSince))` with the `clock_sync` offset and needs no per-second push.
  `runningSince` is `null` while stopped.
- After the last period: `remainingMillis` `0`, `running` `false`.

**`session_state_updated`** gains `timer`, the timer after the transition:

```json
{
  "type": "session_state_updated",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "cursor": 131,
  "state": "paused",
  "result": null,
  "timer": { "period": "break", "round": 2, "goldenRound": false, "durationMillis": 60000, "remainingMillis": 60000, "running": true, "runningSince": "2026-11-08T10:16:30.000Z" }
}
```

**`kerugi_timer_updated`** (push to every subscribed socket) is sent when the timer changes and the state does not: the
end of a break, «Стоп периода» on a stopped round, the time correction, the restore stop during a break:

```json
{
  "type": "kerugi_timer_updated",
  "sessionId": "9c8b7a6d-5e4f-4d3c-8b2a-1f0e9d8c7b60",
  "cursor": 133,
  "timer": { "period": "break", "round": 2, "goldenRound": false, "durationMillis": 60000, "remainingMillis": 0, "running": false, "runningSince": null }
}
```

- One event causes at most one push: a state change carries the timer, and a score change never coincides with a timer
  change. Every push of a session therefore has a greater `cursor` than the previous one, as ADR-009 §4 requires.
- `result.reason` gains `golden_round` and `preference`. A client ignores a reason it does not know and shows the winner.

### 6. Winner advancement

- A `completed` bout is the result of its bracket bout: `kerugi_bout_started` names the category, round and index, and
  `session_completed` the winner side, so the bracket finds the session and every source event by `sessionId`
  (`SES-007`). The bracket projection is derived on every read from the completed bouts; nothing else is journaled.
- **Elimination.** The winner fills the slot `WinnerOf(round, index)` of the next round (`BRK-004`): blue is the upper
  side of the bout, as in ADR-008 §7. Once both sides of a later bout are known it joins the court panel's queue with the
  number ADR-008 §6 gave it. The winner of the final fills the green «Победитель» box. A cancelled bout has no result
  and can be started again; a completed one cannot (`bout_already_judged`).
- **Round robin.** Standings by wins, then the head-to-head bout of two tied entries; «Победитель» is shown once every
  bout is decided and the leader is unique. A tie of three or more is left to the operator's decision outside the system
  (ADR-007 §4) and the box stays empty.
- A category whose every bout is decided is «завершена» in «Все категории» (`CMP-008`).
- The draw of a category is locked by its first «Начать», before the first judge event can exist (`BRK-003`, ADR-008
  §10); a result changes no structure, it only fills derived slots.

### 7. Moving to the next bout

1. «Завершить» completes the bout: `session_state_updated` with `completed` and the result. Judges keep the finished
   bout with the result on screen; controls are disabled (ADR-009 §10).
2. The court panel shows the next bout of the court, now including later rounds whose participants the results named.
3. «Начать» starts it: a new `sessionId` and a `session_snapshot` pushed to every subscribed socket, personalised with the
   device's new seat or `seat: null`. The client replaces its whole session state, cursor included, without a reconnect
   or a restart (`CLI-072`).
4. A command is created only from the current applied snapshot with an assigned seat: none for a session that is not
   the current one (stale) or without a seat (foreign) (`CLI-075`). Outbox entries keep the audit context of their tap; a
   replay for an older bout is rejected by the server with `session_not_current` or `session_closed` (ADR-009 §6, §8).

### 8. Spectator window

The spectator screen is a second desktop window for an external monitor (decision of 28.09.2026), opened from the bout
screen and placed full screen on the last attached display. It renders the same `SessionView` a connected device without
a seat receives (`seat: null`), refreshed on every journaled bout event and the live clock, plus the court panel's call
list «Приглашаются» (the next bout) and «Готовятся» (the bout after it). It has no state-changing control (`UI-004`). The
call list is not part of the device protocol in I2b; a read-only Android TV board after v1 will add it as a push.

### 9. Removed

`KerugiTimerJournal`, `JdbcKerugiTimerJournal` and their event types (`kerugi_timer_started`, `kerugi_round_break_started`
and others) are removed: no production code wrote them, and the clock is part of the bout on the single journal.

## Contract fixtures

`session_snapshot` and `resync_response` gain `session.timer`, `session_state_updated` gains `timer`;
`kerugi_timer_updated` is new. `ContractFixturesTest`
checks them against the running module; the client syncs them by bumping `contract/server-ref`.

## Consequences

- The bout projection now depends on event times (`occurredAt`) as well as order; both are journaled, so a rebuild
  gives the same clock.
- A bout writes up to 12 checkpoints per minute of running clock without other events.
- `final_score` needs the end of round 2: an I2a client is unaffected, desktop operators must run or stop the periods.
- `SessionProjection` allows `paused` → `completed`, because time ends in a stopped clock.

## Open questions

- Ending the golden round automatically at its first point instead of by the operator.
- A round reset (`SES-006`) and the remaining victory reasons (`KER-014`).
- Result lines and scores in the protocol PDF (ADR-008 §8) move to the result protocols of I6.
