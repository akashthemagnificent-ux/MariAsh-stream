package com.agon.app.ui.screens

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agon.app.data.AppPreferences
import com.agon.app.ui.components.VideoPlayer
import com.agon.app.viewmodel.SyncViewModel
import kotlinx.coroutines.delay
import java.util.UUID

data class FloatingReaction(val id: String, val emoji: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomId: String,
    isHost: Boolean,
    webUrl: String? = null,
    viewModel: SyncViewModel = viewModel()
) {
    val context = LocalContext.current
    val relayUrl by AppPreferences.relayUrl(context).collectAsState(initial = "")
    val relayToken by AppPreferences.relayToken(context).collectAsState(initial = "")

    val isConnected by viewModel.isConnected.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isWakingServer by viewModel.isWakingServer.collectAsState()
    val wakingElapsedSeconds by viewModel.wakingElapsedSeconds.collectAsState()
    val proxyUrl by viewModel.proxyUrl.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val isSegmenting by viewModel.isSegmenting.collectAsState()
    val segmentsUploaded by viewModel.segmentsUploaded.collectAsState()

    var isPipMode by remember { mutableStateOf(false) }
    val activity = context as? ComponentActivity
    val activeReactions = remember { mutableStateListOf<FloatingReaction>() }

    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { info ->
            isPipMode = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(observer)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(observer) }
    }

    LaunchedEffect(roomId, relayUrl, relayToken) {
        viewModel.initRoom(roomId, isHost, relayUrl, relayToken)
    }

    LaunchedEffect(webUrl) {
        if (webUrl != null && isHost) {
            viewModel.setWebUrl(webUrl)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.reactionCommand.collect { emoji ->
            val reaction = FloatingReaction(UUID.randomUUID().toString(), emoji)
            activeReactions.add(reaction)
            delay(2000)
            activeReactions.remove(reaction)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.setVideoFile(it) }
    }

    Scaffold(
        topBar = {
            if (!isPipMode) {
                TopAppBar(
                    title = { Text("Room: $roomId") },
                    actions = {
                        if (isHost && webUrl == null) {
                            IconButton(onClick = { launcher.launch(arrayOf("video/*")) }) {
                                Icon(Icons.Default.FileOpen, contentDescription = "Select Video")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isPipMode && (videoUri != null || proxyUrl != null)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("😂", "😮", "❤️", "👏", "🔥").forEach { emoji ->
                            IconButton(onClick = { viewModel.sendReaction(emoji) }) {
                                Text(emoji, fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isPipMode) PaddingValues(0.dp) else padding)
        ) {
            if (isHost) {
                // ── Host view ──────────────────────────────────────────
                if (videoUri == null && webUrl == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Room Code", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(roomId, style = MaterialTheme.typography.displaySmall)
                            Text("Share this with your partner", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { launcher.launch(arrayOf("video/*")) }) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Select Movie File")
                            }
                            Spacer(Modifier.height(24.dp))
                            if (!isConnected) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                if (isWakingServer) {
                                    Text("Waking server up… (${wakingElapsedSeconds}s)")
                                    Text("Server was asleep — it'll be ready in ~30 s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("Waiting for partner… ($connectionStatus)")
                                }
                            } else {
                                Text("Partner connected!", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    // Preparing movie / uploading segments
                    if (isSegmenting && segmentsUploaded < 2 && videoUri != null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Preparing movie…", style = MaterialTheme.typography.titleMedium)
                                Text("$segmentsUploaded segments uploaded (need 2 to start)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("First ${segmentsUploaded * 4} seconds ready",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        val uriToPlay = videoUri ?: Uri.parse(webUrl)
                        Box(Modifier.fillMaxSize()) {
                            VideoPlayer(
                                uri = uriToPlay,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (!isConnected && !isPipMode) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Waiting for partner… ($connectionStatus)",
                                        modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Client view ────────────────────────────────────────
                when {
                    !isConnected && isWakingServer -> {
                        WakingServerScreen(elapsedSeconds = wakingElapsedSeconds)
                    }
                    !isConnected -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Connecting to Room $roomId…")
                                Text(connectionStatus, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    videoUri == null && proxyUrl == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Waiting for host to select a video…")
                            }
                        }
                    }
                    else -> {
                        val uriToPlay = videoUri ?: Uri.parse(proxyUrl)
                        VideoPlayer(
                            uri = uriToPlay!!,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Floating emoji reactions
            activeReactions.forEach { reaction ->
                key(reaction.id) {
                    val offsetY = remember { Animatable(300f) }
                    val alpha = remember { Animatable(1f) }
                    LaunchedEffect(Unit) { offsetY.animateTo(0f, tween(2000)) }
                    LaunchedEffect(Unit) { delay(1000); alpha.animateTo(0f, tween(1000)) }
                    Text(
                        text = reaction.emoji,
                        fontSize = 48.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 32.dp, bottom = 100.dp)
                            .graphicsLayer { translationY = offsetY.value; this.alpha = alpha.value }
                    )
                }
            }
        }
    }
}
