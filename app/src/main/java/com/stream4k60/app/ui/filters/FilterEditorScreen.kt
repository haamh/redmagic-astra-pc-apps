package com.stream4k60.app.ui.filters

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.settings.components.SettingsSlider
import com.stream4k60.app.ui.settings.components.SettingsToggle
import com.stream4k60.app.ui.util.showImeOnFocus
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private data class VideoEffects(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val gamma: Float,
    val hue: Float,
    val chromaKeyEnabled: Boolean,
    val chromaKeyColor: String,
    val chromaSimilarity: Float,
    val chromaSmoothness: Float
)

@Composable
fun FilterEditorScreen(
    source: SourceItem,
    onApply: (String) -> Unit,
    onCancel: () -> Unit
) {
    val initial = remember(source.id, source.configJson) { readEffects(source.configJson) }
    var brightness by remember(initial) { mutableFloatStateOf(initial.brightness) }
    var contrast by remember(initial) { mutableFloatStateOf(initial.contrast) }
    var saturation by remember(initial) { mutableFloatStateOf(initial.saturation) }
    var gamma by remember(initial) { mutableFloatStateOf(initial.gamma) }
    var hue by remember(initial) { mutableFloatStateOf(initial.hue) }
    var chromaKeyEnabled by remember(initial) { mutableStateOf(initial.chromaKeyEnabled) }
    var chromaKeyColor by remember(initial) { mutableStateOf(initial.chromaKeyColor) }
    var chromaSimilarity by remember(initial) { mutableFloatStateOf(initial.chromaSimilarity) }
    var chromaSmoothness by remember(initial) { mutableFloatStateOf(initial.chromaSmoothness) }
    var colorError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Video filters · ${source.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "GPU color correction and chroma key are applied to this source in the live compositor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSlider(
                    "Brightness", brightness, { brightness = it }, valueRange = -1f..1f,
                    displayValue = "${number(brightness)}",
                    description = "Offsets image lightness. Example: 0.10 adds a small lift; large values clip detail."
                )
                SettingsSlider(
                    "Contrast", contrast, { contrast = it }, valueRange = 0f..2f,
                    displayValue = number(contrast),
                    description = "Scales the difference from mid-gray. Example: 1.00 leaves contrast unchanged."
                )
                SettingsSlider(
                    "Saturation", saturation, { saturation = it }, valueRange = 0f..2f,
                    displayValue = number(saturation),
                    description = "Adjusts color intensity. Example: 0.00 is monochrome; 1.00 is unchanged."
                )
                SettingsSlider(
                    "Gamma", gamma, { gamma = it }, valueRange = 0.1f..3f,
                    displayValue = number(gamma),
                    description = "Adjusts midtone brightness. Example: 1.00 is unchanged; extreme values lose detail."
                )
                SettingsSlider(
                    "Hue", hue, { hue = it }, valueRange = -180f..180f,
                    displayValue = "${hue.roundToInt()}°",
                    description = "Rotates source colors around the hue wheel. Example: 0° is unchanged."
                )
                SettingsToggle(
                    label = "Chroma key",
                    checked = chromaKeyEnabled,
                    onCheckedChange = { chromaKeyEnabled = it },
                    description = "Makes pixels close to the key color transparent. Light the background evenly for cleaner edges."
                )
                OutlinedTextField(
                    value = chromaKeyColor,
                    onValueChange = { chromaKeyColor = it.take(9); colorError = false },
                    label = { Text("Key color") },
                    supportingText = {
                        Text(if (colorError) "Enter a valid hex color, for example #FF00FF00." else "Example: #FF00FF00 for green.")
                    },
                    isError = colorError,
                    singleLine = true,
                    enabled = chromaKeyEnabled,
                    modifier = Modifier.showImeOnFocus()
                )
                SettingsSlider(
                    "Similarity", chromaSimilarity, { chromaSimilarity = it }, valueRange = 0f..1f,
                    displayValue = number(chromaSimilarity), enabled = chromaKeyEnabled,
                    description = "Sets how much of the key color is removed. Example: 0.35; too high removes similar subject colors."
                )
                SettingsSlider(
                    "Smoothness", chromaSmoothness, { chromaSmoothness = it }, valueRange = 0.001f..0.5f,
                    displayValue = number(chromaSmoothness), enabled = chromaKeyEnabled,
                    description = "Softens the key edge. Example: 0.08; excessive softness creates halos."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedColor = runCatching { android.graphics.Color.parseColor(chromaKeyColor) }.getOrNull()
                if (chromaKeyEnabled && parsedColor == null) {
                    colorError = true
                } else {
                    val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
                    val settings = root.optJSONObject("settings")
                    val effects = JSONObject()
                        .put("brightness", brightness)
                        .put("contrast", contrast)
                        .put("saturation", saturation)
                        .put("gamma", gamma)
                        .put("hueDegrees", hue)
                        .put("chromaKeyEnabled", chromaKeyEnabled)
                        .put("chromaKeyColor", parsedColor?.let { String.format(Locale.US, "#%08X", it) } ?: chromaKeyColor)
                        .put("chromaSimilarity", chromaSimilarity)
                        .put("chromaSmoothness", chromaSmoothness)
                    if (settings != null) {
                        settings.put("effects", effects)
                        root.put("settings", settings)
                    } else {
                        root.put("effects", effects)
                    }
                    onApply(root.toString())
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private fun readEffects(configJson: String): VideoEffects {
    val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
    val settings = root.optJSONObject("settings") ?: root
    val effects = settings.optJSONObject("effects") ?: root.optJSONObject("effects") ?: JSONObject()
    val color = effects.optString("chromaKeyColor", "#FF00FF00")
    return VideoEffects(
        brightness = effects.optDouble("brightness", 0.0).toFloat().coerceIn(-1f, 1f),
        contrast = effects.optDouble("contrast", 1.0).toFloat().coerceIn(0f, 2f),
        saturation = effects.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
        gamma = effects.optDouble("gamma", 1.0).toFloat().coerceIn(0.1f, 3f),
        hue = effects.optDouble("hueDegrees", 0.0).toFloat().coerceIn(-180f, 180f),
        chromaKeyEnabled = effects.optBoolean("chromaKeyEnabled", false),
        chromaKeyColor = color,
        chromaSimilarity = effects.optDouble("chromaSimilarity", 0.35).toFloat().coerceIn(0f, 1f),
        chromaSmoothness = effects.optDouble("chromaSmoothness", 0.08).toFloat().coerceIn(0.001f, 0.5f)
    )
}

private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)
