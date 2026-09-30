package com.stream4k60.app.ui.filters

import com.stream4k60.app.ui.common.ClosableTitle

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.stream4k60.app.engine.AudioFilterChain
import com.stream4k60.app.engine.AudioFilterStage
import com.stream4k60.app.engine.AudioFilterType
import com.stream4k60.app.engine.NativeAudioGraph
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.VideoFilterChain
import com.stream4k60.app.engine.VideoFilterStage
import com.stream4k60.app.engine.VideoFilterType
import com.stream4k60.app.ui.dialogs.ColorPickerDialog
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.settings.components.SettingsSlider
import com.stream4k60.app.ui.settings.components.SettingsToggle
import com.stream4k60.app.ui.util.showImeOnFocus
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/** Order of the "Add filter" menu: everyday grading first, keys last. */
private val VIDEO_ADD_ORDER = listOf(
    VideoFilterType.COLOR_CORRECTION, VideoFilterType.LUT, VideoFilterType.SHARPEN,
    VideoFilterType.LUMA_KEY, VideoFilterType.COLOR_KEY, VideoFilterType.CHROMA_KEY
)

private val VIDEO_DESCRIPTIONS = mapOf(
    VideoFilterType.COLOR_CORRECTION to "Adjust brightness, contrast and color.",
    VideoFilterType.LUT to "Apply a color grade from a .cube or PNG LUT file.",
    VideoFilterType.SHARPEN to "Crisp up soft webcam or capture images.",
    VideoFilterType.LUMA_KEY to "Make very dark or very bright areas transparent.",
    VideoFilterType.COLOR_KEY to "Make one exact color transparent.",
    VideoFilterType.CHROMA_KEY to "Remove a green or blue screen."
)

/**
 * Filters for one source. Every filter shows its one or two everyday controls; the rest of its
 * OBS options sit under "Advanced". Video and audio filters preview live; Cancel restores the saved state.
 */
