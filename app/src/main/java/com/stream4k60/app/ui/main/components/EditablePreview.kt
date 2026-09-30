package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val visualSourceTypes = setOf(
    "CAMERA", "USB_CAPTURE", "SCREEN_CAPTURE", "MEDIA", "BROWSER", "IMAGE", "IMAGE_SLIDESHOW", "TEXT", "COLOR", "SCENE", "GROUP"
)

private data class SourceRect(
    val x: Float,
    val y: Float,
    val pivotX: Float,
    val pivotY: Float,
    val positionX: Float,
    val positionY: Float,
    val width: Float,
    val height: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotation: Float,
    val canResize: Boolean
)

private data class SnapGuides(val x: Float? = null, val y: Float? = null)
private data class SnapResult(val x: Float, val y: Float, val guides: SnapGuides)

private fun sourceRect(source: SourceItem, canvasWidth: Int, canvasHeight: Int): SourceRect {
    val geometry = resolveSourceTransform(source, canvasWidth, canvasHeight)
    return SourceRect(
        geometry.x, geometry.y, geometry.pivotX, geometry.pivotY, geometry.positionX, geometry.positionY,
        geometry.width, geometry.height, geometry.scaleX, geometry.scaleY,
        geometry.rotation, geometry.canResize
    )
}

/** Applies a source's persisted transform to the live native compositor layer. */
fun applySourceTransformToNative(source: SourceItem, zOrder: Int, canvasWidth: Int, canvasHeight: Int) {
    val geometry = resolveSourceTransform(source, canvasWidth, canvasHeight)
    NativeEngine.setSourceEffectsFromConfig(source.id, source.configJson)
    NativeEngine.setSourceTextureParameters(
        source.id,
        geometry.x, geometry.y, geometry.width, geometry.height, geometry.pivotX, geometry.pivotY,
        geometry.rotation, geometry.scaleX, geometry.scaleY, geometry.opacity,
        geometry.crop.left / geometry.sourceWidth,
        geometry.crop.top / geometry.sourceHeight,
        geometry.crop.right / geometry.sourceWidth,
        geometry.crop.bottom / geometry.sourceHeight,
        source.isVisible,
        zOrder,
        geometry.flipH, geometry.flipV
    )
}

