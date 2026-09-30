package com.stream4k60.app.ui.sources

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.main.components.readSourceCrop
import com.stream4k60.app.ui.util.showImeOnFocus
import org.json.JSONObject
import kotlin.math.max

@Composable
fun TransformDialog(
    source: SourceItem,
    canvasWidth: Int,
    canvasHeight: Int,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val config = remember(source.id) { runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject()) }
    val settings = config.optJSONObject("settings") ?: JSONObject()
    val fullCanvas = source.type.uppercase() in setOf("CAMERA", "USB_CAPTURE", "SCREEN_CAPTURE", "MEDIA")
    val inputWidth = max(1.0, settings.optDouble("width", config.optDouble("width", if (fullCanvas) canvasWidth.toDouble() else 1280.0))).toFloat()
    val inputHeight = max(1.0, settings.optDouble("height", config.optDouble("height", if (fullCanvas) canvasHeight.toDouble() else 720.0))).toFloat()
    val initial = remember(source.id) { runCatching { JSONObject(source.transformJson) }.getOrDefault(JSONObject()) }
    val pos = initial.optJSONObject("pos") ?: JSONObject()
    val scale = initial.optJSONObject("scale") ?: JSONObject()
    val bounds = initial.optJSONObject("bounds") ?: JSONObject()
    val inputCrop = remember(source.id) { readSourceCrop(initial, inputWidth, inputHeight) }
    val rawWidth = initial.optDouble("width", inputWidth.toDouble()).toFloat().coerceAtLeast(1f)
    val rawHeight = initial.optDouble("height", inputHeight.toDouble()).toFloat().coerceAtLeast(1f)
    val initialScaleX = initial.optDouble("scaleX", scale.optDouble("x", 1.0)).toFloat().coerceAtLeast(.001f)
    val initialScaleY = initial.optDouble("scaleY", scale.optDouble("y", 1.0)).toFloat().coerceAtLeast(.001f)
    var x by remember(source.id) { mutableStateOf(initial.optDouble("x", initial.optDouble("positionX", pos.optDouble("x", 0.0))).toFloat().toEditString()) }
    var y by remember(source.id) { mutableStateOf(initial.optDouble("y", initial.optDouble("positionY", pos.optDouble("y", 0.0))).toFloat().toEditString()) }
    var cropLeft by remember(source.id) { mutableStateOf(inputCrop.left.toInt().toString()) }
    var cropRight by remember(source.id) { mutableStateOf(inputCrop.right.toInt().toString()) }
    var cropTop by remember(source.id) { mutableStateOf(inputCrop.top.toInt().toString()) }
    var cropBottom by remember(source.id) { mutableStateOf(inputCrop.bottom.toInt().toString()) }
    var width by remember(source.id) { mutableStateOf((rawWidth * inputCrop.widthFraction(inputWidth) * initialScaleX).toEditString()) }
    var height by remember(source.id) { mutableStateOf((rawHeight * inputCrop.heightFraction(inputHeight) * initialScaleY).toEditString()) }
    var rotation by remember(source.id) { mutableStateOf(initial.optDouble("rotation", 0.0).toFloat().toEditString()) }
    var opacity by remember(source.id) { mutableStateOf(initial.optDouble("opacity", 1.0).toFloat().toEditString()) }
    var flipH by remember(source.id) { mutableStateOf(initial.optBoolean("flipH", false)) }
    var flipV by remember(source.id) { mutableStateOf(initial.optBoolean("flipV", false)) }
    var itemAlignment by remember(source.id) { mutableStateOf(initial.optInt("alignment", initial.optInt("align", 5))) }
    var boundsType by remember(source.id) { mutableStateOf(initial.optInt("boundsType", initial.optInt("bounds_type", 0)).takeIf { it in 0..6 } ?: 0) }
    var boundsAlignment by remember(source.id) { mutableStateOf(initial.optInt("boundsAlignment", initial.optInt("bounds_align", initial.optInt("bounds_alignment", 0)))) }
    var boundsWidth by remember(source.id) { mutableStateOf(initial.optDouble("boundsWidth", bounds.optDouble("x", 0.0)).toFloat().toEditString()) }
    var boundsHeight by remember(source.id) { mutableStateOf(initial.optDouble("boundsHeight", bounds.optDouble("y", 0.0)).toFloat().toEditString()) }
    var cropToBounds by remember(source.id) { mutableStateOf(initial.optBoolean("cropToBounds", initial.optBoolean("crop_to_bounds", false))) }
    val cropL = cropLeft.toFloatOrNull() ?: inputCrop.left
    val cropR = cropRight.toFloatOrNull() ?: inputCrop.right
    val cropT = cropTop.toFloatOrNull() ?: inputCrop.top
    val cropB = cropBottom.toFloatOrNull() ?: inputCrop.bottom
    val visibleInputWidth = (inputWidth - cropL - cropR).coerceAtLeast(2f)
    val visibleInputHeight = (inputHeight - cropT - cropB).coerceAtLeast(2f)
    val cropValid = cropL >= 0f && cropR >= 0f && cropT >= 0f && cropB >= 0f &&
        cropL + cropR <= inputWidth - 2f && cropT + cropB <= inputHeight - 2f
    val targetBoundsWidth = boundsWidth.toFloatOrNull() ?: 0f
    val targetBoundsHeight = boundsHeight.toFloatOrNull() ?: 0f
    val boundsValid = boundsType == 0 || (targetBoundsWidth > 0f && targetBoundsHeight > 0f)
    val cropBoundsAvailable = boundsType in setOf(3, 4, 5)

    fun setSizeAndPosition(nextX: Float, nextY: Float, nextWidth: Float, nextHeight: Float) {
        x = nextX.toEditString(); y = nextY.toEditString()
        width = nextWidth.coerceAtLeast(1f).toEditString(); height = nextHeight.coerceAtLeast(1f).toEditString()
    }
    fun updateCrop(nextLeft: String = cropLeft, nextRight: String = cropRight, nextTop: String = cropTop, nextBottom: String = cropBottom) {
        val oldLeft = cropLeft.toFloatOrNull() ?: 0f
        val oldRight = cropRight.toFloatOrNull() ?: 0f
        val oldTop = cropTop.toFloatOrNull() ?: 0f
        val oldBottom = cropBottom.toFloatOrNull() ?: 0f
        val nextLeftValue = nextLeft.toFloatOrNull() ?: 0f
        val nextRightValue = nextRight.toFloatOrNull() ?: 0f
        val nextTopValue = nextTop.toFloatOrNull() ?: 0f
        val nextBottomValue = nextBottom.toFloatOrNull() ?: 0f
        val oldWidthRatio = ((inputWidth - oldLeft - oldRight) / inputWidth).coerceAtLeast(2f / inputWidth)
        val oldHeightRatio = ((inputHeight - oldTop - oldBottom) / inputHeight).coerceAtLeast(2f / inputHeight)
        val currentScaleX = (width.toFloatOrNull() ?: rawWidth * oldWidthRatio * initialScaleX) / (rawWidth * oldWidthRatio).coerceAtLeast(1f)
        val currentScaleY = (height.toFloatOrNull() ?: rawHeight * oldHeightRatio * initialScaleY) / (rawHeight * oldHeightRatio).coerceAtLeast(1f)
        cropLeft = nextLeft; cropRight = nextRight; cropTop = nextTop; cropBottom = nextBottom
        val newWidthRatio = ((inputWidth - nextLeftValue - nextRightValue) / inputWidth).coerceAtLeast(2f / inputWidth)
        val newHeightRatio = ((inputHeight - nextTopValue - nextBottomValue) / inputHeight).coerceAtLeast(2f / inputHeight)
        width = (rawWidth * newWidthRatio * currentScaleX).toEditString()
        height = (rawHeight * newHeightRatio * currentScaleY).toEditString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle("Transform · ${source.name}", onDismiss) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Position uses the selected anchor. Coordinates and bounds use output pixels; crop uses source pixels.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransformChoice("Item anchor", alignmentOptions, itemAlignment, { itemAlignment = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransformNumberField("Position X", x, { x = it }, "Canvas px", Modifier.weight(1f))
                    TransformNumberField("Position Y", y, { y = it }, "Canvas px", Modifier.weight(1f))
                }
                TransformChoice("Bounds mode", boundsOptions, boundsType, {
                    boundsType = it
                    if (it !in setOf(3, 4, 5)) cropToBounds = false
                }, Modifier.fillMaxWidth())
                if (boundsType != 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransformNumberField("Bounds width", boundsWidth, { boundsWidth = it }, "Output px · example 1920", Modifier.weight(1f))
                        TransformNumberField("Bounds height", boundsHeight, { boundsHeight = it }, "Output px · example 1080", Modifier.weight(1f))
                    }
                    TransformChoice("Bounds alignment", alignmentOptions, boundsAlignment, { boundsAlignment = it }, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cropToBounds, enabled = cropBoundsAvailable, onCheckedChange = { cropToBounds = it })
                        Text("Crop overflow to bounds", fontSize = 12.sp)
                    }
                    Text("Bounds resize the source to a target box. Crop overflow is available for Scale outer/width/height.", fontSize = 11.sp)
                    Text(when (boundsType) {
                        1 -> "Stretch fills the target and can distort aspect ratio."
                        2 -> "Scale inner preserves aspect ratio and keeps the whole image inside the target."
                        3 -> "Scale outer preserves aspect ratio and may extend past the target."
                        4 -> "Scale to width matches the target width; height may overflow."
                        5 -> "Scale to height matches the target height; width may overflow."
                        else -> "Max only keeps the current size unless the source exceeds the target."
                    }, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransformIntegerField("Crop Left", cropLeft, { updateCrop(nextLeft = it) }, "Input px", Modifier.weight(1f))
                    TransformIntegerField("Crop Right", cropRight, { updateCrop(nextRight = it) }, "Input px", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransformIntegerField("Crop Top", cropTop, { updateCrop(nextTop = it) }, "Input px", Modifier.weight(1f))
                    TransformIntegerField("Crop Bottom", cropBottom, { updateCrop(nextBottom = it) }, "Input px", Modifier.weight(1f))
                }
                Text("Crop is measured in source pixels and reduces the visible bounds. Leave at least 2 input pixels on each axis.", fontSize = 11.sp)
                if (boundsType == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransformNumberField("Width", width, { width = it }, "Visible output px", Modifier.weight(1f))
                        TransformNumberField("Height", height, { height = it }, "Visible output px", Modifier.weight(1f))
                    }
                    Text("Size changes scale; it does not change the camera or media input resolution.", fontSize = 11.sp)
                } else Text("The selected bounds mode determines visible size.", fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransformNumberField("Rotation", rotation, { rotation = it }, "Degrees · 0–360", Modifier.weight(1f))
                    TransformNumberField("Opacity", opacity, { opacity = it }, "0 = hidden · 1 = solid", Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = flipH, onCheckedChange = { flipH = it })
                    Text("Flip horizontal", fontSize = 12.sp)
                    Checkbox(checked = flipV, onCheckedChange = { flipV = it })
                    Text("Flip vertical", fontSize = 12.sp)
                }
                if (!cropValid) Text("Crop edges overlap or remove the source. Reduce the values before applying.", color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val w = width.toFloatOrNull()?.takeIf { it > 0f } ?: rawWidth * inputCrop.widthFraction(inputWidth)
                        val h = height.toFloatOrNull()?.takeIf { it > 0f } ?: rawHeight * inputCrop.heightFraction(inputHeight)
                        val boxW = if (boundsType != 0) targetBoundsWidth else w
                        val boxH = if (boundsType != 0) targetBoundsHeight else h
                        val anchorX = alignmentFactor(itemAlignment, horizontal = true)
                        val anchorY = alignmentFactor(itemAlignment, horizontal = false)
                        x = ((canvasWidth - boxW) / 2f + boxW * anchorX).toEditString()
                        y = ((canvasHeight - boxH) / 2f + boxH * anchorY).toEditString()
                    }) { Text("Center") }
                    OutlinedButton(onClick = {
                        boundsType = 2
                        boundsWidth = canvasWidth.toString(); boundsHeight = canvasHeight.toString()
                        boundsAlignment = 0; cropToBounds = false
                        x = (canvasWidth * alignmentFactor(itemAlignment, horizontal = true)).toEditString()
                        y = (canvasHeight * alignmentFactor(itemAlignment, horizontal = false)).toEditString()
                    }) { Text("Fit") }
                    OutlinedButton(onClick = {
                        boundsType = 1
                        boundsWidth = canvasWidth.toString(); boundsHeight = canvasHeight.toString()
                        boundsAlignment = 0; cropToBounds = false
                        x = (canvasWidth * alignmentFactor(itemAlignment, horizontal = true)).toEditString()
                        y = (canvasHeight * alignmentFactor(itemAlignment, horizontal = false)).toEditString()
                    }) { Text("Stretch") }
                }
                Text("Fit preserves the source aspect ratio. Stretch fills the canvas and may distort the image.", fontSize = 11.sp)
                TextButton(onClick = {
                    boundsType = 0; boundsWidth = "0"; boundsHeight = "0"; boundsAlignment = 0; cropToBounds = false; itemAlignment = 5
                    cropLeft = "0"; cropRight = "0"; cropTop = "0"; cropBottom = "0"
                    setSizeAndPosition(0f, 0f, rawWidth, rawHeight)
                    rotation = "0"; opacity = "1"; flipH = false; flipV = false
                }) { Text("Reset transform") }
            }
        },
        confirmButton = {
            Button(enabled = cropValid && boundsValid, onClick = {
                val posX = x.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f
                val posY = y.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f
                val w = width.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: inputWidth
                val h = height.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: inputHeight
                val angle = rotation.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f
                val alpha = opacity.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
                val appliedScaleX = if (boundsType == 0) w / (rawWidth * (visibleInputWidth / inputWidth)).coerceAtLeast(1f) else initialScaleX
                val appliedScaleY = if (boundsType == 0) h / (rawHeight * (visibleInputHeight / inputHeight)).coerceAtLeast(1f) else initialScaleY
                val out = JSONObject(initial.toString())
                    .put("x", posX).put("y", posY)
                    .put("width", rawWidth)
                    .put("height", rawHeight)
                    .put("scaleX", appliedScaleX)
                    .put("scaleY", appliedScaleY)
                    .put("cropLeft", cropL).put("cropRight", cropR)
                    .put("cropTop", cropT).put("cropBottom", cropB)
                    .put("rotation", angle).put("opacity", alpha)
                    .put("alignment", itemAlignment)
                    .put("boundsType", boundsType).put("boundsAlignment", boundsAlignment)
                    .put("boundsWidth", targetBoundsWidth).put("boundsHeight", targetBoundsHeight)
                    .put("cropToBounds", cropToBounds)
                    .put("flipH", flipH).put("flipV", flipV)
                onApply(out.toString())
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TransformIntegerField(label: String, value: String, onValueChange: (String) -> Unit, example: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.length <= 8 && candidate.all(Char::isDigit)) onValueChange(candidate) },
        modifier = modifier.showImeOnFocus(),
        label = { Text(label, fontSize = 12.sp) },
        supportingText = { Text(example, fontSize = 9.sp, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun TransformNumberField(label: String, value: String, onValueChange: (String) -> Unit, example: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.length <= 14 && candidate.matches(Regex("-?\\d*(\\.\\d*)?"))) onValueChange(candidate) },
        modifier = modifier.showImeOnFocus(),
        label = { Text(label, fontSize = 12.sp) },
        supportingText = { Text(example, fontSize = 9.sp, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun Float.toEditString(): String = if (isFinite()) toString().removeSuffix(".0") else "0"

private val boundsOptions = listOf(
    "No bounds" to 0,
    "Stretch" to 1,
    "Scale inner" to 2,
    "Scale outer" to 3,
    "Scale to width" to 4,
    "Scale to height" to 5,
    "Max only" to 6
)

private val alignmentOptions = listOf(
    "Top left" to 5, "Top center" to 4, "Top right" to 6,
    "Center left" to 1, "Center" to 0, "Center right" to 2,
    "Bottom left" to 9, "Bottom center" to 8, "Bottom right" to 10
)

@Composable
private fun TransformChoice(label: String, options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(label) { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.second == selected }?.first ?: "Unknown ($selected)"
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selectedName", maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

private fun alignmentFactor(alignment: Int, horizontal: Boolean): Float {
    val start = if (horizontal) 1 else 4
    val end = if (horizontal) 2 else 8
    return when {
        alignment and end != 0 -> 1f
        alignment and start != 0 -> 0f
        else -> .5f
    }
}