@Composable
fun FilterEditorScreen(
    source: SourceItem,
    onApply: (String) -> Unit,
    onCancel: () -> Unit
) {
    val supportsVideo = source.type.uppercase() in VideoFilterChain.VIDEO_SOURCE_TYPES
    val supportsAudio = source.type.uppercase() in AudioFilterChain.AUDIO_SOURCE_TYPES
    val mixerInputId = remember(source.id, source.type) { AudioFilterChain.mixerInputId(source.id, source.type) }
    val video = remember(source.id, source.configJson) { VideoFilterChain.read(source.configJson).toMutableStateList() }
    val audio = remember(source.id, source.configJson) { AudioFilterChain.read(source.configJson).toMutableStateList() }
    var tab by remember(source.id) { mutableIntStateOf(if (supportsVideo) 0 else 1) }
    var selectedVideo by remember(source.id) { mutableIntStateOf(if (video.isEmpty()) -1 else 0) }
    var selectedAudio by remember(source.id) { mutableIntStateOf(if (audio.isEmpty()) -1 else 0) }

    if (supportsVideo) LaunchedEffect(source.id) {
        snapshotFlow { video.toList() }.collect { NativeEngine.applySourceFilterChain(source.id, it) }
    }
    if (supportsAudio) LaunchedEffect(source.id) {
        snapshotFlow { audio.toList() }.collect { NativeAudioGraph.previewGate(mixerInputId, AudioFilterChain.noiseGate(it)) }
    }
    val cancel = {
        if (supportsVideo) NativeEngine.setSourceEffectsFromConfig(source.id, source.configJson)
        if (supportsAudio) NativeAudioGraph.previewGate(mixerInputId, AudioFilterChain.noiseGate(source.configJson))
        onCancel()
    }

    AlertDialog(
        onDismissRequest = cancel,
        title = { ClosableTitle("Filters · ${source.name}", cancel) },
        text = {
            Column(Modifier.heightIn(max = 600.dp)) {
                if (supportsVideo && supportsAudio) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Video") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Audio") })
                    }
                    Spacer(Modifier.size(8.dp))
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (tab == 0 && supportsVideo) {
                        Hint("Video filters run on the GPU from top to bottom. Changes show live; Apply saves them.")
                        FilterList(
                            names = video.map { it.name to it.type.label }, enabled = video.map { it.enabled },
                            selected = selectedVideo, onSelect = { selectedVideo = it },
                            onEnabled = { i, on -> video[i] = video[i].copy(enabled = on) },
                            onMove = { from, to -> video.add(to, video.removeAt(from)); selectedVideo = to },
                            onRemove = { i -> video.removeAt(i); selectedVideo = if (video.isEmpty()) -1 else selectedVideo.coerceAtMost(video.lastIndex) }
                        )
                        VideoLimitsNotice(video)
                        AddFilterButton(VIDEO_ADD_ORDER.map { type -> Triple(type.label, VIDEO_DESCRIPTIONS[type].orEmpty(), true) }) { index ->
                            val type = VIDEO_ADD_ORDER[index]
                            video.add(VideoFilterStage(type = type, name = uniqueName(type.label, video.map { it.name })))
                            selectedVideo = video.lastIndex
                        }
                        video.getOrNull(selectedVideo)?.let { stage ->
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            VideoStageSettings(stage) { video[selectedVideo] = it }
                        }
                    } else if (supportsAudio) {
                        Hint("Audio filters run before this source's volume and pan. Changes are heard live; Apply saves them.")
                        FilterList(
                            names = audio.map { it.name to it.type.label }, enabled = audio.map { it.enabled },
                            selected = selectedAudio, onSelect = { selectedAudio = it },
                            onEnabled = { i, on -> audio[i] = audio[i].copy(enabled = on) },
                            onMove = { from, to -> audio.add(to, audio.removeAt(from)); selectedAudio = to },
                            onRemove = { i -> audio.removeAt(i); selectedAudio = if (audio.isEmpty()) -1 else selectedAudio.coerceAtMost(audio.lastIndex) }
                        )
                        val hasGate = audio.any { it.type == AudioFilterType.NOISE_GATE }
                        AddFilterButton(listOf(Triple(AudioFilterType.NOISE_GATE.label,
                            if (hasGate) "This source already has a noise gate." else "Mute background noise between words.", !hasGate))) {
                            audio.add(AudioFilterStage(type = AudioFilterType.NOISE_GATE))
                            selectedAudio = audio.lastIndex
                        }
                        audio.getOrNull(selectedAudio)?.let { stage ->
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            AudioStageSettings(stage, mixerInputId) { audio[selectedAudio] = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var config = source.configJson
                if (supportsVideo) config = VideoFilterChain.write(config, video.toList())
                if (supportsAudio) config = AudioFilterChain.write(config, audio.toList())
                onApply(config)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun VideoLimitsNotice(stages: List<VideoFilterStage>) {
    val enabled = stages.filter { it.enabled }
    val luts = enabled.count { it.type == VideoFilterType.LUT }
    val notes = buildList {
        if (enabled.size > VideoFilterChain.MAX_STAGES) add("Only the first ${VideoFilterChain.MAX_STAGES} enabled filters are rendered.")
        if (luts > VideoFilterChain.MAX_LUT_SLOTS) add("Only the first ${VideoFilterChain.MAX_LUT_SLOTS} LUT filters are rendered.")
    }
    notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun FilterList(
    names: List<Pair<String, String>>,
    enabled: List<Boolean>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onEnabled: (Int, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    if (names.isEmpty()) Text("No filters yet.", style = MaterialTheme.typography.bodyMedium)
    names.forEachIndexed { index, (name, typeLabel) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (index == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable { onSelect(index) }
                .padding(end = 4.dp)
        ) {
            Checkbox(checked = enabled[index], onCheckedChange = { onEnabled(index, it) })
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name != typeLabel) Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move $name up") }
            IconButton(onClick = { onMove(index, index + 1) }, enabled = index < names.lastIndex) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move $name down") }
            IconButton(onClick = { onRemove(index) }) { Icon(Icons.Default.Delete, contentDescription = "Remove $name") }
        }
    }
}

/** [options]: label, one-line description, enabled. */
@Composable
private fun AddFilterButton(options: List<Triple<String, String, Boolean>>, onAdd: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add filter")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, (label, description, enabled) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label)
                            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    enabled = enabled,
                    onClick = { open = false; onAdd(index) }
                )
            }
        }
    }
}

