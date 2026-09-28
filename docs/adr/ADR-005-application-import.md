# ADR-005: Application Import (`df-template-v1`)

- Status: Accepted
- Date: 2026-09-27
- Requirements: `IMP-001`-`IMP-003`, `IMP-005`-`IMP-008`, `BAK-001`, `SYS-003`, `AUD-001`, `SYS-007`

## Context

The competition input is not a set of ready brackets, as the earlier requirements assumed, but coach applications
(`заявки`) built from the federation templates in `bullets_info`: `df.xlsx` and `Шаблон (10-11 / 12-14 / 15-17 / 18+ лет).xlsx`.
The accompanying «Требования к заполнению заявок» (2 pages) defines the layout and the field rules:

- one file holds one age group; five sheets, one per discipline: `Весовая категория`, `Приёмы самообороны`,
  `Поединок постановочный - пара`, `Комплекс свободный`, `Поединок постановочный - группа`; unused sheets stay empty;
- rows 1-2 are merged column headers; a merged row spanning all columns starts an age/sex section
  (`2018-2016 г.р. Младшие юноши`); `Комплекс свободный` adds a weapon section (`МЕЧ`, `ШЕСТ`, `ПАРНЫЕ ДВОЙНЫЕ ЦЕПЫ`,
  `ПАРНЫЕ ВЕЕРА`) above the age/sex sections;
- pairs (`Приёмы самообороны`, `Поединок постановочный - пара`) share one number in a merged cell of column A spanning two
  rows; a group team ends with an empty filled (coloured) row and has 5-15 members;
- fields: full name without initials, birth date as a date cell, weight `до N кг` / `свыше N кг` (Kerugi only), sport
  qualification (`МС`, `КМС`, `1р`-`3р`, `1юн`-`3юн`, `б/р`), belt (`10 гып` … `1 гып`, `1 пум`, `1 дан` … `10 дан`, with a
  space), city, full region name, federal district abbreviation, society (`ЦСКА`, `Динамо`, `мин. обр.`, `мин. спорт.`),
  school/club, at least two coaches as `Фамилия И.О.` separated by `, `; the doctor's visa is optional;
- age: the athlete reaches the category age in the competition year; for 12-14 the athlete must be 12 on the competition
  date;
- the organiser reloads the applications after the mandate commission and weigh-in; after the competition starts they are
  not changed.

## Options

| Option | Description | Advantages | Risks |
| --- | --- | --- | --- |
| A. Apache POI (`poi-ooxml`) | Full OOXML model: merged regions, cell types and date formats, fills | Reads every structural signal the template relies on (merged cells, date format, coloured separator row); mature, Apache-2.0 | About 15 MB of jars in the desktop bundle; DOM model needs memory for large files (500 participants is small) |
| B. fastexcel-reader | Streaming reader | Small and fast | No reliable access to fills and limited merged-cell support; separator rows and pairs would be guessed |
| C. Own OOXML parser | Parse `sheetN.xml` and `styles.xml` | No dependency | Date formats, shared strings and styles become our bugs |

## Decision

1. **Adapter.** `ApplicationTemplateV1Reader` reads `df-template-v1` with Apache POI. A workbook is recognised as v1 by the
   five sheet names and the header row texts; any other layout is rejected with a typed `unsupported_template` error
   instead of best-effort parsing. A future layout becomes a new reader version; imported data records the version.
2. **Multiple files, one competition.** The operator selects several files at once (different coaches and age groups).
   All files are parsed and validated together; nothing is written unless every file passes.
3. **Strict validation (decision of 2026-09-27).** Every rule of «Требования к заполнению заявок» is an error that blocks
   the import (the operator fixes the files and checks again): missing or initial-only names, a non-date birth date, a birth year outside the section range, age below 12 on
   the competition date for 12-14, weight not matching `до|свыше N кг`, unknown qualification or belt spelling, missing
   required fields, fewer than two coaches or a wrong coach format, a participant outside any section, a pair with other
   than two members, a group outside 5-15 members, the same athlete (name + birth date) twice in one discipline category
   across files. Each error carries file, sheet, row, column, value and reason (`IMP-005`).
4. **Model.** The import produces a normalised competition: disciplines → categories (discipline + weapon + age group + sex
   + weight for Kerugi) → entries (athlete, pair or team) with the athlete fields and a source reference (file SHA-256,
   sheet, row, number in column A) as the source identifier (`IMP-002`). The competition name and date are entered by the
   operator on the import screen; the date drives the age checks.
5. **Journal.** A valid import is one `competition_applications_imported` domain event carrying the normalised dataset,
   the reader version and the file hashes, appended in one transaction (`IMP-006`). A later import before the competition
   starts is `competition_applications_replaced`, confirmed by the operator and referencing the previous event. An import
   equal to the current one writes nothing, so the history has no duplicates (`IMP-007`). The operator can make an earlier
   import current again (`competition_applications_restored`, a new event that names its origin) or reset all imports
   (`competition_applications_cleared`: nothing is current and the shown history starts over; the next import starts a
   new competition ID). Each of these changes is confirmed and preceded by a backup, which keeps the imports a reset
   hides. The projection is the history of imports since the last reset and the current one; the journal only grows
   (`AUD-001`). «Загрузить» checks and imports in one step: a failed check writes nothing.
6. **Backup before import (`IMP-008`, `BAK-001`).** Before writing, the desktop exports the whole `domain_events` journal
   to `<application data>/backups/<UTC timestamp>-before-import.jsonl` and keeps it until the operator deletes it. Because
   all state is event-sourced, the journal export is a complete logical backup. Encryption with the key storage of
   ADR-003 and restore follow in I6 (decision of 2026-09-27).
7. **Start lock.** Replacing the import is allowed until the competition starts with its first bout or performance; the
   rules forbid changing applications after the start. That state arrives with I4b, which rejects a replacement after it.
8. **Scope boundary.** I4a stops at validated participants and categories with preview. Draws, brackets, performance
   order, editing and the current bout are I4b (ADR-007, draw methods from rules §1.2 and §1.4).

## Consequences

- Requirements change: `IMP-004` (ready brackets) and `BRK-005` (no draw) no longer match the input and are rewritten in I4a
  docs: brackets and performance order are produced by the draw in I4b.
- The desktop bundle grows by Apache POI; `:desktop:suggestRuntimeModules` is rerun for the packaged app.
- Test data: the empty templates are structural fixtures; a generated 500-participant workbook covers `SYS-003`, and at
  least one real anonymised filled application is needed for `IMP-001`.

## Open questions

- A real anonymised filled application for the acceptance of `IMP-001`.
