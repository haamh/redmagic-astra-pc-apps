package com.stream4k60.app.ui.main.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** OBS's preview scaling choices (right-click the preview → Preview Scaling). */
enum class PreviewScale { WINDOW, CANVAS, OUTPUT }

private const val MAX_PREVIEW_PX = 8192f

/**
 * Shows the canvas at the chosen scale with zoom and pan:
 * - Ctrl + mouse wheel or a two-finger pinch on empty preview space zooms around the pointer;
 * - the mouse wheel (Shift for sideways), middle-button drag or a two-finger drag pans.
 * Gestures that start on a source are left to the source editor, so moving/resizing sources is unchanged.
 */
@Composable
fun PreviewViewport(
    canvasWidth: Int,
    canvasHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val prefs = LocalContext.current.getSharedPreferences("studio_layout", Context.MODE_PRIVATE)
    var mode by remember { mutableStateOf(runCatching { PreviewScale.valueOf(prefs.getString("preview_scale", "WINDOW")!!) }.getOrDefault(PreviewScale.WINDOW)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun setMode(m: PreviewScale) { mode = m; zoom = 1f; pan = Offset.Zero; prefs.edit().putString("preview_scale", m.name).apply() }
    val density = LocalDensity.current
    val cw = canvasWidth.coerceAtLeast(1).toFloat()
    val ch = canvasHeight.coerceAtLeast(1).toFloat()

    BoxWithConstraints(modifier.clipToBounds()) {
        val vw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val vh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fit = minOf(vw / cw, vh / ch)
        val base = when (mode) {
            PreviewScale.WINDOW -> fit
            PreviewScale.CANVAS -> 1f
            PreviewScale.OUTPUT -> outputWidth.coerceAtLeast(1) / cw
        }
        val maxZoom = (MAX_PREVIEW_PX / (maxOf(cw, ch) * base)).coerceAtLeast(0.1f)
        val scale = base * zoom.coerceIn(0.1f, maxZoom)
        val dispW = cw * scale
        val dispH = ch * scale
        // Gesture code runs in a long-lived coroutine, so it reads the latest geometry through this.
        val geo by rememberUpdatedState(Triple(base, maxZoom, Offset(vw, vh)))
        fun clampPan(p: Offset, z: Float = zoom): Offset {
            val (b, _, v) = geo
            // Keep at least 60 px of the canvas on screen.
            val mx = ((cw * b * z + v.x) / 2f - 60f).coerceAtLeast(0f)
            val my = ((ch * b * z + v.y) / 2f - 60f).coerceAtLeast(0f)
            return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
        }
        fun zoomAt(focus: Offset, factor: Float) {
            val (_, mz, v) = geo
            val next = (zoom * factor).coerceIn(0.1f, mz)
            val f = next / zoom
            if (abs(f - 1f) < 0.0001f) return
            val rel = focus - Offset(v.x / 2f, v.y / 2f)
            pan = clampPan(rel - (rel - pan) * f, next)
            zoom = next
        }

        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var pinch: Pair<Float, Offset>? = null
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        if (e.type == PointerEventType.Scroll) {
                            val c = e.changes.first()
                            val d = c.scrollDelta
                            when {
                                e.keyboardModifiers.isCtrlPressed -> zoomAt(c.position, if (d.y < 0) 1.1f else 1f / 1.1f)
                                e.keyboardModifiers.isShiftPressed -> pan = clampPan(pan - Offset(d.y * 60f, 0f))
                                else -> pan = clampPan(pan - Offset(d.x * 60f, d.y * 60f))
                            }
                            c.consume()
                            continue
                        }
                        val pressed = e.changes.filter { it.pressed }
                        if (pressed.any { it.isConsumed }) { pinch = null; continue } // a source is being edited
                        if (e.buttons.isTertiaryPressed && pressed.size == 1) {
                            val c = pressed[0]
                            pan = clampPan(pan + (c.position - c.previousPosition)); c.consume()
                            continue
                        }
                        if (pressed.size >= 2) {
                            val a = pressed[0].position; val b = pressed[1].position
                            val dist = (a - b).getDistance().coerceAtLeast(1f)
                            val mid = (a + b) / 2f
                            pinch?.let { (d0, m0) ->
                                pan = clampPan(pan + (mid - m0))
                                zoomAt(mid, dist / d0)
                            }
                            pinch = dist to mid
                            pressed.forEach { it.consume() }
                        } else pinch = null
                    }
                }
            },
            contentAlignment = Alignment.Center
        ) {
            with(density) {
                content(Modifier.requiredSize(dispW.toDp(), dispH.toDp()).offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) })
            }
        }

        // Scaling menu, bottom-right like OBS's status readout.
        var open by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
            Row(
                Modifier.background(Color(0xCC1E2028), RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val label = when (mode) {
                    PreviewScale.WINDOW -> "Scale to window"
                    PreviewScale.CANVAS -> "Canvas"
                    PreviewScale.OUTPUT -> "Output"
                }
                Text("$label · ${(scale * 100).roundToInt()}%", fontSize = 11.sp, color = Color.White)
                Icon(Icons.Default.ArrowDropDown, "Preview scaling", Modifier.size(16.dp), tint = Color.White)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Scale to window") }, onClick = { setMode(PreviewScale.WINDOW); open = false })
                DropdownMenuItem(text = { Text("Canvas (${canvasWidth}×${canvasHeight})") }, onClick = { setMode(PreviewScale.CANVAS); open = false })
                DropdownMenuItem(text = { Text("Output (scaled) (${outputWidth}×${outputHeight})") }, onClick = { setMode(PreviewScale.OUTPUT); open = false })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Zoom in") }, onClick = { zoomAt(Offset(vw / 2f, vh / 2f), 1.25f); open = false })
                DropdownMenuItem(text = { Text("Zoom out") }, onClick = { zoomAt(Offset(vw / 2f, vh / 2f), 0.8f); open = false })
                DropdownMenuItem(text = { Text("Reset zoom") }, enabled = zoom != 1f || pan != Offset.Zero, onClick = { zoom = 1f; pan = Offset.Zero; open = false })
            }
        }
    }
}
