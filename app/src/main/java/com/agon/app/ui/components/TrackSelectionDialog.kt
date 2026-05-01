package com.agon.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Tracks

@Composable
fun TrackSelectionDialog(
    tracks: Tracks,
    onDismiss: () -> Unit,
    onTrackSelected: (Tracks.Group, Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio & Subtitles") },
        text = {
            LazyColumn {
                val groups = tracks.groups
                
                // Audio Tracks
                item {
                    Text("Audio Tracks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
                    items(group.length) { i ->
                        val format = group.getTrackFormat(i)
                        val label = format.language ?: format.label ?: "Audio Track ${i + 1}"
                        val isSelected = group.isTrackSelected(i)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTrackSelected(group, i) }
                                .padding(8.dp)
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }

                item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }

                // Subtitle Tracks
                item {
                    Text("Subtitles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
                    items(group.length) { i ->
                        val format = group.getTrackFormat(i)
                        val label = format.language ?: format.label ?: "Subtitle Track ${i + 1}"
                        val isSelected = group.isTrackSelected(i)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTrackSelected(group, i) }
                                .padding(8.dp)
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}