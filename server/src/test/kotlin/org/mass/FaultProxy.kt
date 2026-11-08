package org.mass

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * An in-process TCP proxy between simulated phones and the server that injects the faults of a venue Wi-Fi (I7):
 * per-chunk latency with jitter and retransmission-like spikes, connections killed mid-stream as packet loss does to
 * a weak link, a partition in which nothing passes and new connections fail, and a server that goes away.
 * TCP keeps a stream ordered and intact, so loss shows up to the application only as delay or a broken connection.
 */
class FaultProxy(@Volatile var target: InetSocketAddress, seed: Int = 7) : AutoCloseable {
    data class Faults(
        val latencyMillis: Long = 0,
        val jitterMillis: Long = 0,
        /** Chance of a 300-800 ms stall of one chunk, as a lost segment waits for its retransmission. */
        val spikeProbability: Double = 0.0,
        /** Chance that a forwarded chunk is the last one of its connection. */
        val dropProbability: Double = 0.0,
    )

    @Volatile
    var faults = Faults()

    private val random = Random(seed)
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val links = Collections.synchronizedSet(mutableSetOf<Pair<Socket, Socket>>())

    @Volatile
    private var partitioned = false

    val port: Int = server.localPort
    val accepted = AtomicInteger()
    val dropped = AtomicInteger()
    val refused = AtomicInteger()

    init {
        Thread({ acceptLoop() }, "fault-proxy-accept").apply { isDaemon = true }.start()
    }

    /** Nothing passes: open connections stall and new ones are refused, until [heal]. */
    fun partition() {
        partitioned = true
    }

    /** Ends the partition; the connections it stalled are dead and closed, as their peers already gave up on them. */
    fun heal() {
        partitioned = false
        closeAll()
    }

    /** Closes every open connection at once, as a server crash or a router restart does. */
    fun closeAll() {
        synchronized(links) { links.toList() }.forEach(::close)
    }

    override fun close() {
        runCatching { server.close() }
        closeAll()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            accepted.incrementAndGet()
            if (partitioned) {
                refused.incrementAndGet()
                runCatching { client.close() }
                continue
            }
            val upstream = try {
                Socket().apply { connect(target, 2_000) }
            } catch (_: IOException) {
                refused.incrementAndGet()
                runCatching { client.close() }
                continue
            }
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            val link = client to upstream
            links += link
            pump(link, client, upstream, "up")
            pump(link, upstream, client, "down")
        }
    }

    private fun pump(link: Pair<Socket, Socket>, from: Socket, to: Socket, direction: String) {
        Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    while (partitioned && link in links) Thread.sleep(20)
                    if (link !in links) break
                    val current = faults
                    val delay = current.latencyMillis + if (current.jitterMillis > 0) nextLong(current.jitterMillis + 1) else 0L
                    val spike = if (chance(current.spikeProbability)) 300L + nextLong(501) else 0L
                    if (delay + spike > 0) Thread.sleep(delay + spike)
                    output.write(buffer, 0, read)
                    output.flush()
                    if (chance(current.dropProbability)) {
                        dropped.incrementAndGet()
                        break
                    }
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                close(link)
            }
        }, "fault-proxy-$direction").apply { isDaemon = true }.start()
    }

    private fun close(link: Pair<Socket, Socket>) {
        links -= link
        runCatching { link.first.close() }
        runCatching { link.second.close() }
    }

    private fun chance(probability: Double) = probability > 0 && synchronized(random) { random.nextDouble() } < probability

    private fun nextLong(bound: Long) = synchronized(random) { random.nextLong(bound) }
}
