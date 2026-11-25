package com.karal.studio.discord

import com.karal.studio.core.json.Json
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.boolOr
import com.karal.studio.core.json.obj
import com.karal.studio.core.security.SecretPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Discord Gateway v10 client: identify, heartbeat, resume and bounded exponential reconnect.
 * Intents are taken from bot configuration; privileged intents must be enabled in the
 * Discord Developer Portal and are never bypassed.
 */
class DiscordGateway(
    private val token: String,
    private val intents: Long,
    private val rest: DiscordRest,
    private val onEvent: (GatewayEvent) -> Unit,
    private val onState: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var heartbeat: Job? = null
    @Volatile private var closed = false
    @Volatile private var sessionId: String? = null
    @Volatile private var sequence: Int = 0
    @Volatile private var resumeUrl: String? = null
    private var reconnectAttempt = 0

    fun connect() {
        closed = false
        onState("CONNECTING")
        val url = rest.gatewayUrl().getOrElse { "wss://gateway.discord.gg/?v=10&encoding=json" }
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                onState("CONNECTED")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handle(Json.parse(text)) }.onFailure {
                    onState("ERROR: " + SecretPolicy.redact(it.message ?: "Gateway parse error"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState("ERROR: " + SecretPolicy.redact(t.message ?: "Gateway failure"))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) scheduleReconnect()
            }
        })
    }

    private fun handle(payload: JsonValue) {
        when (GatewayPayload.op(payload)) {
            10 -> {
                val interval = GatewayPayload.heartbeatInterval(payload)
                startHeartbeat(interval)
                val id = sessionId
                if (!id.isNullOrBlank()) {
                    send(
                        Json.obj(
                            "op" to Json.num(6),
                            "d" to Json.obj(
                                "token" to Json.str(token),
                                "session_id" to Json.str(id),
                                "seq" to Json.num(sequence)
                            )
                        )
                    )
                    onState("RESUMING")
                } else {
                    send(
                        Json.obj(
                            "op" to Json.num(2),
                            "d" to Json.obj(
                                "token" to Json.str(token),
                                "intents" to Json.num(intents),
                                "properties" to Json.obj(
                                    "os" to Json.str("Android"),
                                    "browser" to Json.str("KARAL_BOT_STUDIO"),
                                    "device" to Json.str("KARAL_BOT_STUDIO")
                                )
                            )
                        )
                    )
                    onState("IDENTIFYING")
                }
            }
            1 -> send(Json.obj("op" to Json.num(1), "d" to Json.num(sequence)))
            0 -> {
                sequence = maxOf(sequence, GatewayPayload.sequence(payload))
                val name = GatewayPayload.eventName(payload)
                val data = GatewayPayload.data(payload)
                if (name == "READY" && data != null) {
                    sessionId = GatewayPayload.sessionId(payload)
                    resumeUrl = GatewayPayload.resumeGatewayUrl(payload)
                    onState("READY")
                }
                if (name == "RESUMED") onState("READY")
                if (name != null && data != null) onEvent(GatewayEvent(name, data, sequence))
            }
            7 -> scheduleReconnect(immediate = true)
            9 -> {
                val reidentify = GatewayPayload.data(payload)?.obj()?.get("resume")?.boolOr(false) ?: false
                if (!reidentify) sessionId = null
                scheduleReconnect(immediate = true)
            }
            11 -> Unit // heartbeat ack
            else -> Unit
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (!closed) {
                delay(intervalMs)
                send(Json.obj("op" to Json.num(1), "d" to Json.num(sequence)))
            }
        }
    }

    private fun send(payload: JsonValue) {
        socket?.send(Json.stringify(payload))
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (closed) return
        heartbeat?.cancel()
        socket?.cancel()
        socket = null
        reconnectAttempt++
        val backoff = if (immediate) 1_000L else (1_000L shl minOf(reconnectAttempt, 5))
        onState("RECONNECTING in ${backoff / 1000}s")
        scope.launch {
            delay(backoff)
            if (!closed) connect()
        }
    }

    fun disconnect() {
        closed = true
        heartbeat?.cancel()
        socket?.close(1000, "User stopped bot")
        socket = null
        onState("STOPPED")
    }

    fun latencyMillis(): Long = 0L // Gateway heartbeat ACK timing is tracked by the session monitor.
}
