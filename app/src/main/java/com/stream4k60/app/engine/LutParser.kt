package com.stream4k60.app.engine

import kotlin.math.cbrt
import kotlin.math.roundToInt

/** A 3D color lookup table: size^3 RGB8 texels, red fastest, then green, then blue. */
class LutData(val size: Int, val rgb: ByteArray, val domainMin: FloatArray, val domainMax: FloatArray)

/** Parses the LUT formats OBS's "Apply LUT" filter accepts: Adobe/Resolve .cube and PNG LUT images. */
object LutParser {
    const val MAX_SIZE = 129

    fun parseCube(lines: Sequence<String>): LutData {
        var size = 0
        val min = floatArrayOf(0f, 0f, 0f)
        val max = floatArrayOf(1f, 1f, 1f)
        var rgb: ByteArray? = null
        var count = 0
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val keyword = parts[0].uppercase()
            when {
                keyword == "TITLE" -> Unit
                keyword == "LUT_1D_SIZE" -> throw IllegalArgumentException("1D .cube LUTs are not supported; export a 3D LUT.")
                keyword == "LUT_3D_SIZE" -> {
                    size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE.")
                    require(size in 2..MAX_SIZE) { "LUT_3D_SIZE $size is outside 2..$MAX_SIZE." }
                    rgb = ByteArray(size * size * size * 3)
                }
                keyword == "DOMAIN_MIN" -> readTriple(parts, min)
                keyword == "DOMAIN_MAX" -> readTriple(parts, max)
                keyword.first().isLetter() -> Unit // other metadata keywords
                else -> {
                    val data = rgb ?: throw IllegalArgumentException("LUT data appears before LUT_3D_SIZE.")
                    require(parts.size >= 3) { "Invalid LUT entry: '$line'." }
                    require(count < size * size * size) { "The .cube file has more entries than LUT_3D_SIZE allows." }
                    for (c in 0 until 3) {
                        val value = parts[c].toFloatOrNull() ?: throw IllegalArgumentException("Invalid LUT value '${parts[c]}'.")
                        data[count * 3 + c] = toByte(value)
                    }
                    count++
                }
            }
        }
        val data = rgb ?: throw IllegalArgumentException("Missing LUT_3D_SIZE; only 3D .cube LUTs are supported.")
        require(count == size * size * size) { "The .cube file has $count entries; expected ${size * size * size}." }
        require((0 until 3).all { max[it] > min[it] }) { "DOMAIN_MAX must be greater than DOMAIN_MIN." }
        return LutData(size, data, min, max)
    }

    /**
     * PNG LUT layouts: square tiles (e.g. 512×512 = 64 levels in an 8×8 grid of 64×64 tiles) and horizontal
     * strips (N² × N). Red increases along x within a tile, green along y, blue across tiles.
     */
    fun parseImage(width: Int, height: Int, argb: IntArray): LutData {
        require(argb.size >= width * height) { "Image data is smaller than its dimensions." }
        val size: Int
        val tilesPerRow: Int
        if (width == height) {
            size = cbrt((width.toDouble() * height)).roundToInt()
            require(size >= 2 && size * size * size == width * height && width % size == 0) {
                "Square LUT images must hold N³ pixels in N×N tiles (for example 512×512 for 64 levels)."
            }
            tilesPerRow = width / size
        } else {
            size = height
            require(size >= 2 && width == size * size) { "LUT strips must be N²×N pixels (for example 1024×32)." }
            tilesPerRow = size
        }
        require(size <= MAX_SIZE) { "LUT size $size exceeds $MAX_SIZE." }
        val rgb = ByteArray(size * size * size * 3)
        for (b in 0 until size) {
            val tileX = (b % tilesPerRow) * size
            val tileY = (b / tilesPerRow) * size
            for (g in 0 until size) for (r in 0 until size) {
                val pixel = argb[(tileY + g) * width + tileX + r]
                val o = ((b * size + g) * size + r) * 3
                rgb[o] = (pixel shr 16).toByte()
                rgb[o + 1] = (pixel shr 8).toByte()
                rgb[o + 2] = pixel.toByte()
            }
        }
        return LutData(size, rgb, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    private fun readTriple(parts: List<String>, out: FloatArray) {
        require(parts.size >= 4) { "Invalid ${parts[0]} line." }
        for (i in 0 until 3) out[i] = parts[i + 1].toFloatOrNull() ?: throw IllegalArgumentException("Invalid ${parts[0]} value.")
    }

    private fun toByte(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}
