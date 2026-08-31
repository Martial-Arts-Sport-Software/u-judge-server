# ADR-003: Managed PostgreSQL Persistence Spike

- Status: Proposed
- Date: 2026-08-30
- Requirements: `NFR-004`, `NFR-009`, `NFR-010`, `P2P-010`

## Context

Every U'Judge peer must recover its append-only journal after a restart. The Stage 1 PostgreSQL spike must also determine
how the desktop application provisions, starts, stops and upgrades its local PostgreSQL instance on clean Windows and macOS
installations.

## Current evidence

The server has a JDBC-backed `JdbcPeerJournal` and versioned `V1__peer_journal.sql` migration. The store persists the
ADR-001 envelope fields, preserves event-ID idempotency and owner/sequence uniqueness, and resumes the local sequence after
the store is recreated. Its focused test runs against H2 in PostgreSQL compatibility mode because this development machine
has neither a local PostgreSQL installation nor a running container runtime. `ManagedPostgres` supervises a configured local
child process, reports an occupied loopback port or startup failure, and stops only that child. Its tests exercise those
lifecycle states with a disposable JVM fixture.

This is partial evidence for the durable-journal adapter and process-supervision boundary. It does not demonstrate a real
PostgreSQL server lifecycle or a clean Windows/macOS installation, so `NFR-004`, `NFR-009`, `NFR-010` and `P2P-010` remain
Partial and Gate G1 remains open.

## Decision pending

The managed PostgreSQL distribution, data directory, process supervision, port-conflict handling, upgrade procedure and
backup/restore tooling remain undecided. ADR-003 can be accepted only after the same migration and restart fixture runs
against an application-managed PostgreSQL instance on clean macOS and Windows environments.

## Consequences

- Future persistence work extends a checked-in, ordered SQL migration set rather than mutating schema at runtime.
- The database schema only stores the Stage 1 envelope. Domain fields required by `SYS-007`, `AUD-001` and `P2P-006` must
  be added before domain commands are persisted.
- H2 compatibility tests are not a substitute for PostgreSQL acceptance evidence.
