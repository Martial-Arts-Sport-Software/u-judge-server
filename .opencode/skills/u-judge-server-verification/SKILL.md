---
name: u-judge-server-verification
description: Use when testing, packaging, releasing, or modifying Gradle, dependencies, server build files, or desktop installers.
---

# U'Judge Server Verification

## Required checks

- Use JDK 21.
- For source and dependency changes, run `./gradlew build`.
- For desktop packaging changes, run `./gradlew :desktop:packageDistributionForCurrentOS`.
- For standalone server distribution changes, run `./gradlew :server:installDist`.
- Run `git diff --check` before committing.

## Toolchain rules

- Keep Kotlin, Compose, Gradle, and JDK versions compatible; do not upgrade a single layer in isolation.
- Keep `mainClass` values aligned with actual Kotlin top-level `main` functions.
- Do not commit generated build directories, local PostgreSQL data, IDE state, credentials, or release artifacts unless explicitly requested.
- State when no test sources exist; a passing build is not proof of domain correctness.

## Release evidence

Record the OS, command, and result for installer smoke tests. The pilot requires clean Windows and macOS verification, backup/restore proof, and no anonymous write endpoint.
