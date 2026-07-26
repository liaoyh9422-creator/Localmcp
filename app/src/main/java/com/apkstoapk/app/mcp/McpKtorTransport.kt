package com.apkstoapk.app.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Ktor CIO transport for local MCP.
 *
 * Upper layer stays hand-written JSON-RPC on [McpServer]:
 * - GET  /mcp  → healthJson()
 * - POST /mcp  → dispatch(body)
 *
 * Binds 127.0.0.1 only. Long tools/call work runs on a dedicated pool
 * so CIO event threads are not blocked.
 */
/**
 * Module-internal: must not be public because [McpServer] is package-private Java.
 */
internal class McpKtorTransport(
    private val port: Int,
    private val mcp: McpServer,
) {
    private var engine: ApplicationEngine? = null

    private val toolExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ApksToApkMcpTool").apply { isDaemon = true }
    }
    private val toolDispatcher = toolExecutor.asCoroutineDispatcher()

    @Synchronized
    fun start() {
        if (engine != null) return
        val server = embeddedServer(CIO, port = port, host = HOST) {
            routing {
                get(MCP_PATH) {
                    call.respondText(
                        text = mcp.healthJson(),
                        contentType = ContentType.Application.Json,
                    )
                }
                post(MCP_PATH) {
                    val body = call.receiveText()
                    if (body.length > MAX_BODY_BYTES) {
                        call.respondText(
                            text = "error:body too large",
                            contentType = ContentType.Text.Plain,
                            status = HttpStatusCode.InternalServerError,
                        )
                        return@post
                    }
                    val json = withContext(toolDispatcher) {
                        mcp.dispatch(body)
                    }
                    call.respondText(
                        text = json,
                        contentType = ContentType.Application.Json,
                    )
                }
            }
        }
        server.start(wait = false)
        engine = server
        McpService.addLog("MCP transport: ktor-cio host=$HOST path=$MCP_PATH")
    }

    @Synchronized
    fun stop() {
        val current = engine
        engine = null
        if (current != null) {
            try {
                current.stop(GRACE_MS, TIMEOUT_MS)
            } catch (t: Throwable) {
                McpService.addLog("Ktor stop: " + (t.message ?: t.javaClass.simpleName))
            }
        }
        try {
            toolExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val MCP_PATH = "/mcp"
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val GRACE_MS = 500L
        private const val TIMEOUT_MS = 2000L
    }
}
