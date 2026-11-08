package org.mass.bout

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure clock of ADR-010 §2: periods in order, fighting time clipped at the deadline, stops after the deadline. */
class KerugiClockTest {
    private val start = Instant.parse("2026-11-08T10:00:00Z")
    private val timing = BoutTiming(roundMillis = 60_000)

    @Test
    fun `fighting time is the running round intervals clipped at the deadline`() {
        val clock = KerugiClock(timing).start(start)
            .stop(start.plusSeconds(20)) { false }
            .resume(start.plusSeconds(50))

        assertTrue(clock.counts(start))
        assertTrue(clock.counts(start.plusSeconds(20)))
        assertFalse(clock.counts(start.plusMillis(20_001)))
        assertFalse(clock.counts(start.plusSeconds(35)))
        assertTrue(clock.counts(start.plusSeconds(50)))
        assertTrue(clock.counts(start.plusSeconds(90)))
        assertFalse(clock.counts(start.plusMillis(90_001)))
        assertEquals(start.plusSeconds(90), clock.deadline)
    }

    @Test
    fun `a stop at or after the deadline ends the period at the deadline and starts the break there`() {
        val clock = KerugiClock(timing).start(start).stop(start.plusSeconds(75)) { false }

        assertEquals(BoutPeriod.BREAK, clock.period)
        assertEquals(2, clock.round)
        assertEquals(start.plusSeconds(60), clock.runningSince)
        assertEquals(60_000, clock.remainingMillis)
        assertFalse(clock.counts(start.plusSeconds(61)))
    }

    @Test
    fun `round 2 leads to the golden round only on a tie`() {
        fun roundTwo() = KerugiClock(timing).start(start).endPeriod(start.plusSeconds(10)) { false }.resume(start.plusSeconds(20))

        val decided = roundTwo().endPeriod(start.plusSeconds(30)) { false }
        assertTrue(decided.over)
        assertTrue(decided.regularTimeOver)
        assertNull(decided.runningSince)
        assertFalse(decided.canResume)

        val tied = roundTwo().endPeriod(start.plusSeconds(30)) { true }
        assertEquals(KerugiClock.GOLDEN_BREAK, tied.phase)
        assertTrue(tied.golden)
        val golden = tied.resume(start.plusSeconds(40))
        assertEquals(KerugiClock.GOLDEN_ROUND, golden.phase)
        assertEquals(3, golden.round)
        assertEquals(60_000, golden.remainingMillis)
        assertTrue(golden.endPeriod(start.plusSeconds(200)) { true }.over)
    }

    @Test
    fun `halting keeps the time left and the period`() {
        val halted = KerugiClock(timing).start(start).halt(start.plusSeconds(12))
        assertEquals(48_000, halted.remainingMillis)
        assertEquals(KerugiClock.ROUND_1, halted.phase)
        assertFalse(halted.running)
    }
}
