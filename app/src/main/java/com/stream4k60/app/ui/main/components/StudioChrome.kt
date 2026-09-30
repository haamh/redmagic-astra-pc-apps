package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.ui.theme.ObsBorder
import com.stream4k60.app.ui.theme.ObsButton
import com.stream4k60.app.ui.theme.ObsDockTitle
import com.stream4k60.app.ui.theme.ObsRed
import kotlinx.coroutines.delay
import java.util.Locale

/** An OBS-style dock: bold title bar over its content. */
@Composable
fun Dock(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, ObsBorder).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(ObsDockTitle).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        content()
    }
}

/** Flat full-width button used by the Controls dock, like OBS's. */
@Composable
fun ObsButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (active) MaterialTheme.colorScheme.primary else ObsButton, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The bar under the preview: selected source, Properties and Filters (OBS's source toolbar). */
@Composable
fun SourceToolbar(selectedName: String?, onProperties: () -> Unit, onFilters: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(selectedName ?: "No source selected", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
        ToolbarChip("Properties", Icons.Default.Settings, enabled = selectedName != null, onClick = onProperties)
        ToolbarChip("Filters", Icons.Default.FilterAlt, enabled = selectedName != null, onClick = onFilters)
    }
}

@Composable
private fun ToolbarChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(28.dp).border(1.dp, ObsBorder, RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 13.sp, color = tint)
    }
}

/** Scene Transitions dock body: transition type and duration. */
@Composable
fun TransitionsDockContent(selected: String, onSelect: (String) -> Unit, isStudioMode: Boolean, onTransition: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("Cut", "Fast Fade", "Fade", "Slow Fade")
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth().height(30.dp).background(ObsButton, RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Text(selected, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("▾", fontSize = 13.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); open = false }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Text(when (selected) { "Cut" -> "—"; "Fast Fade" -> "180 ms"; "Slow Fade" -> "600 ms"; else -> "300 ms" }, fontSize = 13.sp)
        }
        if (isStudioMode) ObsButton("Transition", onTransition, active = true)
    }
}

/** Controls dock body, with OBS's buttons. Stops can ask for confirmation. */
@Composable
fun ControlsDockContent(
    isStreaming: Boolean,
    isRecording: Boolean,
    isStudioMode: Boolean,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onReplayBuffer: () -> Unit,
    onToggleStudio: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ObsButton(if (isStreaming) "Stop Streaming" else "Start Streaming", if (isStreaming) onStopStreaming else onStartStreaming, active = isStreaming)
        ObsButton(if (isRecording) "Stop Recording" else "Start Recording", if (isRecording) onStopRecording else onStartRecording, active = isRecording)
        ObsButton("Start Replay Buffer", onReplayBuffer)
        ObsButton("Studio Mode", onToggleStudio, active = isStudioMode)
        ObsButton("Settings", onSettings)
    }
}

/** OBS's status bar: LIVE and REC timers, FPS and render time from the compositor, thermal state. */
@Composable
fun StudioStatusBar(isStreaming: Boolean, isRecording: Boolean, targetFps: Int, thermal: String, modifier: Modifier = Modifier) {
    var liveSeconds by remember { mutableLongStateOf(0L) }
    var recSeconds by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableFloatStateOf(0f) }
    var renderMs by remember { mutableFloatStateOf(0f) }
    var dropped by remember { mutableIntStateOf(0) }
    LaunchedEffect(isStreaming) { liveSeconds = 0; while (isStreaming) { delay(1000); liveSeconds++ } }
    LaunchedEffect(isRecording) { recSeconds = 0; while (isRecording) { delay(1000); recSeconds++ } }
    LaunchedEffect(Unit) {
        var lastFrames = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(0L)
        val droppedAtStart = runCatching { NativeEngine.getDroppedFrames() }.getOrDefault(0L)
        while (true) {
            delay(1000)
            val frames = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(lastFrames)
            fps = (frames - lastFrames).toFloat(); lastFrames = frames
            renderMs = runCatching { NativeEngine.getRenderTimeMs() }.getOrDefault(0f)
            dropped = (runCatching { NativeEngine.getDroppedFrames() }.getOrDefault(droppedAtStart) - droppedAtStart).toInt()
        }
    }
    fun clock(s: Long) = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    Row(
        modifier.fillMaxWidth().height(24.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Thermal: $thermal", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        StatusDot(isStreaming); Text("LIVE ${clock(liveSeconds)}", fontSize = 11.sp)
        StatusDot(isRecording); Text("REC ${clock(recSeconds)}", fontSize = 11.sp)
        Text(String.format(Locale.US, "Render %.1f ms", renderMs), fontSize = 11.sp)
        Text("Dropped $dropped", fontSize = 11.sp)
        Text(String.format(Locale.US, "%.2f / %d FPS", fps, targetFps), fontSize = 11.sp)
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    Box(Modifier.size(8.dp).background(if (on) ObsRed else Color(0xFF55585F), CircleShape))
}

/** Dock widths/heights sized from the screen, tuned for the Astra's 2400×1504 panel. */
data class StudioLayoutMetrics(val leftWidth: Dp, val bottomHeight: Dp, val transitionsWidth: Dp, val controlsWidth: Dp)

fun studioLayoutMetrics(width: Dp, height: Dp) = StudioLayoutMetrics(
    leftWidth = (width * 0.18f).coerceIn(200.dp, 300.dp),
    bottomHeight = (height * 0.30f).coerceIn(170.dp, 250.dp),
    transitionsWidth = (width * 0.16f).coerceIn(180.dp, 260.dp),
    controlsWidth = (width * 0.18f).coerceIn(190.dp, 280.dp)
)
