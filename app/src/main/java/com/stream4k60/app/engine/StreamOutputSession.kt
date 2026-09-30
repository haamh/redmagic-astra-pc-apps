package com.stream4k60.app.engine

import android.content.Context
import android.media.MediaFormat
import android.view.Surface
import com.stream4k60.app.data.model.*
import java.util.concurrent.atomic.AtomicLong

/** One hardware encoder session. Exactly one stream target and/or one recorder can consume its encoded samples. */
class StreamOutputSession(private val context: Context){
    data class Stats(val bytesSent:Long=0, val encodedFrames:Long=0, val bitrate:Long=0, val droppedFrames:Long=0)
    @Volatile var stats=Stats(); private set
    private var encoder:HardwareVideoEncoder?=null
    private var audio:HardwareAudioEncoder?=null
    private var rtmp:RtmpPublisher?=null
    private var hls:YouTubeHlsPublisher?=null
    private var muxer:RecordingMuxer?=null
    private var videoConfig:RtmpPublisher.VideoCodecConfig?=null
    private var audioConfig:RtmpPublisher.AudioCodecConfig?=null
    private var lastVideoFormat:MediaFormat?=null
    private var lastAudioFormat:MediaFormat?=null
    private var replayEnabled=false
    private val bytes=AtomicLong(); private val frames=AtomicLong()

    suspend fun prepareAndStart(config:StreamConfig):Surface = prepare(config, null)

    suspend fun prepareAndStart(recording:RecordingConfig, audioDevice:android.media.AudioDeviceInfo?=null):Surface {
        val cfg=StreamConfig(
            outputWidth=recording.outputWidth,
            outputHeight=recording.outputHeight,
            fps=recording.fps,
            bitrate=recording.bitrate,
            outputCodec=recording.codec,
            audioDeviceIds=recording.audioDeviceIds,
            audioInputs=recording.audioInputs,
            monitorDeviceId=recording.monitorDeviceId,
            monitorEnabled=recording.monitorEnabled
        )
        return prepare(cfg, recording, audioDevice)
    }

    private suspend fun prepare(config:StreamConfig, recording:RecordingConfig?, audioDevice:android.media.AudioDeviceInfo?=null):Surface {
        require(encoder==null) { "Output session is already running" }
        val mime=when(config.outputCodec){OutputCodec.HEVC->"video/hevc";OutputCodec.AV1->"video/av01";else->"video/avc"}
        require(HardwareVideoEncoder.isSupported(mime)){"$mime encoder unavailable on this device"}
        require(config.outputWidth>0 && config.outputHeight>0 && config.fps>0)
        val bitrate=if(config.bitrate>0)config.bitrate else defaultBitrate(config.outputCodec,config.outputWidth,config.outputHeight,config.fps)
        encoder=HardwareVideoEncoder(HardwareVideoEncoder.Config(mime,config.outputWidth,config.outputHeight,config.fps,bitrate,config.keyframeInterval,android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,hdr=config.hdr),
            onSample={sample->
                frames.incrementAndGet(); bytes.addAndGet(sample.data.size.toLong())
                if (replayEnabled) ReplayBufferController.addVideo(sample, lastVideoFormat)
                when(config.protocol){
                    StreamProtocol.HLS -> hls?.video(sample)
                    StreamProtocol.RTMP, StreamProtocol.RTMPS -> if(rtmp!=null) rtmp?.sendVideo(sample,videoConfig)
                    else -> Unit
                }
                muxer?.writeVideo(sample)
            },
            onFormat={format->
                lastVideoFormat=format
                videoConfig=extractCodec(format, config.outputCodec)
                muxer?.setVideoFormat(format)
                if(config.protocol==StreamProtocol.HLS) hls?.setVideoCodecConfig(extractVideoCodecConfigBytes(format))
            })
        val surface=encoder!!.prepare()
        check(NativeEngine.setEncoderSurface(surface)) { "GPU compositor could not attach the hardware encoder surface" }
        if(recording!=null){
            muxer=RecordingMuxer(recording.outputPath)
        } else {
            when(config.protocol){
                StreamProtocol.HLS -> hls=YouTubeHlsPublisher(config.ingestionUrl).also{it.start(mime)}
                StreamProtocol.RTMP, StreamProtocol.RTMPS -> rtmp=RtmpPublisher{_,_->}.also{ok -> ok.configureReconnect(config.autoReconnect,config.reconnectDelayMs,config.maxReconnectAttempts);check(ok.start(config.ingestionUrl,config.streamName,config.protocol==StreamProtocol.RTMPS,config.outputWidth,config.outputHeight,config.fps,config.outputCodec)) { "RTMP(S) publisher could not start" }}
                else -> error("Unsupported output protocol: ${config.protocol}")
            }
        }
        encoder!!.start()
        val routes = if (config.audioInputs.isNotEmpty()) config.audioInputs else resolveAudioDeviceIds(config.audioDeviceIds, audioDevice).map { id -> com.stream4k60.app.data.model.AudioInputRoute("audio_device_$id", id) }
        if (routes.isNotEmpty() || config.audioPlaybackCaptureEnabled) {
            audio=HardwareAudioEncoder(context,onSample={sample->
                if (replayEnabled) ReplayBufferController.addAudio(sample, lastAudioFormat)
                when(config.protocol){
                    StreamProtocol.HLS -> hls?.audio(sample)
                    StreamProtocol.RTMP,StreamProtocol.RTMPS -> rtmp?.sendAudio(sample,audioConfig)
                    else -> Unit
                }
                muxer?.writeAudio(sample)
            },onFormat={format->lastAudioFormat=format;audioConfig=extractAac(format);muxer?.setAudioFormat(format)})
            runCatching {
                audio!!.start(
                    routes,
                    monitorDeviceId = config.monitorDeviceId,
                    monitorEnabled = config.monitorEnabled,
                    playbackCaptureEnabled = config.audioPlaybackCaptureEnabled
                )
            }.onFailure { stop(); throw it }
        }
        return surface
    }

