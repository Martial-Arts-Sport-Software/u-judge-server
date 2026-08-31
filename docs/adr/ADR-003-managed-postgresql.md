# ADR-003: Managed PostgreSQL Persistence

- Status: Accepted
- Date: 2026-08-31
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

`PostgresProvisioner` now invokes a configured `initdb` command for a missing data directory, requires the resulting
`PG_VERSION` marker, reuses an initialized directory, and refuses to overwrite a nonempty unrecognized directory. Its JVM
fixture tests do not execute a real PostgreSQL binary.

This is partial evidence for the durable-journal adapter, cluster-initialization and process-supervision boundaries. It does
not demonstrate a real PostgreSQL server lifecycle or a clean Windows/macOS installation, so `NFR-004`, `NFR-009`,
`NFR-010` and `P2P-010` remain Partial and Gate G1 remains open.

## Options

| Option | Description | Advantages | Risks | Pilot fit |
| --- | --- | --- | --- | --- |
| A. Bundled PostgreSQL distribution | Desktop installer includes a tested PostgreSQL distribution; U'Judge initializes and supervises it as a child process. | Meets application-managed lifecycle; no separate user installation. | Larger installer; per-OS packaging, licensing, upgrade and port handling. | Recommended baseline. |
| B. User-installed PostgreSQL | Operator installs and configures PostgreSQL before running U'Judge. | Smallest U'Judge installer. | Fails clean-install and application-managed lifecycle requirements. | Rejected by `NFR-010`. |
| C. Docker-managed PostgreSQL | U'Judge starts a local container. | Isolated database process. | Docker Desktop is an external prerequisite and is not suitable for clean Pilot machines. | Rejected for Pilot. |
| D. Embedded alternative database | Replace PostgreSQL with an embedded database. | Simplifies distribution. | Violates the declared PostgreSQL requirement and may change production behavior. | Rejected without a requirement change. |

## Decision

U'Judge bundles a tested PostgreSQL distribution for Windows and macOS. Each desktop peer initializes and supervises one
localhost PostgreSQL child process, stops it on normal application exit, and stores its data outside the installer path in
the operating system's application-data directory. The application automatically selects an unused localhost port.

Before a schema migration, import, or completed session, U'Judge creates an encrypted backup and retains the seven newest
backups. Backup encryption keys are held in OS secure storage. PostgreSQL upgrades create a backup, export the old cluster,
and import it into the new bundled cluster; rollback restores the pre-upgrade backup. Database start, corruption, disk-space,
and port-conflict failures present actionable recovery UI without silently discarding data.

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
| PostgreSQL distribution | Bundled, tested Windows/macOS distribution. |
| Data directory | OS application-data directory outside the installer path. |
| Port-conflict policy | Automatically choose an unused localhost port. |
| Process lifecycle | One supervised PostgreSQL child process per desktop peer; stop on normal app exit. |
| Upgrade and rollback policy | Backup, export/import into the new bundled cluster; restore the pre-upgrade backup to roll back. |
| Backup and retention policy | Encrypted automatic backup before migration, import, and completed session; retain seven newest backups; key in OS secure storage. |
| Failure UX | Actionable recovery UI for start, corruption, disk-space, and port-conflict failures; never silently discard data. |

## Consequences

- Future persistence work extends a checked-in, ordered SQL migration set rather than mutating schema at runtime.
- The database schema only stores the Stage 1 envelope. Domain fields required by `SYS-007`, `AUD-001` and `P2P-006` must
  be added before domain commands are persisted.
- H2 compatibility tests are not a substitute for PostgreSQL acceptance evidence.
