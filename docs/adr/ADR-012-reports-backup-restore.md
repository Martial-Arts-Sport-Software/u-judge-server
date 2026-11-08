# ADR-012: Reports, History, Backup and Restore

- Status: Accepted
- Date: 2026-11-08
- Increment: I6 «отчётность, backup и локализация» (`docs/ROADMAP.md`)
- Requirements: `AUD-002`-`AUD-004`, `REP-001`-`REP-004`, `BAK-002`-`BAK-004`, `SYS-004`; keeps `AUD-001`, `BAK-001`,
  `IMP-008`
- Amends: [ADR-003](ADR-003-managed-postgresql.md) (backup encryption and retention), [ADR-005](ADR-005-application-import.md)
  §6 (backup file format), [ADR-008](ADR-008-courts-numbering-protocols.md) §8 (results, places, seal and signatures)
- Source: FHR 2024 §1.2.2 (places), the organiser's protocols (ADR-008), «Требования к заполнению заявок»

## Context

Every state of a competition is a projection of the single `domain_events` journal. The organiser needs the result of any
bout explained («who, what, when, why not counted»), files for organisers and for machines, result protocols with places,
and a copy of the competition that survives the loss of the computer. The requirements leave open: which history is shown
and how it is filtered, the columns of XLSX and CSV, the CSV dialect, the places of incomplete or tied categories, the
backup file and its location, when backups are taken, how a restore works on a running peer and how it is verified.
ADR-003 planned encrypted backups with a key in the OS secure storage and the seven newest kept; `BAK-003` and `BAK-004`
(restore on a new or cleared peer) contradict both.

## Decision

### 1. History (`AUD-002`-`AUD-004`)

1. The history is a read model over the journaled inputs of every bout, rebuilt on each read; nothing is stored for it.
   One row per event of a bout or a technical performance in journal order: time (UTC and local), court, category, bout number, session, source
   (`judge_device`, `operator`, `server`), author, judge seat and surname, action, side, value, status, reference and
   reason.
2. The status says why an input did or did not count, the same as the bout screen (`KER-009`): «засчитан», «нет кворума»,
   «вне времени боя», «отменён» (a correction or a withdrawal targets it), «сброшен» (a bout reset), «применён»
   (operator or arbiter action), «только аудит» (Tanbon `CROSS`, ADR-011 §3), «отброшена (мин./макс.)» (a technical judge
   total the trimmed mean leaves out: among equal totals the lower seat as the lowest and the higher seat as the highest,
   which does not change the result), «записано» (state, clock, result and other events). A correction row references the event it cancels and that event
   shows «отменён» (`AUD-002`); a reset row carries its reason.
