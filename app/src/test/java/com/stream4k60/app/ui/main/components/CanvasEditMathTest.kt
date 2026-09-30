package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEditMathTest {
    private val cw = 1920
    private val ch = 1080
    private fun image(transform: JSONObject) = SourceItem("s", "Image", "IMAGE", true, false,
        JSONObject().put("width", 1000).put("height", 500).toString(), transform.toString())
    private fun SourceItem.with(t: String) = copy(transformJson = t)
    private fun box(s: SourceItem) = itemBox(s, cw, ch)
    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.05f); assertEquals(expected.second, actual.second, 0.05f)
    }

    @Test
    fun cornerResizeKeepsAspectAndOppositeCorner() {
        val s = image(JSONObject().put("x", 100).put("y", 100))
        val before = box(s)
        val after = box(s.with(resizeItem(s, BoxHandle.BOTTOM_RIGHT, 200f, 10f, freeAspect = false, cw, ch)))
        assertEquals(1200f, after.width, 0.05f)
        assertEquals(600f, after.height, 0.05f)
        assertPoint(before.pointAt(0f, 0f), after.pointAt(0f, 0f))
    }

    @Test
    fun freeCornerAndEdgeResize() {
        val s = image(JSONObject().put("x", 100).put("y", 100))
        val free = box(s.with(resizeItem(s, BoxHandle.TOP_LEFT, -100f, 50f, freeAspect = true, cw, ch)))
        assertEquals(1100f, free.width, 0.05f); assertEquals(450f, free.height, 0.05f)
        assertPoint(box(s).pointAt(1f, 1f), free.pointAt(1f, 1f))
        val edge = box(s.with(resizeItem(s, BoxHandle.LEFT, 300f, 999f, freeAspect = false, cw, ch)))
        assertEquals(700f, edge.width, 0.05f); assertEquals(500f, edge.height, 0.05f)
        assertPoint(box(s).pointAt(1f, 0.5f), edge.pointAt(1f, 0.5f))
    }

    @Test
    fun rotatedResizeKeepsAnchor() {
        val s = image(JSONObject().put("x", 900).put("y", 500).put("rotation", 30))
        val before = box(s)
        val after = box(s.with(resizeItem(s, BoxHandle.RIGHT, 80f, 40f, freeAspect = false, cw, ch)))
        assertPoint(before.pointAt(0f, 0.5f), after.pointAt(0f, 0.5f))
        assertTrue(after.width > before.width)
    }

    @Test
    fun boundsItemsResizeTheirBounds() {
        // OBS "fit to screen": bounds box = canvas, scale inner, centered.
        val t = JSONObject().put("x", 0).put("y", 0).put("bounds_type", 2).put("bounds", JSONObject().put("x", 1920).put("y", 1080)).put("bounds_align", 0)
        val s = image(t)
        val before = box(s)
        assertTrue(before.boundsActive)
        assertEquals(1920f, before.width, 0.05f)
        val after = box(s.with(resizeItem(s, BoxHandle.BOTTOM_RIGHT, -960f, 0f, freeAspect = false, cw, ch)))
        assertEquals(960f, after.width, 0.05f); assertEquals(540f, after.height, 0.05f)
        assertPoint(before.pointAt(0f, 0f), after.pointAt(0f, 0f))
    }

    @Test
    fun cropHidesPixelsWithoutMovingTheRest() {
        val s = image(JSONObject().put("x", 100).put("y", 100).put("scaleX", 0.5).put("scaleY", 0.5))
        val before = box(s)
        val cropped = s.with(cropItem(s, BoxHandle.LEFT, 50f, 0f, cw, ch))
        val t = JSONObject(cropped.transformJson)
        assertEquals(100.0, t.getDouble("cropLeft"), 0.5) // 50 canvas px at 0.5 scale = 100 source px
        val after = box(cropped)
        assertEquals(before.width - 50f, after.width, 0.5f)
        assertPoint(before.pointAt(1f, 0.5f), after.pointAt(1f, 0.5f))
    }

    @Test
    fun rotateAndPinchKeepCenter() {
        val s = image(JSONObject().put("x", 100).put("y", 100))
        val center = box(s).center
        val rotated = box(s.with(rotateItem(s, 45f, cw, ch)))
        assertEquals(45f, rotated.rotation, 0.01f)
        assertPoint(center, rotated.center)
        val pinched = box(s.with(pinchItem(s, 2f, 0f, 10f, -20f, cw, ch)))
        assertEquals(2000f, pinched.width, 0.05f)
        assertPoint(center.first + 10f to center.second - 20f, pinched.center)
    }

    @Test
    fun rotationSnapping() {
        assertEquals(90f, snapRotation(88f, fineSnap = false), 0f)
        assertEquals(80f, snapRotation(80f, fineSnap = false), 0f)
        assertEquals(75f, snapRotation(80f, fineSnap = true), 0f)
        assertEquals(-170f, normalizeDegrees(190f), 0f)
    }

    @Test
    fun runtimeSizeIsUsedWhenNoSizeIsSet() {
        // An auto-sized OBS text item at scale 2 covers twice its measured text size.
        val text = SourceItem("t", "Title", "TEXT", true, false, "{}", JSONObject().put("scale", JSONObject().put("x", 2).put("y", 2)).toString())
        com.stream4k60.app.engine.SourceNativeSizes.report("t", 300, 80)
        val b = box(text)
        assertEquals(600f, b.width, 0.01f)
        assertEquals(160f, b.height, 0.01f)
    }
}
