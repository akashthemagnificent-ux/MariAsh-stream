package com.agon.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.agon.app.viewmodel.SyncMessage
import com.agon.app.viewmodel.SyncViewModel
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    val partnerIsBuffering by partnerBuffering.collectAsState()
    val currentLatency by latency.collectAsState()

    // 2-minute buffer, 100MB RAM cap — resilient across bad mobile connections
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // min buffer: 30 seconds before playback starts
                120_000,  // max buffer: 2 minutes always ready
                5_000,    // buffer for initial playback start
                10_000    // buffer after a rebuffer event
            )
            .setTargetBufferBytes(100 * 1024 * 1024)
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

        LaunchedEffect(uri) {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        // Auto-pause if partner is buffering (host waits for client)
        LaunchedEffect(partnerIsBuffering) {
            if (isHost) {
                if (partnerIsBuffering) {
                    wasPlayingBeforeBuffer = exoPlayer.isPlaying
                    exoPlayer.pause()
                } else if (wasPlayingBeforeBuffer) {
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
                    if (!isHandlingSync && isHost && !partnerIsBuffering) {
                        val msg = SyncMessage("state", if (isPlaying) "play" else "pause",
                            exoPlayer.currentPosition)
                        onSendSync(gson.toJson(msg))
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int
                ) {
                    if (!isHandlingSync && isHost && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        val msg = SyncMessage("sync", "seek", exoPlayer.currentPosition)
                        onSendSync(gson.toJson(msg))
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (!isHost) {
                        val action = when (state) {
                            Player.STATE_BUFFERING -> "start"
                            Player.STATE_READY -> "stop"
                            else -> return
                        }
                        onSendSync(gson.toJson(SyncMessage("buffering", action, exoPlayer.currentPosition)))
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    availableTracks = tracks
                }
            }
            exoPlayer.addListener(listener)
            onDispose {
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
        LaunchedEffect(Unit) {
            syncCommands.collectLatest { msg ->
                isHandlingSync = true
                when (msg.type) {
                    "ping" -> {
                        if (isHost) {
                            val pong = SyncMessage("pong", position = exoPlayer.currentPosition,
                                timestamp = msg.timestamp, isPlaying = exoPlayer.isPlaying)
                            onSendSync(gson.toJson(pong))
                        }
                    }
                    "pong" -> {
                        if (!isHost) {
                            val rtt = System.currentTimeMillis() - msg.timestamp
                            val oneWay = rtt / 2
                            // Correct for the transit time to estimate where host is RIGHT NOW
                            val estimatedHostPos = msg.position + if (msg.isPlaying) oneWay else 0L
                            val drift = abs(exoPlayer.currentPosition - estimatedHostPos)
                            when {
                                drift > 3000 -> exoPlayer.seekTo(estimatedHostPos)
                                drift > 300 && msg.isPlaying -> {
                                    exoPlayer.setPlaybackSpeed(
                                        if (exoPlayer.currentPosition < estimatedHostPos) 1.05f else 0.95f
                                    )
                                }
                                else -> exoPlayer.setPlaybackSpeed(1.0f)
                            }
                            if (msg.isPlaying && !exoPlayer.isPlaying) exoPlayer.play()
                            if (!msg.isPlaying && exoPlayer.isPlaying) exoPlayer.pause()
                        }
                    }
                    "state", "sync" -> {
                        if (!isHost) {
                            when (msg.action) {
                                "play" -> { exoPlayer.seekTo(msg.position); exoPlayer.play() }
                                "pause" -> { exoPlayer.pause(); exoPlayer.seekTo(msg.position) }
                                "seek" -> exoPlayer.seekTo(msg.position)
                                "position" -> if (abs(exoPlayer.currentPosition - msg.position) > 2000)
                                    exoPlayer.seekTo(msg.position)
                            }
                        }
                    }
                }
                delay(100)
                isHandlingSync = false
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
            if (!isHost && currentLatency > 0L) {
                Text(
                    text = "Ping ${currentLatency}ms",
                    color = if (currentLatency > 300L) Color.Yellow else Color.Green,
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
    val isHost by viewModel.isHost.collectAsState()
    VideoPlayer(
        uri = uri,
        isHost = isHost,
        syncCommands = viewModel.syncCommand,
        reactionCommands = viewModel.reactionCommand,
        partnerBuffering = viewModel.partnerBuffering,
        latency = viewModel.latency,
        onSendSync = { viewModel.sendMessage(it) },
        onPositionUpdate = null,
        showControls = isHost,
        modifier = modifier
    )
}