3. Filters: court, category (bracket), bout (session), judge (seat surname) and action type, combined with AND (`AUD-003`).
4. Times are stored as UTC instants (`occurred_at`, the candidate's `clientTimestamp`); the history and the exports show
   UTC with `Z` and the local time with its offset and zone ID, so a timestamp is unambiguous in any file (`AUD-004`).
5. Imports, draws and settings stay in their own screens (ADR-005, ADR-008); the history is about results.

### 2. Places (FHR 2024 §1.2.2, ADR-007)

- Elimination: 1 — winner of the final, 2 — its loser, 3 — both semifinal losers (no bronze bout); other entries get no
  place. A category of two entries has places 1 and 2.
- Round robin: by wins; two entries with equal wins are ordered by their bout; three or more with equal wins get no place
  from the system (the operator's decision, ADR-007 §4) and are marked «решение главного судьи».
- Technical disciplines (ADR-011 §8): by the final total, then `K1`, then `K2`; entries equal in all three share the place
  and are marked for the panel vote. This reading is under the expert review of ADR-011 (`TEC-013` stays Partial).
- Places are shown only for a category whose every bout or performance is decided; before that the category is
  «в процессе».

### 3. XLSX export (`REP-001`, `REP-003`, `REP-004`)

`<name>.xlsx` at the path the operator chooses, built offline with Apache POI. Sheets and columns:

| Sheet | Rows | Columns |
|-------|------|---------|
| «Соревнование» | key/value | ID соревнования, название, дата, место, главный судья, главный секретарь, сформировано (UTC), сформировано (местное время), часовой пояс, формат `u-judge-report-v1` |
| «Места» | one per placed entry, then one per category without places | площадка, категория, система, статус, место, участник, город, субъект РФ, организация, тренеры |
| «Поединки» | one per started bout or performance, by number | №, вид (Кёруги, Танбон, выступление), площадка, категория, круг, синий / участник, красный, состояние, счёт синего, счёт красного, Gamjeom синего, Gamjeom красного, победитель, причина, итог, оценка панели, решения арбитра, делитель, формула, судьи, кворум, окно (мс), начало (UTC), завершение (UTC), ID сессии |
| «Оценки» | one per session event (§1) | №, вид, площадка, категория, ID события, последовательность, время (UTC), местное время, время нажатия (UTC), источник, автор, место судьи, судья, действие, сторона, значение, статус, ссылка, причина, ID сессии |

Every value is a text or number cell; times are text, so no spreadsheet time-zone conversion applies. The competition ID
and the generation time distinguish two files (`REP-004`).

### 4. CSV export (`REP-002`)

One file `<name>.csv`: the «Оценки» table joined with the bout context and result, so one file carries both results and
raw inputs for machines.

- Encoding UTF-8 without BOM; delimiter comma; line end CRLF; RFC 4180 quoting (a field with comma, quote, CR or LF is
  quoted, quotes doubled); first line is the header.
- Columns: `competition_id, generated_at, court, category_id, category, kind, number, round, session_id, blue, red,
  session_state, blue_score, red_score, winner, result_reason, total_tenths, event_id, sequence, occurred_at,
  occurred_at_local, client_timestamp, source, author, seat, judge, action, side, value, status, reference_event_id, reason`.
- Codes, not words: `kind` `KERUGI`/`TANBON`/`TECHNICAL`; `BLUE`/`RED`; the journal event type as `action`; `value`
  `HEAD`/`BODY`/`CROSS`, `THROW:2`, `GAMJEOM:1`, `PENALTY:-3`, a technical vector `T:<tenths,…>;P:<tenths,…>;D:<tenths>;J:<total>`,
  a result reason or `<formula>:<total>`; `status` `COUNTED`, `NO_QUORUM`, `OUTSIDE_RUNNING_PERIOD`, `CORRECTED`,
  `RESET`, `APPLIED`, `RECORDED`, `AUDIT_ONLY` (Tanbon `CROSS`), `TRIMMED` (a judge total the trimmed mean leaves out);
  the ADR-010 result reasons. Technical numbers are integer tenths (ADR-011 §8). Times ISO-8601: UTC with `Z`, local with
  offset.

### 5. Protocols (ADR-008 §8)

- «Протокол хода соревнований» now fills the boxes of later rounds with the winners, adds the score after «Бой № N» and
  the result lines of round robins.
- «Протокол результатов»: one A4 page per category with the same header, the places table (место, участник, город,
  субъект РФ, тренер) and the signature lines.
- Seal, chief judge signature and chief secretary signature are competition settings: PNG or JPEG up to 1 MB each,
  journaled as `competition_protocol_images_set` with the images in Base64, so a backup carries them. Signatures are
  printed over the signature lines, the seal next to them, on both protocols.

### 6. Backup file (`BAK-001`-`BAK-003`, amends ADR-005 §6)

- Location `<application data>/backups/<UTC yyyyMMdd-HHmmss-SSS>-<reason>.jsonl`, reasons `before-import`,
  `before-restore`, `after-session`, `manual`. «Сохранить копию» copies a backup to a path the operator chooses (a USB
  stick), which is how a competition moves to a clean computer.
- Format `u-judge-backup` version 1, JSON Lines: line 1 is the header `{format, version, schema, peer_id, created_at,
  reason, events, sha256}`; then one line per `domain_events` row in `(owner_peer_id, sequence)` order with every column.
  `sha256` is the hex SHA-256 of the row lines, each followed by `\n`; `schema` is the highest applied migration.
- I4a files without a header stay restorable: their peer is the single owner of the rows; they have no checksum.
- Backups are taken before an import, a restore and a reset of imports (ADR-005), after every completed bout (`BAK-002`,
  asynchronously, so scoring never waits for the disk), and on demand. A failed automatic backup is shown to the operator.
- Kept until the operator deletes one; a deletion asks for confirmation; nothing expires (`BAK-003`). This replaces the
  «seven newest» of ADR-003.
- Not encrypted in v1 Pilot. A key in the OS secure storage of the source computer would make a backup unrestorable on a
  new computer (`BAK-004`, DoD «Backup восстановлен на отдельном чистом окружении»); the files contain what the paper
  applications already contain and stay in the user's application data. Encryption with an operator passphrase is
  revisited by the security check of I7.

### 7. Restore (`BAK-004`)

1. The operator picks a backup from the list or a file; the desktop validates it (format, version, schema not newer than
   the application, row count, checksum, unique `(owner_peer_id, sequence)` and event IDs) and shows date, reason, peer and
   number of events before the confirmation.
2. After the confirmation the server writes a `before-restore` backup of the current journal, creates a new database
   `u_judge_r<UTC timestamp>` in the managed cluster, migrates it, inserts the peer identity of the backup and all rows in one
   transaction and verifies the result: the rows read back hash to the header's `sha256` (for an I4a file: the same rows as
   the file). Then it journals `journal_restored` (file name, checksum, number of events) in the new database, switches the
   active database atomically (`<application data>/database`) and restarts the server components, which rebuild every
   projection from the journal.
3. Any failure drops the new database and leaves the current one active and unchanged. The replaced database is not
   deleted (`AUD-001`: a restore never deletes journal rows); removing old databases is a post-pilot maintenance task.
4. The restored peer keeps the backup's peer ID, so its brackets remain its own (`CMP-005`); paired devices are restored
   with the journal, but a new computer has a new TLS certificate, so judges pair again there (ADR-006).
5. Export equality: XLSX and CSV of the restored peer equal those of the source peer for the same generation time; the
   acceptance tests compare them.

### 8. Localization (`SYS-004`)

v1 Pilot starts in Russian whatever the OS language; English stays switchable at runtime on the entry screen. Every
user-facing desktop string, rejection code and history label comes from `Localization`, and a test fails when a key is
missing in one language or a desktop source file holds a Cyrillic literal. Server diagnostics (`UI-007`) remain technical
English inside a localized message. Protocols, XLSX and CSV are Russian documents of the organiser and do not follow the
UI language.

## Consequences

- A cluster keeps the databases replaced by restores until they are removed manually.
- XLSX and CSV formats are versioned by `u-judge-report-v1`; a column change is a new version.
- Tanbon and technical results are part of `u-judge-report-v1`; a later discipline adds `kind` and `action` codes only.

## Open questions

- Backup encryption with an operator passphrase (I7 security check).
- Removing replaced databases and old backups from the desktop.