@Composable
fun EditablePreview(
    sources: List<SourceItem>,
    selectedSourceId: String?,
    canvasWidth: Int,
    canvasHeight: Int,
    onSelectSource: (String?) -> Unit,
    onCommitTransform: (String, String) -> Unit,
    onMoveSource: (String, Int) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // Re-layout when a source's runtime size becomes known (auto-sized text, media video size).
    val nativeSizes by com.stream4k60.app.engine.SourceNativeSizes.sizes.collectAsState()
    val selectionColor = Color(0xFF53C7FF)
    val selectedIdState = rememberUpdatedState(selectedSourceId)
    var transientTransforms by remember(sources) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var snapGuides by remember { mutableStateOf(SnapGuides()) }
    val selectedBase = sources.firstOrNull { it.id == selectedSourceId && it.isVisible && it.type.uppercase() in visualSourceTypes }
    val selected = selectedBase?.let { source ->
        transientTransforms[source.id]?.let { source.copy(transformJson = it) } ?: source
    }
    val handleTolerance = with(density) { 16.dp.toPx() }
    val handleSize = with(density) { 8.dp.toPx() }
    val rotationHandleDistance = with(density) { 28.dp.toPx() }
    var cropMode by remember { mutableStateOf(false) }
    var freeResize by remember { mutableStateOf(false) }
    val cropModeState = rememberUpdatedState(cropMode)
    val freeResizeState = rememberUpdatedState(freeResize)
    val snapTolerancePx = with(density) { 8.dp.toPx() }

    Box(modifier = modifier) {
        NativePreviewSurface(Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val selectedItem = sources.firstOrNull { it.id == selectedIdState.value }
                    val candidates = sources.filter { it.isVisible && it.type.uppercase() in visualSourceTypes }
                    when {
                        event.key == Key.Tab && candidates.isNotEmpty() -> {
                            val index = candidates.indexOfFirst { it.id == selectedIdState.value }
                            val step = if (event.isShiftPressed) -1 else 1
                            val next = if (index < 0) 0 else (index + step + candidates.size) % candidates.size
                            onSelectSource(candidates[next].id)
                            true
                        }
                        event.key == Key.Escape && selectedIdState.value != null -> {
                            onSelectSource(null)
                            true
                        }
                        selectedItem != null && event.isCtrlPressed && event.key == Key.DirectionUp -> {
                            onMoveSource(selectedItem.id, -1)
                            true
                        }
                        selectedItem != null && event.isCtrlPressed && event.key == Key.DirectionDown -> {
                            onMoveSource(selectedItem.id, 1)
                            true
                        }
                        selectedItem != null && !selectedItem.isLocked && !event.isCtrlPressed && event.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> {
                            val step = if (event.isShiftPressed) 10f else 1f
                            val dx = when (event.key) { Key.DirectionLeft -> -step; Key.DirectionRight -> step; else -> 0f }
                            val dy = when (event.key) { Key.DirectionUp -> -step; Key.DirectionDown -> step; else -> 0f }
                            val baseJson = transientTransforms[selectedItem.id] ?: selectedItem.transformJson
                            val next = runCatching { JSONObject(baseJson) }.getOrDefault(JSONObject())
                            val rect = sourceRect(selectedItem.copy(transformJson = baseJson), canvasWidth, canvasHeight)
                            next.put("x", (rect.positionX + dx).toDouble())
                            next.put("y", (rect.positionY + dy).toDouble())
                            val transform = next.toString()
                            transientTransforms = transientTransforms + (selectedItem.id to transform)
                            val updated = selectedItem.copy(transformJson = transform)
                            applySourceTransformToNative(updated, sources.indexOfFirst { it.id == updated.id }.coerceAtLeast(0), canvasWidth, canvasHeight)
                            onCommitTransform(updated.id, transform)
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(sources, canvasWidth, canvasHeight, nativeSizes) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focusRequester.requestFocus()
                        val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                        val viewHeight = size.height.toFloat().coerceAtLeast(1f)
                        val toCanvasX = canvasWidth / viewWidth
                        val toCanvasY = canvasHeight / viewHeight
                        val currentSources = sources.map { source ->
                            transientTransforms[source.id]?.let { source.copy(transformJson = it) } ?: source
                        }
                        val selectedAtDown = selectedIdState.value?.let { id -> currentSources.firstOrNull { it.id == id } }
                            ?.takeIf { it.isVisible && !it.isLocked && it.type.uppercase() in visualSourceTypes }
                        val selectedBox = selectedAtDown?.let { itemBox(it, canvasWidth, canvasHeight) }
                        val onRotationHandle = selectedBox != null &&
                            (rotationHandlePoint(selectedBox, viewWidth, viewHeight, canvasWidth, canvasHeight, rotationHandleDistance) - down.position).getDistance() <= handleTolerance
                        val handle = if (onRotationHandle || selectedBox == null) null else
                            findHandle(down.position, selectedBox, viewWidth, viewHeight, canvasWidth, canvasHeight, handleTolerance)
                        val hit = if (onRotationHandle || handle != null) selectedAtDown else
                            findHitSource(down.position, viewWidth, viewHeight, canvasWidth, canvasHeight, currentSources)
                        onSelectSource(hit?.id)
                        snapGuides = SnapGuides()
                        if (hit == null) return@awaitEachGesture
                        val modifiers = currentEvent.keyboardModifiers
                        val crop = cropModeState.value || modifiers.isAltPressed
                        val freeAspect = freeResizeState.value || modifiers.isShiftPressed
                        val initial = sourceRect(hit, canvasWidth, canvasHeight)
                        val initialJson = runCatching { JSONObject(hit.transformJson) }.getOrDefault(JSONObject())
                        val z = sources.indexOfFirst { it.id == hit.id }.coerceAtLeast(0)
                        var total = Offset.Zero
                        var last = down.position
                        var dragging = false
                        var latest = hit.transformJson
                        // Two-finger pinch/twist state, captured when the second finger lands.
                        var pinchBase: SourceItem? = null
                        var pinchStart: Triple<Float, Float, Offset>? = null
                        fun apply(transform: String) {
                            latest = transform
                            transientTransforms = transientTransforms + (hit.id to latest)
                            applySourceTransformToNative(hit.copy(transformJson = latest), z, canvasWidth, canvasHeight)
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (hit.isLocked) continue
                            if (pressed.size >= 2 && handle == null && !onRotationHandle) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val distance = (a - b).getDistance().coerceAtLeast(1f)
                                val angle = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                                val centroid = (a + b) / 2f
                                if (pinchStart == null) {
                                    pinchBase = hit.copy(transformJson = latest)
                                    pinchStart = Triple(distance, angle, centroid)
                                }
                                val (d0, a0, c0) = pinchStart!!
                                val move = centroid - c0
                                apply(pinchItem(pinchBase!!, distance / d0, angle - a0, move.x * toCanvasX, move.y * toCanvasY, canvasWidth, canvasHeight))
                                dragging = true
                                snapGuides = SnapGuides()
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinchStart != null) continue // lifting one finger after a pinch should not jump into a drag
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            total += change.position - last
                            last = change.position
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (!dragging) continue
                            val dx = total.x * toCanvasX
                            val dy = total.y * toCanvasY
                            when {
                                onRotationHandle -> {
                                    val box = selectedBox!!
                                    val degrees = angleFromCenter(box, change.position.x * toCanvasX, change.position.y * toCanvasY)
                                    apply(rotateItem(hit, snapRotation(degrees, fineSnap = currentEvent.keyboardModifiers.isShiftPressed), canvasWidth, canvasHeight))
                                }
                                handle != null && crop -> apply(cropItem(hit, handle, dx, dy, canvasWidth, canvasHeight))
                                handle != null -> apply(resizeItem(hit, handle, dx, dy, freeAspect, canvasWidth, canvasHeight))
                                else -> {
                                    val next = JSONObject(initialJson.toString())
                                    val snap = snapPosition(
                                        hit.id, initial, initial.x + dx, initial.y + dy, currentSources, canvasWidth, canvasHeight,
                                        snapTolerancePx * toCanvasX, snapTolerancePx * toCanvasY
                                    )
                                    next.put("x", (initial.positionX + snap.x - initial.x).toDouble())
                                    next.put("y", (initial.positionY + snap.y - initial.y).toDouble())
                                    snapGuides = snap.guides
                                    apply(next.toString())
                                }
                            }
                            change.consume()
                        }
                        if (dragging && !hit.isLocked) onCommitTransform(hit.id, latest)
                        snapGuides = SnapGuides()
                    }
                }
        ) {
            snapGuides.x?.let { x ->
                val px = x * size.width / canvasWidth.coerceAtLeast(1)
                drawLine(Color(0xFFFF4D6D), Offset(px, 0f), Offset(px, size.height), strokeWidth = 1.dp.toPx())
            }
            snapGuides.y?.let { y ->
                val py = y * size.height / canvasHeight.coerceAtLeast(1)
                drawLine(Color(0xFFFF4D6D), Offset(0f, py), Offset(size.width, py), strokeWidth = 1.dp.toPx())
            }
            nativeSizes.size // redraw the selection when measured sizes change
            selected?.let { source ->
                val box = itemBox(source, canvasWidth, canvasHeight)
                fun view(fx: Float, fy: Float): Offset {
                    val (cx, cy) = box.pointAt(fx, fy)
                    return Offset(cx * size.width / canvasWidth.coerceAtLeast(1), cy * size.height / canvasHeight.coerceAtLeast(1))
                }
                val stroke = 2.dp.toPx()
                val corners = listOf(view(0f, 0f), view(1f, 0f), view(1f, 1f), view(0f, 1f))
                corners.forEachIndexed { i, p -> drawLine(selectionColor, p, corners[(i + 1) % 4], strokeWidth = stroke) }
                if (!source.isLocked) {
                    val handleColor = if (cropMode) Color(0xFFFFA726) else selectionColor
                    BoxHandle.entries.forEach { handle ->
                        val p = view(handle.fx, handle.fy)
                        drawRect(Color.Black, Offset(p.x - handleSize / 2f, p.y - handleSize / 2f), Size(handleSize, handleSize))
                        drawRect(handleColor, Offset(p.x - handleSize / 2f, p.y - handleSize / 2f), Size(handleSize, handleSize), style = Stroke(stroke))
                    }
                    val top = view(0.5f, 0f)
                    val knob = rotationHandlePoint(box, size.width, size.height, canvasWidth, canvasHeight, rotationHandleDistance)
                    drawLine(selectionColor, top, knob, strokeWidth = stroke)
                    drawCircle(Color.Black, radius = handleSize * 0.8f, center = knob)
                    drawCircle(selectionColor, radius = handleSize * 0.8f, center = knob, style = Stroke(stroke))
                }
            }
        }
        if (selected != null && !selected.isLocked) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            ) {
                FilterChip(
                    selected = cropMode,
                    onClick = { cropMode = !cropMode },
                    label = { Text("Crop") },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xCC000000), labelColor = Color.White)
                )
                FilterChip(
                    selected = freeResize,
                    onClick = { freeResize = !freeResize },
                    label = { Text("Free resize") },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xCC000000), labelColor = Color.White)
                )
            }
        }
    }
}

