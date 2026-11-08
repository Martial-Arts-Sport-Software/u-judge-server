package org.mass

import com.appstractive.dnssd.publishService
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mass.applications.CompetitionApplicationsService
import org.mass.bout.CourtBouts
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutService
import org.mass.performance.TechnicalPerformanceService
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.domain.SessionState
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CourtProgress
import org.mass.draw.CompetitionDrawService
import org.mass.draw.JdbcCompetitionDrawJournal
import org.mass.persistence.BackupInspection
import org.mass.persistence.JdbcDomainEventStore
import org.mass.persistence.JournalBackup
import org.mass.persistence.JournalBackups
import org.mass.persistence.JournalRestore
import org.mass.persistence.LocalPeerIdentity
import org.mass.persistence.ManagedPostgresRuntime
import org.mass.persistence.PostgresCommand
import org.mass.persistence.PostgresPlatform
import org.mass.persistence.PostgresRuntimeConfiguration
import org.mass.persistence.PostgresState
import org.mass.report.CompetitionReports
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Where the bundled PostgreSQL lives and where this peer keeps its cluster, logs and other local state. */
data class ServerRuntimeConfiguration(
    val installationDirectory: Path?,
    val applicationDataDirectory: Path,
    /** The only listener: HTTPS and WSS with the peer certificate (ADR-006). */
    val port: Int = DEFAULT_PORT,
    val serviceName: String = "JudgeServer-1",
) {
    companion object {
        const val DEFAULT_PORT = 8443

        /**
         * Uses `uJudge.postgres.installationDirectory` or the Compose Desktop resources directory for the PostgreSQL bundle,
         * and `uJudge.dataDirectory` or the OS application-data directory for local state.
         */
        fun fromEnvironment(): ServerRuntimeConfiguration = ServerRuntimeConfiguration(
            installationDirectory = (
                System.getProperty("uJudge.postgres.installationDirectory")
                    ?: System.getProperty("compose.application.resources.dir")
                )?.let(Path::of),
            applicationDataDirectory = System.getProperty("uJudge.dataDirectory")?.let(Path::of)
                ?: defaultApplicationDataDirectory(),
        )

        fun defaultApplicationDataDirectory(
            osName: String = System.getProperty("os.name"),
            userHome: Path = Path.of(System.getProperty("user.home")),
            environment: Map<String, String> = System.getenv(),
        ): Path = when {
            osName.startsWith("Mac") -> userHome.resolve("Library/Application Support/UJudge")
            osName.startsWith("Windows") ->
                (environment["LOCALAPPDATA"]?.let(Path::of) ?: userHome.resolve("AppData/Local")).resolve("UJudge")
            else -> (environment["XDG_DATA_HOME"]?.let(Path::of) ?: userHome.resolve(".local/share")).resolve("ujudge")
        }
    }
}

sealed interface ServerRuntimeState {
    data object Stopped : ServerRuntimeState

    data object Starting : ServerRuntimeState

    /**
     * [verificationCode] is shown to the operator and compared with the judge's screen before approval (ADR-006);
     * [addresses] are this computer's LAN IPv4 addresses a judge can enter manually.
     */
    data class Running(
        val port: Int,
        val peerId: PeerId,
        val verificationCode: String,
        val addresses: List<String> = emptyList(),
    ) : ServerRuntimeState

    /** A persistence or network failure the operator must see; the diagnostic contains no personal data. */
    data class Failed(val diagnostic: String) : ServerRuntimeState
}

/** The result of «Восстановить» (ADR-012 §7). */
sealed interface RestoreOutcome {
    /** The journal of the backup is active; [safetyBackup] holds the journal it replaced. */
    data class Restored(val events: Int, val sha256: String, val safetyBackup: Path, val database: String) : RestoreOutcome

    /** The file is not a restorable backup ([code] of `JournalBackup`); nothing changed. */
    data class Rejected(val code: String) : RestoreOutcome

    /** Writing or verifying failed; the current journal stays active and unchanged. */
    data class Failed(val diagnostic: String) : RestoreOutcome
}

/**
 * Production composition of one desktop peer: managed PostgreSQL, schema migration, durable journals, the HTTP/WebSocket
 * module and mDNS publication. Supervises PostgreSQL while running and reports its failure instead of silently continuing.
 */
