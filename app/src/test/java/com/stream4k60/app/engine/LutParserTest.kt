package com.stream4k60.app.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LutParserTest {
    private fun identityCube(size: Int, header: String = ""): String = buildString {
        appendLine("TITLE \"identity\"")
        appendLine("# comment")
        append(header)
        appendLine("LUT_3D_SIZE $size")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            appendLine("${r / d} ${g / d} ${b / d}")
        }
    }

    @Test
    fun parsesCubeRedFastest() {
        val lut = LutParser.parseCube(identityCube(2).lineSequence())
        assertEquals(2, lut.size)
        // Entry 1 is r=1,g=0,b=0; entry 2 is r=0,g=1,b=0.
        assertEquals(255, lut.rgb[3].toInt() and 0xFF)
        assertEquals(0, lut.rgb[4].toInt())
        assertEquals(255, lut.rgb[7].toInt() and 0xFF)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.domainMax, 0f)
    }

    @Test
    fun readsDomain() {
        val lut = LutParser.parseCube(identityCube(2, "DOMAIN_MIN 0.1 0.1 0.1\nDOMAIN_MAX 0.9 0.9 0.9\n").lineSequence())
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.1f), lut.domainMin, 1e-6f)
        assertArrayEquals(floatArrayOf(0.9f, 0.9f, 0.9f), lut.domainMax, 1e-6f)
    }

    @Test
    fun rejectsBadCubes() {
        assertThrows(IllegalArgumentException::class.java) { LutParser.parseCube("LUT_1D_SIZE 16".lineSequence()) }
        assertThrows(IllegalArgumentException::class.java) { LutParser.parseCube("LUT_3D_SIZE 2\n0 0 0".lineSequence()) }
        assertThrows(IllegalArgumentException::class.java) { LutParser.parseCube("0 0 0".lineSequence()) }
    }

    private fun identityImage(size: Int, tilesPerRow: Int): Triple<Int, Int, IntArray> {
        val rows = (size + tilesPerRow - 1) / tilesPerRow
        val w = size * tilesPerRow
        val h = size * rows
        val px = IntArray(w * h)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val x = (b % tilesPerRow) * size + r
            val y = (b / tilesPerRow) * size + g
            val scale = 255 / (size - 1)
            px[y * w + x] = (0xFF shl 24) or ((r * scale) shl 16) or ((g * scale) shl 8) or (b * scale)
        }
        return Triple(w, h, px)
    }

    @Test
    fun parsesSquareTiledImage() {
        val (w, h, px) = identityImage(4, 2) // 8x8 = 4^3 pixels in a 2x2 tile grid
        val lut = LutParser.parseImage(w, h, px)
        assertEquals(4, lut.size)
        val o = ((3 * 4 + 2) * 4 + 1) * 3 // b=3, g=2, r=1
        assertEquals(85, lut.rgb[o].toInt() and 0xFF)
        assertEquals(170, lut.rgb[o + 1].toInt() and 0xFF)
        assertEquals(255, lut.rgb[o + 2].toInt() and 0xFF)
    }

    @Test
    fun parsesStripImage() {
        val (w, h, px) = identityImage(4, 4) // 16x4 strip
        val lut = LutParser.parseImage(w, h, px)
        assertEquals(4, lut.size)
        val o = ((2 * 4 + 3) * 4 + 0) * 3 // b=2, g=3, r=0
        assertEquals(0, lut.rgb[o].toInt() and 0xFF)
        assertEquals(255, lut.rgb[o + 1].toInt() and 0xFF)
        assertEquals(170, lut.rgb[o + 2].toInt() and 0xFF)
    }

    @Test
    fun rejectsUnknownImageLayout() {
        assertThrows(IllegalArgumentException::class.java) { LutParser.parseImage(10, 7, IntArray(70)) }
    }
}
