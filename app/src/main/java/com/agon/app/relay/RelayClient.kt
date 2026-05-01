package com.agon.app.relay

import android.util.Log
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var connected = false
    private var destroyed = false
    private var reconnectAttempt = 0

    fun connect() {
        if (destroyed) return
        val wsUrl = relayBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
        val role = if (isHost) "host" else "client"
        val encodedToken = if (relayToken.isNotBlank()) URLEncoder.encode(relayToken, StandardCharsets.UTF_8.toString()) else ""
        val tokenQuery = if (encodedToken.isNotBlank()) "&token=$encodedToken" else ""
        val tokenQuery = if (relayToken.isNotBlank()) "&token=$relayToken" else ""
        val request = Request.Builder()
            .url("$wsUrl/sync/$roomId?role=$role$tokenQuery")
            .apply {
                if (relayToken.isNotBlank()) header("X-Relay-Token", relayToken)
            }
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                reconnectAttempt = 0
                Log.d("RelayClient", "Connected to room $roomId as $role")
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener.onSyncMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.e("RelayClient", "WebSocket failure: ${t.message}")
                val code = response?.code ?: -1
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
            Log.w("RelayClient", "Reconnecting in ${waitMs}ms (attempt $reconnectAttempt)")
            delay(waitMs)
            connect()
        }
    }

    fun sendSync(json: String) {
        if (connected) webSocket?.send(json)
    }

    suspend fun uploadSegment(filename: String, data: ByteArray) {
        val contentType = if (filename.endsWith(".m3u8"))
            "application/vnd.apple.mpegurl".toMediaType()
        else
            "video/MP2T".toMediaType()

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
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("RelayClient", "Uploaded $filename (${data.size} bytes)")
                        return
                    }
                    if (response.code == 429 || response.code in 500..599) {
                        throw IOException("Retryable upload error ${response.code} for $filename")
                    }
                    val body = response.body?.string().orEmpty()
                    Log.e("RelayClient", "Upload failed: ${response.code} for $filename $body")
                    throw IOException("Non-retryable upload error ${response.code} for $filename")
                    if (response.code in 500..599) {
                        throw IOException("Retryable upload error ${response.code} for $filename")
                    }
                    Log.e("RelayClient", "Upload failed: ${response.code} for $filename")
                    return
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt == 3) return@repeat
                val retryDelay = (attempt + 1) * 800L
                Log.w("RelayClient", "Upload retry ${attempt + 1} for $filename in ${retryDelay}ms: ${e.message}")
                delay(retryDelay)
            }
        }
        throw lastErr ?: IOException("Upload failed for $filename")
    }

    fun getHlsUrl(): String = "${relayBaseUrl.trimEnd('/')}/hls/$roomId/playlist.m3u8"

    fun disconnect() {
        destroyed = true
        connected = false
        webSocket?.close(1000, "Disconnected")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
