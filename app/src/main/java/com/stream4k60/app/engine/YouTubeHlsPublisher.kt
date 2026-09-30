package com.stream4k60.app.engine

import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Asynchronous YouTube HTTPS HLS publisher. The encoder/compositor threads never perform network
 * I/O or wait for an HTTP upload. Segments are immutable byte arrays and uploads happen on a
 * dedicated worker; playlist updates are coalesced so a slow server cannot build stale work.
 */
class YouTubeHlsPublisher(private val ingestionTemplate:String){
    private data class Segment(val sequence:Long,val name:String,val duration:Double)
    private data class Upload(val file:String,val data:ByteArray,val type:String,val final:Boolean=false,val segment:Boolean=false)
    private val segments=ArrayDeque<Segment>()
    private val uploads=LinkedBlockingQueue<Upload>(24)
    private val firstSegment=CountDownLatch(1)
    private val workerRunning=AtomicBoolean(false)
    private val workerError=AtomicReference<Throwable?>(null)
    private val playlistPending=AtomicBoolean(false)
    private val latestPlaylist=AtomicReference<ByteArray?>(null)
    private var worker:Thread?=null
    private var sequence=0L
    private var muxer:MpegTsMuxer?=null
    private var segmentStartUs=Long.MIN_VALUE
    private var codec="video/hevc"
    private var codecConfig:ByteArray?=null
    private var started=false
    private var baseUrl=""
    private var segmentHasVideo=false
    private val pendingAudio=ArrayDeque<HardwareVideoEncoder.Sample>()

    fun start(videoCodec:String){
        require(ingestionTemplate.isNotBlank())
        codec=videoCodec
        baseUrl=normalizeTemplate(ingestionTemplate)
        muxer=MpegTsMuxer(videoCodec,videoCodecConfig=codecConfig)
        segmentStartUs=Long.MIN_VALUE
        segments.clear();sequence=0
        started=true;segmentHasVideo=false;pendingAudio.clear()
        workerError.set(null);latestPlaylist.set(null);playlistPending.set(false)
        workerRunning.set(true)
        worker=Thread(::uploadLoop,"Stream4k-HLS-Upload").apply{start()}
    }

    fun setVideoCodecConfig(config:ByteArray?) {
        codecConfig=config?.copyOf()
        muxer?.setVideoCodecConfig(codecConfig)
    }

    fun video(s:HardwareVideoEncoder.Sample){
        if(!started||workerError.get()!=null)return
        if(s.codecConfig){ if(s.data.isNotEmpty()) setVideoCodecConfig(s.data); return }
        if(!segmentHasVideo && !s.keyframe)return
        val m=muxer?:return
        if(segmentStartUs==Long.MIN_VALUE)segmentStartUs=s.ptsUs
        if(s.keyframe&&segmentHasVideo&&s.ptsUs-segmentStartUs>=1_500_000L){flush(s.ptsUs);segmentStartUs=s.ptsUs;segmentHasVideo=false}
        m.addVideo(s);segmentHasVideo=true
        while(pendingAudio.isNotEmpty())m.addAudio(pendingAudio.removeFirst())
    }

    fun audio(s:HardwareVideoEncoder.Sample){
        if(!started||workerError.get()!=null)return
        if(s.codecConfig)return
        if(!segmentHasVideo){while(pendingAudio.size>100)pendingAudio.removeFirst();pendingAudio.addLast(s)}
        else muxer?.addAudio(s)
    }

    fun stop(){
        if(!started)return
        val m=muxer
        if(m!=null&&m.bytes().isNotEmpty()&&segmentHasVideo){
            val end=if(segmentStartUs==Long.MIN_VALUE)2_000_000L else maxOf(segmentStartUs+500_000L,segmentStartUs+2_000_000L)
            flush(end)
        }
        val playlist=playlist(true).toByteArray()
        enqueuePlaylist(playlist, final=true)
        uploads.offer(Upload("__STOP__",ByteArray(0),"",true))
        runCatching{worker?.join(20_000)}
        workerRunning.set(false);worker=null;started=false;muxer=null;pendingAudio.clear()
        workerError.get()?.let{throw it}
    }