/** Collapsed-by-default section holding a filter's remaining OBS options. */
@Composable
private fun AdvancedSection(content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
        Text("Advanced")
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (expanded) Column(Modifier.padding(start = 8.dp)) { content() }
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onChange(it.take(64)) },
        label = { Text("Filter name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().showImeOnFocus()
    )
}

// ---------------------------------------------------------------- video

@Composable
private fun VideoStageSettings(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    NameField(stage.name) { onChange(stage.copy(name = it)) }
    val slider: @Composable (String, String, ClosedFloatingPointRange<Float>, String, (Float) -> String) -> Unit =
        { label, key, range, description, format -> StageSlider(stage.float(key), label, range, description, format) { onChange(stage.with(key, it.toDouble())) } }
    when (stage.type) {
        VideoFilterType.COLOR_CORRECTION -> {
            slider("Brightness", "brightness", -1f..1f, "Lighter or darker overall. 0 is unchanged.", ::signed)
            slider("Contrast", "contrast", 0f..4f, "Difference between darks and lights. 1.00 is unchanged.", ::number)
            slider("Saturation", "saturation", 0f..4f, "Color intensity. 0 is black and white; 1.00 is unchanged.", ::number)
            AdvancedSection {
                slider("Gamma", "gamma", 0.1f..3f, "Brightens (above 1) or darkens (below 1) midtones while keeping pure black and white.", ::number)
                slider("Hue shift", "hueDegrees", -180f..180f, "Rotates every color around the color wheel. 0° is unchanged.") { "${it.roundToInt()}°" }
                slider("Opacity", "opacity", 0f..1f, "Makes the whole source more see-through.", ::percent)
                ColorField("Color multiply", stage, "colorMultiply", "Tints the image by multiplying with this color. White is unchanged.", onChange)
                ColorField("Color add", stage, "colorAdd", "Adds this color on top, lifting the image toward it. Black is unchanged.", onChange)
            }
        }
        VideoFilterType.LUT -> {
            LutPicker(stage, onChange)
            slider("Strength", "amount", 0f..1f, "How much of the LUT's grade to apply. 100% is the full look.", ::percent)
            AdvancedSection {
                Hint("Supported: 3D .cube files (up to 129³) and PNG LUT images, either square tiles such as 512×512 or N²×N strips. .cube DOMAIN_MIN/MAX are respected. Up to ${VideoFilterChain.MAX_LUT_SLOTS} LUTs render per source.")
            }
        }
        VideoFilterType.SHARPEN -> {
            slider("Sharpness", "sharpness", 0f..1f, "Strengthens edges. Around 0.08 suits most webcams; high values add halos and noise.", ::number)
        }
        VideoFilterType.LUMA_KEY -> {
            slider("Remove darker than", "lumaMin", 0f..1f, "Pixels darker than this become transparent. 0 keeps everything.", ::percent)
            slider("Remove brighter than", "lumaMax", 0f..1f, "Pixels brighter than this become transparent. 100% keeps everything.", ::percent)
            AdvancedSection {
                slider("Dark edge softness", "lumaMinSmooth", 0f..1f, "Fades pixels in gradually above the dark cutoff instead of a hard edge.", ::percent)
                slider("Bright edge softness", "lumaMaxSmooth", 0f..1f, "Fades pixels out gradually below the bright cutoff.", ::percent)
            }
        }
        VideoFilterType.COLOR_KEY -> {
            KeyColor(stage, onChange)
            slider("Similarity", "similarity", 0.001f..1f, "How close a color must be to the key color to disappear.", ::number)
            AdvancedSection {
                slider("Smoothness", "smoothness", 0.001f..1f, "Softens the transparent edge. OBS default 0.05.", ::number)
                KeyAdjustments(stage, onChange)
            }
        }
        VideoFilterType.CHROMA_KEY -> {
            KeyColor(stage, onChange)
            slider("Similarity", "similarity", 0.001f..1f, "How much of the screen color is removed. OBS default 0.40.", ::number)
            AdvancedSection {
                slider("Smoothness", "smoothness", 0.001f..1f, "Softens the key edge. OBS default 0.08.", ::number)
                slider("Spill reduction", "spill", 0.001f..1f, "Removes green or blue reflections on the subject. OBS default 0.10.", ::number)
                KeyAdjustments(stage, onChange)
            }
        }
    }
}

