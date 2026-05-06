package com.agon.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agon.app.debug.AppLogger
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

private const val TAG = "SyncVM"

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

    // Bug 41 fix: grace period job — see peer_left handler below
    private var peerLeftGraceJob: Job? = null

    // Bug 30 fix: remember the last web URL set by the host so we can
    // re-send it when the relay connection becomes established (setWebUrl
    // might be called before the WebSocket handshake completes, and
    // sendSync drops messages silently when connected=false).
    private var pendingWebUrl: String? = null
    private var pendingWebEpoch: Long = 0L
    private var pendingLocalEpoch: Long = 0L
    private var hasSentStreamReady: Boolean = false
    private var playlistUploadedForEpoch: Long = 0L

    private var isSegmentingComplete = false

    private fun maybeSendStreamReady() {
        if (!_isHost.value || hasSentStreamReady) return
        if (playlistUploadedForEpoch != _streamEpoch.value) return

        // Bug 49 fix: allow stream_ready if we have 2 segments OR if the video
        // is so short that segmenting is already complete with only 1 segment.
        val ready = _segmentsUploaded.value >= 2 || (isSegmentingComplete && _segmentsUploaded.value >= 1)
        
        if (ready) {
            hasSentStreamReady = true
            AppLogger.i(TAG, "Stream ready (segs=${_segmentsUploaded.value}, complete=$isSegmentingComplete) — sending stream_ready")
            sendMessage(gson.toJson(SyncMessage(type = "stream_ready", streamEpoch = _streamEpoch.value)))
        }
    }

    fun initRoom(roomId: String, isHost: Boolean, relayUrl: String = "", relayToken: String = "") {
        AppLogger.i(TAG, "initRoom: room=$roomId isHost=$isHost relayUrl=${relayUrl.ifBlank { "<blank>" }}")
        relayClient?.disconnect()
        _roomId.value = roomId
        _isHost.value = isHost
        _connectionStatus.value = "Connecting…"
        _isWakingServer.value = false
        _wakingElapsedSeconds.value = 0
        disconnectCount = 0
        wakingTimerJob?.cancel()
        peerLeftGraceJob?.cancel()
        peerLeftGraceJob = null
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
                        AppLogger.i(TAG, "Connected to relay — room=$roomId isHost=$isHost")

                        // Bug 41 fix: if a peer_left grace period was counting down
                        // (host opened file picker → OS killed WebSocket → came back),
                        // cancel it and clear the "Stream ended" state so the client
                        // can continue watching instead of being stuck permanently.
                        peerLeftGraceJob?.cancel()
                        peerLeftGraceJob = null
                        if (_hostLeft.value != null) {
                            AppLogger.i(TAG, "Host reconnected during grace period — clearing hostLeft")
                            _hostLeft.value = null
                        }

                        // Bug 30 fix: re-send web URL if setWebUrl() was called before
                        // the relay connection was established. Without this, the client
                        // would see "Waiting for host to select a video" indefinitely.
                        if (isHost) {
                            delay(300) // brief settle time for the connection
                            val webUrl = pendingWebUrl
                            if (webUrl != null) {
                                sendMessage(gson.toJson(SyncMessage(
                                    type = "stream_reset", streamEpoch = pendingWebEpoch)))
                                sendMessage(gson.toJson(SyncMessage(
                                    type = "web_url", url = webUrl, streamEpoch = pendingWebEpoch)))
                                AppLogger.d(TAG, "Re-sent web_url after connect: $webUrl")
                            } else if (pendingLocalEpoch > 0) {
                                sendMessage(gson.toJson(SyncMessage(
                                    type = "stream_reset", streamEpoch = pendingLocalEpoch)))
                                AppLogger.d(TAG, "Re-sent stream_reset for local file after connect")
                            }
                        }
                    }
                }
                override fun onSyncMessage(json: String) = handleSyncMessage(json, baseUrl, roomId)
                override fun onDisconnected(reason: String) {
                    viewModelScope.launch {
                        _isConnected.value = false
                        AppLogger.w(TAG, "Disconnected: $reason (disconnectCount=${disconnectCount + 1})")
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
                "buffering" -> viewModelScope.launch {
                    AppLogger.d(TAG, "Partner buffering: ${msg.action}")
                    _partnerBuffering.value = (msg.action == "start")
                }
                "latency_update" -> viewModelScope.launch { _latency.value = msg.position }
                "web_url" -> if (!_isHost.value) msg.url?.let { url ->
                    AppLogger.i(TAG, "Received web_url: $url")
                    viewModelScope.launch { _videoUri.value = Uri.parse(url) }
                }
                "stream_reset" -> if (!_isHost.value) viewModelScope.launch {
                    AppLogger.i(TAG, "stream_reset received — epoch=${msg.streamEpoch}")
                    // Bug 41 fix: cancel any pending peer_left grace timer
                    peerLeftGraceJob?.cancel()
                    peerLeftGraceJob = null
                    _hostLeft.value = null
                    _streamEpoch.value = msg.streamEpoch
                    _proxyUrl.value = null
                    _videoUri.value = null
                }
                "stream_ready" -> if (!_isHost.value) viewModelScope.launch {
                    val epoch = if (msg.streamEpoch > 0) msg.streamEpoch else _streamEpoch.value
                    AppLogger.i(TAG, "stream_ready received — epoch=$epoch proxyUrl=${buildPlaylistUrl(baseUrl, roomId, epoch)}")
                    // Bug 41 fix: cancel any pending peer_left grace timer
                    peerLeftGraceJob?.cancel()
                    peerLeftGraceJob = null
                    _hostLeft.value = null
                    _streamEpoch.value = epoch
                    _proxyUrl.value = buildPlaylistUrl(baseUrl, roomId, epoch)
                }
                // Bug 28 fix: relay sends peer_left when the other side disconnects.
                // Bug 41 fix: don't show "Stream ended" immediately — start an 8-second
                // grace period. The host may have just opened the Android file picker
                // (which backgrounds the app), causing the OS to drop the WebSocket.
                // They'll reconnect within 2–5 s. Only show the "Stream ended" screen
                // if they haven't come back after 8 seconds.
                "peer_left" -> viewModelScope.launch {
                    val who = if (msg.action == "host") "The host ended the stream" else "Your partner disconnected"
                    AppLogger.w(TAG, "peer_left received: action=${msg.action} — starting 8s grace period")
                    peerLeftGraceJob?.cancel()
                    peerLeftGraceJob = viewModelScope.launch {
                        delay(8_000)
                        AppLogger.w(TAG, "peer_left grace period expired — showing 'Stream ended'")
                        _hostLeft.value = who
                        _connectionStatus.value = who
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Bad sync message: $json — ${e.message}")
        }
    }

    fun setVideoFile(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        AppLogger.i(TAG, "setVideoFile: $uri")
        _videoUri.value = uri
        if (_isHost.value) {
            val epoch = System.currentTimeMillis()
            _streamEpoch.value = epoch
            hasSentStreamReady = false
            pendingLocalEpoch = epoch
            pendingWebUrl = null // clear web URL if local file is picked
            sendMessage(gson.toJson(SyncMessage(type = "stream_reset", streamEpoch = epoch)))
            startSegmenting(uri)
        }
    }

    fun setWebUrl(url: String) {
        AppLogger.i(TAG, "setWebUrl: $url")
        _videoUri.value = Uri.parse(url)
        if (_isHost.value) {
            val epoch = System.currentTimeMillis()
            _streamEpoch.value = epoch
            hasSentStreamReady = false
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
        isSegmentingComplete = false
        _segmentsUploaded.value = 0
        playlistUploadedForEpoch = 0L
        val outputDir = File(context.cacheDir, "hls_${_roomId.value}").also {
            it.deleteRecursively(); it.mkdirs()
        }
        AppLogger.i(TAG, "startSegmenting: uri=$uri outputDir=$outputDir")
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
                        AppLogger.d(TAG, "Segment $name uploaded (total=${_segmentsUploaded.value})")
                        maybeSendStreamReady()
                    } catch (e: Exception) {
                        val errDetail = e.message ?: e.javaClass.simpleName
                        AppLogger.e(TAG, "Segment upload failed: $name — $errDetail")
                    }
                }
            },
            onPlaylistReady = { content ->
                viewModelScope.launch {
                    try {
                        val patched = patchPlaylistWithToken(content)
                        relayClient?.uploadSegment("playlist.m3u8", patched.toByteArray())
                        playlistUploadedForEpoch = _streamEpoch.value
                        maybeSendStreamReady()
                    }
                    catch (e: Exception) { 
                        val errDetail = e.message ?: e.javaClass.simpleName
                        AppLogger.e(TAG, "Playlist upload failed: $errDetail") 
                    }
                }
            },
            onProgress = {},
            onError = { AppLogger.e(TAG, "Segmenter error: $it"); viewModelScope.launch { _isSegmenting.value = false } },
            onComplete = {
                AppLogger.i(TAG, "Segmenting complete — ${_segmentsUploaded.value} segments total")
                viewModelScope.launch { 
                    isSegmentingComplete = true
                    _isSegmenting.value = false 
                    maybeSendStreamReady()
                }
            }
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
        AppLogger.i(TAG, "onCleared — releasing relay and segmenter")
        super.onCleared()
        wakingTimerJob?.cancel()
        peerLeftGraceJob?.cancel()
        relayClient?.disconnect()
        hlsSegmenter?.stop()
    }
}
