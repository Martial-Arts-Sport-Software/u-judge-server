package org.mass

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceRegistryPersistenceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")

    @Test
    fun `approved device keeps its reconnect credential after the registry is rebuilt`() {
        val dataSource = dataSource("registry-restart")
        val first = registry(dataSource)
        val pending = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("android-1", "Ivanov", "android", "proof-1")))
        val credential = assertIs<PairingApproval.Accepted>(first.approve(pending.request.requestId)).request.reconnectCredential

        val restarted = registry(dataSource)

        assertTrue(restarted.isReconnectCredentialActive(credential))
        assertEquals("android-1", restarted.deviceIdFor(credential))
        assertEquals(
            listOf(OperatorDevice(pending.request.requestId, "android-1", "Ivanov", "android", DeviceConnectionState.DISCONNECTED, revoked = false)),
            restarted.operatorRegistry().devices,
        )
    }

    @Test
    fun `status after a restart issues a new credential and invalidates the undelivered one`() {
        val dataSource = dataSource("registry-reissue")
        val first = registry(dataSource)
        val pending = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("ios-1", "Petrova", "ios", "proof-2")))
        val lost = assertIs<PairingApproval.Accepted>(first.approve(pending.request.requestId)).request.reconnectCredential

        val restarted = registry(dataSource)
        val delivered = requireNotNull(restarted.status(pending.request.requestId, "proof-2", secureDelivery = true)?.reconnectCredential)

        assertNotEquals(lost, delivered)
        assertEquals(delivered, restarted.status(pending.request.requestId, "proof-2", secureDelivery = true)?.reconnectCredential)
        assertTrue(registry(dataSource).isReconnectCredentialActive(delivered))
        assertEquals(false, registry(dataSource).isReconnectCredentialActive(lost))
        assertEquals(null, restarted.status(pending.request.requestId, "wrong-proof", secureDelivery = true)?.reconnectCredential)
    }

    @Test
    fun `pending, rejected and revoked decisions survive a restart`() {
        val dataSource = dataSource("registry-decisions")
        val first = registry(dataSource)
        val waiting = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("ios-2", "Smirnova", "ios")))
        val rejected = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("android-2", "Volkov", "android")))
        val revoked = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("android-3", "Sidorov", "android")))
        first.reject(rejected.request.requestId)
        val credential = assertIs<PairingApproval.Accepted>(first.approve(revoked.request.requestId)).request.reconnectCredential
        first.revoke(revoked.request.requestId)

        val restarted = registry(dataSource)

        assertEquals(listOf(waiting.request), restarted.pending())
        assertEquals(PairingStatusState.REJECTED, restarted.status(rejected.request.requestId)?.state)
        assertEquals(false, restarted.isReconnectCredentialActive(credential))
        assertEquals(true, restarted.operatorRegistry().devices.single().revoked)
        assertIs<PairingRevocation.Revoked>(restarted.revoke(revoked.request.requestId)).also { assertEquals(false, it.created) }
    }

    @Test
    fun `a request replaced by a newer proof stays replaced after a restart`() {
        val dataSource = dataSource("registry-superseded")
        val first = registry(dataSource)
        val old = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("android-5", "Safin", "android", "proof-old")))
        val new = assertIs<PairingSubmission.Pending>(first.submit(PairingRequestCommand("android-5", "Safin", "android", "proof-new")))

        val restarted = registry(dataSource)

        assertEquals(listOf(new.request), restarted.pending())
        assertEquals(PairingStatusCode.SUPERSEDED, restarted.status(old.request.requestId)?.code)
        assertIs<PairingApproval.Accepted>(restarted.approve(new.request.requestId))
        assertTrue(!restarted.status(new.request.requestId, "proof-new", secureDelivery = true)?.reconnectCredential.isNullOrBlank())
    }

    @Test
    fun `journal stores credential and delivery proof only as hashes`() {
        val dataSource = dataSource("registry-secrets")
        val registry = registry(dataSource)
        val pending = assertIs<PairingSubmission.Pending>(registry.submit(PairingRequestCommand("ios-3", "Orlova", "ios", "proof-secret")))
        val credential = assertIs<PairingApproval.Accepted>(registry.approve(pending.request.requestId)).request.reconnectCredential

        val stored = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type, payload, author FROM domain_events").use { result ->
                    buildList { while (result.next()) add("${result.getString(1)} ${result.getString(2)} ${result.getString(3)}") }
                }
            }
        }

        assertEquals(listOf("device_pairing_requested", "device_pairing_approved"), stored.map { it.substringBefore(' ') })
        assertTrue(stored.none { credential in it || "proof-secret" in it })
    }

    private fun registry(dataSource: JdbcDataSource) =
        PairingRequests(JdbcDeviceRegistryJournal(JdbcDomainEventStore(dataSource), peerId))

    private fun dataSource(name: String) = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
