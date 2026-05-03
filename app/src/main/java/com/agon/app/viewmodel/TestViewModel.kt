package com.agon.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agon.app.debug.AppLogger
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

    companion object {
        /**
         * How many segments (each 4 seconds) to produce ahead of the host's
         * current playback position. 30 segments = 2 minutes of look-ahead.
         *
         * Keeping this small prevents the segmenter from reading the entire
         * movie into memory. A 2-hour film at 5 Mbps would otherwise produce
         * ~1800 segments × ~2.5 MB = 4.5 GB — an instant OOM on any phone.
         * With LOOK_AHEAD = 30, peak memory is ~30 × 2.5 MB = 75 MB.
         */
        private const val LOOK_AHEAD_SEGS = 30

        /**
         * Keep this many already-played segments in the relay's in-memory store
         * (for brief seeks back). Segments older than this are evicted.
         */
        private const val KEEP_BEHIND_SEGS = 5
    }

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

    private val _clientPartnerBuffering = MutableStateFlow(false)
    val clientPartnerBuffering: StateFlow<Boolean> = _clientPartnerBuffering

    private val _clientLatency = MutableStateFlow(0L)
    val clientLatency: StateFlow<Long> = _clientLatency

    // ── Sync flows for CLIENT player ─────────────────────────────
    private val _clientSyncCmd = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 32)
    val clientSyncCmd: SharedFlow<SyncMessage> = _clientSyncCmd

    private val _clientReactionCmd = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val clientReactionCmd: SharedFlow<String> = _clientReactionCmd

    private val _hostPartnerBuffering = MutableStateFlow(false)
    val hostPartnerBuffering: StateFlow<Boolean> = _hostPartnerBuffering

    private val _hostLatency = MutableStateFlow(0L)
    val hostLatency: StateFlow<Long> = _hostLatency

    private var segmenter: HlsSegmenter? = null

    // Running count of segments produced (read only on the segmenter thread or
    // from the look-ahead check which is also called on the segmenter thread).
    @Volatile private var totalSegmentsProduced = 0

    // Index of the last segment evicted from the relay (so we don't evict twice)
    private var lastEvictedSegIndex = -1

    init {
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
        totalSegmentsProduced = 0
        lastEvictedSegIndex = -1

        val outputDir = File(context.cacheDir, "test_hls").also {
            it.deleteRecursively(); it.mkdirs()
        }

        // Bug 33 fix: stop existing relay before starting a new one to avoid
        // NanoHTTPD BindException when user picks a second video.
        relay.stop()
        relay.start(_currentProfile.value)

        segmenter = HlsSegmenter(
            context = context,
            onSegmentReady = { name, file ->
                // Read bytes NOW, while the file still exists. HlsSegmenter
                // deletes the file immediately after this callback returns so
                // it does NOT accumulate on disk.
                val data = file.readBytes()
                relay.addSegment(name, data)
                totalSegmentsProduced++

                // Bug 32 fix: capture count before launching so main-thread
                // coroutine sees the value at THIS moment, not whenever it runs.
                val capturedCount = totalSegmentsProduced
                viewModelScope.launch {
                    _testState.value = TestState.Segmenting(capturedCount)
                    if (capturedCount == 2) {
                        // Two segments = 8 seconds buffered: enough for ExoPlayer
                        // to start loading. Show the client player panel.
                        _clientHlsUri.value = Uri.parse(relay.hlsPlaylistUrl)
                        _testState.value = TestState.Ready
                    }
                }
            },
            onPlaylistReady = { content -> relay.updatePlaylist(content) },
            onProgress = {},
            onError = { err ->
                AppLogger.e("TestVM", "Segmenter error: $err")
                viewModelScope.launch { _testState.value = TestState.Error(err) }
            },
            onComplete = {
                AppLogger.d("TestVM", "Segmenting complete ($totalSegmentsProduced segments)")
            }
        )

        // Look-ahead gate: the segmenter's worker thread calls this after each
        // segment. While it returns true, the worker sleeps in 200 ms ticks.
        //
        // Rule: stop when more than LOOK_AHEAD_SEGS segments have been produced
        // beyond the host's current segment index. With 4-second segments:
        //   hostSegIndex = hostPositionMs / 4000
        //   pause when totalSegmentsProduced > hostSegIndex + LOOK_AHEAD_SEGS
        //
        // This caps peak memory at ≈ LOOK_AHEAD_SEGS × segment_size_bytes and
        // prevents the entire movie from being read into the relay's ConcurrentHashMap.
        segmenter!!.pauseCheck = {
            val hostSegIndex = (_hostPositionMs.value / 4_000L).toInt()
            totalSegmentsProduced > hostSegIndex + LOOK_AHEAD_SEGS
        }

        segmenter?.segment(uri, outputDir)
    }

    // ── Called by HOST VideoPlayer ────────────────────────────────
    fun sendSyncAsHost(json: String) = relay.sendSyncFromHost(json)

    fun updateHostPosition(ms: Long) {
        _hostPositionMs.value = ms
        recalcDrift()
        evictOldSegments(ms)
    }

    // ── Called by CLIENT VideoPlayer ──────────────────────────────
    fun sendSyncAsClient(json: String) = relay.sendSyncFromClient(json)

    fun updateClientPosition(ms: Long) {
        _clientPositionMs.value = ms
        recalcDrift()
    }

    private fun recalcDrift() {
        _driftMs.value = abs(_hostPositionMs.value - _clientPositionMs.value)
    }

    /**
     * Evict segments that the host has already played past so the relay's
     * ConcurrentHashMap doesn't grow indefinitely.
     *
     * We keep KEEP_BEHIND_SEGS segments behind the host's current position so
     * that brief seek-backs still work. Everything before that is removed.
     */
    private fun evictOldSegments(hostPositionMs: Long) {
        val hostSegIndex = (hostPositionMs / 4_000L).toInt()
        val evictUpTo = hostSegIndex - KEEP_BEHIND_SEGS - 1
        if (evictUpTo <= lastEvictedSegIndex) return
        for (i in (lastEvictedSegIndex + 1)..evictUpTo) {
            val name = "seg_%05d.mp4".format(i)
            relay.evictSegment(name)
        }
        lastEvictedSegIndex = evictUpTo
    }

    // ── Relay routing ─────────────────────────────────────────────
    private fun routeToClient(json: String) {
        viewModelScope.launch {
            val msg = try { gson.fromJson(json, SyncMessage::class.java) } catch (_: Exception) { return@launch }
            when (msg.type) {
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
        totalSegmentsProduced = 0
        lastEvictedSegIndex = -1
    }

    override fun onCleared() {
        super.onCleared()
        segmenter?.stop()
        relay.stop()
    }
}
