package com.stream4k60.app.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Thread-safe MP4 muxer. Starts with both A/V when available, otherwise falls back to video-only. */
class RecordingMuxer(private val outputPath:String){
    private data class Pending(val video:Boolean,val sample:HardwareVideoEncoder.Sample)
    private val lock=Any()
    private var muxer:MediaMuxer?=null
    private var videoTrack=-1
    private var audioTrack=-1
    private var started=false
    private var videoFormat:MediaFormat?=null
    private var audioFormat:MediaFormat?=null
    private var firstFormatAtMs=0L
    private var paused=false
    private val pending=ArrayDeque<Pending>()
    private var pendingBytes=0L

    fun setVideoFormat(format:MediaFormat){synchronized(lock){videoFormat=format;armStartLocked()}}
    fun setAudioFormat(format:MediaFormat){synchronized(lock){audioFormat=format;armStartLocked()}}

    fun writeVideo(s:HardwareVideoEncoder.Sample){if(s.codecConfig)return;synchronized(lock){if(paused)return;if(started)writeLocked(true,s) else enqueueLocked(true,s);armStartLocked()}}
    fun writeAudio(s:HardwareVideoEncoder.Sample){if(s.codecConfig)return;synchronized(lock){if(paused)return;if(started)writeLocked(false,s) else enqueueLocked(false,s);armStartLocked()}}
    fun setPaused(value:Boolean){synchronized(lock){paused=value}}

    private fun armStartLocked(){
        if(started || videoFormat==null) return
        if(firstFormatAtMs==0L) firstFormatAtMs=System.currentTimeMillis()
        val audioReady=audioFormat!=null
        val waitExpired=System.currentTimeMillis()-firstFormatAtMs>=1500L
        if(audioReady||waitExpired) startLocked()
    }

    private fun startLocked(){
        if(started||videoFormat==null)return
        val file=File(outputPath);file.parentFile?.mkdirs()
        muxer=MediaMuxer(outputPath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrack=muxer!!.addTrack(videoFormat!!)
        audioTrack=audioFormat?.let{muxer!!.addTrack(it)}?:-1
        muxer!!.start();started=true
        while(pending.isNotEmpty()){
            val p=pending.removeFirst();pendingBytes-=p.sample.data.size
            if(p.video||audioTrack>=0) writeLocked(p.video,p.sample)
        }
    }

    private fun enqueueLocked(video:Boolean,s:HardwareVideoEncoder.Sample){
        pending.addLast(Pending(video,s));pendingBytes+=s.data.size
        while(pending.size>512 || pendingBytes>64L*1024*1024){val p=pending.removeFirst();pendingBytes-=p.sample.data.size}
    }

    private fun writeLocked(video:Boolean,s:HardwareVideoEncoder.Sample){
        val track=if(video)videoTrack else audioTrack
        if(track<0)return
        val info=MediaCodec.BufferInfo().apply{offset=0;size=s.data.size;presentationTimeUs=s.ptsUs;flags=if(video&&s.keyframe)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0}
        muxer?.writeSampleData(track,ByteBuffer.wrap(s.data),info)
    }

    fun stop(){synchronized(lock){runCatching{if(!started)startLocked()};runCatching{if(started)muxer?.stop()};runCatching{muxer?.release()};muxer=null;started=false;videoTrack=-1;audioTrack=-1;videoFormat=null;audioFormat=null;pending.clear();pendingBytes=0;firstFormatAtMs=0;paused=false}}
}
