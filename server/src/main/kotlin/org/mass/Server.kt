package org.mass

import com.appstractive.dnssd.publishService
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

fun main() {
    Server.start()
    Runtime.getRuntime().addShutdownHook(Thread(Server::stop))
    Thread.currentThread().join()
}

object Server {
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var mdnsScope: CoroutineScope? = null
    fun start() {
        ktorServer = embeddedServer(
            CIO,
            port = 8080,
            host = "0.0.0.0",
        ) {
            routing {
                get("/") {
                    call.respondText("JudgeServer OK")
                }
                post("/score") {
                    call.respondText("Score OK")
                }
            }
        }.start(wait = false)

        mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mdnsScope!!.launch {
            publishService(type = "_u-judge._tcp", name = "JudgeServer-1") {
                port = 8080
            }
        }
    }

    fun stop() {
        ktorServer?.stop(1000, 5000)
        mdnsScope?.cancel()
        ktorServer = null
        mdnsScope = null
    }
}
