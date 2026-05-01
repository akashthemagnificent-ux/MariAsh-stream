package com.agon.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agon.app.relay.NetworkProfile
import com.agon.app.relay.NetworkProfiles
import com.agon.app.ui.components.VideoPlayer
import com.agon.app.viewmodel.TestState
import com.agon.app.viewmodel.TestViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTestScreen(testViewModel: TestViewModel = viewModel()) {

    val testState by testViewModel.testState.collectAsState()
    val currentProfile by testViewModel.currentProfile.collectAsState()
    val hostVideoUri by testViewModel.hostVideoUri.collectAsState()
    val clientHlsUri by testViewModel.clientHlsUri.collectAsState()

    val driftMs by testViewModel.driftMs.collectAsState()
    val measuredOneWayMs by testViewModel.measuredOneWayMs.collectAsState()
    val uploadedBytes by testViewModel.uploadedBytes.collectAsState()
    val downloadedBytes by testViewModel.downloadedBytes.collectAsState()
    val segmentCount by testViewModel.segmentCount.collectAsState()
    val droppedPackets by testViewModel.droppedPackets.collectAsState()
    val clientPartnerBuffering by testViewModel.clientPartnerBuffering.collectAsState()
    val hostPartnerBuffering by testViewModel.hostPartnerBuffering.collectAsState()

    var showProfilePicker by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { testViewModel.pickVideo(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agon Test Lab", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Honest intercontinental simulation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showProfilePicker = true }) {
                        Text(currentProfile.emoji + " " + currentProfile.label, fontSize = 12.sp)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Live Stats Bar ────────────────────────────────────────
            StatsBar(
                profile = currentProfile,
                driftMs = driftMs,
                measuredOneWayMs = measuredOneWayMs,
                uploadedBytes = uploadedBytes,
                downloadedBytes = downloadedBytes,
                segmentCount = segmentCount,
                droppedPackets = droppedPackets
            )

            // ── HOST panel (top half) ─────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF4CAF50))
            ) {
                when {
                    hostVideoUri != null -> {
                        VideoPlayer(
                            uri = hostVideoUri!!,
                            isHost = true,
                            syncCommands = testViewModel.hostSyncCmd,
                            reactionCommands = testViewModel.hostReactionCmd,
                            partnerBuffering = testViewModel.clientPartnerBuffering,
                            latency = testViewModel.clientLatency,
                            onSendSync = { testViewModel.sendSyncAsHost(it) },
                            onPositionUpdate = { testViewModel.updateHostPosition(it) },
                            showControls = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.VideoFile, contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Pick a movie to start", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { fileLauncher.launch(arrayOf("video/*")) }) {
                                    Icon(Icons.Default.FileOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Select Video")
                                }
                            }
                        }
                    }
                }

                // HOST label
                Surface(
                    color = Color(0xFF4CAF50).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        "HOST  (You, local file)",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Segmenting progress overlay
                if (testState is TestState.Segmenting) {
                    val segs = (testState as TestState.Segmenting).segmentsReady
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(8.dp)
                    ) {
                        Text(
                            "Preparing movie… $segs segments ready (${segs * 4}s buffered)",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            // ── OCEAN Divider ─────────────────────────────────────────
            OceanDivider(profile = currentProfile, measuredMs = measuredOneWayMs)

            // ── CLIENT panel (bottom half) ────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF2196F3))
            ) {
                when {
                    clientHlsUri != null -> {
                        VideoPlayer(
                            uri = clientHlsUri!!,
                            isHost = false,
                            syncCommands = testViewModel.clientSyncCmd,
                            reactionCommands = testViewModel.clientReactionCmd,
                            partnerBuffering = testViewModel.hostPartnerBuffering,
                            latency = testViewModel.hostLatency,
                            onSendSync = { testViewModel.sendSyncAsClient(it) },
                            onPositionUpdate = { testViewModel.updateClientPosition(it) },
                            showControls = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    testState is TestState.Segmenting -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val segs = (testState as TestState.Segmenting).segmentsReady
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("Waiting for stream…")
                                Text("$segs segments ready, need 2 to start", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    testState is TestState.Ready -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Stream ready! Loading…")
                            }
                        }
                    }
                    testState is TestState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
                                Spacer(Modifier.height(8.dp))
                                Text("Error: ${(testState as TestState.Error).message}",
                                    color = Color.Red, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Pick a video above to begin", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // CLIENT label
                Surface(
                    color = Color(0xFF2196F3).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        "CLIENT  (Your partner, via relay)",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Drift indicator chip
                if (driftMs > 100) {
                    DriftChip(driftMs = driftMs, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
            }
        }
    }

    // Profile picker dialog
    if (showProfilePicker) {
        ProfilePickerDialog(
            current = currentProfile,
            onSelect = { profile ->
                testViewModel.setProfile(profile)
                showProfilePicker = false
            },
            onDismiss = { showProfilePicker = false }
        )
    }
}

// ─────────────────────────────────────────────────
// Stats Bar — shows live numbers during the test
// ─────────────────────────────────────────────────
@Composable
fun StatsBar(
    profile: NetworkProfile,
    driftMs: Long,
    measuredOneWayMs: Long,
    uploadedBytes: Long,
    downloadedBytes: Long,
    segmentCount: Int,
    droppedPackets: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(label = "Latency", value = if (measuredOneWayMs > 0) "${measuredOneWayMs}ms" else "${profile.oneWayLatencyMs}ms*",
                color = when {
                    (measuredOneWayMs.takeIf { it > 0 } ?: profile.oneWayLatencyMs) > 300 -> Color(0xFFFF5722)
                    (measuredOneWayMs.takeIf { it > 0 } ?: profile.oneWayLatencyMs) > 150 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                })
            StatItem(label = "Drift", value = "${driftMs}ms",
                color = when {
                    driftMs > 2000 -> Color(0xFFFF5722)
                    driftMs > 500 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                })
            StatItem(label = "Segs", value = "$segmentCount", color = Color(0xFF90CAF9))
            StatItem(label = "Upload", value = formatBytes(uploadedBytes), color = Color(0xFFA5D6A7))
            StatItem(label = "DL", value = formatBytes(downloadedBytes), color = Color(0xFF90CAF9))
            if (droppedPackets > 0) {
                StatItem(label = "Drop", value = "$droppedPackets", color = Color(0xFFFF5722))
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ─────────────────────────────────────────────────
// Ocean Divider — the visual "continent gap" between host and client
// ─────────────────────────────────────────────────
@Composable
fun OceanDivider(profile: NetworkProfile, measuredMs: Long) {
    val displayMs = if (measuredMs > 0) measuredMs else profile.oneWayLatencyMs
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                when {
                    displayMs > 300 -> Color(0xFF37474F)
                    displayMs > 100 -> Color(0xFF0D47A1).copy(alpha = 0.8f)
                    else -> Color(0xFF1565C0).copy(alpha = 0.6f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "∿∿∿  ${profile.emoji}  ${profile.label}  •  ${displayMs}ms one-way  •  ${profile.bandwidthKbps / 1000} Mbps  ∿∿∿",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────
// Drift chip — shows how far the client is behind the host
// ─────────────────────────────────────────────────
@Composable
fun DriftChip(driftMs: Long, modifier: Modifier = Modifier) {
    val color = when {
        driftMs > 2000 -> Color(0xFFFF5722)
        driftMs > 500  -> Color(0xFFFF9800)
        else           -> Color(0xFFFFEB3B)
    }
    Surface(
        color = color.copy(alpha = 0.9f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = "⟳ Drift ${driftMs}ms",
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ─────────────────────────────────────────────────
// Profile Picker Dialog
// ─────────────────────────────────────────────────
@Composable
fun ProfilePickerDialog(
    current: NetworkProfile,
    onSelect: (NetworkProfile) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simulate Network Conditions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose the distance gap to simulate:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                NetworkProfiles.all.forEach { profile ->
                    OutlinedButton(
                        onClick = { onSelect(profile) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (profile.id == current.id)
                            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("${profile.emoji}  ${profile.label}", fontWeight = FontWeight.SemiBold)
                            Text(profile.description, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> "${bytes / 1_048_576}MB"
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}