@Composable
private fun KeyAdjustments(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    fun set(key: String) = { v: Float -> onChange(stage.with(key, v.toDouble())) }
    StageSlider(stage.float("opacity"), "Opacity", 0f..1f, "Opacity of what remains after keying.", ::percent, set("opacity"))
    StageSlider(stage.float("contrast"), "Contrast", 0f..4f, "Applied after keying. 1.00 is unchanged.", ::number, set("contrast"))
    StageSlider(stage.float("brightness"), "Brightness", -1f..1f, "Applied after keying. 0 is unchanged.", ::signed, set("brightness"))
    StageSlider(stage.float("gamma"), "Gamma", 0.1f..3f, "Applied after keying. 1.00 is unchanged.", ::number, set("gamma"))
}

@Composable
private fun StageSlider(
    value: Float,
    label: String,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    format: (Float) -> String,
    onChange: (Float) -> Unit
) {
    SettingsSlider(label, value.coerceIn(range), onChange, valueRange = range, displayValue = format(value), description = description)
}

@Composable
private fun LutPicker(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    val context = LocalContext.current
    val path = stage.settings["path"]?.toString().orEmpty()
    val displayName = remember(path) {
        when {
            path.isBlank() -> "No LUT file selected"
            path.startsWith("content://") -> runCatching { DocumentFile.fromSingleUri(context, Uri.parse(path))?.name }.getOrNull() ?: "Selected LUT"
            else -> path.substringAfterLast('/')
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onChange(stage.with("path", uri.toString()))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("LUT file", style = MaterialTheme.typography.labelLarge)
            Text(displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (path.isBlank()) "Choose…" else "Change…") }
    }
}

@Composable
private fun KeyColor(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        listOf("Green" to "#FF00FF00", "Blue" to "#FF0000FF", "Magenta" to "#FFFF00FF").forEach { (label, hex) ->
            OutlinedButton(onClick = { onChange(stage.with("keyColor", hex)) }) { Text(label) }
        }
    }
    ColorField("Key color", stage, "keyColor", "The color to make transparent. Tap the swatch to pick one.", onChange)
}

@Composable
private fun ColorField(label: String, stage: VideoFilterStage, key: String, description: String, onChange: (VideoFilterStage) -> Unit) {
    var text by remember(stage.id, key) { mutableStateOf(VideoFilterChain.colorHex(stage.color(key))) }
    var picking by remember { mutableStateOf(false) }
    val stored = stage.color(key)
    LaunchedEffect(stored) {
        // Follow external changes (presets, picker) without rewriting a partially typed value.
        if (VideoFilterStage.parseColor(text) != stored) text = VideoFilterChain.colorHex(stored)
    }
    val valid = VideoFilterStage.parseColor(text) != null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.take(9)
                VideoFilterStage.parseColor(text)?.let { onChange(stage.with(key, VideoFilterChain.colorHex(it))) }
            },
            label = { Text(label) },
            isError = !valid,
            supportingText = { Text(if (valid) description else "Enter a hex color such as #FF00FF00.") },
            singleLine = true,
            modifier = Modifier.weight(1f).showImeOnFocus()
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(40.dp).background(Color(stored), RoundedCornerShape(6.dp)).clickable { picking = true })
    }
    if (picking) {
        ColorPickerDialog(
            initialColor = stored,
            onDismiss = { picking = false },
            onColorSelected = { color -> onChange(stage.with(key, VideoFilterChain.colorHex(color))); picking = false }
        )
    }
}

// ---------------------------------------------------------------- audio

