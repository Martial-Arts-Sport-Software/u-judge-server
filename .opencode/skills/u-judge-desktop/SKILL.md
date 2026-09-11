---
name: u-judge-desktop
description: Use when changing the U'Judge Compose Desktop operator, referee, watcher, device pairing, or desktop packaging UI.
---

# U'Judge Desktop UI

Read the applicable `UI-*`, `DEV-*`, `SES-*`, and `BRK-*` requirements before implementing a screen.

## UI behavior

- Desktop is an operator tool, not the source of scoring truth. Render persisted projections and dispatch validated commands.
- Dangerous actions such as score correction, reset, and session completion require confirmation and an audit reason.
- Pairing screens distinguish pending, connected, disconnected, and revoked devices, including Android/iPhone platform.
- Watcher views are read-only. Do not place state-changing controls in watcher flows.
- Use Russian and English resources for all user-facing strings.

## Compose rules

- Keep UI state separate from application state and avoid starting server or network jobs from recomposition.
- Scope coroutines to lifecycle-aware effects; cancel scan and action jobs when the screen leaves composition.
- Do not use global mutable UI flags to represent transport or session truth.
- Preserve the existing visual language and use semantic names for critical controls.

## Validation

Run `./gradlew :desktop:build` after UI work. For installers, run `./gradlew :desktop:packageDistributionForCurrentOS` on the target OS.
