package com.agon.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agon.app.relay.RelayClient
import com.agon.app.relay.RelayListener
import com.agon.app.segmenter.HlsSegmenter
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SyncMessage(
    val type: String,
    val action: String = "",
    val position: Long = 0L,
    val url: String? = null,
    val timestamp: Long = 0L,
    val isPlaying: Boolean = false,
    val streamEpoch: Long = 0L
)

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = Gson()

    private var relayClient: RelayClient? = null
    private var hlsSegmenter: HlsSegmenter? = null
    private var relayToken: String = ""

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isWakingServer = MutableStateFlow(false)
    val isWakingServer: StateFlow<Boolean> = _isWakingServer

    private val _wakingElapsedSeconds = MutableStateFlow(0)
    val wakingElapsedSeconds: StateFlow<Int> = _wakingElapsedSeconds

    private var disconnectCount = 0
    private var wakingTimerJob: Job? = null

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri

    private val _proxyUrl = MutableStateFlow<String?>(null)
    val proxyUrl: StateFlow<String?> = _proxyUrl

    private val _connectionStatus = MutableStateFlow("Connecting…")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _segmentsUploaded = MutableStateFlow(0)
    val segmentsUploaded: StateFlow<Int> = _segmentsUploaded

    private val _isSegmenting = MutableStateFlow(false)
    val isSegmenting: StateFlow<Boolean> = _isSegmenting

    private val _streamEpoch = MutableStateFlow(0L)
    val streamEpoch: StateFlow<Long> = _streamEpoch

    private val _partnerBuffering = MutableStateFlow(false)
    val partnerBuffering: StateFlow<Boolean> = _partnerBuffering

    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency

    private val _syncCommand = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 32)
    val syncCommand: SharedFlow<SyncMessage> = _syncCommand

    private val _reactionCommand = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val reactionCommand: SharedFlow<String> = _reactionCommand

    // Bug 28 fix: null = nobody left, non-null = message to display
    private val _hostLeft = MutableStateFlow<String?>(null)
    val hostLeft: StateFlow<String?> = _hostLeft

    // Bug 30 fix: remember the last web URL set by the host so we can
    // re-send it when the relay connection becomes established (setWebUrl
    // might be called before the WebSocket handshake completes, and
    // sendSync drops messages silently when connected=false).
    private var pendingWebUrl: String? = null
    private var pendingWebEpoch: Long = 0L

    fun initRoom(roomId: String, isHost: Boolean, relayUrl: String = "", relayToken: String = "") {
        relayClient?.disconnect()
        _roomId.value = roomId
        _isHost.value = isHost
        _connectionStatus.value = "Connecting…"
        _isWakingServer.value = false
        _wakingElapsedSeconds.value = 0
        disconnectCount = 0
        wakingTimerJob?.cancel()
        _hostLeft.value = null
        this.relayToken = relayToken.trim()

        val baseUrl = relayUrl.trimEnd('/')

        relayClient = RelayClient(
            relayBaseUrl = baseUrl,
            roomId = roomId,
            isHost = isHost,
            relayToken = this.relayToken,
            listener = object : RelayListener {
                override fun onConnected() {
                    viewModelScope.launch {
                        _isConnected.value = true
                        _connectionStatus.value = "Connected"
                        _isWakingServer.value = false
                        _wakingElapsedSeconds.value = 0
                        disconnectCount = 0
                        wakingTimerJob?.cancel()

                        // Bug 30 fix: re-send web URL if setWebUrl() was called before
                        // the relay connection was established. Without this, the client
                        // would see "Waiting for host to select a video" indefinitely.
                        val webUrl = pendingWebUrl
                        if (isHost && webUrl != null) {
                            delay(300) // brief settle time for the connection
                            sendMessage(gson.toJson(SyncMessage(
                                type = "stream_reset", streamEpoch = pendingWebEpoch)))
                            sendMessage(gson.toJson(SyncMessage(
                                type = "web_url", url = webUrl, streamEpoch = pendingWebEpoch)))
                            Log.d("SyncVM", "Re-sent web_url after connect: $webUrl")
                        }
                    }
                }
                override fun onSyncMessage(json: String) = handleSyncMessage(json, baseUrl, roomId)
                override fun onDisconnected(reason: String) {
                    viewModelScope.launch {
                        _isConnected.value = false
                        // Bug 38 fix: RelayClient now fires onDisconnected("NO_RELAY_URL")
                        // instead of throwing IllegalArgumentException when the relay URL
                        // is blank. Treat this as a permanent config error — do NOT count
                        // it as a network disconnect or trigger the "waking server" screen.
                        if (reason == "NO_RELAY_URL") {
                            _connectionStatus.value = "No relay server set — go to Settings"
                            return@launch
                        }
                        val isAuthError = reason.contains("401") || reason.contains("403") ||
                            reason.contains("Unauthorized", ignoreCase = true)
                        _connectionStatus.value = if (isAuthError) "Authentication failed" else "Reconnecting…"
                        if (!isAuthError) {
                            disconnectCount++
                            if (disconnectCount >= 2 && !_isWakingServer.value) {
                                _isWakingServer.value = true
                                _wakingElapsedSeconds.value = 0
                                wakingTimerJob?.cancel()
                                wakingTimerJob = viewModelScope.launch {
                                    while (_isWakingServer.value && !_isConnected.value) {
                                        delay(1_000)
                                        _wakingElapsedSeconds.value++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
        relayClient?.connect()
    }

    private fun handleSyncMessage(json: String, baseUrl: String, roomId: String) {
        try {
            val msg = gson.fromJson(json, SyncMessage::class.java)
            when (msg.type) {
                "state", "sync", "ping", "pong", "track" -> viewModelScope.launch { _syncCommand.emit(msg) }
                "reaction" -> viewModelScope.launch { _reactionCommand.emit(msg.action) }
                "buffering" -> viewModelScope.launch { _partnerBuffering.value = (msg.action == "start") }
                "latency_update" -> viewModelScope.launch { _latency.value = msg.position }
                "web_url" -> if (!_isHost.value) msg.url?.let { url ->
                    viewModelScope.launch { _videoUri.value = Uri.parse(url) }
                }
                "stream_reset" -> if (!_isHost.value) viewModelScope.launch {
                    _streamEpoch.value = msg.streamEpoch
                    _proxyUrl.value = buildPlaylistUrl(baseUrl, roomId, msg.streamEpoch)
                    _videoUri.value = null
                }
                "stream_ready" -> if (!_isHost.value) viewModelScope.launch {
                    val epoch = if (msg.streamEpoch > 0) msg.streamEpoch else _streamEpoch.value
                    _streamEpoch.value = epoch
                    _proxyUrl.value = buildPlaylistUrl(baseUrl, roomId, epoch)
                }
                // Bug 28 fix: relay sends peer_left when the other side disconnects
                "peer_left" -> viewModelScope.launch {
                    val who = if (msg.action == "host") "The host ended the stream" else "Your partner disconnected"
                    _hostLeft.value = who
                    _connectionStatus.value = who
                }
            }
        } catch (e: Exception) {
            Log.e("SyncVM", "Bad sync message: $json — ${e.message}")
        }
    }

    fun setVideoFile(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        _videoUri.value = uri
        if (_isHost.value) {
            val epoch = System.currentTimeMillis()
            _streamEpoch.value = epoch
            sendMessage(gson.toJson(SyncMessage(type = "stream_reset", streamEpoch = epoch)))
            startSegmenting(uri)
        }
    }

    fun setWebUrl(url: String) {
        _videoUri.value = Uri.parse(url)
        if (_isHost.value) {
            val epoch = System.currentTimeMillis()
            _streamEpoch.value = epoch
            // Bug 30 fix: store URL so onConnected() can re-send it if relay
            // isn't connected yet when this is called.
            pendingWebUrl = url
            pendingWebEpoch = epoch
            sendMessage(gson.toJson(SyncMessage(type = "stream_reset", streamEpoch = epoch)))
            sendMessage(gson.toJson(SyncMessage(type = "web_url", url = url, streamEpoch = epoch)))
        }
    }

    private fun startSegmenting(uri: Uri) {
        hlsSegmenter?.stop()
        _isSegmenting.value = true
        _segmentsUploaded.value = 0
        val outputDir = File(context.cacheDir, "hls_${_roomId.value}").also {
            it.deleteRecursively(); it.mkdirs()
        }
        hlsSegmenter = HlsSegmenter(
            context = context,
            onSegmentReady = { name, file ->
                // Read bytes NOW while the file still exists. HlsSegmenter deletes
                // the file immediately after this callback returns so segments do
                // NOT accumulate on the host phone's cache storage.
                val data = file.readBytes()
                viewModelScope.launch {
                    try {
                        relayClient?.uploadSegment(name, data)
                        _segmentsUploaded.value++
                        if (_segmentsUploaded.value == 2) {
                            sendMessage(gson.toJson(SyncMessage(type = "stream_ready", streamEpoch = _streamEpoch.value)))
                        }
                    } catch (e: Exception) {
                        Log.e("SyncVM", "Segment upload failed: $name — ${e.message}")
                    }
                }
            },
            onPlaylistReady = { content ->
                viewModelScope.launch {
                    try {
                        val patched = patchPlaylistWithToken(content)
                        relayClient?.uploadSegment("playlist.m3u8", patched.toByteArray())
                    }
                    catch (e: Exception) { Log.e("SyncVM", "Playlist upload failed: ${e.message}") }
                }
            },
            onProgress = {},
            onError = { Log.e("SyncVM", "Segmenter error: $it"); viewModelScope.launch { _isSegmenting.value = false } },
            onComplete = { viewModelScope.launch { _isSegmenting.value = false } }
        )

        // In relay mode we deliberately leave pauseCheck = null (no throttle).
        // The OkHttp upload itself provides natural backpressure — each PUT is a
        // real network request with a 60-second read timeout, so the upload
        // coroutines queue up. Segment files are deleted immediately in
        // onSegmentReady (above) so disk cache stays near zero even if the
        // network is slow and many segments are produced before all uploads finish.
        // The relay server enforces maxSegmentsPerRoom on its own side.

        hlsSegmenter?.segment(uri, outputDir)
    }

    fun sendMessage(json: String) = relayClient?.sendSync(json)

    private fun buildPlaylistUrl(baseUrl: String, roomId: String, epoch: Long): String {
        val tokenPart = if (relayToken.isBlank()) "" else
            "&token=${URLEncoder.encode(relayToken, StandardCharsets.UTF_8.toString())}"
        return "${baseUrl.trimEnd('/')}/hls/$roomId/playlist.m3u8?v=$epoch$tokenPart"
    }

    private fun patchPlaylistWithToken(content: String): String {
        if (relayToken.isBlank()) return content
        val encoded = URLEncoder.encode(relayToken, StandardCharsets.UTF_8.toString())
        return content.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("#") || line.isBlank()) return@joinToString line
            if (line.contains("token=")) return@joinToString line
            if (line.endsWith(".mp4") || line.endsWith(".m3u8") || line.endsWith(".vtt")) {
                if (line.contains("?")) "$line&token=$encoded" else "$line?token=$encoded"
            } else line
        }
    }

    fun sendReaction(emoji: String) {
        sendMessage(gson.toJson(SyncMessage("reaction", action = emoji)))
        viewModelScope.launch { _reactionCommand.emit(emoji) }
    }

    override fun onCleared() {
        super.onCleared()
        wakingTimerJob?.cancel()
        relayClient?.disconnect()
        hlsSegmenter?.stop()
    }
}