@Composable
private fun AudioStageSettings(stage: AudioFilterStage, mixerInputId: String, onChange: (AudioFilterStage) -> Unit) {
    NameField(stage.name) { onChange(stage.copy(name = it)) }
    when (stage.type) {
        AudioFilterType.NOISE_GATE -> {
            val gate = AudioFilterChain.gateConfig(stage)
            LevelMeter(mixerInputId, gate.openDb, gate.closeDb)
            StageSlider(stage.float("openDb"), "Start listening at", -70f..0f,
                "Sound louder than this passes through; quieter sound is muted. Speak normally and set it just above the background noise on the meter.",
                ::decibels) { onChange(stage.with("openDb", it.roundToInt().toDouble())) }
            AdvancedSection {
                val follows = stage.bool("closeFollowsOpen")
                SettingsToggle(
                    label = "Stop listening automatically",
                    checked = follows,
                    onCheckedChange = { on -> onChange(stage.with("closeFollowsOpen", on).with("closeDb", (gate.openDb - AudioFilterChain.GATE_HYSTERESIS_DB).toDouble())) },
                    description = "When on, the gate closes ${AudioFilterChain.GATE_HYSTERESIS_DB.roundToInt()} dB below \"Start listening at\" (OBS's Close Threshold), which stops it flickering on and off."
                )
                if (!follows) {
                    StageSlider(stage.float("closeDb"), "Stop listening below", -70f..gate.openDb.coerceAtLeast(-69f),
                        "OBS Close Threshold. Once open, the gate stays open until sound falls below this level. Keep it a few dB under \"Start listening at\".",
                        ::decibels) { onChange(stage.with("closeDb", it.roundToInt().toDouble())) }
                }
                StageSlider(stage.float("attackMs"), "Attack", 0f..500f,
                    "How quickly sound fades in when the gate opens. Very short times can click; OBS default 25 ms.", ::millis
                ) { onChange(stage.with("attackMs", it.roundToInt().toDouble())) }
                StageSlider(stage.float("holdMs"), "Hold", 0f..2000f,
                    "How long the gate stays open after sound drops, so word endings are not cut. OBS default 200 ms.", ::millis
                ) { onChange(stage.with("holdMs", it.roundToInt().toDouble())) }
                StageSlider(stage.float("releaseMs"), "Release", 0f..2000f,
                    "How gradually sound fades out when the gate closes. OBS default 150 ms.", ::millis
                ) { onChange(stage.with("releaseMs", it.roundToInt().toDouble())) }
            }
        }
    }
}

/** Live input level (before the gate) with markers for where the gate opens and closes. */
@Composable
private fun LevelMeter(mixerInputId: String, openDb: Float, closeDb: Float) {
    var levelDb by remember { mutableFloatStateOf(-70f) }
    LaunchedEffect(mixerInputId) {
        while (true) {
            val peak = NativeAudioGraph.peak(mixerInputId)
            levelDb = if (peak <= 0.0000316f) -70f else (20f * log10(peak)).coerceIn(-70f, 0f)
            delay(50)
        }
    }
    fun fraction(db: Float) = ((db + 70f) / 70f).coerceIn(0f, 1f)
    val passing = levelDb >= openDb
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(if (passing) "Input level: ${decibels(levelDb)} — heard" else "Input level: ${decibels(levelDb)} — muted",
            style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction(levelDb))
                .background(if (passing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxHeight().width(2.dp).offset(x = maxWidth * fraction(closeDb)).background(MaterialTheme.colorScheme.tertiary))
            Box(Modifier.fillMaxHeight().width(3.dp).offset(x = maxWidth * fraction(openDb)).background(MaterialTheme.colorScheme.error))
        }
    }
}

// ---------------------------------------------------------------- formatting

private fun uniqueName(base: String, names: List<String>): String {
    if (base !in names) return base
    var n = 2
    while ("$base $n" in names) n++
    return "$base $n"
}

private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)
private fun signed(value: Float): String = String.format(Locale.US, "%+.2f", value)
private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"
private fun decibels(value: Float): String = "${value.roundToInt()} dB"
private fun millis(value: Float): String = "${value.roundToInt()} ms"
