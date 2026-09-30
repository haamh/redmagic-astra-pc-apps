package com.stream4k60.app.engine

/**
 * UVC webcams usually send MJPEG frames without Huffman tables (DHT); the camera relies on the standard tables
 * from the JPEG spec (ITU T.81 Annex K.3). A regular JPEG decoder rejects such frames, so the standard tables
 * are inserted before the scan when a frame has none.
 */
object MjpegFrames {
    private fun hex(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** Complete DHT segment (marker FFC4, length 0x01A2) holding the four standard tables. */
    val STANDARD_DHT: ByteArray = hex("""
        FF C4 01 A2
        00 00 01 05 01 01 01 01 01 01 00 00 00 00 00 00 00 00 01 02 03 04 05 06 07 08 09 0A 0B
        01 00 03 01 01 01 01 01 01 01 01 01 00 00 00 00 00 00 01 02 03 04 05 06 07 08 09 0A 0B
        10 00 02 01 03 03 02 04 03 05 05 04 04 00 00 01 7D
        01 02 03 00 04 11 05 12 21 31 41 06 13 51 61 07 22 71 14 32 81 91 A1 08 23 42 B1 C1 15 52 D1 F0
        24 33 62 72 82 09 0A 16 17 18 19 1A 25 26 27 28 29 2A 34 35 36 37 38 39 3A 43 44 45 46 47 48 49
        4A 53 54 55 56 57 58 59 5A 63 64 65 66 67 68 69 6A 73 74 75 76 77 78 79 7A 83 84 85 86 87 88 89
        8A 92 93 94 95 96 97 98 99 9A A2 A3 A4 A5 A6 A7 A8 A9 AA B2 B3 B4 B5 B6 B7 B8 B9 BA C2 C3 C4 C5
        C6 C7 C8 C9 CA D2 D3 D4 D5 D6 D7 D8 D9 DA E1 E2 E3 E4 E5 E6 E7 E8 E9 EA F1 F2 F3 F4 F5 F6 F7 F8
        F9 FA
        11 00 02 01 02 04 04 03 04 07 05 04 04 00 01 02 77
        00 01 02 03 11 04 05 21 31 06 12 41 51 07 61 71 13 22 32 81 08 14 42 91 A1 B1 C1 09 23 33 52 F0
        15 62 72 D1 0A 16 24 34 E1 25 F1 17 18 19 1A 26 27 28 29 2A 35 36 37 38 39 3A 43 44 45 46 47 48
        49 4A 53 54 55 56 57 58 59 5A 63 64 65 66 67 68 69 6A 73 74 75 76 77 78 79 7A 82 83 84 85 86 87
        88 89 8A 92 93 94 95 96 97 98 99 9A A2 A3 A4 A5 A6 A7 A8 A9 AA B2 B3 B4 B5 B6 B7 B8 B9 BA C2 C3
        C4 C5 C6 C7 C8 C9 CA D2 D3 D4 D5 D6 D7 D8 D9 DA E2 E3 E4 E5 E6 E7 E8 E9 EA F2 F3 F4 F5 F6 F7 F8
        F9 FA
    """)

    /**
     * Returns [frame] unchanged when it already has a DHT segment (or is not a JPEG); otherwise a copy with the
     * standard tables inserted just before the start-of-scan marker.
     */
    fun withHuffmanTables(frame: ByteArray, length: Int = frame.size): ByteArray {
        if (length < 4 || frame[0] != 0xFF.toByte() || frame[1] != 0xD8.toByte()) return frame
        var i = 2
        while (i + 3 < length) {
            if (frame[i] != 0xFF.toByte()) return frame // corrupt header; let the decoder report it
            val marker = frame[i + 1].toInt() and 0xFF
            when {
                marker == 0xC4 -> return frame
                marker == 0xDA -> {
                    val out = ByteArray(length + STANDARD_DHT.size)
                    System.arraycopy(frame, 0, out, 0, i)
                    System.arraycopy(STANDARD_DHT, 0, out, i, STANDARD_DHT.size)
                    System.arraycopy(frame, i, out, i + STANDARD_DHT.size, length - i)
                    return out
                }
                marker == 0xFF -> { i++; continue } // fill byte
                marker in 0xD0..0xD9 || marker == 0x01 -> { i += 2; continue } // markers without a length
            }
            val segLen = ((frame[i + 2].toInt() and 0xFF) shl 8) or (frame[i + 3].toInt() and 0xFF)
            if (segLen < 2) return frame
            i += 2 + segLen
        }
        return frame
    }
}
