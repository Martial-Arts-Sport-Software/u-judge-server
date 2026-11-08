package org.mass

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * `--smoke-test`: a headless check of an installed application (I7). Starts the production [ServerRuntime] with the bundled
 * PostgreSQL, reads `GET /v1/health` and `GET /v1/metadata` over HTTPS trusting only this peer's certificate, stops the
 * runtime and confirms that the PostgreSQL postmaster exited. The installer workflow runs it on clean CI machines; it
 * writes no domain event and opens no window. It uses only `java.base`, so it runs in the trimmed runtime of the installer.
 */
object ServerSmokeCheck {
    const val FLAG = "--smoke-test"

    data class Options(val dataDirectory: Path?, val port: Int?, val report: Path?)

    /** The options of `--smoke-test [--data-directory=DIR] [--port=N] [--report=FILE]`, or null without the flag. */
    fun options(args: Array<String>): Options? {
        if (FLAG !in args) return null
        fun value(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.takeIf(String::isNotBlank)
        return Options(
            dataDirectory = value("data-directory")?.let(Path::of),
            port = value("port")?.toInt(),
            report = value("report")?.let(Path::of),
        )
    }

    /** Runs the check and returns the process exit code: `0` when it passed. */
    fun run(options: Options, environment: ServerRuntimeConfiguration = ServerRuntimeConfiguration.fromEnvironment()): Int {
        val configuration = environment.copy(
            applicationDataDirectory = options.dataDirectory ?: environment.applicationDataDirectory,
            port = options.port ?: environment.port,
        )
        ServerLog.useDirectory(configuration.applicationDataDirectory.resolve("logs"))
        val lines = linkedMapOf<String, String>()
        val passed = try {
            check(configuration, lines)
        } catch (failure: Throwable) {
            // Also a linkage error, for example a JDK module missing from the installer runtime.
            lines["diagnostic"] = "${failure::class.simpleName}: ${failure.message}"
            false
        }
        lines["result"] = if (passed) "passed" else "failed"
        val text = lines.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
        print(text)
        options.report?.let { report ->
            report.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(report, text)
        }
        return if (passed) 0 else 1
    }

    private fun check(configuration: ServerRuntimeConfiguration, lines: MutableMap<String, String>): Boolean {
        lines["dataDirectory"] = configuration.applicationDataDirectory.toAbsolutePath().toString()
        val runtime = ServerRuntime(configuration)
        val postmaster = try {
            val state = runtime.start()
            if (state !is ServerRuntimeState.Running) {
                lines["runtime"] = "failed"
                lines["diagnostic"] = (state as? ServerRuntimeState.Failed)?.diagnostic ?: state.toString()
                return false
            }
            lines["runtime"] = "running"
            lines["peerId"] = state.peerId.value
            lines["port"] = state.port.toString()
            val tls = trusting(configuration.applicationDataDirectory.resolve("tls"), state.peerId.value)
            lines["health"] = get(tls, state.port, "/v1/health").substringAfter("\"status\":\"").substringBefore('"')
            val metadata = get(tls, state.port, "/v1/metadata")
            lines["metadataPeerId"] = metadata.substringAfter("\"peerId\":\"").substringBefore('"')
            postmasterPid(configuration)
        } finally {
            runtime.stop()
        }
        lines["runtimeStopped"] = (runtime.state.value == ServerRuntimeState.Stopped).toString()
        // Waits for the postmaster to exit; a lock file left behind means it was terminated rather than shut down.
        val exited = postmaster?.let { pid ->
            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        } ?: false
        lines["postgresStopped"] = exited.toString()
        lines["postgresLockFile"] = if (Files.exists(lockFile(configuration))) "left" else "removed"
        return lines["health"] == "healthy" && lines["metadataPeerId"] == lines["peerId"] &&
            lines["runtimeStopped"] == "true" && exited
    }

    private fun get(tls: SSLContext, port: Int, path: String): String {
        val connection = URI.create("https://127.0.0.1:$port$path").toURL().openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls.socketFactory
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "GET $path returned ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.replace(" ", "")
        } finally {
            connection.disconnect()
        }
    }

    /** TLS that accepts exactly the certificate of this peer (ADR-006), the way a paired phone pins it. */
    private fun trusting(tlsDirectory: Path, peerId: String): SSLContext {
        val certificate = PeerCertificate.loadOrCreate(tlsDirectory, peerId).certificate
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(PeerCertificate.ALIAS, certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }

    private fun lockFile(configuration: ServerRuntimeConfiguration): Path =
        configuration.applicationDataDirectory.resolve("postgres").resolve("postmaster.pid")

    private fun postmasterPid(configuration: ServerRuntimeConfiguration): Long? = runCatching {
        Files.readAllLines(lockFile(configuration)).first().trim().toLong()
    }.getOrNull()
}
