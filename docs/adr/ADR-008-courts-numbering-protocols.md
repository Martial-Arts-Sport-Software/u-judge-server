# ADR-008: Courts, Bout Numbering and Draw Workflow

- Status: Accepted
- Date: 2026-09-28
- Requirements: `IMP-004`, `BRK-001`, `BRK-002`, `BRK-005`, `SES-003`, `CMP-005`, `CMP-006`, `CMP-008`
- Complements: ADR-005 (applications), ADR-007 (draw algorithm)
- Sources: the organiser's «Протокол хода соревнований» samples (before and after the bouts), the owner's bracket
  mockups, the HapkiPRO presentation video (competition form, category statuses, swap editing, operator «Следующий бой»),
  FHR 2024 §1.2, §1.4

## Context

ADR-007 defines how one category is drawn. It leaves open:

- how courts share the competition: an application covers the whole competition, the pilot runs one desktop peer;
- how bouts are numbered: the organiser's protocols number bouts across the whole competition round by round (round 1
  of a category № 1-4, other categories take № 5-13, its semifinals № 14-15, its final № 22); byes get no number and
  the athlete with a bye is drawn straight in the second-round column;
- whether the draw runs per category or for the whole competition, which system a category gets by default, and what
  happens to categories with one or two entries;
- what the protocol PDF contains, where the operator selects the current and next bout (`SES-003`), and what locks a
  bracket (`BRK-002`, `CMP-006`).

## Decision

1. **Courts.** The operator sets the number of courts (1-10, default 1) and assigns every category to exactly one court;
   a category is never on two courts. One desktop holds the draw, the courts, the numbering and the protocols of the
   whole competition and judges the bouts of one court selected on it. Confirmed brackets belong to the local peer
   (`CMP-005`). Handing brackets to desktops of other courts is post-v1 (ADR-002).
2. **Method and criterion.** One choice for the whole competition (FHR §1.4: one criterion, criteria are not mixed):
   random, or separation by subject, city, organisation or first coach (ADR-007). The criterion can be combined with the
   belt (`гып`, technical qualification), as HapkiPRO's «пояс + тренер» and «пояс + город»: then only entries with the
   same belt and the same criterion value are separated.
3. **System per category.** Default «Выбывание»; «Круговая» for 3-5 entries; the operator can change it per category.
   Kerugi always has two third places without a bronze bout (FHR §1.2.2). One entry: no bout, the status is «нет
   поединков». Two entries: one final bout, the system choice is disabled.
4. **Workflow.** «Провести жеребьёвку» makes previews for every category without a confirmed draw; «Перемешать» redraws
   the selected category with a new seed; «Подтвердить» confirms the previews, one `category_draw_confirmed` per category
   in one transaction. Only confirmed draws are journaled (ADR-007 §2).
5. **Editing.** Before the start the operator selects two entries of a category (both highlighted), swaps them and saves
   or goes back without saving; a save is one `bracket_edited` event (ADR-007 §7).
6. **Bout numbers.** One numbering across all courts, derived from the confirmed draws, not stored: round 1 of every
   category (courts in order, categories of a court in the operator's order, default import order), then round 2, and so
   on; round-robin rounds interleave the same way; byes are not numbered. Technical disciplines get performance numbers
   in the same pass. Numbers are frozen once the first bout of the competition starts (I2a); until every category is
   confirmed the views and the PDF say «нумерация предварительная».
7. **Bracket view.** Per the owner's mockups: rounds left to right, entry boxes in the primary colour (upper slot dark,
   lower slot light), connectors, a green final box «Победитель» (empty until I2b) and «Бой № N» at every bout. The
   preview panel shows it under «Список / Сетка» and «Во весь экран»; the full-screen view has close, «← <category> ·
   Категория N из M →», «Все категории», chips of the categories of the same discipline and age class, «Список / Сетка»
   and the download.
8. **Protocol PDF.** After a draw is confirmed the operator downloads «Протокол хода соревнований» in the organiser's
   layout: A4 landscape, one page per category; title, competition name, date and place; «<section>, <weight>» plus
   «(Площадка № N)» when there is more than one court; boxes «Фамилия И. О. (город)» in the first round and bye entries in
   the second; «Бой № N» at every bout; signature lines «Главный судья» and «Главный секретарь» with names. One category
   from the full-screen view or all confirmed categories in one file. Place, chief judge and chief secretary are
   competition settings (`competition_officials_set`), not part of the applications. Scores, winner lines, the
   abbreviations legend and places come with I2b; seal and signature images with I6. Rendered with Apache PDFBox and a
   bundled Cyrillic font; the result protocols of I6 reuse the renderer.
9. **Current and next bout (`SES-003`).** The court panel, the right column of the future operator screen, shows «Текущий
   бой № N» and «Следующий бой» (pair, category). The next bout defaults to the lowest number of this court whose
   participants are known; the operator can pick another confirmed bout of the court (`court_next_bout_selected`).
   «Начать» (I2a) makes it current. The call screen «Приглашаются / Готовятся» belongs to the spectator window (I2b).
10. **Lock.** A category rejects a redraw or an edit with a typed error once its first bout starts (`BRK-002`, I2a starts
    bouts). Selecting the next bout does not lock.
11. **Progress (`CMP-008`).** «Все категории» shows every category with the statuses «свободна», «в процессе»,
    «завершена», «нет поединков» and «требует новой жеребьёвки» after a changed import; «в процессе» and «завершена»
    come from I2a/I2b.

## Consequences

- A later draw may renumber bouts of other categories until the start; the preliminary mark keeps printed protocols
  honest.
- New dependency: Apache PDFBox.
- `CMP-006` (exclusive ownership while `IN_PROGRESS`) can only be shown end-to-end once bouts start (I2a).

## Open questions

- A different bout order across categories on request of the organiser.
- Olympic system (ADR-007).
