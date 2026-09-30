package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** OBS scene-item crop insets are source-pixel values; this also resolves its bounds modes. */
internal data class SourceCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun widthFraction(sourceWidth: Float): Float = ((sourceWidth - left - right) / sourceWidth.coerceAtLeast(1f)).coerceAtLeast(2f / sourceWidth.coerceAtLeast(2f))
    fun heightFraction(sourceHeight: Float): Float = ((sourceHeight - top - bottom) / sourceHeight.coerceAtLeast(1f)).coerceAtLeast(2f / sourceHeight.coerceAtLeast(2f))
}

internal data class SourceTransformGeometry(
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
    val opacity: Float,
    val crop: SourceCrop,
    val sourceWidth: Float,
    val sourceHeight: Float,
    val flipH: Boolean,
    val flipV: Boolean,
    val canResize: Boolean
)

internal fun readSourceCrop(transform: JSONObject, sourceWidth: Float, sourceHeight: Float): SourceCrop {
    val nested = transform.optJSONObject("crop") ?: JSONObject()
    val maxWidthCrop = (sourceWidth.coerceAtLeast(2f) - 2f).coerceAtLeast(0f)
    val maxHeightCrop = (sourceHeight.coerceAtLeast(2f) - 2f).coerceAtLeast(0f)
    val left = transform.optDouble("cropLeft", transform.optDouble("crop_left", nested.optDouble("left", 0.0))).toFloat().coerceIn(0f, maxWidthCrop)
    val right = transform.optDouble("cropRight", transform.optDouble("crop_right", nested.optDouble("right", 0.0))).toFloat().coerceIn(0f, (maxWidthCrop - left).coerceAtLeast(0f))
    val top = transform.optDouble("cropTop", transform.optDouble("crop_top", nested.optDouble("top", 0.0))).toFloat().coerceIn(0f, maxHeightCrop)
    val bottom = transform.optDouble("cropBottom", transform.optDouble("crop_bottom", nested.optDouble("bottom", 0.0))).toFloat().coerceIn(0f, (maxHeightCrop - top).coerceAtLeast(0f))
    return SourceCrop(left, top, right, bottom)
}

