package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MjpegFramesTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    // SOI, a tiny DQT-like segment, then SOS and some scan bytes, EOI. No DHT, like a webcam frame.
    private val noTables = b(0xFF, 0xD8, 0xFF, 0xDB, 0x00, 0x04, 0x01, 0x02, 0xFF, 0xDA, 0x00, 0x02, 0x11, 0x22, 0xFF, 0xD9)

    @Test fun standardTableSegmentHasSpecLength() {
        assertEquals(2 + 0x01A2, MjpegFrames.STANDARD_DHT.size)
    }

    @Test fun tablesAreInsertedRightBeforeTheScan() {
        val fixed = MjpegFrames.withHuffmanTables(noTables)
        assertEquals(noTables.size + MjpegFrames.STANDARD_DHT.size, fixed.size)
        assertEquals(0xC4, fixed[9].toInt() and 0xFF)
        assertEquals(0xDA, fixed[8 + MjpegFrames.STANDARD_DHT.size + 1].toInt() and 0xFF)
    }

    @Test fun framesThatHaveTablesAreUntouched() {
        val withDht = b(0xFF, 0xD8, 0xFF, 0xC4, 0x00, 0x02, 0xFF, 0xDA, 0x00, 0x02, 0xFF, 0xD9)
        assertSame(withDht, MjpegFrames.withHuffmanTables(withDht))
    }
}
