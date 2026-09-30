package com.stream4k60.app.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ScreenshotController {
    @Volatile private var preview: SurfaceView? = null
    private val main = Handler(Looper.getMainLooper())

    fun register(view: SurfaceView) { preview = view }
    fun unregister(view: SurfaceView) { if (preview === view) preview = null }

    fun request(path: String) {
        val view = preview ?: error("No active program preview surface")
        val outputFile = File(path).apply { parentFile?.mkdirs() }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        main.post {
            val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(view, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        runCatching {
                            FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.onFailure { failure.set(it) }
                    } else failure.set(IllegalStateException("PixelCopy failed: $result"))
                    bitmap.recycle()
                    latch.countDown()
                }, main)
            } catch (t: Throwable) {
                bitmap.recycle()
                failure.set(t)
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Screenshot timed out" }
        failure.get()?.let { throw it }
    }
}