internal fun resolveSourceTransform(source: SourceItem, canvasWidth: Int, canvasHeight: Int): SourceTransformGeometry {
    val transform = runCatching { JSONObject(source.transformJson) }.getOrDefault(JSONObject())
    val config = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
    val settings = config.optJSONObject("settings") ?: config
    val fullCanvas = source.type.uppercase() in setOf("CAMERA", "USB_CAPTURE", "SCREEN_CAPTURE", "MEDIA")
    val inputWidth = settings.optDouble("width", config.optDouble("width", if (fullCanvas) canvasWidth.toDouble() else 1280.0)).toFloat().coerceAtLeast(1f)
    val inputHeight = settings.optDouble("height", config.optDouble("height", if (fullCanvas) canvasHeight.toDouble() else 720.0)).toFloat().coerceAtLeast(1f)
    val rawWidth = transform.optDouble("width", inputWidth.toDouble()).toFloat().coerceAtLeast(1f)
    val rawHeight = transform.optDouble("height", inputHeight.toDouble()).toFloat().coerceAtLeast(1f)
    val position = transform.optJSONObject("pos") ?: JSONObject()
    val scale = transform.optJSONObject("scale") ?: JSONObject()
    val positionX = transform.optDouble("x", transform.optDouble("positionX", position.optDouble("x", 0.0))).toFloat()
    val positionY = transform.optDouble("y", transform.optDouble("positionY", position.optDouble("y", 0.0))).toFloat()
    val initialCrop = readSourceCrop(transform, inputWidth, inputHeight)
    var cropLeft = initialCrop.left
    var cropRight = initialCrop.right
    var cropTop = initialCrop.top
    var cropBottom = initialCrop.bottom
    val alignment = transform.optInt("alignment", transform.optInt("align", 5))
    val boundsAlignment = transform.optInt("boundsAlignment", transform.optInt("bounds_align", transform.optInt("bounds_alignment", 0)))
    val bounds = transform.optJSONObject("bounds") ?: JSONObject()
    val boundsWidth = transform.optDouble("boundsWidth", bounds.optDouble("x", 0.0)).toFloat()
    val boundsHeight = transform.optDouble("boundsHeight", bounds.optDouble("y", 0.0)).toFloat()
    val requestedBoundsType = transform.optInt("boundsType", transform.optInt("bounds_type", 0))
    val boundsType = requestedBoundsType.takeIf { it in 1..6 && boundsWidth > 0f && boundsHeight > 0f } ?: 0
    val cropToBounds = transform.optBoolean("cropToBounds", transform.optBoolean("crop_to_bounds", false))
    var scaleX = transform.optDouble("scaleX", scale.optDouble("x", 1.0)).toFloat()
    var scaleY = transform.optDouble("scaleY", scale.optDouble("y", 1.0)).toFloat()
    val baseWidth = rawWidth * initialCrop.widthFraction(inputWidth)
    val baseHeight = rawHeight * initialCrop.heightFraction(inputHeight)

    if (boundsType != 0) {
        val scaledWidth = baseWidth * abs(scaleX)
        val scaledHeight = baseHeight * abs(scaleY)
        val xFactor = (boundsWidth / scaledWidth.coerceAtLeast(1f))
        val yFactor = (boundsHeight / scaledHeight.coerceAtLeast(1f))
        val multiplier = when (boundsType) {
            1 -> 1f // STRETCH replaces the existing scale on each axis below.
            2 -> min(xFactor, yFactor) // SCALE_INNER
            3 -> max(xFactor, yFactor) // SCALE_OUTER
            4 -> xFactor // SCALE_TO_WIDTH
            5 -> yFactor // SCALE_TO_HEIGHT
            6 -> if (scaledWidth > boundsWidth || scaledHeight > boundsHeight) min(1f, min(xFactor, yFactor)) else 1f // MAX_ONLY
            else -> 1f
        }
        if (boundsType == 1) {
            scaleX = sign(scaleX).let { if (it == 0f) 1f else it } * boundsWidth / baseWidth.coerceAtLeast(1f)
            scaleY = sign(scaleY).let { if (it == 0f) 1f else it } * boundsHeight / baseHeight.coerceAtLeast(1f)
        } else {
            scaleX *= multiplier
            scaleY *= multiplier
        }

        if (cropToBounds && boundsType in setOf(3, 4, 5)) {
            val contentWidth = baseWidth * abs(scaleX)
            val contentHeight = baseHeight * abs(scaleY)
            if (contentWidth > boundsWidth + .1f) {
                val extraSourcePx = (contentWidth - boundsWidth) * inputWidth / (abs(scaleX) * rawWidth).coerceAtLeast(1f)
                val horizontal = splitOverdraw(extraSourcePx, boundsAlignment, horizontal = true)
                cropLeft += horizontal.first
                cropRight += horizontal.second
            }
            if (contentHeight > boundsHeight + .1f) {
                val extraSourcePx = (contentHeight - boundsHeight) * inputHeight / (abs(scaleY) * rawHeight).coerceAtLeast(1f)
                val vertical = splitOverdraw(extraSourcePx, boundsAlignment, horizontal = false)
                cropTop += vertical.first
                cropBottom += vertical.second
            }
            val maxHorizontal = (inputWidth - 2f).coerceAtLeast(0f)
            val maxVertical = (inputHeight - 2f).coerceAtLeast(0f)
            cropLeft = cropLeft.coerceAtMost(maxHorizontal)
            cropRight = cropRight.coerceAtMost((maxHorizontal - cropLeft).coerceAtLeast(0f))
            cropTop = cropTop.coerceAtMost(maxVertical)
            cropBottom = cropBottom.coerceAtMost((maxVertical - cropTop).coerceAtLeast(0f))
        }
    }

    val finalCrop = SourceCrop(cropLeft, cropTop, cropRight, cropBottom)
    val width = rawWidth * finalCrop.widthFraction(inputWidth)
    val height = rawHeight * finalCrop.heightFraction(inputHeight)
    val renderedWidth = width * abs(scaleX)
    val renderedHeight = height * abs(scaleY)
    val boundsActive = boundsType != 0
    val x = if (boundsActive) {
        positionX - boundsWidth * alignmentFactor(alignment, horizontal = true) +
            (boundsWidth - renderedWidth) * alignmentFactor(boundsAlignment, horizontal = true)
    } else positionX - renderedWidth * alignmentFactor(alignment, horizontal = true)
    val y = if (boundsActive) {
        positionY - boundsHeight * alignmentFactor(alignment, horizontal = false) +
            (boundsHeight - renderedHeight) * alignmentFactor(boundsAlignment, horizontal = false)
    } else positionY - renderedHeight * alignmentFactor(alignment, horizontal = false)
    return SourceTransformGeometry(
        x, y, positionX - x, positionY - y, positionX, positionY, width, height, scaleX, scaleY,
        transform.optDouble("rotation", transform.optDouble("rot", 0.0)).toFloat(),
        transform.optDouble("opacity", 1.0).toFloat(), finalCrop, inputWidth, inputHeight,
        transform.optBoolean("flipH", false), transform.optBoolean("flipV", false), !boundsActive
    )
}

internal fun alignmentFactor(alignment: Int, horizontal: Boolean): Float {
    val start = if (horizontal) 1 else 4
    val end = if (horizontal) 2 else 8
    return when {
        alignment and end != 0 -> 1f
        alignment and start != 0 -> 0f
        else -> .5f
    }
}

private fun splitOverdraw(pixels: Float, alignment: Int, horizontal: Boolean): Pair<Float, Float> {
    val startBit = if (horizontal) 1 else 4
    val endBit = if (horizontal) 2 else 8
    return when {
        alignment and startBit != 0 -> 0f to pixels
        alignment and endBit != 0 -> pixels to 0f
        else -> (pixels / 2f) to (pixels / 2f)
    }
}
