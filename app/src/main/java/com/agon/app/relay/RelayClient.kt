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

    fun connect() {
        if (destroyed) return
        val wsUrl = relayBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
        val role = if (isHost) "host" else "client"
        val request = Request.Builder()
            .url("$wsUrl/sync/$roomId?role=$role")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                Log.d("RelayClient", "Connected to room $roomId as $role")
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener.onSyncMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.e("RelayClient", "WebSocket failure: ${t.message}")
                listener.onDisconnected(t.message ?: "Connection failed")
                if (!destroyed) {
                    scope.launch {
                        delay(3000)
                        connect()
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onDisconnected(reason)
            }
        })
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
            .put(data.toRequestBody(contentType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("RelayClient", "Upload failed: ${response.code} for $filename")
                } else {
                    Log.d("RelayClient", "Uploaded $filename (${data.size} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e("RelayClient", "Upload exception for $filename: ${e.message}")
            throw e
        }
    }

    fun getHlsUrl(): String = "${relayBaseUrl.trimEnd('/')}/hls/$roomId/playlist.m3u8"

    fun disconnect() {
        destroyed = true
        connected = false
        webSocket?.close(1000, "Disconnected")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
