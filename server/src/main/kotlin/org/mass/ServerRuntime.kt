package org.mass

import com.appstractive.dnssd.publishService
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import org.mass.persistence.LocalPeerIdentity
import org.mass.persistence.ManagedPostgresRuntime
import org.mass.persistence.PostgresCommand
import org.mass.persistence.PostgresPlatform
import org.mass.persistence.PostgresRuntimeConfiguration
import org.mass.persistence.PostgresState
import org.mass.replication.JdbcPeerJournal
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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
        val realtimeCommands = RealtimeCommands(journal = JdbcPeerJournal(peerId.value, dataSource))
        val pairing = try {
            PairingRequests(JdbcDeviceRegistryJournal(JdbcDomainEventStore(dataSource), peerId))
        } catch (exception: Exception) {
            return failStartup("Device registry could not be restored: ${exception.message}")
        }.also { pairingRequests = it }

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
                module(metadata = metadata, pairingRequests = pairing, realtimeCommands = realtimeCommands)
            }.start(wait = false)
        } catch (exception: Exception) {
            return failStartup("HTTPS port ${configuration.port} is unavailable: ${exception.message}")
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { runtimeScope ->
            runtimeScope.launch {
                runCatching {
                    publishService(type = "_u-judge._tcp", name = configuration.serviceName) { port = configuration.port }
                }
            }
            runtimeScope.launch { supervisePostgres(runtime) }
        }
        return ServerRuntimeState.Running(configuration.port, peerId, certificate.verificationCode, lanAddresses())
    }

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
        return ServerRuntimeState.Failed(diagnostic)
    }
}
