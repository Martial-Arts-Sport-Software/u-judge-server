package org.mass

import org.slf4j.LoggerFactory

/**
 * Structured operational log (`NFR-008`). Events are correlated by stable IDs and typed codes only: no surnames,
 * reconnect credentials, delivery proofs or command payloads.
 */
object ServerLog {
    const val LOGGER_NAME = "org.mass.server"

    // Lazy so that useDirectory() runs before logback reads its configuration.
    private val logger by lazy { LoggerFactory.getLogger(LOGGER_NAME) }

    internal fun runtimeStarted(peerId: String, httpPort: Int) = logger.atInfo().setMessage("runtime_started")
        .addKeyValue("peerId", peerId).addKeyValue("httpPort", httpPort).log()

    internal fun runtimeFailed(diagnostic: String) = logger.atError().setMessage("runtime_failed")
        .addKeyValue("diagnostic", diagnostic).log()

    internal fun runtimeStopped() = logger.atInfo().setMessage("runtime_stopped").log()

    internal fun deviceRegistryChanged(change: String, deviceId: String) = logger.atInfo().setMessage("device_registry_changed")
        .addKeyValue("change", change).addKeyValue("deviceId", deviceId).log()

    internal fun handshakeRejected(code: String) = logger.atWarn().setMessage("realtime_handshake_rejected")
        .addKeyValue("code", code).log()

    internal fun deviceConnected(deviceId: String?) = logger.atInfo().setMessage("realtime_connected")
        .addKeyValue("deviceId", deviceId).log()

    internal fun deviceDisconnected(deviceId: String?) = logger.atInfo().setMessage("realtime_disconnected")
        .addKeyValue("deviceId", deviceId).log()

    internal fun commandAcknowledged(deviceId: String?, eventId: String) = logger.atInfo().setMessage("command_acknowledged")
        .addKeyValue("deviceId", deviceId).addKeyValue("eventId", eventId).log()

    internal fun commandRejected(deviceId: String?, code: String) = logger.atWarn().setMessage("command_rejected")
        .addKeyValue("deviceId", deviceId).addKeyValue("code", code).log()

    /** Sets the log directory for `logback.xml` before the first logger is created. */
    fun useDirectory(directory: java.nio.file.Path) {
        if (System.getProperty(LOG_DIRECTORY_PROPERTY) == null) {
            System.setProperty(LOG_DIRECTORY_PROPERTY, directory.toAbsolutePath().toString())
        }
    }

    private const val LOG_DIRECTORY_PROPERTY = "uJudge.logDirectory"
}
