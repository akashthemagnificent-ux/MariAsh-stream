package com.agon.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.agon.app.data.AppPreferences
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val relayUrl by AppPreferences.relayUrl(context).collectAsState(initial = "")

    var roomId by remember { mutableStateOf("") }
    var webUrl by remember { mutableStateOf("") }

    // Generate a short 6-char uppercase room code
    fun newRoomCode() = UUID.randomUUID().toString()
        .replace("-", "").take(6).uppercase()

    val hostRoomCode = remember { newRoomCode() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MariAsh Stream", fontWeight = FontWeight.ExtraBold)
                        Text("Watch together, wherever you are",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Relay URL warning
            if (relayUrl.isBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Column {
                            Text("No relay server set",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("Go to Settings to add your free Render server. Without one, only web URLs will work.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }

            // ── Host a local file ─────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VideoFile, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Host a Local Movie", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Share a movie file from your phone. Your partner joins with the room code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Your room code: $hostRoomCode",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Button(
                        onClick = { navController.navigate("room/$hostRoomCode/true") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = relayUrl.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start — Room $hostRoomCode")
                    }
                    if (relayUrl.isBlank()) {
                        Text("Set a relay server in Settings first",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ── Host a web URL ────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Host a Web Video", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Paste a direct MP4 or HLS (.m3u8) link. No relay needed for this mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = webUrl,
                        onValueChange = { webUrl = it },
                        label = { Text("Direct video URL") },
                        placeholder = { Text("https://example.com/movie.mp4") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    val webRoomCode = remember { newRoomCode() }
                    Button(
                        onClick = {
                            if (webUrl.isNotBlank()) {
                                val encoded = URLEncoder.encode(webUrl, StandardCharsets.UTF_8.toString())
                                navController.navigate("room/$webRoomCode/true?webUrl=$encoded")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = webUrl.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Host Web Party")
                    }
                }
            }

            HorizontalDivider()

            // ── Join a room ───────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Group, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("Join a Room", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Enter the 6-letter code your partner shared with you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { roomId = it.uppercase().take(8) },
                        label = { Text("Room Code") },
                        placeholder = { Text("MVKX42") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = { if (roomId.isNotBlank()) navController.navigate("room/$roomId/false") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = roomId.isNotBlank()
                    ) {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Join Room")
                    }
                }
            }

            HorizontalDivider()

            // ── Test Lab ──────────────────────────────────────────────
            OutlinedButton(
                onClick = { navController.navigate("local_test") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Science, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Test Lab (Continent Simulation)")
            }
        }
    }
}
