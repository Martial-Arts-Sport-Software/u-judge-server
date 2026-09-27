# ADR-007: Draw and Bracket Generation

- Status: Accepted
- Date: 2026-09-27
- Requirements: `BRK-001`, `BRK-002`, `BRK-005`, `IMP-004`, `SES-003`, `AUD-001`
- Source: «Проект правил соревнований по хапкидо» (FHR 2024), §1.2.1, §1.2.2, §1.4, §2.2.2.2

## Context

Applications (ADR-005) contain participants without brackets or start order. The rules define:

- systems (§1.2.1): single elimination, double elimination, round robin, olympic system;
- places (§1.2.2): in Kerugi both semifinal losers take third place; technical disciplines are placed by points;
- draw methods (§1.4): random, seeding by rating, separation by subject of the Russian Federation, by organisation or by
  coaches; one criterion for the whole draw, criteria are not mixed; separation uses subgroups A-D: two athletes with the
  same origin go to different halves (AB / CD), three or four to different quarters;
- technical disciplines (§2.2.2.2): performance order by draw, an electronic draw at least 2 h before the start is allowed.

Decisions of 2026-09-27: systems in scope are single elimination and round robin (olympic system is to be clarified,
double elimination and seeding by rating are out of scope for now); methods are random and separation; technical
disciplines get a draw plus manual editing.

## Decision

1. **Pure domain.** `Draw` is a pure function `(category entries, system, method, criterion, seed) -> DrawResult` in the
   domain module without I/O. The same inputs and seed always give the same bracket, so tests and audit can replay a draw.
2. **Seed.** The seed is a 64-bit value from `SecureRandom`, stored in the draw event. «Перемешать» generates a new seed;
   only the confirmed result is journaled.
3. **Single elimination (Kerugi, Tanbon).** The bracket size is the next power of two; byes go to the first-round
   positions defined by the standard bye order so that no bye meets another bye and byes are spread evenly between halves
   and quarters. Both semifinal losers get third place; no bronze bout.
4. **Round robin.** For categories the operator selects (default for 3-5 entries, rules allow it for small categories):
   circle method order of rounds; places by wins, then head-to-head, then the operator's decision, because the rules
   define no further tie-break.
5. **Separation.** Criterion is one of: subject of the Russian Federation, city, organisation (`ДСО` or school/club), coach
   (first coach listed, decision of 2026-09-27). Entries are grouped by criterion value; groups are placed largest first, each spread across
   halves, then quarters, then eighths (A-D and deeper) with the seeded random choice among equally good positions. When
   a clash cannot be avoided (a group larger than the number of sections) the draw minimises clashes in the earliest
   round and the preview marks each unavoidable clash.
6. **Technical disciplines.** A random permutation of entries (pairs and groups as one entry) per category forms the
   performance order; the same separation criterion can be applied so that entries of one origin do not perform one after
   another.
7. **Editing before start.** The operator can swap two positions or two entries in the preview; each confirmed edit is a
   `bracket_edited` event with before/after positions. After the first bout of the category starts, the draw and edits are
   rejected with a typed error (`BRK-002`).
8. **Journal.** `category_draw_confirmed` carries category id, system, method, criterion, seed, reader version of the
   applications and the resulting positions; replacing applications (ADR-005) invalidates unconfirmed and confirmed draws
   of changed categories, which the UI shows as «требует новой жеребьёвки».
9. **Current and next bout (`SES-003`).** The operator selects the current bout from confirmed brackets on a court; the
   next bout is derived from bracket order. Bout results and advancement are I2b (`BRK-003`, `BRK-004`).
10. **UI.** The screen «Настройка турнирных сеток» from the user's mockup: court card with the file picker, draw card with
    two selectors «Система» (Выбывание / Круговая) and «Метод» (Случайная / С разведением + criterion), «Перемешать»,
    «Подтвердить»; loaded categories list; preview with «Редактировать».

## Consequences

- `IMP-004` changes from «импорт готовых сеток» to «сетки и порядок выступлений формируются жеребьёвкой по правилам»;
  `BRK-005` changes from «жеребьёвка вне scope» to «случайная и с разведением; посев по рейтингу, двойное выбывание и
  олимпийская система вне scope v1».
- Property tests cover determinism by seed, bye placement, and separation (no avoidable clash before the semifinal for
  two, before the quarterfinal for three or four).

## Open questions

- Olympic system semantics (to be clarified by the organiser); until then it is not offered.
