package com.agon.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agon.app.relay.NetworkProfile
import com.agon.app.relay.NetworkProfiles
import com.agon.app.relay.SimulatedRelay
import com.agon.app.segmenter.HlsSegmenter
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

sealed class TestState {
    object Idle : TestState()
    data class Segmenting(val segmentsReady: Int) : TestState()
    object Ready : TestState()
    object Running : TestState()
    data class Error(val message: String) : TestState()
}

class TestViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = Gson()
    private val relay = SimulatedRelay(9191)

    // ── Network profile ──────────────────────────────────────────
    private val _currentProfile = MutableStateFlow(NetworkProfiles.INDIA_USA)
    val currentProfile: StateFlow<NetworkProfile> = _currentProfile

    // ── Test state ───────────────────────────────────────────────
    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    // ── Video source ─────────────────────────────────────────────
    private val _hostVideoUri = MutableStateFlow<Uri?>(null)
    val hostVideoUri: StateFlow<Uri?> = _hostVideoUri

    private val _clientHlsUri = MutableStateFlow<Uri?>(null)
    val clientHlsUri: StateFlow<Uri?> = _clientHlsUri

    // ── Live playback positions ──────────────────────────────────
    private val _hostPositionMs = MutableStateFlow(0L)
    val hostPositionMs: StateFlow<Long> = _hostPositionMs

    private val _clientPositionMs = MutableStateFlow(0L)
    val clientPositionMs: StateFlow<Long> = _clientPositionMs

    private val _driftMs = MutableStateFlow(0L)
    val driftMs: StateFlow<Long> = _driftMs

    private val _measuredOneWayMs = MutableStateFlow(0L)
    val measuredOneWayMs: StateFlow<Long> = _measuredOneWayMs

    // ── Relay stats ──────────────────────────────────────────────
    val uploadedBytes: StateFlow<Long> = relay.uploadedBytes
    val downloadedBytes: StateFlow<Long> = relay.downloadedBytes
    val segmentCount: StateFlow<Int> = relay.segmentCount
    val droppedPackets: StateFlow<Int> = relay.droppedPackets

    // ── Sync flows for HOST player ────────────────────────────────
    private val _hostSyncCmd = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 32)
    val hostSyncCmd: SharedFlow<SyncMessage> = _hostSyncCmd

    private val _hostReactionCmd = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val hostReactionCmd: SharedFlow<String> = _hostReactionCmd

    private val _clientPartnerBuffering = MutableStateFlow(false) // host sees client buffering
    val clientPartnerBuffering: StateFlow<Boolean> = _clientPartnerBuffering

    private val _clientLatency = MutableStateFlow(0L)
    val clientLatency: StateFlow<Long> = _clientLatency

    // ── Sync flows for CLIENT player ─────────────────────────────
    private val _clientSyncCmd = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 32)
    val clientSyncCmd: SharedFlow<SyncMessage> = _clientSyncCmd

    private val _clientReactionCmd = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val clientReactionCmd: SharedFlow<String> = _clientReactionCmd

    private val _hostPartnerBuffering = MutableStateFlow(false) // client sees host buffering
    val hostPartnerBuffering: StateFlow<Boolean> = _hostPartnerBuffering

    private val _hostLatency = MutableStateFlow(0L)
    val hostLatency: StateFlow<Long> = _hostLatency

    private var segmenter: HlsSegmenter? = null

    init {
        // Wire relay callbacks
        relay.onSyncToClient = { json -> viewModelScope.launch { routeToClient(json) } }
        relay.onSyncToHost   = { json -> viewModelScope.launch { routeToHost(json) } }
    }

    fun setProfile(profile: NetworkProfile) {
        _currentProfile.value = profile
        relay.setProfile(profile)
    }

    fun pickVideo(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        _hostVideoUri.value = uri
        startSegmenting(uri)
    }

    private fun startSegmenting(uri: Uri) {
        segmenter?.stop()
        _testState.value = TestState.Segmenting(0)
        _clientHlsUri.value = null

        val outputDir = File(context.cacheDir, "test_hls").also {
            it.deleteRecursively(); it.mkdirs()
        }

        // Bug 33 fix: stop the relay before restarting it, so NanoHTTPD doesn't
        // throw a BindException when a second video is picked while already running.
        relay.stop()
        relay.start(_currentProfile.value)

        var segCount = 0
        segmenter = HlsSegmenter(
            context = context,
            onSegmentReady = { name, file ->
                val data = file.readBytes()
                relay.addSegment(name, data)
                segCount++
                // Bug 32 fix: capture the current count in a local val BEFORE
                // launching the coroutine. The HlsSegmenter thread continues
                // immediately, so by the time the main thread runs the lambda
                // segCount may already be 3, 4, … and if(segCount == 2) would
                // never be true → clientHlsUri never set → black client panel.
                val capturedCount = segCount
                viewModelScope.launch {
                    _testState.value = TestState.Segmenting(capturedCount)
                    if (capturedCount == 2) {
                        _clientHlsUri.value = Uri.parse(relay.hlsPlaylistUrl)
                        _testState.value = TestState.Ready
                    }
                }
            },
            onPlaylistReady = { content -> relay.updatePlaylist(content) },
            onProgress = {},
            onError = { err ->
                Log.e("TestVM", "Segmenter error: $err")
                viewModelScope.launch { _testState.value = TestState.Error(err) }
            },
            onComplete = {
                viewModelScope.launch {
                    if (_testState.value is TestState.Ready || _testState.value is TestState.Running) {
                        // Done segmenting, keep running
                    }
                }
            }
        )
        segmenter?.segment(uri, outputDir)
    }

    // ── Called by HOST VideoPlayer ────────────────────────────────
    fun sendSyncAsHost(json: String) = relay.sendSyncFromHost(json)
    fun updateHostPosition(ms: Long) { _hostPositionMs.value = ms; recalcDrift() }

    // ── Called by CLIENT VideoPlayer ──────────────────────────────
    fun sendSyncAsClient(json: String) = relay.sendSyncFromClient(json)
    fun updateClientPosition(ms: Long) { _clientPositionMs.value = ms; recalcDrift() }

    private fun recalcDrift() {
        _driftMs.value = abs(_hostPositionMs.value - _clientPositionMs.value)
    }

    // ── Relay routing ─────────────────────────────────────────────
    private fun routeToClient(json: String) {
        viewModelScope.launch {
            val msg = try { gson.fromJson(json, SyncMessage::class.java) } catch (_: Exception) { return@launch }
            when (msg.type) {
                // Bug 23 fix: RTT was measured in routeToHost("pong") which only fires
                // when the HOST sends a ping. But in normal flow, the CLIENT sends pings
                // and the HOST replies with pongs that travel host→client through THIS
                // function. So measure RTT here, on "pong" delivery to the client player.
                "pong" -> {
                    val rtt = System.currentTimeMillis() - msg.timestamp
                    val oneWay = rtt / 2
                    _measuredOneWayMs.value = oneWay
                    _clientLatency.value = oneWay
                    _clientSyncCmd.emit(msg)
                }
                "buffering" -> _hostPartnerBuffering.value = (msg.action == "start")
                else -> _clientSyncCmd.emit(msg)
            }
        }
    }

    private fun routeToHost(json: String) {
        viewModelScope.launch {
            val msg = try { gson.fromJson(json, SyncMessage::class.java) } catch (_: Exception) { return@launch }
            when (msg.type) {
                "buffering" -> _clientPartnerBuffering.value = (msg.action == "start")
                else -> _hostSyncCmd.emit(msg)
            }
        }
    }

    fun startTest() {
        _testState.value = TestState.Running
    }

    fun stopTest() {
        segmenter?.stop()
        relay.stop()
        _testState.value = TestState.Idle
        _hostVideoUri.value = null
        _clientHlsUri.value = null
        _hostPositionMs.value = 0L
        _clientPositionMs.value = 0L
        _driftMs.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        segmenter?.stop()
        relay.stop()
    }
}