/** View-space point of the rotation knob, [distance] px outward from the top edge's middle. */
private fun rotationHandlePoint(box: ItemBox, viewWidth: Float, viewHeight: Float, canvasWidth: Int, canvasHeight: Int, distance: Float): Offset {
    val (tx, ty) = box.pointAt(0.5f, 0f)
    val radians = Math.toRadians(box.rotation.toDouble())
    val top = Offset(tx * viewWidth / canvasWidth.coerceAtLeast(1), ty * viewHeight / canvasHeight.coerceAtLeast(1))
    return top + Offset((sin(radians) * distance).toFloat(), (-cos(radians) * distance).toFloat())
}

private fun findHandle(
    point: Offset,
    box: ItemBox,
    viewWidth: Float,
    viewHeight: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    tolerance: Float
): BoxHandle? = BoxHandle.entries
    .map { handle ->
        val (cx, cy) = box.pointAt(handle.fx, handle.fy)
        handle to (Offset(cx * viewWidth / canvasWidth.coerceAtLeast(1), cy * viewHeight / canvasHeight.coerceAtLeast(1)) - point).getDistance()
    }
    .filter { it.second <= tolerance }
    // Prefer corners when handles overlap on small items.
    .minWithOrNull(compareBy<Pair<BoxHandle, Float>> { it.second - if (it.first.isCorner) tolerance * 0.25f else 0f })
    ?.first

