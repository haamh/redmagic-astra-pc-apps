package com.stream4k60.app.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
import com.stream4k60.app.data.model.OutputCodec
import java.util.concurrent.atomic.AtomicBoolean

/** Native RTMP/RTMPS publisher with legacy AVC and Enhanced-RTMP HEVC support. */
class RtmpPublisher(private val onState:(State,String)->Unit={_,_->}){
    enum class State{IDLE,CONNECTING,CONNECTED,PUBLISHING,STOPPING,ERROR}
    data class VideoCodecConfig(
        val codec: OutputCodec,
        val sps: ByteArray? = null,
        val pps: ByteArray? = null,
        val hvcC: ByteArray? = null
    )
    data class AudioCodecConfig(val asc:ByteArray)

    @Volatile private var state=State.IDLE
    private var socket:Socket?=null
    private var videoWidth=3840
    private var videoHeight=2160
    private var videoFps=60;private var input:BufferedInputStream?=null;private var output:BufferedOutputStream?=null
    private var streamId=1;private var outChunkSize=4096;private var inChunkSize=128;private var timestampBase=Long.MIN_VALUE
    private val bytesSent=AtomicLong();private val lock=Any();private var lastAck=0L;private var ackWindow=0L
    private val inStates=HashMap<Int,ChunkState>()
    private data class Outbound(val video:Boolean,val sample:HardwareVideoEncoder.Sample,val v:VideoCodecConfig?,val a:AudioCodecConfig?)
    private val outbound=ArrayBlockingQueue<Outbound>(256)
    @Volatile private var writerRunning=false
    private val reconnecting=AtomicBoolean(false)
    private var writerThread:Thread?=null
    private var reconnectThread:Thread?=null
    private var reconnectUrl=""
    private var reconnectKey=""
    private var reconnectTls=false
    private var reconnectTimeoutMs=20_000L
    private var reconnectWidth=3840
    private var reconnectHeight=2160
    private var reconnectFps=60
    private var reconnectCodec=OutputCodec.H264
    private var videoCodec=OutputCodec.H264

    fun start(url:String,streamKey:String,tls:Boolean,width:Int=3840,height:Int=2160,fps:Int=60,codec:OutputCodec=OutputCodec.H264,timeoutMs:Long=20_000):Boolean {
        require(codec == OutputCodec.H264 || codec == OutputCodec.HEVC) { "RTMP/RTMPS currently supports H.264 and Enhanced-RTMP HEVC" }
        videoWidth=width; videoHeight=height; videoFps=fps; videoCodec=codec
        reconnectUrl=url; reconnectKey=streamKey; reconnectTls=tls; reconnectTimeoutMs=timeoutMs
        reconnectWidth=width; reconnectHeight=height; reconnectFps=fps; reconnectCodec=codec
        if(state!=State.IDLE)return state==State.PUBLISHING
        reconnecting.set(false)
        return runCatching{connect(url,streamKey,tls,timeoutMs);true}.onFailure{state=State.ERROR;onState(state,it.message?:"RTMP connect failed")}.getOrDefault(false)
    }

    fun stop(){
        synchronized(lock){
            if(state==State.IDLE)return
            state=State.STOPPING
            onState(state,"Stopping")
            reconnecting.set(false)
            writerRunning=false
        }
        runCatching{writerThread?.join(1500)}
        writerThread=null
        runCatching{reconnectThread?.join(1500)}
        reconnectThread=null
        outbound.clear()
        synchronized(lock){
            runCatching{socket?.close()}
            socket=null;input=null;output=null
            state=State.IDLE
            onState(state,"Stopped")
        }
    }

    fun isPublishing()=state==State.PUBLISHING

    fun sendVideo(sample:HardwareVideoEncoder.Sample,config:VideoCodecConfig?) {
        if(state!=State.PUBLISHING)return
        if(sample.codecConfig&&config==null)return
        enqueue(Outbound(true,sample,config,null))
    }
    fun sendAudio(sample:HardwareVideoEncoder.Sample,config:AudioCodecConfig?) {
        if(state!=State.PUBLISHING)return
        if(sample.codecConfig&&config==null)return
        enqueue(Outbound(false,sample,null,config))
    }

