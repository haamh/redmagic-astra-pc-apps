package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.rotate
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
    "CAMERA", "USB_CAPTURE", "SCREEN_CAPTURE", "MEDIA", "BROWSER", "IMAGE", "IMAGE_SLIDESHOW", "TEXT", "COLOR"
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

private enum class ResizeCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false)
}

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
    val selectionColor = Color(0xFF53C7FF)
    val selectedIdState = rememberUpdatedState(selectedSourceId)
    var transientTransforms by remember(sources) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var snapGuides by remember { mutableStateOf(SnapGuides()) }
    val selectedBase = sources.firstOrNull { it.id == selectedSourceId && it.isVisible && it.type.uppercase() in visualSourceTypes }
    val selected = selectedBase?.let { source ->
        transientTransforms[source.id]?.let { source.copy(transformJson = it) } ?: source
    }
    val resizeTolerance = with(density) { 11.dp.toPx() }
    val handleSize = with(density) { 7.dp.toPx() }
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
                .pointerInput(sources, canvasWidth, canvasHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focusRequester.requestFocus()
                        val viewWidth = size.width.toFloat()
                        val viewHeight = size.height.toFloat()
                        val currentSources = sources.map { source ->
                            transientTransforms[source.id]?.let { source.copy(transformJson = it) } ?: source
                        }
                        val selectedAtDown = selectedIdState.value?.let { id -> currentSources.firstOrNull { it.id == id } }
                        val resizeCorner = selectedAtDown?.takeIf { it.isVisible && !it.isLocked && sourceRect(it, canvasWidth, canvasHeight).canResize }?.let {
                            findResizeCorner(down.position, it, viewWidth, viewHeight, canvasWidth, canvasHeight, resizeTolerance)
                        }
                        val hit = if (resizeCorner != null) selectedAtDown else
                            findHitSource(down.position, viewWidth, viewHeight, canvasWidth, canvasHeight, currentSources)
                        onSelectSource(hit?.id)
                        snapGuides = SnapGuides()
                        if (hit != null) {
                            val initial = sourceRect(hit, canvasWidth, canvasHeight)
                            val initialJson = runCatching { JSONObject(hit.transformJson) }.getOrDefault(JSONObject())
                            var last = down.position
                            var total = Offset.Zero
                            var dragging = false
                            var latest = hit.transformJson
                            var change = down
                            while (change.pressed) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                change = event.changes.firstOrNull { it.id == down.id } ?: break
                                total += change.position - last
                                last = change.position
                                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
                                if (dragging && !hit.isLocked) {
                                    val next = JSONObject(initialJson.toString())
                                    if (resizeCorner != null && initial.canResize && initial.scaleX > 0f && initial.scaleY > 0f) {
                                        val dx = total.x * canvasWidth / viewWidth.coerceAtLeast(1f)
                                        val dy = total.y * canvasHeight / viewHeight.coerceAtLeast(1f)
                                        val radians = Math.toRadians(initial.rotation.toDouble())
                                        val localDx = cos(radians).toFloat() * dx + sin(radians).toFloat() * dy
                                        val localDy = -sin(radians).toFloat() * dx + cos(radians).toFloat() * dy
                                        val oldWidth = initial.width * initial.scaleX
                                        val oldHeight = initial.height * initial.scaleY
                                        val maxWidth = max(canvasWidth * 4f, 16f)
                                        val maxHeight = max(canvasHeight * 4f, 16f)
                                        val newWidth = (oldWidth + if (resizeCorner.left) -localDx else localDx).coerceIn(16f, maxWidth)
                                        val newHeight = (oldHeight + if (resizeCorner.top) -localDy else localDy).coerceIn(16f, maxHeight)
                                        val oldAnchorX = if (resizeCorner.left) oldWidth else 0f
                                        val oldAnchorY = if (resizeCorner.top) oldHeight else 0f
                                        val anchorRadians = radians
                                        val oldDx = oldAnchorX - initial.pivotX
                                        val oldDy = oldAnchorY - initial.pivotY
                                        val anchorX = initial.positionX + cos(anchorRadians).toFloat() * oldDx - sin(anchorRadians).toFloat() * oldDy
                                        val anchorY = initial.positionY + sin(anchorRadians).toFloat() * oldDx + cos(anchorRadians).toFloat() * oldDy
                                        next.put("scaleX", (newWidth / initial.width.coerceAtLeast(1f)).toDouble())
                                        next.put("scaleY", (newHeight / initial.height.coerceAtLeast(1f)).toDouble())
                                        val resizedRect = sourceRect(hit.copy(transformJson = next.toString()), canvasWidth, canvasHeight)
                                        val newAnchorX = if (resizeCorner.left) newWidth else 0f
                                        val newAnchorY = if (resizeCorner.top) newHeight else 0f
                                        val newDx = newAnchorX - resizedRect.pivotX
                                        val newDy = newAnchorY - resizedRect.pivotY
                                        val nextPositionX = anchorX - cos(anchorRadians).toFloat() * newDx + sin(anchorRadians).toFloat() * newDy
                                        val nextPositionY = anchorY - sin(anchorRadians).toFloat() * newDx - cos(anchorRadians).toFloat() * newDy
                                        next.put("x", nextPositionX.toDouble())
                                        next.put("y", nextPositionY.toDouble())
                                        snapGuides = SnapGuides()
                                    } else if (resizeCorner == null) {
                                        val proposedX = initial.x + total.x * canvasWidth / viewWidth.coerceAtLeast(1f)
                                        val proposedY = initial.y + total.y * canvasHeight / viewHeight.coerceAtLeast(1f)
                                        val snap = snapPosition(
                                            hit.id, initial, proposedX, proposedY, currentSources, canvasWidth, canvasHeight,
                                            snapTolerancePx * canvasWidth / viewWidth.coerceAtLeast(1f),
                                            snapTolerancePx * canvasHeight / viewHeight.coerceAtLeast(1f)
                                        )
                                        next.put("x", (initial.positionX + snap.x - initial.x).toDouble())
                                        next.put("y", (initial.positionY + snap.y - initial.y).toDouble())
                                        snapGuides = snap.guides
                                    }
                                    latest = next.toString()
                                    transientTransforms = transientTransforms + (hit.id to latest)
                                    val updated = hit.copy(transformJson = latest)
                                    val z = sources.indexOfFirst { it.id == hit.id }.coerceAtLeast(0)
                                    applySourceTransformToNative(updated, z, canvasWidth, canvasHeight)
                                    change.consume()
                                }
                            }
                            if (dragging && !hit.isLocked) onCommitTransform(hit.id, latest)
                            snapGuides = SnapGuides()
                        }
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
            selected?.let { source ->
                val rect = sourceRect(source, canvasWidth, canvasHeight)
                val rawLeft = rect.x * size.width / canvasWidth.coerceAtLeast(1)
                val rawTop = rect.y * size.height / canvasHeight.coerceAtLeast(1)
                val signedWidth = rect.width * rect.scaleX * size.width / canvasWidth.coerceAtLeast(1)
                val signedHeight = rect.height * rect.scaleY * size.height / canvasHeight.coerceAtLeast(1)
                val left = rawLeft + min(0f, signedWidth)
                val top = rawTop + min(0f, signedHeight)
                val width = abs(signedWidth)
                val height = abs(signedHeight)
                val sourceCenter = Offset(
                    rawLeft + rect.pivotX * size.width / canvasWidth.coerceAtLeast(1),
                    rawTop + rect.pivotY * size.height / canvasHeight.coerceAtLeast(1)
                )
                val stroke = 2.dp.toPx()
                rotate(rect.rotation, sourceCenter) {
                    drawRect(selectionColor, Offset(left, top), Size(width, height), style = Stroke(stroke))
                    if (rect.canResize && rect.scaleX > 0f && rect.scaleY > 0f) {
                        listOf(
                            Offset(left, top), Offset(left + width, top),
                            Offset(left, top + height), Offset(left + width, top + height)
                        ).forEach { corner ->
                            drawRect(Color.Black, Offset(corner.x - handleSize / 2f, corner.y - handleSize / 2f), Size(handleSize, handleSize))
                            drawRect(selectionColor, Offset(corner.x - handleSize / 2f, corner.y - handleSize / 2f), Size(handleSize, handleSize), style = Stroke(stroke))
                        }
                    }
                }
            }
        }
    }
}

