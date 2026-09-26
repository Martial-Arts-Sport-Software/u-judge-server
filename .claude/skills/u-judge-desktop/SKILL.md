---
name: u-judge-desktop
description: Use when changing the U'Judge Compose Desktop operator, referee, watcher, device pairing, or desktop packaging UI.
---

# U'Judge Desktop UI

Read the applicable `UI-*`, `DEV-*`, `SES-*`, and `BRK-*` requirements and the roadmap increment before implementing a screen.

## UI behavior

- Desktop is an operator tool, not the source of scoring truth. Render persisted projections and dispatch validated commands.
- The desktop depends on `:server` in-process: call server application services, not HTTP loopback or test fixtures.
- A screen is done only when it is reachable from `./gradlew :desktop:run` against the production server wiring.
- Dangerous actions such as score correction, reset, and session completion require confirmation and an audit reason.
- Pairing screens distinguish pending, connected, disconnected, and revoked devices, including Android/iPhone platform.
- Watcher views are read-only. Do not place state-changing controls in watcher flows.
- Show connection and persistence errors to the operator (`UI-007`).
- v1 Pilot requires Russian UI; keep Russian and English resources for every user-facing string.

## Compose rules

- Keep UI state separate from application state and avoid starting server or network jobs from recomposition.
- Scope coroutines to lifecycle-aware effects; cancel scan and action jobs when the screen leaves composition.
- Do not use global mutable UI flags to represent transport or session truth.
- Preserve the existing visual language and Figma references in `docs/PROJECT.md`; use semantic names for critical controls.

## Validation

Run `./gradlew :desktop:build` after UI work and record a manual smoke run of the increment scenario in the PR.
For installers, run `./gradlew :desktop:packageDistributionForCurrentOS` on the target OS.