    private fun flush(endUs:Long){
        val m=muxer?:return
        val data=m.bytes();if(data.isEmpty()||!segmentHasVideo)return
        val dur=((endUs-segmentStartUs).coerceAtLeast(500_000L))/1_000_000.0
        val name="seg_${sequence}.ts"
        val seg=Segment(sequence++,name,dur)
        segments.addLast(seg);while(segments.size>5)segments.removeFirst()
        // A full segment must never be dropped or replaced: failing fast is preferable to blocking
        // the encoder thread and turning an HLS network problem into accumulating live latency.
        check(uploads.offer(Upload(name,data,"video/mp2t",segment=true))) { "YouTube HLS segment queue is full" }
        m.resetSegment();segmentHasVideo=false
        enqueuePlaylist(playlist(false).toByteArray())
    }

    private fun enqueuePlaylist(data:ByteArray, final:Boolean=false){
        latestPlaylist.set(data)
        if(playlistPending.compareAndSet(false,true)){
            // A marker causes the worker to upload whichever playlist is latest at dequeue time.
            if(!uploads.offer(Upload("__PLAYLIST__",ByteArray(0),"application/vnd.apple.mpegurl",final))) {
                playlistPending.set(false)
                if(final) error("YouTube HLS playlist queue is full")
            }
        }
    }

    private fun uploadLoop(){
        try{
            while(workerRunning.get()||uploads.isNotEmpty()){
                val job=uploads.poll(500,TimeUnit.MILLISECONDS)?:continue
                when(job.file){
                    "__STOP__" -> break
                    "__PLAYLIST__" -> {
                        playlistPending.set(false)
                        val data=latestPlaylist.getAndSet(null) ?: continue
                        upload("playlist.m3u8",data,job.type)
                        if(job.final) break
                    }
                    else -> {
                        upload(job.file,job.data,job.type)
                        if(job.segment){firstSegment.countDown();
                            // Upload the newest playlist after the segment is safely available.
                            val latest=latestPlaylist.getAndSet(null)
                            if(latest!=null) upload("playlist.m3u8",latest,"application/vnd.apple.mpegurl")
                            playlistPending.set(false)
                        }
                    }
                }
            }
        }catch(t:Throwable){workerError.set(t);firstSegment.countDown()}
    }

    private fun playlist(end:Boolean):String{val first=segments.firstOrNull()?.sequence?:sequence;return buildString{append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');for(s in segments)append("#EXTINF:").append(String.format(Locale.US,"%.3f",s.duration)).append(",\n").append(s.name).append('\n');if(end)append("#EXT-X-ENDLIST\n")}}
    fun awaitFirstSegment(timeoutMs:Long):Boolean=firstSegment.await(timeoutMs,TimeUnit.MILLISECONDS)&&workerError.get()==null

    private fun normalizeTemplate(url:String):String {
        val cleaned=url.trim()
        return when {
            cleaned.contains("file=") -> cleaned.substringBefore("file=")+"file={file}"
            cleaned.contains("?") -> cleaned+"&file={file}"
            else -> cleaned+"?file={file}"
        }
    }

    private fun upload(file:String,data:ByteArray,type:String){
        var last:Throwable?=null
        repeat(3){attempt->
            try{
                val u=baseUrl.replace("{file}",file)
                val c=URL(u).openConnection() as HttpURLConnection
                c.requestMethod="PUT";c.doOutput=true;c.useCaches=false
                c.connectTimeout=10_000;c.readTimeout=20_000
                c.setFixedLengthStreamingMode(data.size)
                c.setRequestProperty("Content-Type",type)
                c.setRequestProperty("Cache-Control","no-cache")
                c.setRequestProperty("User-Agent","Stream4k60/1.0 Android")
                c.outputStream.use{it.write(data)}
                val code=c.responseCode
                val err=runCatching{c.errorStream?.bufferedReader()?.use{it.readText()}}.getOrNull()
                c.disconnect()
                if(code !in 200..299)error("YouTube HLS upload failed: HTTP $code ${err.orEmpty()}")
                return
            }catch(t:Throwable){last=t;if(attempt<2)Thread.sleep(250L*(attempt+1))}
        }
        throw last?:IllegalStateException("HLS upload failed")
    }
}
