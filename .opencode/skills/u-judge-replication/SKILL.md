---
name: u-judge-replication
description: Use when implementing U'Judge P2P discovery, peer join, replication, PostgreSQL persistence, backup, or recovery.
---

# U'Judge Replication And Persistence

Read `docs/REQUIREMENTS.md` sections 11-13 and `docs/ROADMAP.md` stages 1-2 before implementation.

## Architecture constraints

- U'Judge has no central coordinator. Every peer stores a complete event journal and can continue judging its own brackets during a partition.
- Replication is at-least-once with deduplication by stable event ID.
- Per-owner sequence gaps must be detected, exposed diagnostically, and requested after reconnect. Never silently apply an incomplete sequence.
- Validate event ownership before projecting it. Events from a non-owner must not change a bracket.
- PostgreSQL lifecycle, schema migration, upgrade, backup, and restore are application responsibilities on Windows and macOS.

## Required spike evidence

Before production architecture, prove with at least three local peers:

1. Events created on two peers survive an artificial partition.
2. Reconnect produces identical event-ID sets and projections.
3. Duplicate delivery, restart, and sequence gaps are handled without corruption.
4. PostgreSQL starts, migrates, stops, and recovers on clean macOS and Windows environments.

## Design rules

- Write or update ADRs for the event envelope, P2P protocol, PostgreSQL packaging, and protocol versioning before binding implementation choices.
- Keep replication protocol code separate from persistence and domain projection code.
- Test backup/restore and rebuild-from-events against the same acceptance fixture.
- Do not replace P2P with a central server without an explicit requirement change.
