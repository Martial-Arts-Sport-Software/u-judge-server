package org.mass.replication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryPeerJournalTest {
    @Test
    fun `reconnect converges three peers after a partition`() {
        val courtOne = InMemoryPeerJournal("court-1")
        val courtTwo = InMemoryPeerJournal("court-2")
        val courtThree = InMemoryPeerJournal("court-3")

        courtOne.append("court-1-score")
        courtTwo.append("court-2-score")

        courtOne.synchronizeWith(courtThree)
        courtTwo.synchronizeWith(courtThree)
        courtOne.synchronizeWith(courtThree)

        val expectedEventIds = courtOne.eventIds
        val expectedProjection = courtOne.projectedPayloads
        assertEquals(expectedEventIds, courtTwo.eventIds)
        assertEquals(expectedEventIds, courtThree.eventIds)
        assertEquals(expectedProjection, courtTwo.projectedPayloads)
        assertEquals(expectedProjection, courtThree.projectedPayloads)
    }

    @Test
    fun `duplicate delivery and restart do not apply an event twice`() {
        val original = InMemoryPeerJournal("court-1")
        val event = original.append("score")
        val restarted = InMemoryPeerJournal("court-1", original.events)

        restarted.receive(listOf(event, event))

        assertEquals(setOf(event.id), restarted.eventIds)
        assertEquals(listOf("score"), restarted.projectedPayloads)
    }

    @Test
    fun `sequence gap is reported and event waits for its predecessor`() {
        val receivingPeer = InMemoryPeerJournal("court-2")
        val secondEvent = JournalEvent("event-2", "court-1", 2, "second")
        val firstEvent = JournalEvent("event-1", "court-1", 1, "first")

        receivingPeer.receive(listOf(secondEvent))

        assertEquals(setOf(1L), receivingPeer.missingSequences["court-1"])
        assertTrue(receivingPeer.projectedPayloads.isEmpty())

        receivingPeer.receive(listOf(firstEvent))

        assertTrue(receivingPeer.missingSequences.isEmpty())
        assertEquals(listOf("first", "second"), receivingPeer.projectedPayloads)
    }
}
