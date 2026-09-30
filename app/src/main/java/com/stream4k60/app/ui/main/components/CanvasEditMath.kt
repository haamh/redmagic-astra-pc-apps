package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Canvas editing math shared by pointer, touch and keyboard editing.
 *
 * An item's "box" is what OBS draws handles on: the scaled, cropped source, or its bounds box when bounds
 * are set. Boxes rotate about the item's position (its OBS alignment point). Every edit rewrites the
 * transform JSON with the keys [resolveSourceTransform] reads first (x/y, scaleX/scaleY, boundsWidth/
 * boundsHeight, cropLeft…, rotation), so imported OBS values and later edits never conflict.
 */
internal data class ItemBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val positionX: Float,
    val positionY: Float,
    val rotation: Float,
    val boundsActive: Boolean,
    val geometry: SourceTransformGeometry
) {
    /** Canvas point of a point given as a fraction of the (unrotated) box. */
    fun pointAt(fx: Float, fy: Float): Pair<Float, Float> {
        val radians = Math.toRadians(rotation.toDouble())
        val dx = left + fx * width - positionX
        val dy = top + fy * height - positionY
        return (positionX + (cos(radians) * dx - sin(radians) * dy).toFloat()) to
            (positionY + (sin(radians) * dx + cos(radians) * dy).toFloat())
    }

    val center: Pair<Float, Float> get() = pointAt(0.5f, 0.5f)
}

/** Handles: hx/hy are -1 (left/top edge), 0 (middle) or 1 (right/bottom edge). */
internal enum class BoxHandle(val hx: Int, val hy: Int) {
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
    LEFT(-1, 0), RIGHT(1, 0),
    BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1);

    val isCorner get() = hx != 0 && hy != 0
    /** Box fraction of this handle. */
    val fx get() = (hx + 1) / 2f
    val fy get() = (hy + 1) / 2f
}

internal const val MIN_ITEM_SIZE = 16f

private fun transformOf(source: SourceItem): JSONObject = runCatching { JSONObject(source.transformJson) }.getOrDefault(JSONObject())

internal fun itemBox(source: SourceItem, canvasWidth: Int, canvasHeight: Int): ItemBox {
    val g = resolveSourceTransform(source, canvasWidth, canvasHeight)
    val t = transformOf(source)
    val bounds = t.optJSONObject("bounds") ?: JSONObject()
    val boundsWidth = t.optDouble("boundsWidth", bounds.optDouble("x", 0.0)).toFloat()
    val boundsHeight = t.optDouble("boundsHeight", bounds.optDouble("y", 0.0)).toFloat()
    return if (!g.canResize && boundsWidth > 0f && boundsHeight > 0f) {
        val alignment = t.optInt("alignment", t.optInt("align", 5))
        ItemBox(
            g.positionX - boundsWidth * alignmentFactor(alignment, true),
            g.positionY - boundsHeight * alignmentFactor(alignment, false),
            boundsWidth, boundsHeight, g.positionX, g.positionY, g.rotation, true, g
        )
    } else {
        val w = g.width * g.scaleX
        val h = g.height * g.scaleY
        // Flipped items (negative scale) extend the other way from their origin.
        ItemBox(g.x + minOf(0f, w), g.y + minOf(0f, h), abs(w), abs(h), g.positionX, g.positionY, g.rotation, false, g)
    }
}

/**
 * Moves [next] so the point at box fraction (fx, fy) lands on canvas point [target].
 * Position is the rotation pivot, so a translation moves every box point equally.
 */
private fun place(next: JSONObject, source: SourceItem, fx: Float, fy: Float, target: Pair<Float, Float>, canvasWidth: Int, canvasHeight: Int): String {
    val box = itemBox(source.copy(transformJson = next.toString()), canvasWidth, canvasHeight)
    val (px, py) = box.pointAt(fx, fy)
    next.put("x", (box.positionX + target.first - px).toDouble())
    next.put("y", (box.positionY + target.second - py).toDouble())
    return next.toString()
}

private fun toLocal(box: ItemBox, dx: Float, dy: Float): Pair<Float, Float> {
    val radians = Math.toRadians(box.rotation.toDouble())
    return (cos(radians) * dx + sin(radians) * dy).toFloat() to (-sin(radians) * dx + cos(radians) * dy).toFloat()
}

