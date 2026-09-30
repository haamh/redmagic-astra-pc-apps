package com.stream4k60.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.engine.NativeAudioMixer
import dagger.hilt.android.AndroidEntryPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

@AndroidEntryPoint
class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val result = intent?.getIntExtra(EXTRA_RESULT, -1) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        val notification = ServiceNotifications.notification(
            this,
            ServiceNotifications.AUDIO_CHANNEL,
            ServiceNotifications.AUDIO_ID,
            "Playback audio capture",
            "Android playback audio is a live source"
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ServiceNotifications.AUDIO_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(ServiceNotifications.AUDIO_ID, notification)
        runCatching { startCapture(result, data) }.onFailure { stopCapture() }
        return START_STICKY
    }

    private fun startCapture(result: Int, data: Intent) {
        stopCapture(false)
        val handle = NativeAudioBridge.handle()
        check(handle != 0L) { "The native audio mixer is not running" }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(result, data)
        check(projection != null) { "MediaProjection was not granted" }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(min, 48_000 * 2 * 4 / 5))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "Android playback capture could not be initialized" }
        recorder!!.startRecording()
        running = true
        worker = Thread({ captureLoop(handle) }, "Stream4k-PlaybackAudio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun captureLoop(handle: Long) {
        val blockFrames = 480
        val pcm = ByteBuffer.allocateDirect(blockFrames * 2 * 4).order(ByteOrder.nativeOrder())
        val timestamp = android.media.AudioTimestamp()
        while (running && NativeAudioBridge.handle() == handle && !Thread.currentThread().isInterrupted) {
            pcm.clear()
            val bytes = recorder?.read(pcm, pcm.capacity(), AudioRecord.READ_BLOCKING) ?: break
            if (bytes <= 0) continue
            val frames = bytes / (2 * 4)
            val ptsUs = if (recorder?.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                timestamp.nanoTime / 1_000L - frames * 1_000_000L / 48_000L
            } else {
                System.nanoTime() / 1_000L - frames * 1_000_000L / 48_000L
            }
            NativeAudioMixer.pushExternalPcm(
                handle,
                NativeAudioBridge.PLAYBACK_SOURCE_ID,
                pcm,
                frames,
                2,
                48_000,
                ptsUs
            )
        }
    }

    private fun stopCapture(stopService: Boolean = true) {
        running = false
        worker?.interrupt()
        runCatching { worker?.join(500) }
        worker = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopCapture(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.stream4k60.START_PLAYBACK_AUDIO"
        const val ACTION_STOP = "com.stream4k60.STOP_PLAYBACK_AUDIO"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"

        fun start(context: Context, result: Int, data: Intent) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT, result)
                    putExtra(EXTRA_DATA, data)
                }
            )
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }
}
