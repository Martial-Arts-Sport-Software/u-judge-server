---
name: u-judge-replication
description: Use when implementing U'Judge PostgreSQL persistence, managed PostgreSQL lifecycle, backup, recovery, client resync, or post-v1 P2P discovery and replication.
---

# U'Judge Persistence, Recovery And Replication

Read `docs/REQUIREMENTS.md` sections 11-13, `docs/ROADMAP.md` stages 1-2 and increment I1, and ADR-001 to ADR-003
before implementation.

## v1 Pilot scope

- v1 Pilot is single-peer: one desktop peer per court. P2P replication, peer join and leader claims are post-v1 (`P2P-*` are
  `Could`); do not implement them without an explicit requirement change.
- PostgreSQL lifecycle, schema migration, upgrade, backup, and restore are application responsibilities on Windows and macOS.
- The production entry point must start managed PostgreSQL and use JDBC journals; an in-memory default is not v1 evidence.
- Client delivery is at-least-once with deduplication by stable event ID; reconnect resync must not apply an event twice.

## Required evidence

1. PostgreSQL starts, migrates, stops, and recovers after an abnormal exit; record clean macOS and Windows runs in the PR.
2. Journal, device registry and projections survive a process kill and restart with identical state.
3. Backup/restore and rebuild-from-events are tested against the same acceptance fixture.
4. `RealPostgresLifecycleTest` runs against a real binary, not only the JVM fixture.

## Design rules

- Keep versioned migrations append-only; never edit a released migration.
- Keep transport, persistence and domain projection code separate.
- Surface persistence failures to the operator (`UI-007`) and in `/v1/health` readiness.

## Post-v1 P2P constraints

When P2P work is scheduled, preserve ADR-001 and ADR-002: no central coordinator, full journal per peer, per-owner
sequence gaps detected and requested, ownership validated before projection, and multi-process tests with at least three
peers. Do not replace P2P with a central server without an explicit requirement change.
