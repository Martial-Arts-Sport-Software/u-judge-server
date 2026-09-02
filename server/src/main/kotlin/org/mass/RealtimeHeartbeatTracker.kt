package org.mass

import java.time.Duration
import java.time.Instant

class RealtimeHeartbeatTracker(
    private val timeout: Duration,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(!timeout.isNegative && !timeout.isZero)
    }

    private val deadlinesByCredential = mutableMapOf<String, Instant>()

    fun connected(reconnectCredential: String) = synchronized(this) {
        deadlinesByCredential[reconnectCredential] = now().plus(timeout)
    }

    fun heartbeat(reconnectCredential: String) = synchronized(this) {
        if (reconnectCredential in deadlinesByCredential) {
            deadlinesByCredential[reconnectCredential] = now().plus(timeout)
        }
    }

    fun expireInactive(): Set<String> = synchronized(this) {
        val expiredCredentials = deadlinesByCredential
            .filterValues { deadline -> !deadline.isAfter(now()) }
            .keys
            .toSet()
        deadlinesByCredential.keys.removeAll(expiredCredentials)
        expiredCredentials
    }

    fun isConnected(reconnectCredential: String): Boolean = synchronized(this) {
        reconnectCredential in deadlinesByCredential
    }

    fun disconnected(reconnectCredential: String) = synchronized(this) {
        deadlinesByCredential.remove(reconnectCredential)
    }
}
