package org.mass

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Tag
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCommand
import org.mass.bout.BoutCorner
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutEvents
import org.mass.bout.KerugiBoutService
import org.mass.bout.RosterJudge
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import org.mass.domain.TechnicalDiscipline
import org.mass.performance.PerformanceCommand
import org.mass.performance.PerformanceStartSpec
import org.mass.performance.TechnicalPerformanceEvents
import org.mass.performance.TechnicalPerformanceService
import org.mass.persistence.JdbcDomainEventStore
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * I7 reliability (`NFR-003`, `REL-003`, `REL-004`): seven paired phones work one court through [FaultProxy] against the
 * production `module()` on Netty over TCP and the JDBC journal. A Kerugi bout with three judges and four watching devices
 * goes through latency, jitter, stalls, connections killed mid-stream, a long partition and a server crash with a
 * restart over the same journal; then a Hosinsool performance with all seven as judges goes through drops and a restart.
 * Every phone keeps an outbox and replays it after reconnect, as the client does (its durability across an app kill is
 * the client's `CLI-102`). The journal must hold every input once,
 * the score must equal the one computed from the plan, and every phone must resync to it.
 *
 * Runs in `:server:faultTest` (tag `fault`), not in `test`.
 */
@Tag("fault")
class FaultInjectionLoadTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val start = Instant.parse("2026-11-08T10:00:00Z")

    @Volatile
    private var clock = start
    private val pairing = PairingRequests()
    private val devices = (1..7).map { "android-$it" }
    private val credentials = devices.associateWith(::approve)
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:fault-load-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private lateinit var hub: RealtimeHub
    private lateinit var proxy: FaultProxy

    @AfterTest
    fun cleanUp() {
        server?.stop(0, 0)
        if (::proxy.isInitialized) proxy.close()
    }

    @Test
    fun `seven phones through latency, drops, a long partition and a restart lose, duplicate and misapply nothing`() = runBlocking {
        startServer()
        proxy = FaultProxy(InetSocketAddress("127.0.0.1", serverPort()))
        val phones = devices.map { Phone(it) }
        val jobs = phones.map { phone -> launch(Dispatchers.IO) { phone.run() } }
        try {
            judgeBout(phones)
            judgePerformance(phones)
        } finally {
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    private suspend fun judgeBout(phones: List<Phone>) {
        val judges = phones.take(3)
        val sessionId = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), boutSpec()))).sessionId
        assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId, BoutStateAction.START)))
        // The round runs from `start`; the server clock stands 110 s into it, so every planned tap lies in fighting time.
        clock = start.plusSeconds(110)
        val sessions = judges.map { judge -> awaitSession(judge) { it.string("sessionId") == sessionId.value && it.string("state") == "running" } }

        // Thirty exchanges 3 s apart: three or two judges agree (scored) or one judge alone (audited only).
        val plan = (0 until 30).map { exchange ->
            val competitor = if (exchange % 2 == 0) "BLUE" else "RED"
            val area = if (exchange % 4 < 2) "HEAD" else "BODY"
            val seats = when {
                exchange % 5 == 4 -> listOf(exchange % 3)
                exchange % 3 == 0 -> listOf(0, 1, 2)
                else -> listOf(exchange % 3, (exchange + 1) % 3)
            }
            val at = start.plusSeconds(1L + 3 * exchange)
            Exchange(competitor, area, seats.map { seat -> seat to scoreCommandFor(sessions[seat], id().value, at.plusMillis(150L * seat), competitor, area) })
        }
        val operatorPoints = mutableMapOf("BLUE" to 0, "RED" to 0)
        fun award(competitor: KerugiCompetitor, points: Int) {
            assertIs<BoutOutcome.Applied>(
                hub.operator("Оператор", BoutCommand.AwardAction(id(), sessionId, KerugiOperatorActionType.THROW, competitor, points)),
            )
            operatorPoints.merge(competitor.name, points, Int::plus)
        }
        fun enqueue(range: IntRange) = range.forEach { index -> plan[index].taps.forEach { (seat, tap) -> judges[seat].outbox += tap } }

        // Healthy court: NFR-002 latency is measured here on loopback (informational; the LAN target is I3).
        enqueue(0..4)
        awaitDrained(judges)
        val healthy = judges.flatMap { it.firstAckMillis }.sorted()

        // Venue Wi-Fi: latency, jitter, stalls and connections killed mid-stream.
        proxy.faults = FaultProxy.Faults(latencyMillis = 20, jitterMillis = 60, spikeProbability = 0.03, dropProbability = 0.04)
        enqueue(5..11)
        award(KerugiCompetitor.RED, 2)
        awaitDrained(judges)

        // A long partition: the phones keep scoring offline into their outboxes and replay after the network returns.
        proxy.partition()
        enqueue(12..19)
        delay(8_000)
        assertTrue(judges.all { it.outbox.isNotEmpty() }, "Nothing passes during the partition")
        proxy.heal()
        awaitDrained(judges)

        // A server crash mid-bout with taps in flight, more taps while it is down, then a restart over the same journal.
        enqueue(20..24)
        delay(150)
        crashServer()
        enqueue(25..29)
        delay(1_500)
        startServer()
        proxy.target = InetSocketAddress("127.0.0.1", serverPort())
        awaitDrained(judges)
        assertEquals("paused", hub.bouts.current()!!.state.name.lowercase(), "A restart stops the clock at the saved time (ADR-010 §3)")
        assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId, BoutStateAction.RESUME)))
        award(KerugiCompetitor.BLUE, 1)

        // Every judge replays its first tap once more: a duplicate is acknowledged and changes nothing.
        judges.forEach { judge -> judge.outbox += plan.flatMap(Exchange::taps).first { (seat, _) -> judges[seat] == judge }.second }
        awaitDrained(judges)
        proxy.faults = FaultProxy.Faults()

        val expected = mutableMapOf("BLUE" to operatorPoints.getValue("BLUE"), "RED" to operatorPoints.getValue("RED"))
        plan.filter { it.taps.size >= 2 }.forEach { expected.merge(it.competitor, if (it.area == "HEAD") 2 else 1, Int::plus) }
        val score = hub.bouts.current()!!.score()
        assertEquals(expected.getValue("BLUE") to expected.getValue("RED"), score.blue to score.red)

        // The journal holds every planned tap exactly once and nothing else from the judges.
        val taps = plan.flatMap { exchange -> exchange.taps.map { it.second.string("eventId") } }
        assertEquals(taps.sorted(), journalEventIds(KerugiBoutEvents.SCORE_CANDIDATE).sorted())
        assertEquals(emptyMap(), phones.flatMap { it.rejections.entries }.associate { it.key to it.value })
        assertTrue(taps.all { tap -> judges.sumOf { it.acks[tap] ?: 0 } >= 1 }, "Every tap is acknowledged")

        // Every one of the seven phones resyncs to the same score.
        phones.forEach { phone ->
            val view = phone.resyncFresh()
            val side = view.getValue("score").jsonObject
            assertEquals(score.blue to score.red, side.getValue("blue").jsonPrimitive.int to side.getValue("red").jsonPrimitive.int, phone.deviceId)
        }
        assertTrue(proxy.dropped.get() > 0 && proxy.refused.get() > 0, "Faults were injected: ${proxy.dropped} drops, ${proxy.refused} refusals")
        assertTrue(judges.all { it.connections.get() >= 3 }, "Every judge reconnected after the partition and the restart")
        assertTrue(judges.sumOf { it.duplicateAcks.get() } >= judges.size, "Duplicates were acknowledged")
        println(
            "fault-load bout: ${taps.size} taps, score ${score.blue}:${score.red}, drops ${proxy.dropped}, refused ${proxy.refused}, " +
                "connections ${phones.sumOf { it.connections.get() }}, failed links ${phones.sumOf { it.failures.get() }}, " +
                "duplicate ACKs ${judges.sumOf { it.duplicateAcks.get() }}, " +
                "healthy ACK p50/p95/max ${percentile(healthy, 0.5)}/${percentile(healthy, 0.95)}/${healthy.last()} ms",
        )
        assertTrue(percentile(healthy, 0.95) <= 200, "Healthy ACK p95 within the NFR-002 target on loopback")

        assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.CancelBout(id(), sessionId, "Нагрузочный тест")))
    }

    private suspend fun judgePerformance(phones: List<Phone>) {
        val sessionId = assertIs<BoutOutcome.Applied>(hub.performanceOperator("Оператор", PerformanceCommand.Start(id(), performanceSpec()))).sessionId
        assertIs<BoutOutcome.Applied>(hub.performanceOperator("Оператор", PerformanceCommand.Begin(id(), sessionId)))
        val sessions = phones.map { phone -> awaitSession(phone) { it.string("sessionId") == sessionId.value && it.string("state") == "running" } }
        val sends = phones.mapIndexed { seat, phone ->
            val (technique, presentation, deduction) = VECTORS[seat]
            phone.deviceId to technicalCommand(sessions[seat], technique, presentation, deduction)
        }

        proxy.faults = FaultProxy.Faults(latencyMillis = 20, jitterMillis = 60, spikeProbability = 0.03, dropProbability = 0.08)
        sends.take(4).forEachIndexed { seat, (_, send) -> phones[seat].outbox += send }
        delay(300)
        crashServer()
        sends.drop(4).forEachIndexed { index, (_, send) -> phones[4 + index].outbox += send }
        delay(1_000)
        startServer()
        proxy.target = InetSocketAddress("127.0.0.1", serverPort())
        // Each phone replays its Send once more after the restart; a Send is final and stays one per seat.
        sends.forEachIndexed { seat, (_, send) -> phones[seat].outbox += send }
        awaitDrained(phones)
        proxy.faults = FaultProxy.Faults()

        assertEquals(sends.map { it.second.string("eventId") }.sorted(), journalEventIds(TechnicalPerformanceEvents.SCORE_SENT).sorted())
        assertEquals(emptyMap(), phones.flatMap { it.rejections.entries }.associate { it.key to it.value })
        assertIs<BoutOutcome.Applied>(hub.performanceOperator("Оператор", PerformanceCommand.Complete(id(), sessionId)))
        // Totals 6.2 6.3 6.4 6.5 7.0 7.0 7.1: the trimmed mean drops 6.2 and 7.1, (6.3+6.4+6.5+7.0+7.0)/5 = 6.64 → 6.6.
        val totals = VECTORS.map { (technique, presentation, deduction) -> technique.sum() + presentation.sum() - deduction }
        assertEquals(listOf(62, 63, 64, 65, 70, 70, 71), totals.sorted())
        assertEquals(66, hub.performances.current()!!.result()!!.totalTenths)
        phones.forEach { phone ->
            val result = phone.resyncFresh().getValue("score").jsonObject
            assertEquals((1..7).toList(), result.getValue("submittedSeats").jsonArray.map { it.jsonPrimitive.int }, phone.deviceId)
            assertEquals(66, result.getValue("result").jsonObject.getValue("totalTenths").jsonPrimitive.int, phone.deviceId)
        }
        println(
            "fault-load performance: 7 sends, total 66, drops ${proxy.dropped}, connections ${phones.sumOf { it.connections.get() }}, " +
                "failed links ${phones.sumOf { it.failures.get() }}",
        )
    }

    /**
     * A phone with an outbox: it connects through the proxy, resyncs, sends the oldest unacknowledged command and
     * removes it only on its reply; any failure or a silent link drops the socket and reconnects with a short backoff.
     */
    private inner class Phone(val deviceId: String) {
        val outbox = ConcurrentLinkedQueue<JsonObject>()
        val acks = ConcurrentHashMap<String, Int>()
        val rejections = ConcurrentHashMap<String, String>()
        val connections = AtomicInteger()
        val failures = AtomicInteger()
        val duplicateAcks = AtomicInteger()
        val firstAckMillis = CopyOnWriteArrayList<Long>()

        @Volatile
        var session: JsonObject? = null

        /** Where the phone is, for the message of a failed drain. */
        @Volatile
        var step = "new"

        /** Each connection gets its own HTTP client, so a socket abandoned in a fault never holds a pool slot. */
        private fun simulator(http: HttpClient, replyTimeoutMillis: Long) = JudgeSimulator(
            http, credentials.getValue(deviceId), deviceId, "ws://127.0.0.1:${proxy.port}/v1/realtime", replyTimeoutMillis,
        )

        suspend fun run() {
            while (currentCoroutineContext().isActive) {
                val http = HttpClient(CIO) { install(WebSockets) }
                val simulator = simulator(http, replyTimeoutMillis = 2_000)
                try {
                    step = "connect"
                    withTimeout(3_000) {
                        simulator.connect()
                        session = simulator.resync()["session"] as? JsonObject
                    }
                    connections.incrementAndGet()
                    var idle = 0
                    while (true) {
                        val next = outbox.peek()
                        if (next == null) {
                            delay(25)
                            // An idle phone resyncs now and then, which also notices a dead link.
                            if (++idle % 20 == 0) session = withTimeout(5_000) { simulator.resync()["session"] as? JsonObject }
                            continue
                        }
                        idle = 0
                        step = "command"
                        val eventId = next.string("eventId")
                        val startedAt = System.nanoTime()
                        // A watchdog over the whole exchange: a send to a dead socket can suspend indefinitely.
                        val reply = withTimeout(5_000) { simulator.command(next) }
                        when (reply.string("type")) {
                            "command_ack" -> {
                                val count = acks.merge(eventId, 1, Int::plus)!!
                                if (count == 1) firstAckMillis += (System.nanoTime() - startedAt) / 1_000_000 else duplicateAcks.incrementAndGet()
                            }
                            else -> rejections[eventId] = reply.string("code")
                        }
                        outbox.poll()
                        step = "idle"
                    }
                } catch (failure: CancellationException) {
                    // A reply timeout or a cancelled socket of this connection; only the test's own cancel ends the phone.
                    if (!currentCoroutineContext().isActive) throw failure
                    failures.incrementAndGet()
                    delay(100)
                } catch (failure: Exception) {
                    failures.incrementAndGet()
                    delay(100)
                } finally {
                    simulator.abort()
                    http.close()
                    step = "reconnecting"
                }
            }
        }

        /** A new connection's resync, the view a phone gets after it is restarted. */
        suspend fun resyncFresh(): JsonObject = withTimeout(10_000) {
            val http = HttpClient(CIO) { install(WebSockets) }
            val simulator = simulator(http, replyTimeoutMillis = 5_000)
            try {
                simulator.connect()
                simulator.resync().getValue("session").jsonObject
            } finally {
                simulator.abort()
                http.close()
            }
        }
    }

    private data class Exchange(val competitor: String, val area: String, val taps: List<Pair<Int, JsonObject>>)

    private suspend fun awaitSession(phone: Phone, matches: (JsonObject) -> Boolean): JsonObject = withTimeout(20_000) {
        var found = phone.session?.takeIf(matches)
        while (found == null) {
            delay(25)
            found = phone.session?.takeIf(matches)
        }
        found
    }

    private suspend fun awaitDrained(phones: List<Phone>) {
        val deadline = System.nanoTime() + 60_000_000_000
        while (phones.any { it.outbox.isNotEmpty() }) {
            check(System.nanoTime() < deadline) {
                "Outboxes not drained: " + phones.joinToString { "${it.deviceId} ${it.outbox.size} left, ${it.connections} connections, ${it.step}" }
            }
            delay(50)
        }
    }

    private fun startServer() {
        val journal = JdbcBoutEventJournal(JdbcDomainEventStore(dataSource))
        hub = RealtimeHub(KerugiBoutService(journal, peerId, now = { clock }), TechnicalPerformanceService(journal, peerId, now = { clock }))
        val current = hub
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            module(pairingRequests = pairing, realtime = current, clock = { clock })
        }.start(wait = false)
    }

    /** Stops the server without a grace period and closes every link, as a crashed desktop does. */
    private fun crashServer() {
        server?.stop(0, 0)
        server = null
        proxy.closeAll()
    }

    private fun serverPort(): Int = runBlocking { requireNotNull(server).engine.resolvedConnectors().first().port }

    private fun journalEventIds(type: String): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT event_id FROM domain_events WHERE event_type = ?").use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /** A `technical_score_send_command` of the seat in [session] (ADR-011 §7), as the client stores it in the outbox. */
    private fun technicalCommand(session: JsonObject, technique: List<Int>, presentation: List<Int>, deduction: Int) = buildJsonObject {
        val seat = session.getValue("seat").jsonObject
        put("type", JsonPrimitive("technical_score_send_command"))
        put("eventId", JsonPrimitive(id().value))
        put("sequence", JsonPrimitive(0))
        put("clientTimestamp", JsonPrimitive(clock.toString()))
        listOf("competitionId", "peerId", "courtId", "bracketId", "sessionId").forEach { put(it, JsonPrimitive(session.string(it))) }
        put("judgeId", seat.getValue("judgeId"))
        put("deviceId", seat.getValue("deviceId"))
        put("discipline", JsonPrimitive(session.string("discipline")))
        put("techniqueTenths", JsonArray(technique.map(::JsonPrimitive)))
        put("presentationTenths", JsonArray(presentation.map(::JsonPrimitive)))
        put("deductionTenths", JsonPrimitive(deduction))
        put("totalTenths", JsonPrimitive(maxOf(0, technique.sum() + presentation.sum() - deduction)))
    }

    private fun approve(deviceId: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, "Судья${deviceId.last()}", "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }

    private fun boutSpec() = testBoutSpec(devices.take(3))

    private fun performanceSpec() = PerformanceStartSpec(
        competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60"),
        bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41"),
        categoryId = "self-defense",
        categoryTitle = "Приёмы самообороны · 2013-2011 г.р. Юноши",
        performanceNumber = 12,
        round = 1,
        index = 0,
        entry = BoutCorner("entry-5", "Иванов И. И. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
        discipline = TechnicalDiscipline.HOSINSOOL,
        techniqueCriteria = 4,
        judges = devices.mapIndexed { index, device -> RosterJudge(device, "Судья${index + 1}") },
    )

    private fun id() = EventId(UUID.randomUUID().toString())

    private fun percentile(sorted: List<Long>, quantile: Double): Long = sorted[((sorted.size - 1) * quantile).toInt()]

    private companion object {
        /** Seven Hosinsool vectors in tenths (4 technique and 4 presentation criteria, deduction in steps of 0.3). */
        val VECTORS = listOf(
            Triple(listOf(8, 7, 9, 6), listOf(7, 8, 8, 9), 0),
            Triple(listOf(8, 8, 8, 8), listOf(8, 8, 8, 8), 0),
            Triple(listOf(9, 9, 8, 9), listOf(9, 8, 9, 9), 0),
            Triple(listOf(8, 9, 9, 9), listOf(9, 9, 9, 9), 0),
            Triple(listOf(8, 8, 9, 8), listOf(8, 8, 9, 8), 3),
            Triple(listOf(9, 8, 8, 8), listOf(8, 8, 8, 8), 0),
            Triple(listOf(9, 9, 9, 8), listOf(9, 9, 8, 9), 0),
        )
    }
}
