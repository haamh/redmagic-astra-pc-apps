package com.stream4k60.app.engine

import android.graphics.Color
import android.view.Surface
import org.json.JSONObject

/** Native GPU compositor. Pixels remain on the GPU after a source reaches a SurfaceTexture. */
object NativeEngine {
    init { System.loadLibrary("stream4k60_engine") }

    external fun initializeRenderer(canvasWidth: Int, canvasHeight: Int, fps: Int): Boolean
    external fun shutdownRenderer()
    external fun startRenderer(): Boolean
    external fun stopRenderer()
    external fun setCanvasSize(width: Int, height: Int)
    external fun setVideoSettings(canvasWidth: Int, canvasHeight: Int, fps: Int)
    external fun setPreviewSurface(surface: Surface?): Boolean
    external fun setEncoderSurface(surface: Surface?): Boolean
    external fun createSourceSurface(sourceId: String): Surface?
    external fun releaseSourceSurface(sourceId: String)
    external fun setSourceBufferSize(sourceId: String, width: Int, height: Int)
    external fun setSourceTextureParameters(
        sourceId: String,
        x: Float, y: Float, width: Float, height: Float, pivotX: Float, pivotY: Float,
        rotation: Float, scaleX: Float, scaleY: Float,
        opacity: Float, cropLeft: Float, cropTop: Float,
        cropRight: Float, cropBottom: Float,
        visible: Boolean, zOrder: Int, flipH: Boolean, flipV: Boolean
    )
    external fun setSourceEffects(
        sourceId: String, brightness: Float, contrast: Float, saturation: Float, gamma: Float, hueDegrees: Float,
        chromaKeyEnabled: Boolean, chromaR: Float, chromaG: Float, chromaB: Float, chromaSimilarity: Float, chromaSmoothness: Float
    )
    fun setSourceEffectsFromConfig(sourceId: String, configJson: String) {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val settings = root.optJSONObject("settings") ?: root
        val effects = settings.optJSONObject("effects") ?: root.optJSONObject("effects") ?: JSONObject()
        val keyColor = runCatching { Color.parseColor(effects.optString("chromaKeyColor", "#FF00FF00")) }
            .getOrDefault(Color.GREEN)
        setSourceEffects(
            sourceId,
            effects.optDouble("brightness", 0.0).toFloat(),
            effects.optDouble("contrast", 1.0).toFloat(),
            effects.optDouble("saturation", 1.0).toFloat(),
            effects.optDouble("gamma", 1.0).toFloat(),
            effects.optDouble("hueDegrees", 0.0).toFloat(),
            effects.optBoolean("chromaKeyEnabled", false),
            Color.red(keyColor) / 255f,
            Color.green(keyColor) / 255f,
            Color.blue(keyColor) / 255f,
            effects.optDouble("chromaSimilarity", 0.35).toFloat(),
            effects.optDouble("chromaSmoothness", 0.08).toFloat()
        )
    }
    external fun updateSourceRgba(sourceId: String, rgba: ByteArray, width: Int, height: Int): Boolean
    external fun updateSourceYuvDirect(sourceId: String, frame: java.nio.ByteBuffer, width: Int, height: Int, pixelFormat: Int): Boolean
    external fun removeSourceLayer(sourceId: String)
    external fun setTransition(type: Int, durationMs: Int)
    external fun setTransitionProgress(progress: Float)

    external fun getRenderTimeMs(): Float
    external fun getDroppedFrames(): Long
    external fun getTotalFrames(): Long
}