private fun snapPosition(
    sourceId: String,
    dragged: SourceRect,
    proposedX: Float,
    proposedY: Float,
    sources: List<SourceItem>,
    canvasWidth: Int,
    canvasHeight: Int,
    toleranceX: Float,
    toleranceY: Float
): SnapResult {
    val draggedBounds = boundsOffsets(dragged)
    val otherRects = sources.asSequence()
        .filter { it.id != sourceId && it.isVisible && it.type.uppercase() in visualSourceTypes }
        .map { sourceRect(it, canvasWidth, canvasHeight) }
        .map(::boundsOffsets)
        .toList()
    val xTargets = mutableListOf(0f, canvasWidth / 2f, canvasWidth.toFloat())
    val yTargets = mutableListOf(0f, canvasHeight / 2f, canvasHeight.toFloat())
    otherRects.forEach { bounds -> xTargets += listOf(bounds.left, bounds.centerX, bounds.right); yTargets += listOf(bounds.top, bounds.centerY, bounds.bottom) }
    val draggedXOffsets = listOf(draggedBounds.left - dragged.x, draggedBounds.centerX - dragged.x, draggedBounds.right - dragged.x)
    val draggedYOffsets = listOf(draggedBounds.top - dragged.y, draggedBounds.centerY - dragged.y, draggedBounds.bottom - dragged.y)
    val xSnap = nearestSnap(proposedX, draggedXOffsets, xTargets, toleranceX)
    val ySnap = nearestSnap(proposedY, draggedYOffsets, yTargets, toleranceY)
    return SnapResult(xSnap.first, ySnap.first, SnapGuides(xSnap.second, ySnap.second))
}