/**
 * Resizes from [handle] by a canvas-space drag of ([dx], [dy]); the opposite side stays put.
 * Corners keep the aspect ratio unless [freeAspect]; edges stretch one axis, like OBS.
 */
internal fun resizeItem(source: SourceItem, handle: BoxHandle, dx: Float, dy: Float, freeAspect: Boolean, canvasWidth: Int, canvasHeight: Int): String {
    val box = itemBox(source, canvasWidth, canvasHeight)
    val (lx, ly) = toLocal(box, dx, dy)
    var newW = if (handle.hx != 0) box.width + handle.hx * lx else box.width
    var newH = if (handle.hy != 0) box.height + handle.hy * ly else box.height
    if (handle.isCorner && !freeAspect && box.width > 0f && box.height > 0f) {
        // Follow whichever axis the pointer moved further along, so shrinking and growing both work.
        val fw = newW / box.width
        val fh = newH / box.height
        val factor = if (abs(fw - 1f) >= abs(fh - 1f)) fw else fh
        newW = box.width * factor
        newH = box.height * factor
    }
    val minFactor = max(MIN_ITEM_SIZE / box.width.coerceAtLeast(1f), MIN_ITEM_SIZE / box.height.coerceAtLeast(1f))
    if (handle.isCorner && !freeAspect && newW / box.width.coerceAtLeast(1f) < minFactor) {
        newW = box.width * minFactor; newH = box.height * minFactor
    }
    newW = newW.coerceIn(MIN_ITEM_SIZE, canvasWidth * 8f)
    newH = newH.coerceIn(MIN_ITEM_SIZE, canvasHeight * 8f)
    val next = setBoxSize(source, box, newW, newH)
    val anchorFx = 1f - handle.fx
    val anchorFy = 1f - handle.fy
    return place(next, source, anchorFx, anchorFy, box.pointAt(anchorFx, anchorFy), canvasWidth, canvasHeight)
}

private fun setBoxSize(source: SourceItem, box: ItemBox, width: Float, height: Float): JSONObject {
    val next = transformOf(source)
    if (box.boundsActive) {
        next.put("boundsWidth", width.toDouble())
        next.put("boundsHeight", height.toDouble())
    } else {
        val g = box.geometry
        val sx = sign(g.scaleX).let { if (it == 0f) 1f else it }
        val sy = sign(g.scaleY).let { if (it == 0f) 1f else it }
        next.put("scaleX", (sx * width / g.width.coerceAtLeast(1f)).toDouble())
        next.put("scaleY", (sy * height / g.height.coerceAtLeast(1f)).toDouble())
    }
    return next
}

/**
 * Crops from [handle] (OBS Alt+drag): dragging an edge inward hides source pixels on that side and the
 * rest of the image stays where it is. Dragging outward uncrops.
 */
