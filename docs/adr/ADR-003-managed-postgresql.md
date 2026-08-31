# ADR-003: Managed PostgreSQL Persistence

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
has neither a local PostgreSQL installation nor a running container runtime.

This is evidence for a durable-journal adapter only. It does not demonstrate the PostgreSQL server lifecycle or a clean
Windows/macOS installation, so `NFR-004`, `NFR-009`, `NFR-010` and `P2P-010` remain Partial and Gate G1 remains open.

## Options

| Option | Description | Advantages | Risks | Pilot fit |
| --- | --- | --- | --- | --- |
| A. Bundled PostgreSQL distribution | Desktop installer includes a tested PostgreSQL distribution; U'Judge initializes and supervises it as a child process. | Meets application-managed lifecycle; no separate user installation. | Larger installer; per-OS packaging, licensing, upgrade and port handling. | Recommended baseline. |
| B. User-installed PostgreSQL | Operator installs and configures PostgreSQL before running U'Judge. | Smallest U'Judge installer. | Fails clean-install and application-managed lifecycle requirements. | Rejected by `NFR-010`. |
| C. Docker-managed PostgreSQL | U'Judge starts a local container. | Isolated database process. | Docker Desktop is an external prerequisite and is not suitable for clean Pilot machines. | Rejected for Pilot. |
| D. Embedded alternative database | Replace PostgreSQL with an embedded database. | Simplifies distribution. | Violates the declared PostgreSQL requirement and may change production behavior. | Rejected without a requirement change. |

## Product-Owner Decisions

1. **Distribution:** approve bundled PostgreSQL for Windows and macOS, or explicitly change the Pilot requirement.
2. **Data location:** approve an application-data directory outside the installer path. Specify whether operators can choose a
   different directory during setup.
3. **Port conflicts:** choose whether U'Judge selects an unused localhost port automatically or requires an operator-selected
   port. The selected port must never be exposed as a LAN service.
4. **Process ownership:** approve one PostgreSQL child process per U'Judge peer, supervised by the desktop application and
   stopped on normal app exit.
5. **Upgrade policy:** choose in-place PostgreSQL upgrade, export/import into a bundled version, or a Pilot rule that blocks
   upgrades while preserving backup/restore. State the supported downgrade behavior.
6. **Backup policy:** approve the backup location, retention, encryption-at-rest requirement, and whether a backup is required
   before schema migration as well as before import/completed sessions.
7. **Failure policy:** define the operator-visible behavior for database start failure, corrupted data directory, insufficient
   disk space, and a port conflict.

## Required Acceptance Evidence

ADR-003 can be accepted only after the selected option proves on clean Windows and macOS machines:

- initialize, start, stop, forced-stop recovery, and restart;
- ordered schema migration from an earlier Pilot schema;
- durable journal and cursor recovery after restart;
- port-conflict diagnosis without data loss;
- backup and restore into a clean data directory;
- installer size, third-party license obligations, and upgrade/rollback limits.

## Decision Record

| Field | Approved value |
| --- | --- |
| PostgreSQL distribution | _Pending product-owner decision_ |
| Data directory | _Pending product-owner decision_ |
| Port-conflict policy | _Pending product-owner decision_ |
| Process lifecycle | _Pending product-owner decision_ |
| Upgrade and rollback policy | _Pending product-owner decision_ |
| Backup and retention policy | _Pending product-owner decision_ |
| Failure UX | _Pending product-owner decision_ |

## Consequences

- Future persistence work extends a checked-in, ordered SQL migration set rather than mutating schema at runtime.
- The database schema only stores the Stage 1 envelope. Domain fields required by `SYS-007`, `AUD-001` and `P2P-006` must
  be added before domain commands are persisted.
- H2 compatibility tests are not a substitute for PostgreSQL acceptance evidence.
