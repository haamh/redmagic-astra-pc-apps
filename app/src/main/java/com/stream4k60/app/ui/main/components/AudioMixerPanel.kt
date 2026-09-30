package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.engine.NativeAudioBridge
import org.json.JSONObject

@Composable
fun AudioMixerPanel(
    sources: List<SourceItem>,
    onSourceConfigChanged: (SourceItem, String) -> Unit,
    peakProvider: (String) -> Float = { 0f },
    modifier: Modifier = Modifier
) {
    val audioSources = sources.filter {
        it.type.equals("AUDIO_INPUT", true) || it.type.equals("PLAYBACK_AUDIO", true) || it.type.equals("MEDIA", true) ||
            (it.type.equals("USB_CAPTURE", true) && runCatching {
                val root = JSONObject(it.configJson); val settings = root.optJSONObject("settings") ?: root
                settings.optInt("audioDeviceId", -1) >= 0
            }.getOrDefault(false))
    }
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Audio Mixer", fontSize = 12.sp)
            Icon(Icons.Default.Settings, contentDescription = "Mixer settings", modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(thickness = 1.dp)
        if (audioSources.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("No audio sources", style = MaterialTheme.typography.labelMedium)
                    Text("Add an Audio Input, media source, or Android Playback Audio source", style = MaterialTheme.typography.bodySmall)
                }
            }
            return
        }
        Row(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            audioSources.forEach { src ->
                AudioStrip(src, onSourceConfigChanged, peakProvider, Modifier.width(94.dp).heightIn(min = 160.dp))
            }
        }
    }
}

@Composable
private fun AudioStrip(
    source: SourceItem,
    onSourceConfigChanged: (SourceItem, String) -> Unit,
    peakProvider: (String) -> Float,
    modifier: Modifier
) {
    val state = remember(source.id, source.configJson) {
        val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
        val j = root.optJSONObject("settings") ?: root
        val audio = root.optJSONObject("audio")
        MixerUiState(
            volume = j.optDouble("volume", audio?.optDouble("volume", 1.0) ?: 1.0).toFloat().coerceIn(0f, 2f),
            balance = j.optDouble("balance", audio?.optDouble("balance", 0.0) ?: 0.0).toFloat().coerceIn(-1f, 1f),
            muted = j.optBoolean("muted", audio?.optBoolean("muted", false) ?: false),
            monitoring = j.optString("monitoring", audio?.optString("monitoring", "MONITOR_AND_OUTPUT") ?: "MONITOR_AND_OUTPUT"),
            solo = j.optBoolean("solo", audio?.optBoolean("solo", false) ?: false),
            syncOffsetMs = j.optInt("syncOffsetMs", audio?.optInt("syncOffsetMs", 0) ?: 0)
        )
    }
    fun commit(next: MixerUiState) {
        val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
        val j = root.optJSONObject("settings") ?: root
        j.put("volume", next.volume); j.put("balance", next.balance); j.put("muted", next.muted)
        j.put("monitoring", next.monitoring); j.put("solo", next.solo); j.put("syncOffsetMs", next.syncOffsetMs)
        onSourceConfigChanged(source, root.toString())
    }
    var peak by remember(source.id) { mutableStateOf(0f) }
    val peakId=when(source.type.uppercase()){"PLAYBACK_AUDIO"->NativeAudioBridge.PLAYBACK_SOURCE_ID;"MEDIA"->"media_audio_${source.id}";"USB_CAPTURE"->"usb_audio_${source.id}";else->source.id}
    LaunchedEffect(peakId) { while (true) { peak = peakProvider(peakId).coerceIn(0f, 1f); kotlinx.coroutines.delay(80) } }
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(source.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${(20 * kotlin.math.log10(state.volume.coerceAtLeast(0.0001f))).coerceIn(-80f, 6f).let { String.format("%.1f dB", it) }}", fontSize = 9.sp)
            LinearProgressIndicator(progress = { peak }, modifier = Modifier.fillMaxWidth().height(5.dp))
            Slider(
                value = state.volume,
                onValueChange = { commit(state.copy(volume = it)) },
                valueRange = 0f..2f,
                modifier = Modifier.height(120.dp)
            )
            Text("Pan", fontSize = 9.sp)
            Slider(value = state.balance, onValueChange = { commit(state.copy(balance = it)) }, valueRange = -1f..1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(selected = state.muted, onClick = { commit(state.copy(muted = !state.muted)) }, label = { Text("M", fontSize = 9.sp) }, enabled = true)
                FilterChip(selected = state.solo, onClick = { commit(state.copy(solo = !state.solo)) }, label = { Text("S", fontSize = 9.sp) }, enabled = true)
            }
            Spacer(Modifier.height(2.dp))
            var menu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text(when (state.monitoring) {
                        "OFF" -> "Off"
                        "MONITOR_ONLY" -> "Monitor"
                        "OUTPUT_ONLY" -> "Output"
                        else -> "Both"
                    }, fontSize = 9.sp)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Off") }, onClick = { commit(state.copy(monitoring = "OFF")); menu = false })
                    listOf("OUTPUT_ONLY" to "Output only", "MONITOR_ONLY" to "Monitor only", "MONITOR_AND_OUTPUT" to "Both").forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { commit(state.copy(monitoring = id)); menu = false })
                    }
                }
            }
            Text("Sync ${state.syncOffsetMs} ms", fontSize = 8.sp)
        }
    }
}

private data class MixerUiState(
    val volume: Float,
    val balance: Float,
    val muted: Boolean,
    val monitoring: String,
    val solo: Boolean,
    val syncOffsetMs: Int
)
