package org.mass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant
import java.util.UUID

/**
 * Append-only history of device pairing decisions (`DEV-004`-`DEV-006`, `AUD-001`). Only SHA-256 hashes of the reconnect
 * credential and delivery proof are recorded; the plaintext credential never leaves process memory before delivery.
 */
sealed interface DeviceRegistryEvent {
    val requestId: String

    @Serializable
    data class Requested(
        override val requestId: String,
        val deviceId: String,
        val surname: String,
        val platform: String,
        val deliveryProofHash: String?,
    ) : DeviceRegistryEvent

    @Serializable
    data class Approved(override val requestId: String, val credentialHash: String) : DeviceRegistryEvent

    /** A new credential for an approved device whose previous plaintext was lost with the process before delivery. */
    @Serializable
    data class CredentialIssued(override val requestId: String, val credentialHash: String) : DeviceRegistryEvent

    @Serializable
    data class Rejected(override val requestId: String) : DeviceRegistryEvent

    @Serializable
    data class Revoked(override val requestId: String) : DeviceRegistryEvent
}

interface DeviceRegistryJournal {
    fun append(deviceId: String, event: DeviceRegistryEvent)

    fun events(): List<DeviceRegistryEvent>

    /** Keeps the registry in process memory, as tests and the pre-persistence contract did. */
    object InMemory : DeviceRegistryJournal {
        override fun append(deviceId: String, event: DeviceRegistryEvent) = Unit

        override fun events(): List<DeviceRegistryEvent> = emptyList()
    }
}

class JdbcDeviceRegistryJournal(
    private val store: JdbcDomainEventStore,
    private val peerId: PeerId,
    private val now: () -> Instant = Instant::now,
) : DeviceRegistryJournal {
    override fun append(deviceId: String, event: DeviceRegistryEvent) {
        val (source, author) = when (event) {
            is DeviceRegistryEvent.Requested -> "judge_device" to "device:$deviceId"
            else -> "operator" to "operator"
        }
        store.appendPeerEvent(
            JdbcDomainEventStore.PeerScopedEvent(
                eventId = EventId(UUID.randomUUID().toString()),
                peerId = peerId,
                deviceId = deviceId,
                source = EventSource(source),
                author = author,
                occurredAt = now(),
                type = typeOf(event),
                payload = encode(event),
            ),
        )
    }

    override fun events(): List<DeviceRegistryEvent> = store.peerEvents(TYPES.keys).map { stored ->
        when (TYPES.getValue(stored.type)) {
            DeviceRegistryEvent.Requested::class -> json.decodeFromString<DeviceRegistryEvent.Requested>(stored.payload)
            DeviceRegistryEvent.Approved::class -> json.decodeFromString<DeviceRegistryEvent.Approved>(stored.payload)
            DeviceRegistryEvent.CredentialIssued::class ->
                json.decodeFromString<DeviceRegistryEvent.CredentialIssued>(stored.payload)
            DeviceRegistryEvent.Rejected::class -> json.decodeFromString<DeviceRegistryEvent.Rejected>(stored.payload)
            else -> json.decodeFromString<DeviceRegistryEvent.Revoked>(stored.payload)
        }
    }

    private fun typeOf(event: DeviceRegistryEvent): String = TYPES.entries.first { it.value == event::class }.key

    private fun encode(event: DeviceRegistryEvent): String = when (event) {
        is DeviceRegistryEvent.Requested -> json.encodeToString(event)
        is DeviceRegistryEvent.Approved -> json.encodeToString(event)
        is DeviceRegistryEvent.CredentialIssued -> json.encodeToString(event)
        is DeviceRegistryEvent.Rejected -> json.encodeToString(event)
        is DeviceRegistryEvent.Revoked -> json.encodeToString(event)
    }

    private companion object {
        val json = Json { explicitNulls = false }
        val TYPES = mapOf(
            "device_pairing_requested" to DeviceRegistryEvent.Requested::class,
            "device_pairing_approved" to DeviceRegistryEvent.Approved::class,
            "device_credential_issued" to DeviceRegistryEvent.CredentialIssued::class,
            "device_pairing_rejected" to DeviceRegistryEvent.Rejected::class,
            "device_pairing_revoked" to DeviceRegistryEvent.Revoked::class,
        )
    }
}
