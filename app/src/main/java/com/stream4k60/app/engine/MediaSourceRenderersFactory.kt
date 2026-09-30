package com.stream4k60.app.engine

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

class MediaSourceRenderersFactory(
    context: Context,
    private val audioProcessor: MediaAudioProcessor,
    decoderPreference: String = "hardware"
) : DefaultRenderersFactory(context) {
    init {
        if (!decoderPreference.equals("automatic", true)) {
            setMediaCodecSelector(MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                val preferHardware = decoderPreference.equals("hardware", true)
                codecs.sortedByDescending { it.hardwareAccelerated == preferHardware }
            })
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(audioProcessor))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
