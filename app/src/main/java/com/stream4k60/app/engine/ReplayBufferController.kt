package com.stream4k60.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.max

/** Encoded-packet replay buffer. Saves only from a video keyframe so the MP4 is decodable. */
object ReplayBufferController{
    data class Packet(val video:Boolean,val sample:HardwareVideoEncoder.Sample)
    private var maxUs=30_000_000L
    private var maxBytes=256L*1024*1024
    private val q=ArrayDeque<Packet>()
    private var bytes=0L
    private var videoFormat:MediaFormat?=null
    private var audioFormat:MediaFormat?=null
    @Volatile private var active=false

    fun start(seconds:Int,sizeMb:Int){maxUs=max(1,seconds)*1_000_000L;maxBytes=max(32,sizeMb)*1024L*1024L;q.clear();bytes=0;active=true}
    fun stop(){active=false;q.clear();bytes=0}
    fun isActive()=active
    fun addVideo(s:HardwareVideoEncoder.Sample,f:MediaFormat?){if(!active||s.codecConfig)return;if(f!=null)videoFormat=f;add(Packet(true,s))}
    fun addAudio(s:HardwareVideoEncoder.Sample,f:MediaFormat?){if(!active||s.codecConfig)return;if(f!=null)audioFormat=f;add(Packet(false,s))}
    private fun add(p:Packet){q.addLast(p);bytes+=p.sample.data.size;val now=p.sample.ptsUs;while(q.isNotEmpty()&&(now-q.first.sample.ptsUs>maxUs||bytes>maxBytes)){bytes-=q.removeFirst().sample.data.size}}

    fun save(context:Context):File?{
        val vf=videoFormat?:return null
        val first=q.firstOrNull{it.video&&it.sample.keyframe}?:return null
        val startPts=first.sample.ptsUs
        val af=audioFormat
        val out=File(context.getExternalFilesDir("recordings"),"replay_${System.currentTimeMillis()}.mp4");out.parentFile?.mkdirs()
        val m=MediaMuxer(out.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt=m.addTrack(vf);val at=af?.let{m.addTrack(it)}?:-1;m.start()
        var lastVideo=-1L;var lastAudio=-1L
        try{
            q.filter{(!it.video && at>=0 || it.video) && it.sample.ptsUs>=startPts}.forEach{
                val pts=max(0L,it.sample.ptsUs-startPts)
                val norm=if(it.video){max(lastVideo+1,pts).also{lastVideo=it}} else {max(lastAudio+1,pts).also{lastAudio=it}}
                val info=MediaCodec.BufferInfo().apply{offset=0;size=it.sample.data.size;presentationTimeUs=norm;flags=if(it.video&&it.sample.keyframe)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0}
                m.writeSampleData(if(it.video)vt else at,ByteBuffer.wrap(it.sample.data),info)
            }
        }finally{runCatching{m.stop()};m.release()}
        return out
    }
}
