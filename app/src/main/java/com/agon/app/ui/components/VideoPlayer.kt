package com.agon.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.agon.app.debug.AppLogger
import com.agon.app.viewmodel.SyncMessage
import com.agon.app.viewmodel.SyncViewModel
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "VideoPlayer"

// ─────────────────────────────────────────────────────────────────────────────
// VideoPlayer — ExoPlayer composable with full sync engine built in
//
// Accepts all sync dependencies as explicit parameters so it can be used in
// both production (SyncViewModel) and the Test Lab (TestViewModel).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VideoPlayer(
    uri: Uri,
    isHost: Boolean,
    syncCommands: SharedFlow<SyncMessage>,
    reactionCommands: SharedFlow<String>,
    partnerBuffering: StateFlow<Boolean>,
    latency: StateFlow<Long>,
    onSendSync: (String) -> Unit,
    onPositionUpdate: ((Long) -> Unit)? = null,
    showControls: Boolean = isHost,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gson = remember { Gson() }

    var isHandlingSync by remember { mutableStateOf(false) }
    var showTrackSelector by remember { mutableStateOf(false) }
    var availableTracks by remember { mutableStateOf<Tracks?>(null) }
    var wasPlayingBeforeBuffer by remember { mutableStateOf(false) }
    var localLatencyMs by remember { mutableStateOf(0L) }

    // Bug 39 fix: ExoPlayer fails silently on unplayable URLs (TikTok share
    // links, geo-blocked CDN URLs, URLs requiring login cookies, etc.) —
    // the PlayerView stays black with no indication of what went wrong.
    // Capture the PlaybackException and show a human-readable overlay so the
    // user knows the URL is the problem, not the app.
    var playerError by remember { mutableStateOf<String?>(null) }

    // Bug fix (buffering deadlock): client must NOT send "buffering start/stop"
    // until it has received at least one play command from the host.
    //
    // Without this guard the sequence is:
    //   1. Client ExoPlayer enters STATE_BUFFERING immediately on prepare()
    //   2. Client sends "buffering start" — host pauses, wasPlayingBeforeBuffer=true
    //   3. Host pong: isPlaying=false (host is paused) — client stays paused
    //   4. Client stuck in STATE_BUFFERING because playWhenReady=false
    //   5. Client never exits buffering → host never resumes → permanent deadlock
    var hasEverStartedPlaying by remember { mutableStateOf(isHost) }

    // Bug 36 fix (seek storm → permanent black screen):
    //
    // Problem: on startup the client is at position 0 while the host may be
    // tens of seconds ahead. drift > 3 000 ms is ALWAYS true. Every pong
    // (arriving every ~2.5 s) fires seekTo(estimatedHostPos). Each seek makes
    // ExoPlayer abandon its in-progress segment download and restart from the
    // new position. With a simulated 10 Mbps link a single 15 MB segment takes
    // ~12 s to download — but seeks arrive every 2.5 s. The client can never
    // finish loading a single segment and stays in STATE_BUFFERING forever.
    //
    // Fix: allow at most ONE startup seek (before the client has played even
    // one frame). After that seek, suppress further seeks until ExoPlayer is
    // actually producing frames (exoPlayer.isPlaying == true). The one seek
    // aligns the client to the host's position; ExoPlayer then fills its buffer
    // undisturbed. Mid-playback drift > 3 s still triggers an immediate seek —
    // that corrects real desync after a network stall or host scrub.
    var hasSeekedForStartup by remember { mutableStateOf(isHost) }

    val partnerIsBuffering by partnerBuffering.collectAsState()
    val currentLatency by latency.collectAsState()

    // Buffer tuned for HLS EVENT streams:
    // minBuffer=8s  — ExoPlayer stops calling this "unhealthy" with only 2 segments ready
    // maxBuffer=60s — keep 60s prefetched (was 120s, wastes RAM on mobile)
    // bufferForPlayback=2s — start playing as soon as 2 seconds are downloaded
    // bufferForPlaybackAfterRebuffer=5s — resume quickly after stall
    // Bug 16 fix: old minBuffer=30000 forced ExoPlayer to demand 30s ahead of live edge.
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                8_000,    // min buffer: 8 s (= 2 segments) before considered healthy
                60_000,   // max buffer: 60 s prefetched
                2_000,    // buffer for initial playback start
                5_000     // buffer after a rebuffer event
            )
            .setTargetBufferBytes(50 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    // key(uri) = recreate ExoPlayer when the video changes (fixes "stuck on first video" bug)
    key(uri.toString()) {
        val exoPlayer = remember {
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
        }

        // Bug 51 fix: throttle sync messages to avoid overwhelming the relay
        var lastSyncTime by remember { mutableStateOf(0L) }
        fun throttledSendSync(json: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastSyncTime > 500) {
                onSendSync(json)
                lastSyncTime = now
            }
        }

        LaunchedEffect(uri) {
            AppLogger.i(TAG, "Loading URI: $uri isHost=$isHost")
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            // Host plays immediately; client starts PAUSED and waits for the first
            // sync "pong" with isPlaying=true from the host.
            // Bug 17 fix: client with playWhenReady=true starts at position 0 while
            // the host is already seconds ahead; desync on first load.
            exoPlayer.playWhenReady = isHost
        }

        // Auto-pause if partner is buffering (host waits for client)
        LaunchedEffect(partnerIsBuffering) {
            if (isHost) {
                if (partnerIsBuffering) {
                    wasPlayingBeforeBuffer = exoPlayer.isPlaying
                    AppLogger.w(TAG, "Partner buffering — host paused (wasPlaying=$wasPlayingBeforeBuffer)")
                    exoPlayer.pause()
                } else if (wasPlayingBeforeBuffer) {
                    AppLogger.i(TAG, "Partner buffering stopped — host resuming")
                    exoPlayer.play()
                }
            }
        }

        // Position reporting for test-lab drift tracking
        if (onPositionUpdate != null) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(500)
                    onPositionUpdate(exoPlayer.currentPosition)
                }
            }
        }

        DisposableEffect(Unit) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    AppLogger.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying isHost=$isHost handlingSync=$isHandlingSync")
                    if (!isHandlingSync && isHost && !partnerIsBuffering) {
                        val msg = SyncMessage("state", if (isPlaying) "play" else "pause",
                            exoPlayer.currentPosition)
                        throttledSendSync(gson.toJson(msg), force = true)
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int
                ) {
                    if (!isHandlingSync && isHost && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        AppLogger.d(TAG, "Host seek: ${oldPos.positionMs}→${newPos.positionMs}ms")
                        val msg = SyncMessage("sync", "seek", exoPlayer.currentPosition)
                        throttledSendSync(gson.toJson(msg), force = true)
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    val stateName = when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($state)"
                    }
                    AppLogger.d(TAG, "onPlaybackStateChanged: $stateName pos=${exoPlayer.currentPosition}ms hasEverStarted=$hasEverStartedPlaying")
                    if (!isHost) {
                        // Only report buffering events after the client has been told to play
                        // at least once. Before the first play command, STATE_BUFFERING is
                        // just normal ExoPlayer startup — not a real mid-playback stall.
                        // Reporting it early causes the deadlock described above.
                        if (!hasEverStartedPlaying) return
                        val action = when (state) {
                            Player.STATE_BUFFERING -> "start"
                            Player.STATE_READY -> "stop"
                            else -> return
                        }
                        AppLogger.i(TAG, "Client buffering $action — notifying host")
                        throttledSendSync(gson.toJson(SyncMessage("buffering", action, exoPlayer.currentPosition)), force = true)
                    }
                }

                // Bug 39 fix: capture playback errors so we can show an overlay
                // instead of leaving the user staring at a silent black screen.
                // Common causes: TikTok/YouTube/Netflix CDN returns 403, geo-block,
                // URL requires login cookies, or the URL is a webpage not a video.
                override fun onPlayerError(error: PlaybackException) {
                    val cause = error.cause?.message ?: error.message ?: "Unknown error"
                    AppLogger.e(TAG, "PlaybackException: ${error.errorCodeName} — cause: $cause")
                    playerError = when {
                        cause.contains("403") || cause.contains("Forbidden", ignoreCase = true) ->
                            "Video URL blocked (HTTP 403). TikTok/YouTube/Netflix links don't work — use a direct .mp4 or .m3u8 URL."
                        cause.contains("404") || cause.contains("Not Found", ignoreCase = true) ->
                            "Video not found (HTTP 404). The URL may have expired or the file was moved."
                        cause.contains("UnrecognizedInputFormatException") || cause.contains("format", ignoreCase = true) ->
                            "Unsupported video format. Make sure the URL points to a .mp4 or .m3u8 file, not a webpage."
                        cause.contains("Unable to connect") || cause.contains("ECONNREFUSED") ||
                            cause.contains("timeout", ignoreCase = true) ->
                            "Could not reach the video server. Check your internet connection."
                        else -> "Playback error: $cause"
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    availableTracks = tracks
                }
            }
            exoPlayer.addListener(listener)
            onDispose {
                AppLogger.d(TAG, "Disposing ExoPlayer for uri=$uri")
                exoPlayer.removeListener(listener)
                exoPlayer.release()
            }
        }

        // NTP-style ping/pong sync loop — runs every 2 seconds
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                if (!isHost) {
                    // Client pings host to measure round-trip and get exact position
                    onSendSync(gson.toJson(SyncMessage("ping", timestamp = System.currentTimeMillis())))
                } else if (exoPlayer.isPlaying) {
                    // Host broadcasts position as a safety heartbeat
                    onSendSync(gson.toJson(SyncMessage("sync", "position", exoPlayer.currentPosition)))
                }
            }
        }

        // Sync command handler
        // Bug 15/20 fix:
        //   - Changed collectLatest → collect so no sync message is ever dropped.
        //   - isHandlingSync is now wrapped in try/finally so it always resets.
        LaunchedEffect(Unit) {
            syncCommands.collect { msg ->
                isHandlingSync = true
                try {
                    when (msg.type) {
                        "ping" -> {
                            if (isHost) {
                                // Bug fix (host pong — intended play state):
                                // Report the play state the host INTENDS, not what it currently
                                // is. When the host is paused only because the partner is
                                // buffering, it still intends to be playing.
                                val intendedPlaying = exoPlayer.isPlaying ||
                                    (partnerIsBuffering && wasPlayingBeforeBuffer)
                                val pong = SyncMessage(
                                    "pong",
                                    position = exoPlayer.currentPosition,
                                    timestamp = msg.timestamp,
                                    isPlaying = intendedPlaying
                                )
                                onSendSync(gson.toJson(pong))
                            }
                        }
                        "pong" -> {
                            if (!isHost) {
                                val rtt = System.currentTimeMillis() - msg.timestamp
                                val oneWay = rtt / 2
                                localLatencyMs = oneWay
                                // Correct for the transit time to estimate where host is RIGHT NOW
                                val estimatedHostPos = msg.position + if (msg.isPlaying) oneWay else 0L
                                val drift = abs(exoPlayer.currentPosition - estimatedHostPos)

                                AppLogger.d(TAG, "PONG: hostPos=${msg.position}ms estimated=${estimatedHostPos}ms " +
                                    "clientPos=${exoPlayer.currentPosition}ms drift=${drift}ms " +
                                    "rtt=${rtt}ms hostPlaying=${msg.isPlaying} clientPlaying=${exoPlayer.isPlaying} " +
                                    "hasSeeked=$hasSeekedForStartup hasStarted=$hasEverStartedPlaying")

                                // Bug 36 fix: guard against seek storm during startup.
                                // See hasSeekedForStartup declaration above for full explanation.
                                when {
                                    drift > 3000 && exoPlayer.isPlaying -> {
                                        // Mid-playback: seek immediately to correct real desync
                                        AppLogger.w(TAG, "Mid-playback seek: drift=${drift}ms → seekTo $estimatedHostPos")
                                        exoPlayer.seekTo(estimatedHostPos)
                                    }
                                    drift > 3000 && !hasSeekedForStartup && msg.isPlaying -> {
                                        // Startup: seek ONCE to align with host, then let
                                        // ExoPlayer buffer undisturbed until isPlaying=true.
                                        AppLogger.i(TAG, "Startup seek: drift=${drift}ms → seekTo $estimatedHostPos")
                                        exoPlayer.seekTo(estimatedHostPos)
                                        hasSeekedForStartup = true
                                    }
                                    drift > 300 && msg.isPlaying -> {
                                        val speed = if (exoPlayer.currentPosition < estimatedHostPos) 1.05f else 0.95f
                                        AppLogger.d(TAG, "Speed nudge: drift=${drift}ms → speed=$speed")
                                        exoPlayer.setPlaybackSpeed(speed)
                                    }
                                    else -> exoPlayer.setPlaybackSpeed(1.0f)
                                }

                                // First pong with isPlaying=true starts the client at the
                                // correct position (Bug 17 fix). Setting hasEverStartedPlaying
                                // here enables mid-playback buffering reports.
                                if (msg.isPlaying && !exoPlayer.isPlaying) {
                                    AppLogger.i(TAG, "First play command from host — starting client playback")
                                    hasEverStartedPlaying = true
                                    // Bug 43 fix: after PlaybackException, ExoPlayer enters
                                    // STATE_IDLE. play() on an IDLE player does nothing — the
                                    // client is permanently stuck on the error screen.
                                    // Fix: if IDLE (post-error), seekTo the estimated host
                                    // position THEN prepare(). seekTo() before prepare() sets
                                    // the start position so ExoPlayer loads from the right
                                    // HLS segment instead of restarting from 0.
                                    if (exoPlayer.playbackState == Player.STATE_IDLE) {
                                        AppLogger.w(TAG, "Player in IDLE (post-error) — re-preparing at ${estimatedHostPos}ms")
                                        exoPlayer.seekTo(estimatedHostPos)
                                        exoPlayer.prepare()
                                        hasSeekedForStartup = true
                                        playerError = null
                                    }
                                    exoPlayer.play()
                                }
                                if (!msg.isPlaying && exoPlayer.isPlaying) {
                                    AppLogger.i(TAG, "Host paused — pausing client")
                                    exoPlayer.pause()
                                }
                            }
                        }
                        "state", "sync" -> {
                            if (!isHost) {
                                when (msg.action) {
                                    "play" -> {
                                        AppLogger.i(TAG, "CMD play pos=${msg.position}ms")
                                        hasEverStartedPlaying = true
                                        hasSeekedForStartup = true
                                        // Bug 43 fix: same IDLE guard as in the pong handler —
                                        // a CMD play after a post-error recovery also needs
                                        // prepare() before seekTo()/play().
                                        if (exoPlayer.playbackState == Player.STATE_IDLE) {
                                            AppLogger.w(TAG, "CMD play: player in IDLE — re-preparing at ${msg.position}ms")
                                            playerError = null
                                            exoPlayer.seekTo(msg.position)
                                            exoPlayer.prepare()
                                        } else {
                                            exoPlayer.seekTo(msg.position)
                                        }
                                        exoPlayer.play()
                                    }
                                    "pause" -> {
                                        AppLogger.i(TAG, "CMD pause pos=${msg.position}ms")
                                        exoPlayer.pause()
                                        exoPlayer.seekTo(msg.position)
                                    }
                                    "seek" -> {
                                        AppLogger.i(TAG, "CMD seek pos=${msg.position}ms")
                                        exoPlayer.seekTo(msg.position)
                                    }
                                    "position" -> if (abs(exoPlayer.currentPosition - msg.position) > 2000) {
                                        AppLogger.i(TAG, "CMD position correction pos=${msg.position}ms (drift=${abs(exoPlayer.currentPosition - msg.position)}ms)")
                                        exoPlayer.seekTo(msg.position)
                                    }
                                }
                            }
                        }
                        "track" -> {
                            if (!isHost) {
                                val targetType = when (msg.action) {
                                    "audio" -> C.TRACK_TYPE_AUDIO
                                    "text" -> C.TRACK_TYPE_TEXT
                                    else -> -1
                                }
                                if (targetType != -1) {
                                    val targetIndex = msg.position.toInt().coerceAtLeast(0)
                                    val candidateGroup = availableTracks?.groups
                                        ?.firstOrNull { it.type == targetType && targetIndex < it.length }
                                    if (candidateGroup != null) {
                                        AppLogger.i(TAG, "Track switch: type=${msg.action} index=$targetIndex")
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(candidateGroup.mediaTrackGroup, targetIndex)
                                            )
                                            .build()
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    isHandlingSync = false
                }
            }
        }

        Box(modifier = modifier) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        keepScreenOn = true
                        useController = showControls
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Bug 39 fix: show a human-readable error instead of silent black screen
            if (playerError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.BrokenImage, contentDescription = null,
                            tint = Color.Red.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 12.dp))
                        Text("Video failed to load",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(playerError ?: "",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }

            IconButton(
                onClick = { showTrackSelector = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = "Audio & Subtitles", tint = Color.White)
            }

            // Partner buffering banner
            if (isHost && partnerIsBuffering) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 32.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Partner is buffering… paused", color = Color.White)
                }
            }

            // Latency indicator (client only)
            val shownLatency = if (localLatencyMs > 0L) localLatencyMs else currentLatency
            if (!isHost && shownLatency > 0L) {
                Text(
                    text = "Ping ${shownLatency}ms",
                    color = if (shownLatency > 300L) Color.Yellow else Color.Green,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                )
            }

            if (showTrackSelector && availableTracks != null) {
                TrackSelectionDialog(
                    tracks = availableTracks!!,
                    onDismiss = { showTrackSelector = false },
                    onTrackSelected = { group, index ->
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                            .build()
                        if (isHost) {
                            val action = when (group.type) {
                                C.TRACK_TYPE_AUDIO -> "audio"
                                C.TRACK_TYPE_TEXT -> "text"
                                else -> ""
                            }
                            if (action.isNotEmpty()) {
                                onSendSync(gson.toJson(SyncMessage(type = "track", action = action, position = index.toLong())))
                            }
                        }
                        showTrackSelector = false
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Convenience overload — takes SyncViewModel directly (used by RoomScreen)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VideoPlayer(
    uri: Uri,
    viewModel: SyncViewModel,
    modifier: Modifier = Modifier
) {
    VideoPlayer(
        uri = uri,
        isHost = viewModel.isHost.collectAsState().value,
        syncCommands = viewModel.syncCommand,
        reactionCommands = viewModel.reactionCommand,
        partnerBuffering = viewModel.partnerBuffering,
        latency = viewModel.latency,
        onSendSync = { viewModel.sendMessage(it) },
        modifier = modifier
    )
}