internal fun cropItem(source: SourceItem, handle: BoxHandle, dx: Float, dy: Float, canvasWidth: Int, canvasHeight: Int): String {
    val box = itemBox(source, canvasWidth, canvasHeight)
    val g = box.geometry
    val (lx, ly) = toLocal(box, dx, dy)
    // Canvas pixels per source pixel on each axis.
    val perSourceX = (abs(g.scaleX) * g.width / (g.sourceWidth - g.crop.left - g.crop.right).coerceAtLeast(1f)).coerceAtLeast(1e-4f)
    val perSourceY = (abs(g.scaleY) * g.height / (g.sourceHeight - g.crop.top - g.crop.bottom).coerceAtLeast(1f)).coerceAtLeast(1e-4f)
    var left = g.crop.left; var right = g.crop.right; var top = g.crop.top; var bottom = g.crop.bottom
    // Screen-left edge is the source's right edge when flipped.
    val flipX = g.scaleX < 0f
    val flipY = g.scaleY < 0f
    val maxX = (g.sourceWidth - 2f).coerceAtLeast(0f)
    val maxY = (g.sourceHeight - 2f).coerceAtLeast(0f)
    when (handle.hx) {
        -1 -> { val amount = lx / perSourceX; if (flipX) right = (right + amount).coerceIn(0f, maxX - left) else left = (left + amount).coerceIn(0f, maxX - right) }
        1 -> { val amount = -lx / perSourceX; if (flipX) left = (left + amount).coerceIn(0f, maxX - right) else right = (right + amount).coerceIn(0f, maxX - left) }
    }
    when (handle.hy) {
        -1 -> { val amount = ly / perSourceY; if (flipY) bottom = (bottom + amount).coerceIn(0f, maxY - top) else top = (top + amount).coerceIn(0f, maxY - bottom) }
        1 -> { val amount = -ly / perSourceY; if (flipY) top = (top + amount).coerceIn(0f, maxY - bottom) else bottom = (bottom + amount).coerceIn(0f, maxY - top) }
    }
    val next = transformOf(source)
    next.put("cropLeft", left.roundToInt().toDouble()); next.put("cropRight", right.roundToInt().toDouble())
    next.put("cropTop", top.roundToInt().toDouble()); next.put("cropBottom", bottom.roundToInt().toDouble())
    // Keep the scale per source pixel unchanged, so cropping hides pixels instead of stretching the rest.
    if (!box.boundsActive) {
        val newSourceW = (g.sourceWidth - left - right).coerceAtLeast(2f)
        val newSourceH = (g.sourceHeight - top - bottom).coerceAtLeast(2f)
        val cropped = resolveSourceTransform(source.copy(transformJson = next.toString()), canvasWidth, canvasHeight)
        next.put("scaleX", (sign(g.scaleX).let { if (it == 0f) 1f else it } * perSourceX * newSourceW / cropped.width.coerceAtLeast(1f)).toDouble())
        next.put("scaleY", (sign(g.scaleY).let { if (it == 0f) 1f else it } * perSourceY * newSourceH / cropped.height.coerceAtLeast(1f)).toDouble())
        val anchorFx = 1f - handle.fx
        val anchorFy = 1f - handle.fy
        return place(next, source, anchorFx, anchorFy, box.pointAt(anchorFx, anchorFy), canvasWidth, canvasHeight)
    }
    return next.toString()
}

/** Sets rotation, turning about the box center (like OBS's rotation handle). */
internal fun rotateItem(source: SourceItem, degrees: Float, canvasWidth: Int, canvasHeight: Int): String {
    val box = itemBox(source, canvasWidth, canvasHeight)
    val next = transformOf(source)
    next.put("rotation", normalizeDegrees(degrees).toDouble())
    return place(next, source, 0.5f, 0.5f, box.center, canvasWidth, canvasHeight)
}

/**
 * Two-finger edit relative to [initial]: scale by [scale] and turn by [rotationDelta] about the box center,
 * then move by ([dx], [dy]) canvas pixels.
 */
internal fun pinchItem(initial: SourceItem, scale: Float, rotationDelta: Float, dx: Float, dy: Float, canvasWidth: Int, canvasHeight: Int): String {
    val box = itemBox(initial, canvasWidth, canvasHeight)
    val factor = scale.coerceIn(
        MIN_ITEM_SIZE / minOf(box.width, box.height).coerceAtLeast(1f),
        (canvasWidth * 8f) / box.width.coerceAtLeast(1f)
    )
    val next = setBoxSize(initial, box, box.width * factor, box.height * factor)
    next.put("rotation", snapRotation(box.rotation + rotationDelta, fineSnap = false).toDouble())
    val (cx, cy) = box.center
    return place(next, initial, 0.5f, 0.5f, (cx + dx) to (cy + dy), canvasWidth, canvasHeight)
}

/** Degrees from the box center to a canvas point, measured so "straight up" is 0°. */
internal fun angleFromCenter(box: ItemBox, x: Float, y: Float): Float {
    val (cx, cy) = box.center
    return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
}

/** Snaps to 15° steps when [fineSnap], otherwise only near right angles (within 3°). */
internal fun snapRotation(degrees: Float, fineSnap: Boolean): Float {
    val d = normalizeDegrees(degrees)
    if (fineSnap) return normalizeDegrees((d / 15f).roundToInt() * 15f)
    val nearest = (d / 90f).roundToInt() * 90f
    return if (abs(d - nearest) <= 3f) normalizeDegrees(nearest) else d
}

internal fun normalizeDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}
