package com.stream4k60.app.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.util.showImeOnFocus
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit
) {
    var selectedColor by remember(initialColor) { mutableIntStateOf(initialColor) }
    var hexValue by remember(initialColor) { mutableStateOf(formatColor(initialColor)) }
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(selectedColor, it) }
    val alpha = android.graphics.Color.alpha(selectedColor) / 255f

    fun setHsv(hue: Float = hsv[0], saturation: Float = hsv[1], value: Float = hsv[2], opacity: Float = alpha) {
        val next = android.graphics.Color.HSVToColor(
            (opacity.coerceIn(0f, 1f) * 255).roundToInt(),
            floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        )
        selectedColor = next
        hexValue = formatColor(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Choose a fill color and transparency. Example: #FF1E88E5 is opaque blue; #801E88E5 is translucent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = ComposeColor(selectedColor),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.small
                ) {}
                OutlinedTextField(
                    value = hexValue,
                    onValueChange = { typed ->
                        hexValue = typed.take(9)
                        runCatching { android.graphics.Color.parseColor(hexValue) }
                            .getOrNull()?.let { selectedColor = it }
                    },
                    label = { Text("Hex color") },
                    supportingText = { Text("Use #RRGGBB or #AARRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().showImeOnFocus()
                )
                Text("Hue · ${hsv[0].roundToInt()}°", style = MaterialTheme.typography.labelMedium)
                Slider(value = hsv[0], onValueChange = { setHsv(hue = it) }, valueRange = 0f..360f, steps = 35)
                Text("Saturation · ${(hsv[1] * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = hsv[1], onValueChange = { setHsv(saturation = it) }, valueRange = 0f..1f, steps = 19)
                Text("Brightness · ${(hsv[2] * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = hsv[2], onValueChange = { setHsv(value = it) }, valueRange = 0f..1f, steps = 19)
                Text("Opacity · ${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = alpha, onValueChange = { setHsv(opacity = it) }, valueRange = 0f..1f, steps = 19)
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(selectedColor) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatColor(color: Int): String = String.format(Locale.US, "#%08X", color)
