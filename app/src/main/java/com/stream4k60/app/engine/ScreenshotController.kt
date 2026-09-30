package com.stream4k60.app.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ScreenshotController {
    @Volatile private var preview: TextureView? = null
    private val main = Handler(Looper.getMainLooper())

    fun register(view: TextureView) { preview = view }
    fun unregister(view: TextureView) { if (preview === view) preview = null }

    fun request(path: String) {
        val view = preview ?: error("No active program preview surface")
        val outputFile = File(path).apply { parentFile?.mkdirs() }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        main.post {
            try {
                val bitmap = view.bitmap ?: error("The preview has not drawn a frame yet")
                FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } catch (t: Throwable) {
                failure.set(t)
            }
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Screenshot timed out" }
        failure.get()?.let { throw it }
    }
}