    private fun enqueue(item:Outbound) {
        if(outbound.offer(item)) return
        // Never back-pressure MediaCodec. Discard stale non-key video first; if the queue is still
        // saturated, discard the oldest audio packet or, as a last resort, the newest non-key video.
        val it=outbound.iterator()
        while(it.hasNext()){
            val old=it.next()
            if(old.video && !old.sample.keyframe && !old.sample.codecConfig){ it.remove(); return@enqueue }
        }
        if(!item.video){ outbound.poll(); outbound.offer(item) }
        else if(!item.sample.keyframe && !item.sample.codecConfig){ return }
        else { outbound.poll(); outbound.offer(item) }
    }

    private fun connect(url:String,key:String,tls:Boolean,timeoutMs:Long){
        state=State.CONNECTING;onState(state,"Connecting to ingest server")
        val uri=URI(if(url.startsWith("rtmps://")||url.startsWith("rtmp://"))url else error("Invalid RTMP(S) URL"))
        val host=uri.host?:error("Invalid RTMP host");val port=if(uri.port>0)uri.port else if(uri.scheme.equals("rtmps",true)||tls)443 else 1935
        val s=if(uri.scheme.equals("rtmps",true)||tls){
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket().also{it.connect(InetSocketAddress(host,port),timeoutMs.toInt().coerceAtLeast(1000));(it as SSLSocket).soTimeout=timeoutMs.toInt().coerceAtLeast(1000);it.startHandshake()}
        }else Socket().also{it.soTimeout=timeoutMs.toInt().coerceAtLeast(1000);it.connect(InetSocketAddress(host,port),timeoutMs.toInt().coerceAtLeast(1000))}
        socket=s;input=BufferedInputStream(s.getInputStream(),64*1024);output=BufferedOutputStream(s.getOutputStream(),64*1024)
        handshake()
        writeMessage(1,2,0,byteArrayOf(0,0x00,0x10,0x00)) // 4096 chunk size
        outChunkSize=4096
        val app=uri.path.trim('/').substringBefore('/').ifBlank{"live2"};val tcUrl="${if(uri.scheme.equals("rtmps",true)||tls)"rtmps" else "rtmp"}://$host:$port/$app"
        val props=linkedMapOf<String,Any?>(
            "app" to app,
            "type" to "nonprivate",
            "tcUrl" to tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 4071.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "objectEncoding" to 0.0
        ).apply {
            if (videoCodec == OutputCodec.HEVC) put("fourCcList", listOf("hvc1"))
        }
        sendCommand(0,"connect",1,listOf(props));waitFor(predicate={it.command=="_result"&&it.transaction==1.0})
        state=State.CONNECTED;onState(state,"Connected")
        sendCommand(0,"releaseStream",2,listOf(key));drainUntil(800)
        sendCommand(0,"FCPublish",3,listOf(key));drainUntil(800)
        sendCommand(0,"createStream",4,emptyList());val created=waitFor(predicate={it.command=="_result"&&it.transaction==4.0});streamId=created.values.lastOrNull{it is Double}?.let{(it as Double).toInt()}?:error("RTMP server did not return a stream id")
        sendCommand(0,"publish",5,listOf(key,"live"));waitFor(predicate={it.command=="onStatus"&&it.info["code"]=="NetStream.Publish.Start"})
        sendMetadata();timestampBase=Long.MIN_VALUE;state=State.PUBLISHING;onState(state,"Publishing")
        writerRunning=true;writerThread=Thread(::writeLoop,"Stream4k-RTMP-Writer").apply{priority=Thread.MAX_PRIORITY;start()}
    }

