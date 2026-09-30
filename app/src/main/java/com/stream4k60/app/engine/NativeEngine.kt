package com.stream4k60.app.engine

import android.view.Surface

/** Native GPU compositor. Pixels remain on the GPU after a source reaches a SurfaceTexture. */
object NativeEngine {
    init { System.loadLibrary("stream4k60_engine") }

    external fun initializeRenderer(canvasWidth: Int, canvasHeight: Int, fps: Int): Boolean
    /** The EGL/shader error behind a failed [initializeRenderer]. */
    external fun getLastError(): String
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
    /** [types] holds one VideoFilterType.nativeId per stage; [params] holds VideoFilterChain.FLOATS_PER_STAGE floats per stage. */
    external fun setSourceFilterChain(sourceId: String, types: IntArray, params: FloatArray)
    fun setSourceEffectsFromConfig(sourceId: String, configJson: String) {
        applySourceFilterChain(sourceId, VideoFilterChain.read(configJson))
    }
    fun applySourceFilterChain(sourceId: String, stages: List<VideoFilterStage>) {
        val (types, params) = VideoFilterChain.pack(stages)
        setSourceFilterChain(sourceId, types, params)
        LutLibrary.sync(sourceId, VideoFilterChain.lutPaths(stages))
    }
    external fun setSourceLut(sourceId: String, slot: Int, key: String, size: Int, rgb: ByteArray, domainMin: FloatArray, domainMax: FloatArray)
    external fun clearSourceLut(sourceId: String, slot: Int)
    external fun getSourceLutKey(sourceId: String, slot: Int): String
    external fun updateSourceRgba(sourceId: String, rgba: ByteArray, width: Int, height: Int): Boolean
    external fun updateSourceYuvDirect(sourceId: String, frame: java.nio.ByteBuffer, width: Int, height: Int, pixelFormat: Int): Boolean
    external fun removeSourceLayer(sourceId: String)

    /** Offscreen canvas for a nested scene or group; sources owned by [key] draw into it. */
    external fun setSceneTarget(key: String, width: Int, height: Int)
    /** Frees scene canvases not listed in [keys]. */
    external fun retainSceneTargets(keys: Array<String>)
    /** Draws [sourceId] into the canvas [owner] ("" = program canvas). */
    external fun setSourceOwner(sourceId: String, owner: String)
    /** Makes [sourceId] a layer showing the scene canvas [key]. */
    external fun setSourceSceneRef(sourceId: String, key: String)
    external fun setTransition(type: Int, durationMs: Int)
    external fun setTransitionProgress(progress: Float)

    external fun getRenderTimeMs(): Float
    external fun getDroppedFrames(): Long
    external fun getTotalFrames(): Long
}
