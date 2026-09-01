package org.mass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingApprovalTest {
    @Test
    fun `operator approval accepts a pending device and issues one reconnect credential`() {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-3", "Petrova", "ios")),
        )

        val approval = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val repeatedApproval = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))

        assertTrue(approval.created)
        assertEquals("accepted", approval.request.state)
        assertEquals("ios-3", approval.request.deviceId)
        assertTrue(approval.request.reconnectCredential.isNotBlank())
        assertEquals(approval.request.reconnectCredential, repeatedApproval.request.reconnectCredential)
        assertEquals(false, repeatedApproval.created)
        assertEquals(emptyList(), pairingRequests.pending())
    }

    @Test
    fun `unknown operator approval does not create or alter a pairing request`() {
        val pairingRequests = PairingRequests()

        val approval = pairingRequests.approve("unknown-request")

        assertEquals(PairingApproval.UnknownRequest, approval)
        assertEquals(emptyList(), pairingRequests.pending())
    }

    @Test
    fun `operator rejection transitions a pending device to rejected idempotently`() {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-9", "Volkov", "android")),
        )

        val rejection = assertIs<PairingRejection.Rejected>(pairingRequests.reject(pending.request.requestId))
        val repeatedRejection = assertIs<PairingRejection.Rejected>(pairingRequests.reject(pending.request.requestId))

        assertTrue(rejection.created)
        assertEquals(
            PairingStatus(
                state = PairingStatusState.REJECTED,
                deviceId = "android-9",
                code = PairingStatusCode.OPERATOR_REJECTED,
            ),
            rejection.status,
        )
        assertEquals(rejection.status, repeatedRejection.status)
        assertEquals(false, repeatedRejection.created)
        assertEquals(emptyList(), pairingRequests.pending())
    }

    @Test
    fun `unknown operator rejection does not alter pending state`() {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-9", "Kuznetsova", "ios")),
        )

        assertEquals(PairingRejection.UnknownRequest, pairingRequests.reject("unknown-request"))
        assertEquals(listOf(pending.request), pairingRequests.pending())
    }

    @Test
    fun `operator revocation makes an accepted reconnect credential inactive`() {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-4", "Sidorov", "android")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))

        val revocation = assertIs<PairingRevocation.Revoked>(pairingRequests.revoke(accepted.request.requestId))
        val repeatedRevocation = assertIs<PairingRevocation.Revoked>(pairingRequests.revoke(accepted.request.requestId))

        assertTrue(revocation.created)
        assertEquals("revoked", revocation.request.state)
        assertEquals(accepted.request.reconnectCredential, revocation.request.reconnectCredential)
        assertEquals(false, pairingRequests.isReconnectCredentialActive(accepted.request.reconnectCredential))
        assertEquals(false, repeatedRevocation.created)
    }

    @Test
    fun `unknown operator revocation does not alter accepted credentials`() {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-4", "Smirnova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))

        val revocation = pairingRequests.revoke("unknown-request")

        assertEquals(PairingRevocation.UnknownRequest, revocation)
        assertTrue(pairingRequests.isReconnectCredentialActive(accepted.request.reconnectCredential))
    }
}