    private fun writeLoop(){
        try{
            while(writerRunning){
                val item=outbound.take()
                if(!writerRunning)break
                if(item.video){
                    val s=item.sample;val ts=timestamp(s.ptsUs)
                    val body=if(s.codecConfig)flvVideoSequence(item.v?:return)else flvVideoFrame(s.data,s.keyframe, videoCodec)
                    writeMessage(0x09,6,ts,body)
                }else{
                    val s=item.sample;val ts=timestamp(s.ptsUs)
                    val body=if(s.codecConfig)flvAudioSequence(item.a?:return)else flvAudioRaw(s.data)
                    writeMessage(0x08,4,ts,body)
                }
            }
        }catch(t:Throwable){
            if(writerRunning && state!=State.STOPPING && state!=State.IDLE){
                writerRunning=false
                runCatching{socket?.close()}
                scheduleReconnect(t.message?:"RTMP connection lost")
            }
        }
    }

    private fun scheduleReconnect(reason:String){
        if(!reconnecting.compareAndSet(false,true))return
        state=State.CONNECTING
        onState(state,"Connection lost; reconnecting")
        reconnectThread=Thread({
            var delay=500L
            repeat(8){
                if(!reconnecting.get())return@Thread
                Thread.sleep(delay)
                if(!reconnecting.get())return@Thread
                runCatching{
                    socket?.close()
                    socket=null;input=null;output=null
                    outbound.clear()
                    connect(reconnectUrl,reconnectKey,reconnectTls,reconnectTimeoutMs)
                }.onSuccess{
                    reconnecting.set(false)
                    return@Thread
                }.onFailure{
                    delay=(delay*2).coerceAtMost(8_000L)
                }
            }
            reconnecting.set(false)
            state=State.ERROR
            onState(state,"RTMP reconnect failed: $reason")
        },"Stream4k-RTMP-Reconnect").apply{priority=Thread.NORM_PRIORITY+1;start()}
    }

    private fun sendMetadata(){
        val videoCodeId: Any = if(videoCodec == OutputCodec.HEVC) fourCcNumber("hvc1").toDouble() else "avc1"
        val meta=linkedMapOf<String,Any?>(
            "width" to videoWidth.toDouble(),
            "height" to videoHeight.toDouble(),
            "framerate" to videoFps.toDouble(),
            "videocodecid" to videoCodeId,
            "audiocodecid" to "mp4a",
            "stereo" to true,
            "2.0" to 2.0
        )
        val body=Amf.encode(listOf("@setDataFrame","onMetaData",meta));writeMessage(0x12,5,0,body)
    }

    private fun timestamp(ptsUs:Long):Int{if(timestampBase==Long.MIN_VALUE)timestampBase=ptsUs;return ((ptsUs-timestampBase)/1000L).coerceIn(0,0xFFFFFFFFL).toInt()}

    private fun writeMessage(type:Int,chunkStreamId:Int,ts:Int,body:ByteArray){synchronized(lock){val o=output?:return;var off=0;var first=true;val msgStream=when(chunkStreamId){4,5,6->streamId else->0};while(first||off<body.size){val n=minOf(outChunkSize,body.size-off);val useFmt=if(first)0 else 3;writeBasicHeader(o,useFmt,chunkStreamId);if(first){val ext=if(ts>=0xFFFFFF)0xFFFFFF else ts;writeU24(o,ext);writeU24(o,body.size);o.write(type);writeU32LE(o,msgStream);if(ts>=0xFFFFFF)writeU32BE(o,ts.toLong() and 0xffffffffL)}else if(ts>=0xFFFFFF){writeU32BE(o,ts.toLong() and 0xffffffffL)};o.write(body,off,n);off+=n;first=false};o.flush();bytesSent.addAndGet(body.size.toLong())}}

    private fun writeBasicHeader(o:BufferedOutputStream,fmt:Int,csid:Int){require(csid in 2..65599);val f=fmt shl 6;when{csid<64->o.write(f or csid);csid<320-> {o.write(f);o.write(csid-64)};else->{o.write(f or 1);val n=csid-64;o.write(n and 0xff);o.write(n ushr 8)}}}

    private fun sendCommand(csid:Int,name:String,tx:Int,args:List<Any?>){val stream=if(csid<2)3 else csid;writeMessage(0x14,stream,0,Amf.encode(listOf(name,tx.toDouble(),null)+args))}

