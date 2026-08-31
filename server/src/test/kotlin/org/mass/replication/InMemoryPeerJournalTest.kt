package org.mass.replication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

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

    @Test
    fun `synchronization reports transmitted envelope metadata separately from payload bytes`() {
        val courtOne = InMemoryPeerJournal(
            "court-1",
            listOf(JournalEvent("e1", "court-1", 1, "abc")),
        )
        val courtTwo = InMemoryPeerJournal(
            "court-2",
            listOf(JournalEvent("e2", "court-2", 1, "xy")),
        )

        val metrics = courtOne.synchronizeWith(courtTwo)

        assertEquals(3, metrics.transmittedEventCount)
        assertEquals(51, metrics.metadataBytes)
        assertEquals(7, metrics.payloadBytes)
        assertTrue(metrics.elapsed >= ZERO)
        assertEquals(courtOne.eventIds, courtTwo.eventIds)
    }

    @Test
    fun `three peer reconnect reports convergence metrics for the partition exchange`() {
        val courtOne = InMemoryPeerJournal("court-1")
        val courtTwo = InMemoryPeerJournal("court-2")
        val courtThree = InMemoryPeerJournal("court-3")

        courtOne.append("court-1-score")
        courtTwo.append("court-2-score")

        val metrics = courtOne.synchronizeWith(courtThree) +
            courtTwo.synchronizeWith(courtThree) +
            courtOne.synchronizeWith(courtThree)

        assertEquals(8, metrics.transmittedEventCount)
        assertEquals(408, metrics.metadataBytes)
        assertEquals(104, metrics.payloadBytes)
        assertTrue(metrics.elapsed >= ZERO)
        assertEquals(courtOne.eventIds, courtTwo.eventIds)
        assertEquals(courtOne.eventIds, courtThree.eventIds)
    }
}
