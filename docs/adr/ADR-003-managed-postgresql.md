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
fixture tests do not execute a real PostgreSQL binary. `ManagedPostgres.restart()` replaces an unexpectedly exited child
with a new supervised process after repeating the provisioning/start path.

`PostgresCommand.withAvailableLoopbackPort()` obtains an available IPv4 loopback (`127.0.0.1`) port for a new command. It
does not reserve that port across child-process launch, so `ManagedPostgres.start()` continues to diagnose an occupied port
immediately before launching PostgreSQL.

`PostgresRuntimeConfiguration` derives the platform-specific bundled `initdb` and `postgres` commands, cluster directory,
loopback port, database role and JDBC URLs from one configuration. It rejects an application-data path below the installation
directory and an unsafe database name. The provisioner appends the one authoritative `-D` argument to `initdb`; configuration
does not duplicate it. `ManagedPostgresRuntime` waits for a JDBC connection to the supervised child, creates the configured
database when absent, and exposes its datasource only after both steps succeed. A readiness or database-creation failure stops
the child and returns a diagnostic rather than publishing a nonfunctional JDBC URL.

`RealPostgresLifecycleTest` is an opt-in acceptance test for an actual bundled PostgreSQL binary. It initializes a disposable
cluster outside the repository, starts the process, applies ordered journal migrations, persists an event, restarts, and proves
journal recovery and sequence continuity. Run it with
`./gradlew :server:test --tests org.mass.persistence.RealPostgresLifecycleTest -DuJudge.postgres.installationDirectory=/path/to/bundle`,
where the bundle root contains `postgresql/bin/initdb` and `postgresql/bin/postgres` (use `.exe` on Windows). It is skipped when
the property is absent, so normal CI does not claim real-binary evidence.

This is partial evidence for the durable-journal adapter, cluster-initialization and process-supervision boundaries. The
real-binary test has not run on the current development machine because no PostgreSQL bundle is installed. It does not
demonstrate clean Windows/macOS installation, backup/restore, or the cross-repository mobile reconnect flow, so `NFR-004`,
`NFR-009`, `NFR-010` and `P2P-010` remain Partial and Gate G1 remains open.

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
