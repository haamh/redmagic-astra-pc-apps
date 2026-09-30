package com.stream4k60.app.ui.main.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.theme.ObsBorder

/**
 * Dock sizes the user dragged, remembered across launches. A value of 0 means "use the default for this screen".
 * Drag the thin bars between docks to resize; double-tap a bar to put that split back to its default.
 */
@Stable
class DockSizes(private val context: Context) {
    private val prefs = context.getSharedPreferences("studio_layout", Context.MODE_PRIVATE)
    var leftWidth by mutableFloatStateOf(prefs.getFloat("left", 0f))
    var scenesFraction by mutableFloatStateOf(prefs.getFloat("scenes", 0.5f))
    var bottomHeight by mutableFloatStateOf(prefs.getFloat("bottom", 0f))
    var transitionsWidth by mutableFloatStateOf(prefs.getFloat("transitions", 0f))
    var controlsWidth by mutableFloatStateOf(prefs.getFloat("controls", 0f))

    fun save() {
        prefs.edit().putFloat("left", leftWidth).putFloat("scenes", scenesFraction).putFloat("bottom", bottomHeight)
            .putFloat("transitions", transitionsWidth).putFloat("controls", controlsWidth).apply()
    }

    fun reset() { leftWidth = 0f; scenesFraction = 0.5f; bottomHeight = 0f; transitionsWidth = 0f; controlsWidth = 0f; save() }
}

@Composable
fun rememberDockSizes(): DockSizes {
    val ctx = LocalContext.current.applicationContext
    return remember { DockSizes(ctx) }
}

internal fun Float.orDefault(default: Dp): Dp = if (this > 0f) this.dp else default

/**
 * A bar between two docks. [vertical] bars sit between side-by-side docks and drag horizontally.
 * [onDrag] gets the movement in dp.
 */
@Composable
fun DockSplitter(vertical: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onReset: () -> Unit) {
    val density = LocalDensity.current
    val size = 6.dp
    Box(
        (if (vertical) Modifier.width(size).fillMaxHeight() else Modifier.height(size).fillMaxWidth())
            .background(ObsBorder.copy(alpha = 0.35f))
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(vertical) {
                detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                    change.consume()
                    onDrag(with(density) { (if (vertical) drag.x else drag.y).toDp().value })
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
    )
}
