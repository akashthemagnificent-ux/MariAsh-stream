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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class SyncMessage(
    val type: String,
    val action: String = "",
    val position: Long = 0L,
    val url: String? = null,
    val timestamp: Long = 0L,
    val isPlaying: Boolean = false
)

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = Gson()

    private var relayClient: RelayClient? = null
    private var hlsSegmenter: HlsSegmenter? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

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

    // Fixed: partnerBuffering and latency were missing — VideoPlayer needs these
    private val _partnerBuffering = MutableStateFlow(false)
    val partnerBuffering: StateFlow<Boolean> = _partnerBuffering

    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency

    private val _syncCommand = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 32)
    val syncCommand: SharedFlow<SyncMessage> = _syncCommand

    private val _reactionCommand = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val reactionCommand: SharedFlow<String> = _reactionCommand

    fun initRoom(roomId: String, isHost: Boolean, relayUrl: String = "") {
        _roomId.value = roomId
        _isHost.value = isHost
        _connectionStatus.value = "Connecting…"

        val baseUrl = if (relayUrl.isBlank()) "https://agon-relay.onrender.com"
                      else relayUrl.trimEnd('/')

        relayClient = RelayClient(
            relayBaseUrl = baseUrl,
            roomId = roomId,
            isHost = isHost,
            listener = object : RelayListener {
                override fun onConnected() {
                    viewModelScope.launch {
                        _isConnected.value = true
                        _connectionStatus.value = "Connected"
                        if (!isHost) {
                            _proxyUrl.value = "$baseUrl/hls/$roomId/playlist.m3u8"
                        }
                    }
                }
                override fun onSyncMessage(json: String) = handleSyncMessage(json, baseUrl, roomId)
                override fun onDisconnected(reason: String) {
                    viewModelScope.launch {
                        _isConnected.value = false
                        _connectionStatus.value = "Reconnecting…"
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
                "state", "sync", "ping", "pong" -> viewModelScope.launch { _syncCommand.emit(msg) }
                "reaction" -> viewModelScope.launch { _reactionCommand.emit(msg.action) }
                "buffering" -> viewModelScope.launch { _partnerBuffering.value = (msg.action == "start") }
                "latency_update" -> viewModelScope.launch { _latency.value = msg.position }
                "web_url" -> if (!_isHost.value) msg.url?.let { url ->
                    viewModelScope.launch { _videoUri.value = Uri.parse(url) }
                }
                "stream_ready" -> if (!_isHost.value) viewModelScope.launch {
                    _proxyUrl.value = "$baseUrl/hls/$roomId/playlist.m3u8"
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
        if (_isHost.value) startSegmenting(uri)
    }

    fun setWebUrl(url: String) {
        _videoUri.value = Uri.parse(url)
        if (_isHost.value) sendMessage(gson.toJson(SyncMessage("web_url", url = url)))
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
                viewModelScope.launch {
                    try {
                        relayClient?.uploadSegment(name, file.readBytes())
                        _segmentsUploaded.value++
                        if (_segmentsUploaded.value == 2) {
                            sendMessage(gson.toJson(SyncMessage("stream_ready")))
                        }
                    } catch (e: Exception) {
                        Log.e("SyncVM", "Segment upload failed: $name — ${e.message}")
                    }
                }
            },
            onPlaylistReady = { content ->
                viewModelScope.launch {
                    try { relayClient?.uploadSegment("playlist.m3u8", content.toByteArray()) }
                    catch (e: Exception) { Log.e("SyncVM", "Playlist upload failed: ${e.message}") }
                }
            },
            onProgress = {},
            onError = { Log.e("SyncVM", "Segmenter error: $it"); viewModelScope.launch { _isSegmenting.value = false } },
            onComplete = { viewModelScope.launch { _isSegmenting.value = false } }
        )
        hlsSegmenter?.segment(uri, outputDir)
    }

    fun sendMessage(json: String) = relayClient?.sendSync(json)

    fun sendReaction(emoji: String) {
        sendMessage(gson.toJson(SyncMessage("reaction", action = emoji)))
        viewModelScope.launch { _reactionCommand.emit(emoji) }
    }

    override fun onCleared() {
        super.onCleared()
        relayClient?.disconnect()
        hlsSegmenter?.stop()
    }
}
