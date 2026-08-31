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
}
