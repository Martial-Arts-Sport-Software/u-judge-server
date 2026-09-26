---
name: u-judge-server-verification
description: Use when testing, packaging, releasing, or modifying Gradle, dependencies, server build files, or desktop installers, and before opening any PR.
---

# U'Judge Server Verification

## Delivery scope

- Before implementation, name the roadmap increment (`I1`...`I8` in `docs/ROADMAP.md`) and the requirement IDs or gate
  items it will close. Use the `u-judge-increment-planning` skill.
- Include every server-owned layer the scenario needs: domain, persistence/rebuild, transport, publication, production
  wiring in `Server.start()` and desktop UI.
- For a cross-repository outcome, link the client work and record the integration or physical-device evidence needed to close it.
- A narrow prerequisite is allowed only for an urgent fix, blocking preparation, CI/docs change, or a dependency that
  cannot be verified inside the increment. Explain the exception and the parent increment in the issue and PR; never mark
  partial evidence as a completed requirement or gate.

## Required checks

- Use JDK 21.
- For source and dependency changes, run `./gradlew build`.
- For persistence changes, also run `RealPostgresLifecycleTest` against a real PostgreSQL bundle (see `README.md`).
- For desktop packaging changes, run `./gradlew :desktop:packageDistributionForCurrentOS`.
- For standalone server distribution changes, run `./gradlew :server:installDist`.
- Run `git diff --check` before committing.

## Toolchain rules

- Keep Kotlin, Compose, Gradle, and JDK versions compatible; do not upgrade a single layer in isolation.
- Keep `mainClass` values aligned with actual Kotlin top-level `main` functions.
- Do not commit generated build directories, local PostgreSQL data, IDE state, credentials, or release artifacts unless explicitly requested.
- State when no test sources exist; a passing build is not proof of domain correctness.

## Release evidence

Record the OS, command, and result for installer smoke tests. The pilot requires clean Windows and macOS verification,
backup/restore proof, and no anonymous write endpoint.
