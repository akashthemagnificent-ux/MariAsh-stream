package com.agon.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.agon.app.data.AppPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val storedUrl by AppPreferences.relayUrl(context).collectAsState(initial = "")
    var urlInput by remember(storedUrl) { mutableStateOf(storedUrl) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        keyboard?.hide()
        scope.launch {
            AppPreferences.setRelayUrl(context, urlInput.trim())
            saved = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Relay Server ──────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Relay Server", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Enter the URL of your deployed relay server. " +
                        "Get one free at render.com — no credit card needed. " +
                        "Leave blank to use the default (shared, may be slow).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; saved = false },
                        label = { Text("Relay URL") },
                        placeholder = { Text("https://your-app.onrender.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        trailingIcon = {
                            if (saved) {
                                Icon(Icons.Default.Check, contentDescription = "Saved",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )

                    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (saved) "Saved!" else "Save Relay URL")
                    }
                }
            }

            // ── Deploy Guide ──────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How to get a free relay server",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall)
                    
                    DeployStep(num = 1, text = "Open render.com in your browser and sign up with email — no card needed")
                    DeployStep(num = 2, text = "Tap \"New +\" → \"Web Service\"")
                    DeployStep(num = 3, text = "Choose \"Deploy from GitHub\" and connect the agon-relay repo")
                    DeployStep(num = 4, text = "Select the Free plan and tap \"Create Web Service\"")
                    DeployStep(num = 5, text = "Wait ~2 minutes for first deploy. Copy the URL it gives you (ends in .onrender.com)")
                    DeployStep(num = 6, text = "Paste that URL above and tap Save. Done!")

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tip: the free tier goes to sleep after 15 min idle. " +
                        "It wakes in ~30 seconds when you start a movie — the app shows a \"waking server\" screen so nobody is confused.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────
            Text(
                "Agon v1.0 • Built for distance relationships\nNo ads. No accounts. No data collection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun DeployStep(num: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("$num", color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
