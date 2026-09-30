package com.stream4k60.app.ui.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.VideoFilterChain
import com.stream4k60.app.engine.VideoFilterStage
import com.stream4k60.app.engine.VideoFilterType
import com.stream4k60.app.ui.dialogs.ColorPickerDialog
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.settings.components.SettingsSlider
import com.stream4k60.app.ui.util.showImeOnFocus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ordered video filter chain editor. Filters apply top to bottom, like the OBS filter list.
 * Edits preview live in the compositor; Cancel restores the saved chain.
 */
@Composable
fun FilterEditorScreen(
    source: SourceItem,
    onApply: (String) -> Unit,
    onCancel: () -> Unit
) {
    val stages = remember(source.id, source.configJson) { VideoFilterChain.read(source.configJson).toMutableStateList() }
    var selected by remember(source.id) { mutableIntStateOf(if (stages.isEmpty()) -1 else 0) }
    var addMenu by remember { mutableStateOf(false) }

    LaunchedEffect(source.id) {
        // Live preview: re-send the chain whenever any stage changes.
        snapshotFlow { stages.toList() }.collect { NativeEngine.applySourceFilterChain(source.id, it) }
    }
    val cancel = {
        NativeEngine.setSourceEffectsFromConfig(source.id, source.configJson)
        onCancel()
    }
    val enabledCount = stages.count { it.enabled }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Video filters · ${source.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Filters run on the GPU from top to bottom. Changes preview live; Apply saves them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                if (stages.isEmpty()) {
                    Text("No filters on this source.", style = MaterialTheme.typography.bodyMedium)
                }
                stages.forEachIndexed { index, stage ->
                    FilterRow(
                        stage = stage,
                        selected = index == selected,
                        canMoveUp = index > 0,
                        canMoveDown = index < stages.lastIndex,
                        onSelect = { selected = index },
                        onEnabledChange = { stages[index] = stage.copy(enabled = it) },
                        onMoveUp = { stages.add(index - 1, stages.removeAt(index)); selected = index - 1 },
                        onMoveDown = { stages.add(index + 1, stages.removeAt(index)); selected = index + 1 },
                        onRemove = {
                            stages.removeAt(index)
                            selected = if (stages.isEmpty()) -1 else selected.coerceAtMost(stages.lastIndex)
                        }
                    )
                }
                if (enabledCount > VideoFilterChain.MAX_STAGES) {
                    Text(
                        "Only the first ${VideoFilterChain.MAX_STAGES} enabled filters are rendered. Disable or remove ${enabledCount - VideoFilterChain.MAX_STAGES}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Box {
                    OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add filter")
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        VideoFilterType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(type.label) }, onClick = {
                                stages.add(VideoFilterStage(type = type, name = uniqueName(type.label, stages)))
                                selected = stages.lastIndex
                                addMenu = false
                            })
                        }
                    }
                }
                stages.getOrNull(selected)?.let { stage ->
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    StageSettings(stage) { updated -> stages[selected] = updated }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(VideoFilterChain.write(source.configJson, stages.toList())) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

@Composable
private fun FilterRow(
    stage: VideoFilterStage,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(end = 4.dp)
    ) {
        Checkbox(checked = stage.enabled, onCheckedChange = onEnabledChange)
        Column(Modifier.weight(1f)) {
            Text(stage.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stage.name != stage.type.label) {
                Text(stage.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${stage.name} up") }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${stage.name} down") }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove ${stage.name}") }
    }
}

@Composable
private fun StageSettings(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    OutlinedTextField(
        value = stage.name,
        onValueChange = { onChange(stage.copy(name = it.take(64))) },
        label = { Text("Filter name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().showImeOnFocus()
    )
    when (stage.type) {
        VideoFilterType.COLOR_CORRECTION -> {
            StageSlider(stage, onChange, "Gamma", "gamma", 0.1f..3f, "Midtone brightness. 1.00 is unchanged; above 1 brightens midtones.")
            StageSlider(stage, onChange, "Contrast", "contrast", 0f..4f, "Scales the distance from mid-gray. 1.00 is unchanged.")
            StageSlider(stage, onChange, "Brightness", "brightness", -1f..1f, "Adds a constant lift. 0.00 is unchanged; large values clip detail.")
            StageSlider(stage, onChange, "Saturation", "saturation", 0f..4f, "Color intensity. 0.00 is monochrome; 1.00 is unchanged.")
            StageSlider(stage, onChange, "Hue shift", "hueDegrees", -180f..180f, "Rotates colors around the hue wheel. 0° is unchanged.") { "${it.roundToInt()}°" }
            StageSlider(stage, onChange, "Opacity", "opacity", 0f..1f, "Multiplies the source's alpha at this point in the chain.") { "${(it * 100).roundToInt()}%" }
            ColorField("Color multiply", stage, "colorMultiply", onChange)
            ColorField("Color add", stage, "colorAdd", onChange)
        }
        VideoFilterType.CHROMA_KEY -> {
            KeyColorPresets(stage, onChange)
            StageSlider(stage, onChange, "Similarity", "similarity", 0.001f..1f, "How much of the key color's chroma is removed. OBS default 0.40.")
            StageSlider(stage, onChange, "Smoothness", "smoothness", 0.001f..1f, "Softens the key edge. OBS default 0.08.")
            StageSlider(stage, onChange, "Spill reduction", "spill", 0.001f..1f, "Desaturates key-colored spill on the subject. OBS default 0.10.")
            KeyAdjustSliders(stage, onChange)
        }
        VideoFilterType.COLOR_KEY -> {
            KeyColorPresets(stage, onChange)
            StageSlider(stage, onChange, "Similarity", "similarity", 0.001f..1f, "RGB distance treated as fully transparent. OBS default 0.08.")
            StageSlider(stage, onChange, "Smoothness", "smoothness", 0.001f..1f, "Width of the soft edge beyond the similarity range. OBS default 0.05.")
            KeyAdjustSliders(stage, onChange)
        }
        VideoFilterType.LUMA_KEY -> {
            StageSlider(stage, onChange, "Luma min", "lumaMin", 0f..1f, "Pixels darker than this become transparent.")
            StageSlider(stage, onChange, "Luma min smoothness", "lumaMinSmooth", 0f..1f, "Soft edge above the minimum.")
            StageSlider(stage, onChange, "Luma max", "lumaMax", 0f..1f, "Pixels brighter than this become transparent.")
            StageSlider(stage, onChange, "Luma max smoothness", "lumaMaxSmooth", 0f..1f, "Soft edge below the maximum.")
        }
    }
}

@Composable
private fun StageSlider(
    stage: VideoFilterStage,
    onChange: (VideoFilterStage) -> Unit,
    label: String,
    key: String,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    format: (Float) -> String = ::number
) {
    val value = stage.float(key)
    SettingsSlider(label, value.coerceIn(range), { onChange(stage.with(key, it.toDouble())) }, valueRange = range, displayValue = format(value), description = description)
}

@Composable
private fun KeyAdjustSliders(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    StageSlider(stage, onChange, "Opacity", "opacity", 0f..1f, "Opacity of the keyed result.") { "${(it * 100).roundToInt()}%" }
    StageSlider(stage, onChange, "Contrast", "contrast", 0f..4f, "Applied after keying. 1.00 is unchanged.")
    StageSlider(stage, onChange, "Brightness", "brightness", -1f..1f, "Applied after keying. 0.00 is unchanged.")
    StageSlider(stage, onChange, "Gamma", "gamma", 0.1f..3f, "Applied after keying. 1.00 is unchanged.")
}

@Composable
private fun KeyColorPresets(stage: VideoFilterStage, onChange: (VideoFilterStage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        listOf("Green" to "#FF00FF00", "Blue" to "#FF0000FF", "Magenta" to "#FFFF00FF").forEach { (label, hex) ->
            OutlinedButton(onClick = { onChange(stage.with("keyColor", hex)) }) { Text(label) }
        }
    }
    ColorField("Key color", stage, "keyColor", onChange)
}

@Composable
private fun ColorField(label: String, stage: VideoFilterStage, key: String, onChange: (VideoFilterStage) -> Unit) {
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
            supportingText = { if (!valid) Text("Enter a hex color such as #FF00FF00.") },
            singleLine = true,
            modifier = Modifier.weight(1f).showImeOnFocus()
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .background(Color(stage.color(key)), RoundedCornerShape(6.dp))
                .clickable { picking = true }
        )
    }
    if (picking) {
        ColorPickerDialog(
            initialColor = stage.color(key),
            onDismiss = { picking = false },
            onColorSelected = { color -> onChange(stage.with(key, VideoFilterChain.colorHex(color))); picking = false }
        )
    }
}

private fun uniqueName(base: String, stages: List<VideoFilterStage>): String {
    if (stages.none { it.name == base }) return base
    var n = 2
    while (stages.any { it.name == "$base $n" }) n++
    return "$base $n"
}

private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)
