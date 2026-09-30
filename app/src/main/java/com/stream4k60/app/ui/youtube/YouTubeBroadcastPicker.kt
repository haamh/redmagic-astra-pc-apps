package com.stream4k60.app.ui.youtube

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stream4k60.app.data.model.*
import com.stream4k60.app.youtube.*

/** OBS's "Manage Broadcast": sign in once, pick a scheduled YouTube broadcast, and its server and key are filled in. */
@Composable
fun YouTubeBroadcastPicker(onSelected: (StreamConfig, String) -> Unit, onDismiss: () -> Unit, vm: YouTubeBroadcastPickerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current as Activity
    val connected by vm.connected.collectAsState()
    val broadcasts by vm.broadcasts.collectAsState()
    val loading by vm.loading.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val video by vm.videoConfig.collectAsState()
    val settingsLoaded by vm.settingsLoaded.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.handleResult(ctx, r.data) { ok, msg -> error = msg; if (ok) vm.load() }
    }
    LaunchedEffect(Unit) { vm.restoreAndLoad(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Broadcast") },
        text = {
            Column {
                if (!connected) {
                    Text("Connect your YouTube account to stream to a scheduled broadcast without copying a stream key.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { error = null; vm.authorize(ctx, { launcher.launch(it) }) { ok, msg -> error = msg; if (ok) vm.load() } }) {
                        Text("Connect Google / YouTube")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ YouTube account connected", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.load() }, enabled = !loading) { Text("Refresh") }
                        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                    }
                    Text(
                        if (settingsLoaded) "Output: ${video.outputResWidth} × ${video.outputResHeight} at ${video.frameRate} FPS" else "Loading video settings…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Loading broadcasts…")
                        }
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        broadcasts.isEmpty() -> Text("No upcoming broadcasts. Schedule one in YouTube Studio (Create → Go live → Schedule stream), then tap Refresh.")
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(broadcasts) { b ->
                                val hasKey = !b.streamName.isNullOrBlank()
                                ListItem(
                                    headlineContent = { Text(b.title.ifBlank { "Untitled broadcast" }) },
                                    supportingContent = {
                                        Text(listOfNotNull(
                                            b.lifeCycle?.replaceFirstChar { it.uppercase() },
                                            b.scheduledStart?.take(16)?.replace('T', ' '),
                                            if (hasKey) null else "no stream key attached yet: open it in YouTube Studio once"
                                        ).joinToString(" · "))
                                    },
                                    trailingContent = {
                                        Button(enabled = settingsLoaded && hasKey, onClick = {
                                            runCatching { vm.toConfig(b) }.onSuccess { onSelected(it, b.title) }.onFailure { error = it.message }
                                        }) { Text("Use") }
                                    }
                                )
                            }
                        }
                    }
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
