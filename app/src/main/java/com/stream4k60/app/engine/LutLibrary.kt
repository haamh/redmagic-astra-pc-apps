package com.stream4k60.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads LUT files for LUT filter stages off the UI thread and hands them to the compositor.
 * The compositor keeps each loaded LUT keyed by its path, so unchanged LUTs are not reloaded.
 */
object LutLibrary {
    @Volatile private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Stream4k-LutLoader").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap<String, String>()
    // Last path that failed per slot, so a bad file is reported once instead of retried on every refresh.
    private val failed = ConcurrentHashMap<String, String>()

    fun initialize(context: Context) { appContext = context.applicationContext }

    /** [paths] holds the LUT file for each slot, in slot order (see VideoFilterChain.lutPaths). */
    fun sync(sourceId: String, paths: List<String>) {
        for (slot in 0 until VideoFilterChain.MAX_LUT_SLOTS) {
            val path = paths.getOrNull(slot).orEmpty()
            val flightKey = "$sourceId#$slot"
            if (path.isBlank()) {
                inFlight.remove(flightKey)
                failed.remove(flightKey)
                if (NativeEngine.getSourceLutKey(sourceId, slot).isNotEmpty()) NativeEngine.clearSourceLut(sourceId, slot)
                continue
            }
            if (NativeEngine.getSourceLutKey(sourceId, slot) == path || inFlight[flightKey] == path || failed[flightKey] == path) continue
            failed.remove(flightKey)
            inFlight[flightKey] = path
            executor.execute {
                val result = runCatching { load(path) }
                if (inFlight[flightKey] != path) return@execute // superseded while loading
                result.onSuccess { lut ->
                    NativeEngine.setSourceLut(sourceId, slot, path, lut.size, lut.rgb, lut.domainMin, lut.domainMax)
                }.onFailure { error ->
                    failed[flightKey] = path
                    SourceRuntimeErrors.report(sourceId, "LUT file could not be loaded: ${error.message ?: "unreadable file"}")
                }
                inFlight.remove(flightKey, path)
            }
        }
    }

    private fun load(path: String): LutData {
        val bytes = open(path).use { it.readBytes() }
        val isImage = bytes.size >= 4 && (
            (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) ||           // PNG
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()))                    // JPEG
        if (!isImage) return LutParser.parseCube(bytes.toString(Charsets.UTF_8).lineSequence())
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("The LUT image could not be decoded.")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return LutParser.parseImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(path: String): InputStream {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            val context = appContext ?: throw IllegalStateException("LUT loader is not initialized.")
            return context.contentResolver.openInputStream(Uri.parse(path)) ?: throw IllegalArgumentException("The LUT file is not accessible.")
        }
        val file = File(path)
        require(file.isFile) { "LUT file not found: ${file.name}" }
        return file.inputStream()
    }
}