    private data class Command(val command:String,val transaction:Double,val values:List<Any?>,val info:Map<String,String>)
    private fun waitFor(predicate:(Command)->Boolean,timeoutMs:Long=15_000):Command{val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);while(System.nanoTime()<deadline){val c=readCommand(remainingTimeout(deadline))?:continue;if(predicate(c))return c};error("Timed out waiting for RTMP server response")}
    private fun drainUntil(ms:Long){val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(System.nanoTime()<deadline){val m=readMessage(remainingTimeout(deadline))?:break;handleControl(m)}}
    private fun remainingTimeout(deadline:Long)=((deadline-System.nanoTime())/1_000_000).toInt().coerceIn(1,1000)

    private fun readCommand(timeoutMs:Int):Command?{val m=readMessage(timeoutMs)?:return null;handleControl(m);if(m.type!=20&&m.type!=17)return null;return Amf.decodeCommand(m.body)}

    private data class InMessage(val type:Int,val streamId:Int,val timestamp:Int,val body:ByteArray)
    private data class ChunkState(var timestamp:Int=0,var delta:Int=0,var length:Int=0,var type:Int=0,var streamId:Int=0,var remaining:Int=0,var buffer:ByteArrayOutputStream=ByteArrayOutputStream(),var extended:Boolean=false)
    private fun readMessage(timeoutMs:Int):InMessage?{
        val i=input?:return null;i.skip(0);socket?.soTimeout=timeoutMs
        val (fmt,csid)=readBasic(i);val st=inStates.getOrPut(csid){ChunkState()};var timestamp=st.timestamp
        if(fmt==0){timestamp=readU24(i);st.length=readU24(i);st.type=i.read();st.streamId=readU32LE(i).toInt();st.delta=0;st.buffer.reset();st.remaining=st.length;st.extended=timestamp==0xFFFFFF;if(st.extended)timestamp=readU32BE(i).toInt();st.timestamp=timestamp}
        else if(fmt==1){st.delta=readU24(i);st.length=readU24(i);st.type=i.read();st.remaining=st.length;st.buffer.reset();timestamp=st.timestamp+st.delta;st.timestamp=timestamp;if(st.delta==0xFFFFFF)readU32BE(i)}
        else if(fmt==2){st.delta=readU24(i);timestamp=st.timestamp+st.delta;st.timestamp=timestamp;st.remaining=st.length;st.buffer.reset();if(st.delta==0xFFFFFF)readU32BE(i)}
        else {if(st.remaining==0){st.remaining=st.length;st.buffer.reset();};if(st.extended)readU32BE(i)}
        val take=minOf(inChunkSize,st.remaining);val buf=ByteArray(take);readFully(i,buf);st.buffer.write(buf);st.remaining-=take;if(st.remaining>0){while(st.remaining>0){val (cf,cc)=readBasic(i);require(cc==csid){"Unexpected RTMP continuation chunk"};if(cf==3&&st.extended)readU32BE(i);val n=minOf(inChunkSize,st.remaining);val b=ByteArray(n);readFully(i,b);st.buffer.write(b);st.remaining-=n}}
        val body=st.buffer.toByteArray();val msg=InMessage(st.type,st.streamId,st.timestamp,body);st.buffer.reset();st.remaining=0;return msg
    }

    private fun handleControl(m:InMessage){when(m.type){1->if(m.body.size>=4){inChunkSize=readU32BE(m.body).toInt().coerceIn(128,1024*1024)};5->if(m.body.size>=4){ackWindow=readU32BE(m.body);};6->{if(m.body.size>=8)ackWindow=readU32BE(m.body);};3->{/* acknowledgement */};8,9,18->{}};if(ackWindow>0&&bytesSent.get()-lastAck>=ackWindow){val b=ByteArray(4);writeU32BE(b,bytesSent.get() and 0xffffffffL);writeMessageRaw(3,2,0,b);lastAck=bytesSent.get()}}

    private fun writeMessageRaw(type:Int,chunkStreamId:Int,ts:Int,body:ByteArray){val o=output?:return;writeBasicHeader(o,0,chunkStreamId);writeU24(o,ts.coerceAtMost(0xFFFFFF));writeU24(o,body.size);o.write(type);writeU32LE(o,0);o.write(body);o.flush()}

    private fun handshake(){val o=output?:error("No RTMP output");val i=input?:error("No RTMP input");o.write(3);val time=(System.currentTimeMillis()/1000).toInt();writeU32BE(o,time.toLong());writeU32BE(o,0);val c1=ByteArray(1528);Random.nextBytes(c1);o.write(c1);o.flush();require(i.read()==3){"Invalid RTMP S0"};val s1=ByteArray(1536);readFully(i,s1);val s2=ByteArray(1536);readFully(i,s2);o.write(s1);o.flush();/* C2 sent */}

    private fun writeU24(o:OutputStream,v:Int){o.write(v ushr 16);o.write(v ushr 8);o.write(v)}
    private fun writeU32LE(o:OutputStream,v:Int){o.write(v);o.write(v ushr 8);o.write(v ushr 16);o.write(v ushr 24)}
    private fun writeU32BE(o:OutputStream,v:Long){o.write((v ushr 24).toInt());o.write((v ushr 16).toInt());o.write((v ushr 8).toInt());o.write(v.toInt())}
    private fun writeU32BE(b:ByteArray,v:Long){b[0]=(v ushr 24).toByte();b[1]=(v ushr 16).toByte();b[2]=(v ushr 8).toByte();b[3]=v.toByte()}
    private fun readBasic(i:BufferedInputStream):Pair<Int,Int>{val b=i.read();require(b>=0);val fmt=b ushr 6;var csid=b and 63;when(csid){0->csid=64+i.read();1->{val a=i.read();val b2=i.read();csid=64+a+(b2 shl 8)}};return fmt to csid}
    private fun readU24(i:BufferedInputStream)=readU24(byteArrayOf(i.read().toByte(),i.read().toByte(),i.read().toByte()))
    private fun readU24(b:ByteArray)=((b[0].toInt() and 255) shl 16) or ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)
    private fun readU32LE(i:BufferedInputStream)=((i.read() and 255)) or ((i.read() and 255) shl 8) or ((i.read() and 255) shl 16) or ((i.read() and 255) shl 24)
    private fun readU32BE(i:BufferedInputStream)=((i.read() and 255).toLong() shl 24) or ((i.read() and 255).toLong() shl 16) or ((i.read() and 255).toLong() shl 8) or (i.read() and 255).toLong()
    private fun readU32BE(b:ByteArray)=((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
    private fun readFully(i:BufferedInputStream,b:ByteArray){var o=0;while(o<b.size){val n=i.read(b,o,b.size-o);require(n>0){"RTMP socket closed"};o+=n}}

    object AnnexB{
        fun nalus(data:ByteArray):List<ByteArray>{val out=ArrayList<ByteArray>();var start=findStart(data,0);if(start<0)return lengthPrefixed(data);while(start>=0){val prefix=if(data[start+2].toInt()==1)3 else 4;val nstart=start+prefix;val next=findStart(data,nstart);val end=if(next<0)data.size else next;if(end>nstart)out.add(data.copyOfRange(nstart,end));start=next};return if(out.isEmpty())listOf(data)else out}
        private fun findStart(d:ByteArray,from:Int):Int{var i=from;while(i+3<d.size){if(d[i].toInt()==0&&d[i+1].toInt()==0&&(d[i+2].toInt()==1||(d[i+2].toInt()==0&&d[i+3].toInt()==1)))return i;i++};return -1}
        private fun lengthPrefixed(d:ByteArray):List<ByteArray>{val out=ArrayList<ByteArray>();var p=0;while(p+4<=d.size){val n=((d[p].toInt() and 255) shl 24) or ((d[p+1].toInt() and 255) shl 16) or ((d[p+2].toInt() and 255) shl 8) or (d[p+3].toInt() and 255);p+=4;if(n<=0||p+n>d.size)return listOf(d);out.add(d.copyOfRange(p,p+n));p+=n};return if(out.isEmpty())listOf(d)else out}
    }

    private fun flvVideoSequence(c:VideoCodecConfig):ByteArray {
        return when(c.codec) {
            OutputCodec.H264 -> {
                val sps=c.sps ?: error("H.264 SPS missing")
                val pps=c.pps ?: error("H.264 PPS missing")
                val o=ByteArrayOutputStream();o.write(0x17);o.write(0);writeU24(o,0);o.write(1)
                o.write(sps.getOrElse(1){0}.toInt() and 255);o.write(sps.getOrElse(2){0}.toInt() and 255);o.write(0xFF)
                o.write(0xE1);writeU16(o,sps.size);o.write(sps);o.write(1);writeU16(o,pps.size);o.write(pps);o.toByteArray()
            }
            OutputCodec.HEVC -> {
                val cfg=c.hvcC ?: error("HEVC configuration record missing")
                ByteArrayOutputStream().apply {
                    write(0x08) // Enhanced-RTMP: extended header + PacketTypeSequenceStart
                    writeFourCc(this, "hvc1")
                    write(cfg)
                }.toByteArray()
            }
            else -> error("Unsupported RTMP video codec: ${c.codec}")
        }
    }

    private fun flvVideoFrame(data:ByteArray,key:Boolean,codec:OutputCodec):ByteArray{
        return when(codec) {
            OutputCodec.H264 -> {
                val o=ByteArrayOutputStream();o.write(if(key)0x17 else 0x27);o.write(1);writeU24(o,0);for(n in AnnexB.nalus(data)){writeU32(o,n.size);o.write(n)};o.toByteArray()
            }
            OutputCodec.HEVC -> {
                val o=ByteArrayOutputStream();o.write(if(key)0x09 else 0x0A);writeFourCc(o,"hvc1");writeU24(o,0);for(n in AnnexB.nalus(data)){writeU32(o,n.size);o.write(n)};o.toByteArray()
            }
            else -> error("Unsupported RTMP video codec: $codec")
        }
    }
    private fun writeFourCc(o:ByteArrayOutputStream, value:String){
        require(value.length==4)
        value.forEach{ o.write(it.code) }
    }

    private fun fourCcNumber(value:String):Long {
        require(value.length==4)
        return value.fold(0L){acc,c->(acc shl 8) or c.code.toLong()}
    }

    object HevcConfiguration {
        fun fromCsd(csd:ByteArray, profileIdc:Int = 1, levelIdc:Int = 153):ByteArray {
            // Android exposes HEVC CSD as VPS+SPS+PPS, each beginning with 00 00 00 01.
            // Enhanced-RTMP requires ISO/IEC 14496-15 HEVCDecoderConfigurationRecord (hvcC).
            val nalus = AnnexB.nalus(csd)
            val vps = nalus.filter { nalType(it)==32 }
            val sps = nalus.filter { nalType(it)==33 }
            val pps = nalus.filter { nalType(it)==34 }
            require(vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()) { "HEVC CSD lacks VPS/SPS/PPS" }
            val arrays = listOf(32 to vps, 33 to sps, 34 to pps)
            return ByteArrayOutputStream().apply {
                write(1) // configurationVersion
                write(profileIdc and 0x1F) // general_profile_space=0, tier=0, profile_idc
                repeat(4){write(0)} // general_profile_compatibility_flags
                repeat(6){write(0)} // general_constraint_indicator_flags
                write(levelIdc and 0xFF)
                write(0xF0); write(0x00) // reserved + min_spatial_segmentation_idc=0
                write(0xFC or 0) // reserved + parallelismType=0
                write(0xFD) // reserved + chromaFormat=1 (4:2:0)
                write(0xF8) // reserved + bitDepthLumaMinus8=0
                write(0xF8) // reserved + bitDepthChromaMinus8=0
                write(0); write(0) // avgFrameRate=0
                write(0x0F) // constantFrameRate=0, numTemporalLayers=0, temporalIdNested=1, lengthSizeMinusOne=3
                write(arrays.size)
                for((type,list) in arrays){
                    write(0x80 or (type and 0x3F))
                    writeU16(this,list.size)
                    for(nal in list){ writeU16(this,nal.size); write(nal) }
                }
            }.toByteArray()
        }
        private fun nalType(nal:ByteArray)=if(nal.size>=2)((nal[0].toInt() ushr 1) and 0x3F) else -1
        private fun writeU16(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)}
    }

    private fun flvAudioSequence(c:AudioCodecConfig)=byteArrayOf(0xAF.toByte(),0,c.asc.getOrElse(0){0},c.asc.getOrElse(1){0})
    private fun flvAudioRaw(d:ByteArray)=byteArrayOf(0xAF.toByte(),1)+d
    private fun writeU16(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)}
    private fun writeU32(o:ByteArrayOutputStream,v:Int){o.write(v ushr 24);o.write(v ushr 16);o.write(v ushr 8);o.write(v)}

    private object Amf{
        fun encode(v:List<Any?>):ByteArray{val o=ByteArrayOutputStream();v.forEach{write(o,it)};return o.toByteArray()}
        private fun write(o:ByteArrayOutputStream,v:Any?){when(v){null->o.write(5);is String->{o.write(2);writeUtf(o,v)};is Double->{o.write(0);writeLong(o,java.lang.Double.doubleToRawLongBits(v))};is Float->{o.write(0);writeLong(o,java.lang.Double.doubleToRawLongBits(v.toDouble()))};is Boolean->o.write(if(v)1 else 2);is List<*>->{o.write(10);writeInt32(o,v.size);v.forEach{write(o,it)}};is Map<*,*>->{o.write(3);for((k,x)in v){writeShort(o,k.toString().length);o.write(k.toString().toByteArray());write(o,x)};o.write(0);o.write(0);o.write(9)};else->write(o,v.toString())}}
        private fun writeUtf(o:ByteArrayOutputStream,s:String){val b=s.toByteArray();writeShort(o,b.size);o.write(b)};private fun writeShort(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)};private fun writeInt32(o:ByteArrayOutputStream,v:Int){o.write(v ushr 24);o.write(v ushr 16);o.write(v ushr 8);o.write(v)};private fun writeLong(o:ByteArrayOutputStream,v:Long){for(s in 56 downTo 0 step 8)o.write((v ushr s).toInt())}
        data class Decoded(val command:String,val transaction:Double,val values:List<Any?>,val info:Map<String,String>)
        fun decodeCommand(b:ByteArray):Command?{val r=Reader(b);val command=r.read() as? String?:return null;val tx=(r.read() as? Double)?:0.0;val values=mutableListOf<Any?>();while(r.remaining()>0)values.add(r.read());val info=values.flatMap{(it as? Map<*,*>)?.entries?:emptySet()}.associate{it.key.toString() to it.value.toString()};return Command(command,tx,values,info)}
        class Reader(private val b:ByteArray){var p=0;fun remaining()=b.size-p;fun read():Any?{if(p>=b.size)return null;return when(val t=b[p++].toInt() and 255){0->{val x=readLong();java.lang.Double.longBitsToDouble(x)};1->(b[p++].toInt()!=0);2->{val n=readShort();String(b,p,n).also{p+=n}};3->{val m=linkedMapOf<String,Any?>();while(p+3<=b.size){val n=readShort();if(n==0&&(b[p].toInt() and 255)==9){p++;return m};val k=String(b,p,n);p+=n;m[k]=read()};m};5->null;8->{val count=readInt32();val m=linkedMapOf<String,Any?>();repeat(count){val n=readShort();val k=String(b,p,n);p+=n;m[k]=read()};m};10->{val n=readInt32();List(n){read()}};11->{val time=readLong();val tz=readShort();Pair(time,tz)};else->null}}
            private fun readShort()=((b[p++].toInt() and 255) shl 8) or (b[p++].toInt() and 255);private fun readInt32():Int{val v=((b[p++].toInt() and 255) shl 24) or ((b[p++].toInt() and 255) shl 16) or ((b[p++].toInt() and 255) shl 8) or (b[p++].toInt() and 255);return v};private fun readLong():Long{var v=0L;repeat(8){v=(v shl 8) or (b[p++].toLong() and 255)};return v}
        }
    }
}
