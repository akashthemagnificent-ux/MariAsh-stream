package com.agon.app.relay

import com.agon.app.debug.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlin.random.Random
import java.util.concurrent.TimeUnit

private const val TAG = "RelayClient"

interface RelayListener {
    fun onConnected()
    fun onSyncMessage(json: String)
    fun onDisconnected(reason: String)
}

class RelayClient(
    private val relayBaseUrl: String,
    private val roomId: String,
    private val isHost: Boolean,
    private val relayToken: String,
    private val listener: RelayListener
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket client: readTimeout=0 (infinite) is REQUIRED so the connection
    // stays open between messages without the OS closing it.
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Bug 31 fix: separate client for HTTP segment uploads with a proper read
    // timeout. The WebSocket client uses readTimeout=0 (infinite), which is
    // correct for WebSockets but dangerous for HTTP uploads — a stalled server
    // response would hang the upload coroutine forever, blocking the entire
    // segment pipeline.
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var connected = false
    private var destroyed = false
    private var reconnectAttempt = 0

    fun connect() {
        if (destroyed) return

        // Bug 38 fix: if the relay URL is blank the OkHttp Request.Builder will
        // throw IllegalArgumentException ("Expected URL scheme 'http' or 'https'
        // but no colon was found") because the constructed WebSocket URL becomes
        // "/sync/ROOM?role=client" — a relative URL with no scheme or host.
        // That exception propagates uncaught through initRoom() → LaunchedEffect
        // → crashes the app. Guard here and fire onDisconnected with a clear
        // message so RoomScreen can show "configure relay" instead of crashing.
        if (relayBaseUrl.isBlank()) {
            AppLogger.w(TAG, "connect() called with blank relay URL — aborting")
            listener.onDisconnected("NO_RELAY_URL")
            return
        }

        val wsUrl = relayBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
        val role = if (isHost) "host" else "client"
        val encodedToken = if (relayToken.isNotBlank())
            URLEncoder.encode(relayToken, StandardCharsets.UTF_8.toString()) else ""
        val tokenQuery = if (encodedToken.isNotBlank()) "&token=$encodedToken" else ""

        // Bug 38 fix (secondary): wrap Request.Builder in try-catch so a
        // malformed URL (e.g. user typed "myserver" with no scheme) produces a
        // user-visible error instead of a crash.
        val request = try {
            Request.Builder()
                .url("$wsUrl/sync/$roomId?role=$role$tokenQuery")
                .apply {
                    if (relayToken.isNotBlank()) header("X-Relay-Token", relayToken)
                }
                .build()
        } catch (e: IllegalArgumentException) {
            AppLogger.e(TAG, "Invalid relay URL '$relayBaseUrl': ${e.message}")
            listener.onDisconnected("Invalid relay URL — check Settings. (${e.message})")
            return
        }

        AppLogger.i(TAG, "Connecting to room $roomId as $role via ${relayBaseUrl.trimEnd('/')}")

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                reconnectAttempt = 0
                AppLogger.i(TAG, "WebSocket connected — room=$roomId role=$role")
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                AppLogger.d(TAG, "MSG ← $text")
                listener.onSyncMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val code = response?.code ?: -1
                AppLogger.e(TAG, "WebSocket failure: ${t.message} (HTTP $code)")
                if (code == 401 || code == 403) {
                    listener.onDisconnected("Unauthorized (HTTP $code). Check Relay Token.")
                    destroyed = true
                    return
                }
                listener.onDisconnected(t.message ?: "Connection failed")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                AppLogger.w(TAG, "WebSocket closed: code=$code reason='$reason'")
                listener.onDisconnected(reason)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        scope.launch {
            reconnectAttempt++
            val exp = min(reconnectAttempt, 6) // cap around 64s
            val baseDelayMs = (1 shl exp) * 1000L
            val jitter = Random.nextLong(250L, 1250L)
            val waitMs = baseDelayMs + jitter
            AppLogger.w(TAG, "Reconnect attempt $reconnectAttempt in ${waitMs}ms")
            delay(waitMs)
            connect()
        }
    }

    fun sendSync(json: String) {
        if (connected) {
            AppLogger.d(TAG, "MSG → $json")
            webSocket?.send(json)
        } else {
            AppLogger.w(TAG, "sendSync dropped (not connected): $json")
        }
    }

    suspend fun uploadSegment(filename: String, data: ByteArray) {
        val contentType = if (filename.endsWith(".m3u8"))
            "application/vnd.apple.mpegurl".toMediaType()
        else
            "video/mp4".toMediaType()

        val request = Request.Builder()
            .url("${relayBaseUrl.trimEnd('/')}/upload/$roomId/$filename")
            .apply {
                if (relayToken.isNotBlank()) header("X-Relay-Token", relayToken)
            }
            .put(data.toRequestBody(contentType))
            .build()

        var lastErr: Exception? = null
        repeat(4) { attempt ->
            try {
                uploadClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        AppLogger.d(TAG, "Uploaded $filename (${data.size} bytes)")
                        return
                    }
                    if (response.code == 429 || response.code in 500..599) {
                        throw IOException("Retryable upload error ${response.code} for $filename")
                    }
                    val body = response.body?.string().orEmpty()
                    AppLogger.e(TAG, "Upload failed: ${response.code} for $filename — $body")
                    throw IOException("Non-retryable upload error ${response.code} for $filename")
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt == 3) return@repeat
                val retryDelay = (attempt + 1) * 800L
                val errDetail = e.message ?: e.javaClass.simpleName
                AppLogger.w(TAG, "Upload retry ${attempt + 1} for $filename in ${retryDelay}ms: $errDetail")
                delay(retryDelay)
            }
        }
        throw lastErr ?: IOException("Upload failed for $filename")
    }

    fun getHlsUrl(): String = "${relayBaseUrl.trimEnd('/')}/hls/$roomId/playlist.m3u8"

    fun disconnect() {
        AppLogger.i(TAG, "disconnect() called — destroying relay for room $roomId")
        destroyed = true
        connected = false
        webSocket?.close(1000, "Disconnected")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