private fun findResizeCorner(
    point: Offset,
    source: SourceItem,
    viewWidth: Float,
    viewHeight: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    tolerance: Float
): ResizeCorner? {
    if (viewWidth <= 0f || viewHeight <= 0f) return null
    val rect = sourceRect(source, canvasWidth, canvasHeight)
    if (!rect.canResize || rect.scaleX <= 0f || rect.scaleY <= 0f || rect.width <= 0f || rect.height <= 0f) return null
    val left = rect.x * viewWidth / canvasWidth.coerceAtLeast(1)
    val top = rect.y * viewHeight / canvasHeight.coerceAtLeast(1)
    val width = rect.width * rect.scaleX * viewWidth / canvasWidth.coerceAtLeast(1)
    val height = rect.height * rect.scaleY * viewHeight / canvasHeight.coerceAtLeast(1)
    val center = Offset(
        left + rect.pivotX * viewWidth / canvasWidth.coerceAtLeast(1),
        top + rect.pivotY * viewHeight / canvasHeight.coerceAtLeast(1)
    )
    val radians = Math.toRadians(rect.rotation.toDouble())
    fun rotatePoint(x: Float, y: Float): Offset {
        val dx = x - center.x
        val dy = y - center.y
        return Offset(
            center.x + cos(radians).toFloat() * dx - sin(radians).toFloat() * dy,
            center.y + sin(radians).toFloat() * dx + cos(radians).toFloat() * dy
        )
    }
    val corners = listOf(
        ResizeCorner.TOP_LEFT to rotatePoint(left, top),
        ResizeCorner.TOP_RIGHT to rotatePoint(left + width, top),
        ResizeCorner.BOTTOM_LEFT to rotatePoint(left, top + height),
        ResizeCorner.BOTTOM_RIGHT to rotatePoint(left + width, top + height)
    )
    return corners.minByOrNull { (_, corner) -> (corner - point).getDistance() }
        ?.takeIf { (_, corner) -> (corner - point).getDistance() <= tolerance }
        ?.first
}

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