    fun awaitPublisherReady(timeoutMs:Long):Boolean {
        if (rtmp != null) {
            val end=System.nanoTime()+timeoutMs*1_000_000L
            while(System.nanoTime()<end){if(rtmp?.isPublishing()==true)return true;Thread.sleep(20)}
            return false
        }
        return hls?.awaitFirstSegment(timeoutMs) ?: true
    }
    fun enableRecording(recording:RecordingConfig){
        check(encoder!=null){"Encoder is not running"};if(muxer!=null)return
        require(recording.outputPath.isNotBlank()){"Recording path is empty"}
        muxer=RecordingMuxer(recording.outputPath)
        lastVideoFormat?.let{muxer?.setVideoFormat(it)};lastAudioFormat?.let{muxer?.setAudioFormat(it)}
    }
    fun disableRecording(){muxer?.stop();muxer=null}
    fun pauseRecording(){muxer?.setPaused(true)}
    fun resumeRecording(){muxer?.setPaused(false)}
    fun startReplayBuffer(seconds:Int,sizeMb:Int){ReplayBufferController.start(seconds,sizeMb);replayEnabled=true}
    fun stopReplayBuffer(){replayEnabled=false;ReplayBufferController.stop()}
    fun updateAudioRoute(route: AudioInputRoute): Boolean = audio?.setInputConfig(route) ?: false
    fun audioPeak(sourceId: String): Float = audio?.peak(sourceId) ?: 0f
    fun stopStreamingOnly(){rtmp?.stop();rtmp=null;hls?.stop();hls=null}

    fun stop(){replayEnabled=false;runCatching{audio?.stop()};NativeEngine.setEncoderSurface(null);runCatching{encoder?.stop()};runCatching{rtmp?.stop()};runCatching{hls?.stop()};runCatching{muxer?.stop()};audio=null;encoder=null;rtmp=null;hls=null;muxer=null}
    fun requestKeyframe(){encoder?.requestKeyframe()}
    fun setBitrate(v:Int){encoder?.setBitrate(v)}
    private fun resolveAudioDeviceIds(ids:List<Int>,fallback:android.media.AudioDeviceInfo?):List<Int>{
        if(ids.isNotEmpty())return ids
        fallback?.let{return listOf(fallback.id)}
        return emptyList()
    }

    private fun extractVideoCodecConfigBytes(f:MediaFormat): ByteArray? {
        val a = f.getByteBuffer("csd-0")?.duplicate() ?: return null
        val out = ByteArray(a.remaining())
        a.get(out)
        return out
    }

    private fun extractCodec(f:MediaFormat, codec:OutputCodec):RtmpPublisher.VideoCodecConfig?{
        val b0=f.getByteBuffer("csd-0")?.duplicate() ?: return null
        val csd=ByteArray(b0.remaining()).also{b0.get(it)}
        return when(codec){
            OutputCodec.H264 -> {
                val b1=f.getByteBuffer("csd-1")?.duplicate() ?: return null
                val pps=ByteArray(b1.remaining()).also{b1.get(it)}
                RtmpPublisher.VideoCodecConfig(OutputCodec.H264,sps=csd,pps=pps)
            }
            OutputCodec.HEVC -> {
                val profile=runCatching{f.getInteger(MediaFormat.KEY_PROFILE)}.getOrDefault(1)
                val level=runCatching{f.getInteger(MediaFormat.KEY_LEVEL)}.getOrDefault(153).let{if(it in 30..255)it else 153}
                RtmpPublisher.VideoCodecConfig(OutputCodec.HEVC,hvcC=RtmpPublisher.HevcConfiguration.fromCsd(csd,profile,level))
            }
            else -> null
        }
    }
    private fun extractAac(f:MediaFormat):RtmpPublisher.AudioCodecConfig?{val b=f.getByteBuffer("csd-0")?.duplicate()?:return null;val x=ByteArray(b.remaining());b.get(x);return RtmpPublisher.AudioCodecConfig(x)}
    companion object{fun defaultBitrate(codec:OutputCodec,w:Int,h:Int,fps:Int)=when{w>=3840&&fps>=60&&codec==OutputCodec.HEVC->35_000_000;w>=3840&&fps>=60->40_000_000;w>=2560&&fps>=60&&codec==OutputCodec.HEVC->20_000_000;w>=2560&&fps>=60->24_000_000;h>=1080&&fps>=60&&codec==OutputCodec.HEVC->10_000_000;h>=1080&&fps>=60->12_000_000;else->6_000_000}}
}