class ServerRuntime(
    private val configuration: ServerRuntimeConfiguration,
    private val supervisionInterval: Duration = Duration.ofSeconds(2),
) {
    private val mutableState = MutableStateFlow<ServerRuntimeState>(ServerRuntimeState.Stopped)
    val state: StateFlow<ServerRuntimeState> = mutableState.asStateFlow()

    /** Operator pairing service of the current run, used in-process by the desktop UI. */
    @Volatile
    var pairingRequests: PairingRequests = PairingRequests()
        private set

    /** Import of competition applications (ADR-005); null while the server and its journal are not running. */
    @Volatile
    var competitionApplications: CompetitionApplicationsService? = null
        private set

    /** The draw of the current competition (ADR-007, ADR-008); null while the server and its journal are not running. */
    @Volatile
    var competitionDraws: CompetitionDrawService? = null
        private set

    /** Courts, bout numbers and the court panel of this desktop (ADR-008); null while the server is not running. */
    @Volatile
    var competitionCourts: CompetitionCourtsService? = null
        private set

    /** The realtime dispatcher and the Kerugi bouts of this court (ADR-009); null while the server is not running. */
    @Volatile
    var realtime: RealtimeHub? = null
        private set

    /** «Начать» and the operator actions of the bout, used in-process by the desktop; null while the server is not running. */
    @Volatile
    var courtBouts: CourtBouts? = null
        private set

    /** The backups of this peer (ADR-012 §6); null while the server and its journal are not running. */
    @Volatile
    var backups: JournalBackups? = null
        private set

    /** History, XLSX and CSV of the current competition (ADR-012); null while the server is not running. */
    @Volatile
    var reports: CompetitionReports? = null
        private set

    private var postgres: ManagedPostgresRuntime? = null
    private var httpServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(): ServerRuntimeState {
        if (mutableState.value is ServerRuntimeState.Running) return mutableState.value
        mutableState.value = ServerRuntimeState.Starting
        return startComponents().also { state ->
            mutableState.value = state
            when (state) {
                is ServerRuntimeState.Running -> ServerLog.runtimeStarted(state.peerId.value, state.port)
                is ServerRuntimeState.Failed -> ServerLog.runtimeFailed(state.diagnostic)
                else -> Unit
            }
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        httpServer?.stop(1_000, 5_000)
        httpServer = null
        postgres?.stop()
        postgres = null
        competitionApplications = null
        competitionDraws = null
        competitionCourts = null
        realtime = null
        courtBouts = null
        backups = null
        reports = null
        if (mutableState.value != ServerRuntimeState.Stopped) ServerLog.runtimeStopped()
        mutableState.value = ServerRuntimeState.Stopped
    }

    @Synchronized
    fun restart(): ServerRuntimeState {
        stop()
        return start()
    }

    private fun startComponents(): ServerRuntimeState {
        val installationDirectory = configuration.installationDirectory
        if (installationDirectory == null || !Files.isDirectory(installationDirectory.resolve("postgresql").resolve("bin"))) {
            return ServerRuntimeState.Failed("Bundled PostgreSQL was not found in ${installationDirectory ?: "the application resources"}")
        }
        restoreExecutablePermissions(installationDirectory.resolve("postgresql").resolve("bin"))?.let {
            return ServerRuntimeState.Failed(it)
        }
        val runtime = ManagedPostgresRuntime(
            PostgresRuntimeConfiguration(
                installationDirectory = installationDirectory,
                applicationDataDirectory = configuration.applicationDataDirectory,
                port = PostgresCommand.withAvailableLoopbackPort(listOf("postgres")).port,
                platform = PostgresPlatform.current(),
                databaseName = activeDatabase(),
            ),
        )
        postgres = runtime
        when (val postgresState = runtime.start()) {
            is PostgresState.Failed -> return failStartup(postgresState.diagnostic)
            PostgresState.Stopped -> return failStartup("PostgreSQL did not start")
            is PostgresState.Running -> Unit
        }
        val dataSource = runtime.dataSource ?: return failStartup("PostgreSQL stopped during startup")

        val peerId = try {
            LocalPeerIdentity.loadOrCreate(dataSource)
        } catch (exception: Exception) {
            return failStartup("Database migration failed: ${exception.message}")
        }
        val metadata = ServerMetadata.local(peerId = peerId.value)
        val store = JdbcDomainEventStore(dataSource)
        val pairing = try {
            PairingRequests(JdbcDeviceRegistryJournal(store, peerId))
        } catch (exception: Exception) {
            return failStartup("Device registry could not be restored: ${exception.message}")
        }.also { pairingRequests = it }
        val journalBackups = JournalBackups(configuration.applicationDataDirectory.resolve("backups"), dataSource)
            .also { backups = it }
        competitionApplications = try {
            CompetitionApplicationsService(
                journal = JdbcCompetitionApplicationsJournal(store, peerId),
                backup = { journalBackups.create(JournalBackups.BEFORE_IMPORT) },
            )
        } catch (exception: Exception) {
            return failStartup("Competition applications could not be restored: ${exception.message}")
        }
        competitionDraws = try {
            CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), checkNotNull(competitionApplications).history, peerId)
        } catch (exception: Exception) {
            return failStartup("Competition draw could not be restored: ${exception.message}")
        }
        competitionCourts = CompetitionCourtsService(checkNotNull(competitionDraws), peerId) {
            realtime?.let { CourtBouts.progress(it) } ?: CourtProgress.None
        }
        val hub = try {
            // Bouts and technical performances share the court's session journal (ADR-011 §2).
            val sessions = JdbcBoutEventJournal(store)
            RealtimeHub(KerugiBoutService(sessions, peerId), TechnicalPerformanceService(sessions, peerId))
        } catch (exception: Exception) {
            return failStartup("Bouts and performances could not be restored: ${exception.message}")
        }.also { realtime = it }
        courtBouts = CourtBouts(hub, checkNotNull(competitionDraws), checkNotNull(competitionCourts), pairing)
        reports = CompetitionReports(checkNotNull(competitionApplications), checkNotNull(competitionCourts), hub)

        val certificate = try {
            PeerCertificate.loadOrCreate(configuration.applicationDataDirectory.resolve("tls"), peerId.value)
        } catch (exception: Exception) {
            return failStartup("TLS certificate could not be loaded: ${exception.message}")
        }
        httpServer = try {
            embeddedServer(
                Netty,
                environment = applicationEnvironment(),
                configure = {
                    sslConnector(
                        keyStore = certificate.keyStore,
                        keyAlias = PeerCertificate.ALIAS,
                        keyStorePassword = { certificate.password.toCharArray() },
                        privateKeyPassword = { certificate.password.toCharArray() },
                    ) {
                        host = "0.0.0.0"
                        port = configuration.port
                    }
                },
            ) {
                module(metadata = metadata, pairingRequests = pairing, realtime = hub)
            }.start(wait = false)
        } catch (exception: Exception) {
            return failStartup("HTTPS port ${configuration.port} is unavailable: ${exception.message}")
        }

        val addresses = lanAddresses()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { runtimeScope ->
            // dns-sd-kt binds one JmDNS instance to a single address (by default the first site-local one, which may be
            // a VM or VPN interface), so the service is published on every LAN address judges can reach.
            addresses.forEach { address ->
                runtimeScope.launch {
                    runCatching {
                        publishService(type = "_u-judge._tcp", name = configuration.serviceName) {
                            port = configuration.port
                            this.addresses = listOf(address)
                        }
                    }.onFailure { failure ->
                        if (failure !is CancellationException) ServerLog.mdnsFailed(address, failure.message.orEmpty())
                    }
                }
            }
            runtimeScope.launch { supervisePostgres(runtime) }
            // `BAK-002`: a backup after every completed bout or performance, off the scoring path; a failure is shown to the
            // operator.
            runtimeScope.launch {
                var completed = completedBouts(hub)
                combine(hub.bouts.revision, hub.performances.revision) { bouts, performances -> bouts to performances }.collect {
                    val now = completedBouts(hub)
                    if (now > completed) {
                        completed = now
                        runCatching { journalBackups.create(JournalBackups.AFTER_SESSION) }
                            .onFailure { ServerLog.backupFailed(it.message.orEmpty()) }
                    }
                }
            }
            // The bout clock (ADR-010 §2): periods end at their deadline and the running clock is saved for a restart.
            runtimeScope.launch {
                while (isActive) {
                    delay(TIMER_TICK_MILLIS)
                    hub.tick()
                }
            }
        }
        return ServerRuntimeState.Running(configuration.port, peerId, certificate.verificationCode, addresses)
    }

    /**
     * «Восстановить» (ADR-012 §7): checks [file], backs up the current journal, restores the backup into a new database of
     * the managed cluster, verifies it, journals `journal_restored`, makes the new database active and restarts the
     * components, which rebuild every projection from the restored journal. A failure drops the new database and leaves
     * the current one active; the replaced database is kept (`AUD-001`).
     */
    @Synchronized
    fun restore(file: Path, now: () -> Instant = Instant::now): RestoreOutcome {
        val backup = when (val inspection = JournalBackup.inspect(file)) {
            is BackupInspection.Invalid -> return RestoreOutcome.Rejected(inspection.code)
            is BackupInspection.Valid -> inspection.backup
        }
        val runtime = postgres?.takeIf { mutableState.value is ServerRuntimeState.Running }
            ?: return RestoreOutcome.Failed("The server is not running")
        val journalBackups = backups ?: return RestoreOutcome.Failed("The server is not running")
        val safetyBackup = try {
            journalBackups.create(JournalBackups.BEFORE_RESTORE)
        } catch (exception: Exception) {
            return RestoreOutcome.Failed("The current journal could not be backed up: ${exception.message}")
        }
        val restoredAt = now()
        val database = "${PostgresRuntimeConfiguration.DEFAULT_DATABASE}_r${DATABASE_SUFFIX.format(restoredAt)}"
        try {
            runtime.createDatabase(database)
        } catch (exception: Exception) {
            return RestoreOutcome.Failed("A database for the restore could not be created: ${exception.message}")
        }
        val target = runtime.dataSource(database)
        val result = try {
            JournalBackup.restore(backup, target, restoredAt)
        } catch (exception: Exception) {
            JournalRestore.Failed(JournalBackup.WRITE_FAILED, "The backup could not be restored: ${exception.message}")
        }
        val restored = when (result) {
            is JournalRestore.Restored -> result
            is JournalRestore.Failed -> {
                runCatching { runtime.dropDatabase(database) }
                return RestoreOutcome.Failed(result.diagnostic)
            }
        }
        try {
            val peer = LocalPeerIdentity.loadOrCreate(target)
            JdbcDomainEventStore(target).appendPeerEvent(
                JdbcDomainEventStore.PeerScopedEvent(
                    eventId = EventId.new(),
                    peerId = peer,
                    deviceId = null,
                    source = EventSource("operator"),
                    author = "operator",
                    occurredAt = restoredAt,
                    type = JOURNAL_RESTORED,
                    payload = buildJsonObject {
                        put("file", JsonPrimitive(file.fileName.toString()))
                        put("sha256", JsonPrimitive(restored.sha256))
                        put("events", JsonPrimitive(restored.events))
                        put("reason", JsonPrimitive(backup.header.reason))
                        put("safety_backup", JsonPrimitive(safetyBackup.fileName.toString()))
                    }.toString(),
                ),
            )
            val pointer = databasePointer()
            Files.createDirectories(pointer.parent)
            val temporary = pointer.resolveSibling("${pointer.fileName}.tmp")
            Files.writeString(temporary, database)
            Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: Exception) {
            runCatching { runtime.dropDatabase(database) }
            return RestoreOutcome.Failed("The restored journal could not be activated: ${exception.message}")
        }
        ServerLog.journalRestored(database, restored.events)
        restart()
        return RestoreOutcome.Restored(restored.events, restored.sha256, safetyBackup, database)
    }

    /** The database the components use: `u_judge`, or the target of the latest restore (ADR-012 §7). */
    private fun activeDatabase(): String = runCatching { Files.readString(databasePointer()).trim() }
        .getOrNull()
        ?.takeIf { it.matches(PostgresRuntimeConfiguration.IDENTIFIER) }
        ?: PostgresRuntimeConfiguration.DEFAULT_DATABASE

    private fun databasePointer(): Path = configuration.applicationDataDirectory.resolve("database")

    private fun completedBouts(hub: RealtimeHub): Int = hub.bouts.bouts().count { it.state == SessionState.COMPLETED } +
        hub.performances.performances().count { it.state == SessionState.COMPLETED }

    private suspend fun supervisePostgres(runtime: ManagedPostgresRuntime) {
        while (scope?.isActive != false) {
            delay(supervisionInterval.toMillis())
            val postgresState = runtime.state
            if (postgresState is PostgresState.Failed && mutableState.value is ServerRuntimeState.Running) {
                mutableState.value = ServerRuntimeState.Failed(postgresState.diagnostic)
                ServerLog.runtimeFailed(postgresState.diagnostic)
                return
            }
        }
    }

    /**
     * Desktop packaging drops Unix permission bits of app resources, so the bundled PostgreSQL binaries arrive without `+x`.
     * Restores them before the first start; a read-only location (for example a mounted DMG) is reported to the operator.
     */
    internal fun restoreExecutablePermissions(binDirectory: Path): String? {
        if (PostgresPlatform.current() == PostgresPlatform.Windows) return null
        val binaries = Files.list(binDirectory).use { files -> files.filter(Files::isRegularFile).toList() }
        val notExecutable = binaries.filterNot(Files::isExecutable).filterNot { it.toFile().setExecutable(true, false) }
        return notExecutable.takeIf { it.isNotEmpty() }?.let {
            "Bundled PostgreSQL binaries are not executable and cannot be fixed in $binDirectory; install the application " +
                "into a writable folder such as Applications"
        }
    }

    /** Site-local IPv4 addresses of active non-loopback interfaces, the ones reachable from the venue Wi-Fi. */
    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun failStartup(diagnostic: String): ServerRuntimeState {
        httpServer?.stop(0, 0)
        httpServer = null
        postgres?.stop()
        postgres = null
        competitionApplications = null
        competitionDraws = null
        competitionCourts = null
        realtime = null
        courtBouts = null
        backups = null
        reports = null
        return ServerRuntimeState.Failed(diagnostic)
    }
}

/** The event of a restore in the restored journal (ADR-012 §7.2). */
const val JOURNAL_RESTORED = "journal_restored"

private val DATABASE_SUFFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC)

/** How often the bout clock is checked: the end of a period is journaled at most this long after its deadline. */
private const val TIMER_TICK_MILLIS = 200L