private data class RectBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

private fun boundsOffsets(rect: SourceRect): RectBounds {
    val signedWidth = rect.width * rect.scaleX
    val signedHeight = rect.height * rect.scaleY
    val left = rect.x + min(0f, signedWidth)
    val right = rect.x + max(0f, signedWidth)
    val top = rect.y + min(0f, signedHeight)
    val bottom = rect.y + max(0f, signedHeight)
    val pivotX = rect.x + rect.pivotX
    val pivotY = rect.y + rect.pivotY
    val radians = Math.toRadians(rect.rotation.toDouble())
    val corners = listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).map { point ->
        val dx = point.x - pivotX; val dy = point.y - pivotY
        Offset(pivotX + cos(radians).toFloat() * dx - sin(radians).toFloat() * dy, pivotY + sin(radians).toFloat() * dx + cos(radians).toFloat() * dy)
    }
    return RectBounds(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

private fun nearestSnap(
    proposed: Float,
    draggedFeatureOffsets: List<Float>,
    targets: List<Float>,
    tolerance: Float
): Pair<Float, Float?> {
    val candidate = targets.flatMap { target -> draggedFeatureOffsets.map { offset -> (target - offset) to target } }
        .minByOrNull { (position, _) -> abs(position - proposed) }
        ?: return proposed to null
    return if (abs(candidate.first - proposed) <= tolerance) candidate else proposed to null
}

private fun findHitSource(
    point: Offset,
    viewWidth: Float,
    viewHeight: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    sources: List<SourceItem>
): SourceItem? {
    if (viewWidth <= 0f || viewHeight <= 0f) return null
    val x = point.x * canvasWidth / viewWidth
    val y = point.y * canvasHeight / viewHeight
    return sources.asReversed().firstOrNull { source ->
        if (!source.isVisible || source.type.uppercase() !in visualSourceTypes) return@firstOrNull false
        val rect = sourceRect(source, canvasWidth, canvasHeight)
        val signedWidth = rect.width * rect.scaleX
        val signedHeight = rect.height * rect.scaleY
        val left = rect.x + min(0f, signedWidth)
        val top = rect.y + min(0f, signedHeight)
        val width = abs(signedWidth)
        val height = abs(signedHeight)
        val centerX = rect.x + rect.pivotX
        val centerY = rect.y + rect.pivotY
        val radians = Math.toRadians(rect.rotation.toDouble())
        val dx = x - centerX
        val dy = y - centerY
        val localX = cos(radians).toFloat() * dx + sin(radians).toFloat() * dy + centerX
        val localY = -sin(radians).toFloat() * dx + cos(radians).toFloat() * dy + centerY
        localX in left..(left + width) && localY in top..(top + height)
    }
}
