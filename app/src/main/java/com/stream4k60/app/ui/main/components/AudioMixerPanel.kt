package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.common.ClosableTitle

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.theme.ObsBorder
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

private val MeterGreen = Color(0xFF4CAF50)
private val MeterYellow = Color(0xFFFFC107)
private val MeterRed = Color(0xFFE53935)
private val GlobalBadge = Color(0xFF1E3A8A)
private val ActiveText = Color(0xFF6EA8FE)
private const val METER_MIN_DB = -60f

/**
 * OBS's Audio Mixer: one channel per audio source, side by side (vertical layout) or stacked (horizontal layout).
 * Each channel has a fader in dB, a meter, mute and monitoring buttons, and a menu for the rest.
 */
@Composable
fun AudioMixerPanel(
    sources: List<SourceItem>,
    onSourceConfigChanged: (SourceItem, String) -> Unit,
    peakProvider: (String) -> Float = { 0f },
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    onFilters: ((SourceItem) -> Unit)? = null,
    onProperties: ((SourceItem) -> Unit)? = null,
    onRename: ((SourceItem, String) -> Unit)? = null
) {
    val prefs = LocalContext.current.getSharedPreferences("studio_layout", Context.MODE_PRIVATE)
    var vertical by remember { mutableStateOf(prefs.getBoolean("mixer_vertical", true)) }
    fun setVertical(v: Boolean) { vertical = v; prefs.edit().putBoolean("mixer_vertical", v).apply() }
    var advanced by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SourceItem?>(null) }

    val audioSources = sources.filter(::isAudioSource)
    val shown = audioSources.filterNot { mixerState(it).hidden }
    val hiddenCount = audioSources.size - shown.size
    val unhideAll = { audioSources.filter { mixerState(it).hidden }.forEach { commit(it, mixerState(it).copy(hidden = false), onSourceConfigChanged) } }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        if (showHeader) {
            Text("Audio Mixer", fontSize = 12.sp, modifier = Modifier.padding(7.dp))
            HorizontalDivider(thickness = 1.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                audioSources.isEmpty() -> Text(
                    "No audio sources. Turn on Desktop Audio or Mic/Aux in Settings → Audio, or add an audio source.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(12.dp), textAlign = TextAlign.Center
                )
                vertical -> Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    shown.forEach { src ->
                        Channel(src, true, onSourceConfigChanged, peakProvider, onFilters, onProperties, { renaming = it }, { advanced = true }, unhideAll,
                            Modifier.width(72.dp).fillMaxHeight())
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    shown.forEach { src ->
                        Channel(src, false, onSourceConfigChanged, peakProvider, onFilters, onProperties, { renaming = it }, { advanced = true }, unhideAll,
                            Modifier.fillMaxWidth())
                    }
                }
            }
        }
        HorizontalDivider(color = ObsBorder)
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$hiddenCount hidden", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = hiddenCount > 0) { unhideAll() }.padding(4.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { setVertical(!vertical) }, modifier = Modifier.size(30.dp)) {
                Icon(if (vertical) Icons.Default.ViewStream else Icons.Default.ViewColumn, "Switch mixer layout", Modifier.size(17.dp))
            }
            IconButton(onClick = { advanced = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Tune, "Advanced Audio Properties", Modifier.size(17.dp))
            }
            var options by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { options = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Options", fontSize = 12.sp); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(text = { Text(if (vertical) "Horizontal layout" else "Vertical layout") }, onClick = { setVertical(!vertical); options = false })
                    DropdownMenuItem(text = { Text("Unhide all") }, enabled = hiddenCount > 0, onClick = { unhideAll(); options = false })
                    DropdownMenuItem(text = { Text("Advanced Audio Properties") }, onClick = { advanced = true; options = false })
                }
            }
        }
    }

    if (advanced) AdvancedAudioPropertiesDialog(audioSources, onSourceConfigChanged) { advanced = false }
    renaming?.let { src ->
        var name by remember(src.id) { mutableStateOf(src.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { ClosableTitle("Rename", { renaming = null }) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename?.invoke(src, name); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

private fun isAudioSource(it: SourceItem) =
    it.type.equals("AUDIO_INPUT", true) || it.type.equals("PLAYBACK_AUDIO", true) || it.type.equals("MEDIA", true) ||
        (it.type.equals("USB_CAPTURE", true) && runCatching {
            val root = JSONObject(it.configJson); (root.optJSONObject("settings") ?: root).optInt("audioDeviceId", -1) >= 0
        }.getOrDefault(false))

private fun isGlobal(src: SourceItem) = src.id.startsWith("global:")

private fun peakIdOf(source: SourceItem) = when (source.type.uppercase()) {
    "PLAYBACK_AUDIO" -> NativeAudioBridge.PLAYBACK_SOURCE_ID
    "MEDIA" -> "media_audio_${source.id}"
    "USB_CAPTURE" -> "usb_audio_${source.id}"
    else -> source.id
}

internal data class MixerUiState(
    val volume: Float,
    val balance: Float,
    val muted: Boolean,
    val monitoring: String,
    val syncOffsetMs: Int,
    val hidden: Boolean,
    val locked: Boolean
)

internal fun mixerState(source: SourceItem): MixerUiState {
    val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
    val j = root.optJSONObject("settings") ?: root
    val audio = root.optJSONObject("audio")
    return MixerUiState(
        volume = j.optDouble("volume", audio?.optDouble("volume", 1.0) ?: 1.0).toFloat().coerceIn(0f, 2f),
        balance = j.optDouble("balance", audio?.optDouble("balance", 0.0) ?: 0.0).toFloat().coerceIn(-1f, 1f),
        muted = j.optBoolean("muted", audio?.optBoolean("muted", false) ?: false),
        monitoring = j.optString("monitoring", audio?.optString("monitoring", "MONITOR_AND_OUTPUT") ?: "MONITOR_AND_OUTPUT"),
        syncOffsetMs = j.optInt("syncOffsetMs", audio?.optInt("syncOffsetMs", 0) ?: 0),
        hidden = j.optBoolean("mixerHidden", false),
        locked = j.optBoolean("volumeLocked", false)
    )
}

private fun commit(source: SourceItem, next: MixerUiState, onChange: (SourceItem, String) -> Unit) {
    val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
    val j = root.optJSONObject("settings") ?: root
    j.put("volume", next.volume); j.put("balance", next.balance); j.put("muted", next.muted)
    j.put("monitoring", next.monitoring); j.put("syncOffsetMs", next.syncOffsetMs)
    j.put("mixerHidden", next.hidden); j.put("volumeLocked", next.locked)
    onChange(source, root.toString())
}

/** OBS's cubic fader: the top is 0 dB and most of the travel covers the useful -30…0 dB range. */
private fun faderToVolume(pos: Float) = pos.coerceIn(0f, 1f).pow(3)
private fun volumeToFader(volume: Float) = volume.coerceIn(0f, 1f).pow(1f / 3f)
private fun toDb(volume: Float) = if (volume <= 0.00001f) Float.NEGATIVE_INFINITY else 20f * log10(volume)
private fun dbText(volume: Float): String = toDb(volume).let { if (it.isInfinite()) "-inf dB" else String.format(Locale.US, "%.1f dB", it) }

@Composable
private fun Channel(
    source: SourceItem,
    vertical: Boolean,
    onChange: (SourceItem, String) -> Unit,
    peakProvider: (String) -> Float,
    onFilters: ((SourceItem) -> Unit)?,
    onProperties: ((SourceItem) -> Unit)?,
    onRename: (SourceItem) -> Unit,
    onAdvanced: () -> Unit,
    onUnhideAll: () -> Unit,
    modifier: Modifier
) {
    val state = remember(source.id, source.configJson) { mixerState(source) }
    fun set(next: MixerUiState) = commit(source, next, onChange)
    val level = rememberMeterLevel(peakIdOf(source), peakProvider)
    val global = isGlobal(source)

    val badge: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(14.dp).background(if (global) GlobalBadge else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(if (global) "Global" else "Active", fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = if (global) Color.White else ActiveText)
        }
    }
    val nameMenu: @Composable (Modifier) -> Unit = { m ->
        var open by remember { mutableStateOf(false) }
        Box(m) {
            Row(Modifier.clickable { open = true }.padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Default.ArrowDropDown, "Channel menu", Modifier.size(15.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(if (state.muted) "Unmute" else "Mute") }, onClick = { set(state.copy(muted = !state.muted)); open = false })
                DropdownMenuItem(text = { Text("Hide") }, onClick = { set(state.copy(hidden = true)); open = false })
                DropdownMenuItem(text = { Text("Unhide All") }, onClick = { onUnhideAll(); open = false })
                DropdownMenuItem(text = { Text(if (state.locked) "Unlock Volume" else "Lock Volume") }, onClick = { set(state.copy(locked = !state.locked)); open = false })
                if (!global) DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename(source); open = false })
                HorizontalDivider()
                if (onFilters != null) DropdownMenuItem(text = { Text("Filters") }, onClick = { onFilters(source); open = false })
                if (onProperties != null) DropdownMenuItem(text = { Text("Properties") }, onClick = { onProperties(source); open = false })
                DropdownMenuItem(text = { Text("Advanced Audio Properties") }, onClick = { onAdvanced(); open = false })
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        IconButton(onClick = { set(state.copy(muted = !state.muted)) }, modifier = Modifier.size(28.dp)) {
            Icon(if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (state.muted) "Unmute" else "Mute",
                Modifier.size(17.dp), tint = if (state.muted) MeterRed else MaterialTheme.colorScheme.onSurface)
        }
        val monitoring = state.monitoring == "MONITOR_ONLY" || state.monitoring == "MONITOR_AND_OUTPUT"
        IconButton(onClick = { set(state.copy(monitoring = if (monitoring) "OUTPUT_ONLY" else "MONITOR_AND_OUTPUT")) }, modifier = Modifier.size(28.dp)) {
            Icon(if (monitoring) Icons.Default.Headphones else Icons.Default.HeadsetOff,
                if (monitoring) "Monitoring on (you hear it)" else "Monitoring off", Modifier.size(17.dp),
                tint = if (monitoring) ActiveText else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (vertical) {
        Column(modifier.border(0.5.dp, ObsBorder).padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            badge()
            nameMenu(Modifier.fillMaxWidth())
            Text(dbText(state.volume), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.weight(1f).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Fader(state.volume, state.locked, true, { set(state.copy(volume = it)) }, Modifier.width(18.dp).fillMaxHeight())
                Meter(level, state.muted, true, Modifier.width(34.dp).fillMaxHeight())
            }
            Row { buttons() }
        }
    } else {
        Column(modifier.border(0.5.dp, ObsBorder).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (global) Text("Global ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ActiveText)
                nameMenu(Modifier.weight(1f))
                Text(dbText(state.volume), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Meter(level, state.muted, false, Modifier.fillMaxWidth().height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Fader(state.volume, state.locked, false, { set(state.copy(volume = it)) }, Modifier.weight(1f).height(20.dp))
                buttons()
            }
        }
    }
}

/** Polls the engine's peak and adds OBS-style fall-off plus a peak-hold marker. */
@Composable
private fun rememberMeterLevel(peakId: String, peakProvider: (String) -> Float): Pair<Float, Float> {
    var level by remember(peakId) { mutableFloatStateOf(0f) }
    var hold by remember(peakId) { mutableFloatStateOf(0f) }
    LaunchedEffect(peakId) {
        var holdAge = 0
        while (true) {
            val db = toDb(peakProvider(peakId).coerceIn(0f, 1f)).coerceAtLeast(METER_MIN_DB)
            val norm = (db - METER_MIN_DB) / -METER_MIN_DB
            level = maxOf(norm, level - 0.03f).coerceIn(0f, 1f)
            if (norm >= hold || holdAge > 30) { hold = norm; holdAge = 0 } else holdAge++
            delay(50)
        }
    }
    return level to hold
}

@Composable
private fun Meter(level: Pair<Float, Float>, muted: Boolean, vertical: Boolean, modifier: Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier) {
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val bars = 2
            val gap = 2.dp.toPx()
            val len = if (vertical) size.height else size.width
            val thick = ((if (vertical) size.width else size.height) - gap) / bars
            for (b in 0 until bars) {
                val across = b * (thick + gap)
                zone(vertical, across, thick, len, 0f, 40f / 60f, MeterGreen, level.first, muted)
                zone(vertical, across, thick, len, 40f / 60f, 51f / 60f, MeterYellow, level.first, muted)
                zone(vertical, across, thick, len, 51f / 60f, 1f, MeterRed, level.first, muted)
                val h = level.second * len
                if (h > 1f) {
                    if (vertical) drawRect(Color.White, Offset(across, len - h), Size(thick, 1.5.dp.toPx()))
                    else drawRect(Color.White, Offset(h, across), Size(1.5.dp.toPx(), thick))
                }
            }
        }
        if (vertical) {
            // dB scale beside the meter, 0 at the top.
            BoxWithConstraints(Modifier.width(16.dp).fillMaxHeight()) {
                (0 downTo -60 step 6).forEach { t ->
                    Text("$t", fontSize = 7.sp, lineHeight = 7.sp, color = labelColor,
                        modifier = Modifier.offset(y = maxHeight * (-t / 60f) - 4.dp).padding(start = 1.dp))
                }
            }
        }
    }
}

private fun DrawScope.zone(vertical: Boolean, across: Float, thick: Float, len: Float, from: Float, to: Float, color: Color, level: Float, muted: Boolean) {
    val c = if (muted) Color.Gray else color
    val a = from * len; val b = to * len
    val lit = (level * len).coerceIn(a, b)
    if (vertical) {
        drawRect(c.copy(alpha = 0.25f), Offset(across, len - b), Size(thick, b - a))
        if (lit > a) drawRect(c, Offset(across, len - lit), Size(thick, lit - a))
    } else {
        drawRect(c.copy(alpha = 0.25f), Offset(a, across), Size(b - a, thick))
        if (lit > a) drawRect(c, Offset(a, across), Size(lit - a, thick))
    }
}

/** Drag or tap along the track; double-tap returns to 0 dB. A locked channel ignores touches. */
@Composable
private fun Fader(volume: Float, locked: Boolean, vertical: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    var pos by remember(volume) { mutableFloatStateOf(volumeToFader(volume)) }
    Canvas(
        modifier
            .pointerInput(locked, vertical) {
                if (locked) return@pointerInput
                fun posAt(o: Offset) = if (vertical) 1f - o.y / size.height else o.x / size.width
                detectTapGestures(onDoubleTap = { pos = 1f; onChange(1f) }, onTap = { pos = posAt(it).coerceIn(0f, 1f); onChange(faderToVolume(pos)) })
            }
            .pointerInput(locked, vertical) {
                if (locked) return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    pos = (pos + if (vertical) -drag.y / size.height else drag.x / size.width).coerceIn(0f, 1f)
                    onChange(faderToVolume(pos))
                }
            }
    ) {
        val t = 4.dp.toPx()
        val knob = 14.dp.toPx()
        val knobColor = if (locked) Color.Gray else Color.White
        if (vertical) {
            val cx = size.width / 2
            drawRect(track, Offset(cx - t / 2, 0f), Size(t, size.height))
            val y = (1f - pos) * (size.height - knob)
            drawRect(accent, Offset(cx - t / 2, y + knob / 2), Size(t, size.height - y - knob / 2))
            drawRoundRect(knobColor, Offset(0f, y), Size(size.width, knob), CornerRadius(knob / 2))
        } else {
            val cy = size.height / 2
            drawRect(track, Offset(0f, cy - t / 2), Size(size.width, t))
            val x = pos * (size.width - knob)
            drawRect(accent, Offset(0f, cy - t / 2), Size(x + knob / 2, t))
            drawRoundRect(knobColor, Offset(x, 0f), Size(knob, size.height), CornerRadius(knob / 2))
        }
    }
}

/** OBS's Advanced Audio Properties: every audio source in one table. */
@Composable
private fun AdvancedAudioPropertiesDialog(sources: List<SourceItem>, onChange: (SourceItem, String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 900.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced Audio Properties", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    listOf("Name" to 1.4f, "Volume (dB)" to 1f, "Balance" to 1.6f, "Sync offset (ms)" to 1f, "Monitoring" to 1.4f).forEach { (h, w) ->
                        Text(h, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(w))
                    }
                }
                sources.forEach { src ->
                    val s = remember(src.id, src.configJson) { mixerState(src) }
                    fun set(next: MixerUiState) = commit(src, next, onChange)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(src.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                        NumberCell(toDb(s.volume).let { if (it.isInfinite()) -100f else it }, Modifier.weight(1f)) { db ->
                            set(s.copy(volume = if (db <= -100f) 0f else 10f.pow(db.coerceAtMost(6f) / 20f)))
                        }
                        Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                            Slider(s.balance, { set(s.copy(balance = it)) }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
                            TextButton(onClick = { set(s.copy(balance = 0f)) }, contentPadding = PaddingValues(2.dp)) { Text("C", fontSize = 10.sp) }
                        }
                        NumberCell(s.syncOffsetMs.toFloat(), Modifier.weight(1f)) { set(s.copy(syncOffsetMs = it.toInt().coerceIn(-2000, 2000))) }
                        var open by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1.4f)) {
                            val labels = linkedMapOf("OFF" to "Monitor Off", "MONITOR_ONLY" to "Monitor Only (mute output)", "MONITOR_AND_OUTPUT" to "Monitor and Output", "OUTPUT_ONLY" to "Output only")
                            TextButton(onClick = { open = true }) { Text(labels[s.monitoring] ?: s.monitoring, fontSize = 11.sp, maxLines = 1) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                labels.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { set(s.copy(monitoring = id)); open = false }) }
                            }
                        }
                    }
                }
                Text(
                    "Volume can go up to +6 dB. Sync offset delays a source to line up with video. Monitoring decides whether you hear a source in your headphones; the stream gets it unless it's \"Monitor Only\".",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Commits when you press Done or leave the field, so typing "-12" doesn't apply "-1" first. */
@Composable
private fun NumberCell(value: Float, modifier: Modifier, onCommit: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format(Locale.US, "%.1f", value)) }
    fun apply() { text.toFloatOrNull()?.let { if (it != value) onCommit(it) } }
    OutlinedTextField(
        text, { text = it }, singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { apply() }),
        modifier = modifier.padding(end = 4.dp).heightIn(min = 40.dp).onFocusChanged { if (!it.isFocused) apply() }
    )
}
