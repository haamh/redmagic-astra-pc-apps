package com.stream4k60.app.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.content.ContextCompat
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.engine.NativeAudioMixer
import com.stream4k60.app.engine.NativeEngine
import dagger.hilt.android.AndroidEntryPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Owns the single MediaProjection grant and fans it out to real screen and playback-audio
 * sources. Keeping both consumers in one service avoids duplicate projection grants and keeps
 * the capture lifetime independent of the Compose Activity.
 */
@AndroidEntryPoint
class ProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private val running = AtomicBoolean(false)
    private val screens = LinkedHashMap<String, ScreenTarget>()
    private var projectionDisplay: android.hardware.display.VirtualDisplay? = null
    private var projectionDensityDpi = 0
    private var playbackRecorder: AudioRecord? = null
    private var playbackThread: Thread? = null
    @Volatile private var playbackEnabled = false
    @Volatile private var playbackSourceId: String? = null

    private data class ScreenTarget(
        val sourceId: String,
        val surface: Surface,
        var width: Int,
        var height: Int
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        val result = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val initialScreens = intent?.getStringArrayListExtra(EXTRA_SCREENS) ?: arrayListOf()
        val audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val audioSourceId = intent?.getStringExtra(EXTRA_AUDIO_SOURCE_ID)
        ensureForeground()
        if (projection == null) {
            if (result != Activity.RESULT_OK || data == null) return START_NOT_STICKY
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(result, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    resizeCapturedContent(width, height)
                }

                override fun onStop() {
                    val stoppedScreens = screens.keys.toList()
                    val stoppedPlaybackId = playbackSourceId.takeIf { playbackEnabled }
                    stopAll(false)
                    stoppedScreens.forEach { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "Android stopped the screen-capture session. Grant capture permission again to resume.") }
                    stoppedPlaybackId?.let { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "Android stopped playback capture. Grant capture permission again to resume.") }
                }
            }, null)
        }
        running.set(true)
        active = true
        reconfigure(initialScreens, audio, audioSourceId)
        return START_STICKY
    }

    private fun ensureForeground() {
        val n = ServiceNotifications.notification(
            this,
            ServiceNotifications.SCREEN_CHANNEL,
            ServiceNotifications.SCREEN_ID,
            "Capture sources",
            "Screen and Android playback sources are active"
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ServiceNotifications.SCREEN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(ServiceNotifications.SCREEN_ID, n)
    }

    private fun reconfigure(sourceIds: List<String>, audio: Boolean, audioSourceId: String?) {
        val selectedScreenId = sourceIds.firstOrNull()
        screens.keys.filter { it != selectedScreenId }.toList().forEach { removeScreen(it) }
        selectedScreenId?.let { if (!screens.containsKey(it)) addScreen(it) }
        sourceIds.drop(1).forEach { id ->
            com.stream4k60.app.engine.SourceRuntimeErrors.report(
                id,
                "Android allows one screen/app-window capture output per consent session. Use one visible Screen Capture source at a time."
            )
        }
        if (audio && !playbackEnabled) startPlayback(audioSourceId)
        if (!audio && playbackEnabled) stopPlayback()
        playbackSourceId = audioSourceId.takeIf { audio }
        if (screens.isEmpty() && !playbackEnabled) stopAll()
    }

    private fun addScreen(sourceId: String) {
        val p = projection ?: run { com.stream4k60.app.engine.SourceRuntimeErrors.report(sourceId, "Android screen-capture permission is not active."); return }
        val surface = NativeEngine.createSourceSurface(sourceId) ?: run { com.stream4k60.app.engine.SourceRuntimeErrors.report(sourceId, "Could not create the screen-capture compositor surface."); return }
        runCatching {
            val displayInfo = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = displayInfo?.mode
            val width = mode?.physicalWidth?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val height = mode?.physicalHeight?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val density = resources.configuration.densityDpi.coerceAtLeast(1)
            NativeEngine.setSourceBufferSize(sourceId, width, height)
            if (projectionDisplay == null) {
                projectionDensityDpi = density
                projectionDisplay = p.createVirtualDisplay(
                    "Stream4k60-$sourceId",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                ) ?: error("Android did not create a virtual display")
            } else {
                projectionDisplay?.resize(width, height, density)
                projectionDisplay?.setSurface(surface)
            }
            screens[sourceId] = ScreenTarget(sourceId, surface, width, height)
            com.stream4k60.app.engine.SourceRuntimeErrors.clear(sourceId)
        }.onFailure {
            surface.release()
            NativeEngine.releaseSourceSurface(sourceId)
            com.stream4k60.app.engine.SourceRuntimeErrors.report(sourceId, "Android could not start screen capture: ${it.message ?: "virtual display error"}.")
        }
    }

    private fun removeScreen(sourceId: String) {
        val target = screens.remove(sourceId) ?: return
        projectionDisplay?.setSurface(null)
        target.surface.release()
        NativeEngine.releaseSourceSurface(sourceId)
        com.stream4k60.app.engine.SourceRuntimeErrors.clear(sourceId)
    }

    private fun resizeCapturedContent(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || width <= 0 || height <= 0) return
        val target = screens.values.firstOrNull() ?: return
        if (target.width == width && target.height == height) return
        target.width = width
        target.height = height
        NativeEngine.setSourceBufferSize(target.sourceId, width, height)
        projectionDisplay?.resize(width, height, projectionDensityDpi.coerceAtLeast(1))
    }

    private fun startPlayback(sourceId: String?) {
        val handle = waitForMixerHandle() ?: run { sourceId?.let { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "The audio mixer is not ready for Android playback capture.") }; return }
        // We already own the live MediaProjection; AudioRecord consumes the same token.
        val p = projection ?: run { sourceId?.let { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "Android screen-capture permission is not active.") }; return }
        runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(min, 48_000 * 2 * 4 / 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            check(r.state == AudioRecord.STATE_INITIALIZED) { "Android playback capture unavailable" }
            playbackRecorder = r
            r.startRecording()
            playbackEnabled = true
            playbackThread = Thread({ playbackLoop(handle, r) }, "Stream4k-PlaybackAudio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            sourceId?.let(com.stream4k60.app.engine.SourceRuntimeErrors::clear)
        }.onFailure {
            playbackEnabled = false
            playbackRecorder?.let { runCatching { it.release() } }
            playbackRecorder = null
            sourceId?.let { id -> com.stream4k60.app.engine.SourceRuntimeErrors.report(id, "Android playback capture could not start: ${it.message ?: "device or application policy restriction"}.") }
        }
    }

    private fun waitForMixerHandle(): Long? {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val h = NativeAudioBridge.handle()
            if (h != 0L) return h
            Thread.sleep(10)
        }
        return null
    }

    private fun playbackLoop(handle: Long, r: AudioRecord) {
        val blockFrames = 480
        val pcm = ByteBuffer.allocateDirect(blockFrames * 2 * 4).order(ByteOrder.nativeOrder())
        val timestamp = android.media.AudioTimestamp()
        while (running.get() && playbackEnabled && NativeAudioBridge.handle() == handle && !Thread.currentThread().isInterrupted) {
            pcm.clear()
            val bytes = r.read(pcm, pcm.capacity(), AudioRecord.READ_BLOCKING)
            if (bytes <= 0) {
                if (bytes == 0) continue
                val screenIds = screens.keys.toList()
                val playbackId = playbackSourceId
                stopAll()
                screenIds.forEach { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "Android playback audio capture stopped (${bytes}). Request capture permission again.") }
                playbackId?.let { com.stream4k60.app.engine.SourceRuntimeErrors.report(it, "Android playback capture stopped (${bytes}). Request capture permission again.") }
                return
            }
            val frames = bytes / (2 * 4)
            val ptsUs = if (r.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                timestamp.nanoTime / 1_000L - frames * 1_000_000L / 48_000L
            } else System.nanoTime() / 1_000L - frames * 1_000_000L / 48_000L
            NativeAudioMixer.pushExternalPcm(handle, NativeAudioBridge.PLAYBACK_SOURCE_ID, pcm, frames, 2, 48_000, ptsUs)
        }
    }

    private fun stopPlayback() {
        playbackEnabled = false
        val worker = playbackThread
        worker?.interrupt()
        if (worker !== Thread.currentThread()) runCatching { worker?.join(500) }
        playbackThread = null
        runCatching { playbackRecorder?.stop() }
        playbackRecorder?.release()
        playbackRecorder = null
        playbackSourceId?.let(com.stream4k60.app.engine.SourceRuntimeErrors::clear)
    }

    private fun stopAll(stopService: Boolean = true) {
        running.set(false)
        active = false
        screens.keys.toList().forEach(::removeScreen)
        projectionDisplay?.release()
        projectionDisplay = null
        projectionDensityDpi = 0
        stopPlayback()
        val oldProjection = projection
        projection = null
        playbackSourceId = null
        oldProjection?.stop()
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { stopAll(false); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var active = false
        fun isActive(): Boolean = active
        const val ACTION_SYNC = "com.stream4k60.SYNC_PROJECTION"
        const val ACTION_STOP = "com.stream4k60.STOP_PROJECTION"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_SCREENS = "screens"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_AUDIO_SOURCE_ID = "audio_source_id"

        fun start(context: Context, result: Int, data: Intent, screenIds: List<String>, audio: Boolean, audioSourceId: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProjectionCaptureService::class.java).apply {
                    action = ACTION_SYNC
                    putExtra(EXTRA_RESULT, result)
                    putExtra(EXTRA_DATA, data)
                    putStringArrayListExtra(EXTRA_SCREENS, ArrayList(screenIds))
                    putExtra(EXTRA_AUDIO, audio)
                    putExtra(EXTRA_AUDIO_SOURCE_ID, audioSourceId)
                }
            )
        }

        fun sync(context: Context, screenIds: List<String>, audio: Boolean, audioSourceId: String?) {
            context.startService(
                Intent(context, ProjectionCaptureService::class.java).apply {
                    action = ACTION_SYNC
                    putStringArrayListExtra(EXTRA_SCREENS, ArrayList(screenIds))
                    putExtra(EXTRA_AUDIO, audio)
                    putExtra(EXTRA_AUDIO_SOURCE_ID, audioSourceId)
                }
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionCaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
