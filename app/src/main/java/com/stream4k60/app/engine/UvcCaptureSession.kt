package com.stream4k60.app.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Native UVC transport supporting both bulk and true isochronous USB streaming.
 * Compressed UVC payloads are fed directly into hardware MediaCodec; uncompressed
 * YUYV/UYVY/NV12 is uploaded to the GPU without a Kotlin pixel conversion stage.
 */
class UvcCaptureSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val sourceId: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val formatName: String,
    private val sourceConfigJson: String = "{}"
) {
    data class Format(
        val index:Int,val frameIndex:Int,val width:Int,val height:Int,val fps:Int,
        val codec:String,val maxFrameSize:Int
    )

    private data class EndpointChoice(val intf:UsbInterface,val endpoint:UsbEndpoint,val iso:Boolean,val packetBytes:Int)

    private val running = AtomicBoolean(false)
    private var decoder: MediaCodec? = null
    /** Set when the phone has no MJPEG MediaCodec (the Astra doesn't): frames are decoded on the CPU instead. */
    private var softwareJpeg = false
    private var jpegBitmap: android.graphics.Bitmap? = null
    private var streamInterface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null
    private var selectedFormat: Format? = null
    private var surface: Surface? = null
    private var isoHandle: Long = 0
    private var bulkHandle: Long = 0
    private data class CompressedFrame(val data: ByteArray, val ptsUs: Long)
    private val decodeQueue = ArrayBlockingQueue<CompressedFrame>(64)
    /** H.264/HEVC frames depend on earlier ones: after any loss, wait for the next keyframe. */
    @Volatile private var needKeyframe = true
    private var parameterSets: ByteArray? = null
    @Volatile private var decodedCount = 0L
    @Volatile private var lastDecodeError: String? = null
    private var watchdog: Thread? = null
    private var decodeThread: Thread? = null
    private var frameErrors = 0L
    private var frameCount = 0L

    fun start(): Surface {
        check(!running.get()) { "UVC session already running" }
        val formats = parseFormats(connection.rawDescriptors)
        check(formats.isNotEmpty()) { "The camera did not advertise any video formats" }
        val advertised = chooseFormat(formats, width, height, fps, formatName)
        // A custom frame rate on a listed resolution/format is requested exactly; the camera answers the UVC probe
        // with the closest rate it can do. Resolutions and formats must come from the device's own list.
        val selected = if (advertised.width == width && advertised.height == height && advertised.codec.equals(formatName, true) && advertised.fps != fps && fps > 0)
            advertised.copy(fps = fps) else advertised

        val choice = chooseEndpoint(selected)
            ?: error("UVC device has no usable real-time bulk or isochronous streaming endpoint")
        check(connection.claimInterface(choice.intf, true)) { "Could not claim UVC streaming interface ${choice.intf.id}" }
        streamInterface = choice.intf
        endpoint = choice.endpoint
        selectedFormat = selected

        // UVC negotiation is performed while the streaming interface is in the zero-bandwidth
        // alternate setting. The selected alternate is applied only after PROBE/COMMIT succeeds.
        val zeroBandwidth = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.id == choice.intf.id && it.interfaceClass == 14 && it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroBandwidth != null) connection.setInterface(zeroBandwidth)
        negotiate(choice, selected)
        check(connection.setInterface(choice.intf)) { "Could not select UVC alternate setting ${choice.intf.alternateSetting}" }
        applyConfiguredVideoControls()
        surface = NativeEngine.createSourceSurface(sourceId) ?: error("Could not create GPU source surface")
        NativeEngine.setSourceEffectsFromConfig(sourceId, sourceConfigJson)
        NativeEngine.setSourceBufferSize(sourceId, selected.width, selected.height)
        if (selected.codec.uppercase() in setOf("MJPEG","H264","HEVC","H265","AVC")) {
            val hw = runCatching { createDecoder(selected, surface!!) }
            if (hw.isSuccess) { decoder = hw.getOrThrow(); decoder?.start() }
            else if (selected.codec.equals("MJPEG", true)) {
                android.util.Log.i("Stream4k60", "No MJPEG MediaCodec (${hw.exceptionOrNull()?.message}); decoding MJPEG in software")
                softwareJpeg = true
            } else throw hw.exceptionOrNull()!!
        }

        val fd = connection.fileDescriptor
        check(fd >= 0) { "USB native file descriptor unavailable" }
        // Mark the session live before submitting the first URBs; an attached device may
        // deliver the first frame immediately after SUBMITURB.
        running.set(true)
        if (decoder != null || softwareJpeg) {
            decodeThread = Thread({ decodeLoop() }, "Stream4k-UVCDecode-${sourceId.take(8)}").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }
        try {
            if (choice.iso) {
                isoHandle = NativeUsbIso.start(fd, choice.endpoint.address, choice.packetBytes, 32, 16, this)
                if (isoHandle == 0L) error("Native isochronous USB queue could not be started")
            } else {
                bulkHandle = NativeUsbBulk.start(fd, choice.endpoint.address, choice.packetBytes, NativeUsbBulk.transferSize(choice.endpoint), 16, this)
                if (bulkHandle == 0L) error("Native asynchronous bulk UVC queue could not be started")
            }
        } catch (t: Throwable) {
            running.set(false)
            decodeThread?.interrupt()
            runCatching { decodeThread?.join(500) }
            decodeThread = null
            decodeQueue.clear()
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null
            runCatching { connection.releaseInterface(choice.intf) }
            streamInterface = null
            runCatching { surface?.release() }
            surface = null
            runCatching { NativeEngine.releaseSourceSurface(sourceId) }
            throw t
        }
        startWatchdog()
        return surface!!
    }

    /**
     * Says where the video stops when the preview stays black: nothing from USB, data that doesn't decode, or a
     * decoder error. Clears the message once pictures come out.
     */
    private fun startWatchdog(){
        watchdog=Thread({
            var lastFrames=0L;var lastDecoded=0L
            try{
                Thread.sleep(4000)
                while(running.get()){
                    val frames=frameCount;val decoded=if(softwareJpeg)frames-frameErrors else decodedCount
                    val fmt=selectedFormat?.let{"${it.width}x${it.height}@${it.fps} ${it.codec}"}.orEmpty()
                    val msg=when{
                        frames==lastFrames->"The camera accepted $fmt but sent no video over USB in the last few seconds (${transportLabel()}, ${frameErrors} packet errors). Try another format or USB port."
                        !softwareJpeg&&decoded==lastDecoded->"Receiving $fmt from the camera (${frames} frames) but the decoder produced no picture${lastDecodeError?.let{": $it"}?:""}."
                        else->null
                    }
                    if(msg!=null)SourceRuntimeErrors.report(sourceId,msg) else SourceRuntimeErrors.clear(sourceId)
                    lastFrames=frames;lastDecoded=decoded
                    Thread.sleep(3000)
                }
            }catch(_:InterruptedException){}
        },"Stream4k-UVCWatch").apply{isDaemon=true;start()}
    }
    private fun transportLabel()=if(isoHandle!=0L)"isochronous" else if(bulkHandle!=0L)"bulk" else "no transfer"

    /** Called synchronously by native usbfs from the URB completion thread. */
    @Suppress("UNUSED_PARAMETER")
    fun onNativeFrame(buffer: ByteBuffer, ptsUs: Long, packetErrorCount: Int) {
        if (!running.get()) return
        frameCount++
        frameErrors += packetErrorCount
        val format = selectedFormat ?: return
        when (format.codec.uppercase()) {
            "YUYV", "YUY2" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 1)
            "UYVY" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 2)
            "NV12" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 3)
            else -> {
                val src = buffer.duplicate()
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                if (format.codec.uppercase() == "MJPEG") {
                    // Every MJPEG frame stands alone: keep only the newest.
                    while (decodeQueue.size >= 2) decodeQueue.poll()
                    decodeQueue.offer(CompressedFrame(bytes, ptsUs))
                } else if (!decodeQueue.offer(CompressedFrame(bytes, ptsUs))) {
                    // Decoder fell behind: dropping part of an H.264 stream corrupts it, so restart at a keyframe.
                    decodeQueue.clear(); needKeyframe = true
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && isoHandle == 0L && bulkHandle == 0L && streamInterface == null && surface == null && decoder == null) return
        val h = isoHandle; isoHandle = 0L
        if (h != 0L) runCatching { NativeUsbIso.stop(h) }
        val b = bulkHandle; bulkHandle = 0L
        if (b != 0L) runCatching { NativeUsbBulk.stop(b) }
        watchdog?.interrupt(); watchdog = null
        decodeThread?.interrupt()
        runCatching { decodeThread?.join(500) }
        decodeThread = null
        decodeQueue.clear()
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
        streamInterface?.let { runCatching { connection.releaseInterface(it) } }
        streamInterface = null; endpoint = null
        runCatching { surface?.release() }; surface = null
        runCatching { NativeEngine.releaseSourceSurface(sourceId) }
    }

    fun currentFormat(): Format? = selectedFormat
    fun currentTransport(): String = if (isoHandle != 0L) "ISOCHRONOUS" else if (bulkHandle != 0L) "BULK" else ""
    fun deliveredFrames():Long = frameCount
    fun transportErrors():Long = frameErrors

    private fun chooseEndpoint(f:Format):EndpointChoice? {
        val interfaces = (0 until device.interfaceCount).asSequence()
            .map(device::getInterface)
            .filter { it.interfaceClass == 14 && it.interfaceSubclass == 2 }
            .toList()
        val iso = interfaces.flatMap { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).filter {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && intf.alternateSetting > 0
            }.map { ep -> EndpointChoice(intf,ep,true,packetCapacity(ep)) }
        }.maxByOrNull { it.packetBytes }
        if (iso != null) return iso
        return interfaces.flatMap { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).filter {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }.map { ep -> EndpointChoice(intf,ep,false,ep.maxPacketSize) }
        }.maxByOrNull { it.packetBytes }
    }

    private fun packetCapacity(endpoint:UsbEndpoint):Int {
        val raw=endpoint.maxPacketSize
        val base=raw and 0x07ff
        val transactions=1+((raw ushr 11) and 0x3)
        val companion=superSpeedCompanionBytes(endpoint.address)
        return companion ?: (base*transactions).coerceAtLeast(base)
    }

    private fun superSpeedCompanionBytes(address:Int):Int? {
        val d=connection.rawDescriptors
        var i=0
        while(i+2<d.size){
            val len=d[i].toInt() and 0xff;if(len<2||i+len>d.size)break
            val type=d[i+1].toInt() and 0xff
            if(type==5&&len>=7){
                val ep=d[i+2].toInt() and 0xff
                if(ep==address && i+len+6<d.size){
                    val n=d[i+len].toInt() and 0xff
                    val t=d[i+len+1].toInt() and 0xff
                    if(n>=6&&t==0x30){
                        val w=(d[i+len+4].toInt() and 0xff) or ((d[i+len+5].toInt() and 0xff) shl 8)
                        if(w>0)return w
                    }
                }
            }
            i+=len
        }
        return null
    }

    private fun createDecoder(f:Format,surface:Surface):MediaCodec {
        val mime=when(f.codec.uppercase()){
            "MJPEG"->"video/mjpeg"
            "H264","AVC","AVC1"->"video/avc"
            "H265","HEVC"->"video/hevc"
            else->error("No hardware decoder mapping for ${f.codec}")
        }
        val cfg=runCatching{org.json.JSONObject(sourceConfigJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(org.json.JSONObject())
        val preference=cfg.optString("videoDecoderPreference","hardware").lowercase()
        if(preference=="automatic") return configureDecoder(MediaCodec.createDecoderByType(mime),mime,f,surface)
        val preferHardware=preference!="software"
        val candidates=MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter{info->!info.isEncoder&&info.supportedTypes.any{it.equals(mime,true)}}
            .sortedByDescending{it.isHardwareAccelerated==preferHardware}
        var lastFailure:Throwable?=null
        for(info in candidates){
            val codec=try{MediaCodec.createByCodecName(info.name)}catch(error:Throwable){lastFailure=error;continue}
            val configured=runCatching{configureDecoder(codec,mime,f,surface)}
            if(configured.isSuccess)return configured.getOrThrow()
            lastFailure=configured.exceptionOrNull()
        }
        throw lastFailure?:IllegalStateException("Android has no decoder for $mime")
    }

    private fun configureDecoder(codec:MediaCodec,mime:String,f:Format,surface:Surface):MediaCodec {
        val format=MediaFormat.createVideoFormat(mime,f.width,f.height).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, f.maxFrameSize.coerceAtLeast(512*1024))
        }
        return try{codec.configure(format,surface,null,0);codec}catch(error:Throwable){runCatching{codec.release()};throw error}
    }

    private fun decodeLoop(){
        while(running.get() && !Thread.currentThread().isInterrupted){
            val frame=runCatching{decodeQueue.poll(20,TimeUnit.MILLISECONDS)}.getOrNull() ?: continue
            try{
                if(softwareJpeg)decodeJpeg(frame.data)
                else{val prepared=prepareCompressed(frame.data)?:continue;feedDecoder(ByteBuffer.wrap(prepared),frame.ptsUs)}
            }catch(t:Throwable){
                if(t is InterruptedException)return
                lastDecodeError="${t.javaClass.simpleName}: ${t.message}";android.util.Log.w("Stream4k60","UVC decode failed",t)
            }
        }
    }

    /**
     * Software MJPEG: Android's JPEG decoder (libjpeg-turbo) into a reused bitmap, then drawn into the source's
     * GPU surface with a hardware canvas. Only the newest frame is decoded, so a slow frame never builds a backlog.
     */
    private fun decodeJpeg(data:ByteArray){
        val target=surface?:return
        val jpeg=MjpegFrames.withHuffmanTables(data)
        val opts=android.graphics.BitmapFactory.Options().apply{inMutable=true;inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;inBitmap=jpegBitmap}
        val bmp=try{android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,opts)}catch(_:IllegalArgumentException){
            // The reusable bitmap no longer fits (size changed): decode into a fresh one.
            jpegBitmap=null;android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,android.graphics.BitmapFactory.Options().apply{inMutable=true})
        }?:run{frameErrors++;return}
        jpegBitmap=bmp
        val canvas=try{target.lockHardwareCanvas()}catch(_:Throwable){return}
        try{canvas.drawBitmap(bmp,null,android.graphics.Rect(0,0,canvas.width,canvas.height),null)}finally{runCatching{target.unlockCanvasAndPost(canvas)}}
    }

    /**
     * H.264/HEVC: remembers the camera's parameter sets (SPS/PPS, and VPS for HEVC), adds them to keyframes that
     * lack them, and skips frames until the first keyframe so the decoder always starts from a complete picture.
     */
    private fun prepareCompressed(data:ByteArray):ByteArray?{
        val hevc=selectedFormat?.codec?.uppercase().let{it=="HEVC"||it=="H265"}
        var hasParams=false;var keyframe=false
        val params=java.io.ByteArrayOutputStream()
        forEachNal(data){start,end,header->
            val type=if(hevc)(header shr 1) and 0x3F else header and 0x1F
            val isParam=if(hevc)type in 32..34 else type==7||type==8
            if(isParam){hasParams=true;params.write(byteArrayOf(0,0,0,1));params.write(data,start,end-start)}
            if(if(hevc)type in 16..21 else type==5)keyframe=true
        }
        if(hasParams)parameterSets=params.toByteArray()
        if(needKeyframe){
            if(!keyframe||parameterSets==null){lastDecodeError=if(parameterSets==null)"Waiting for the camera's stream setup (SPS/PPS)" else "Waiting for a keyframe";return null}
            needKeyframe=false
        }
        val ps=parameterSets
        return if(keyframe&&!hasParams&&ps!=null)ps+data else data
    }

    /** Calls [block] with (start of NAL payload, end, first payload byte) for each Annex-B NAL unit. */
    private inline fun forEachNal(d:ByteArray,block:(Int,Int,Int)->Unit){
        var i=0;var nalStart=-1
        while(i+3<=d.size){
            val sc=if(d[i]==0.toByte()&&d[i+1]==0.toByte()&&d[i+2]==1.toByte())3 else if(i+4<=d.size&&d[i]==0.toByte()&&d[i+1]==0.toByte()&&d[i+2]==0.toByte()&&d[i+3]==1.toByte())4 else 0
            if(sc>0){
                if(nalStart>=0&&nalStart<i)block(nalStart,i,d[nalStart].toInt() and 0xFF)
                nalStart=i+sc;i+=sc
            }else i++
        }
        if(nalStart in 0 until d.size)block(nalStart,d.size,d[nalStart].toInt() and 0xFF)
    }

    private fun feedDecoder(frame:ByteBuffer,ptsUs:Long){
        val c=decoder?:return
        var inIndex=c.dequeueInputBuffer(20_000)
        if(inIndex<0){drain(c);inIndex=c.dequeueInputBuffer(40_000)}
        if(inIndex<0){needKeyframe=true;lastDecodeError="The hardware decoder stopped accepting frames";return}
        val src=frame.duplicate()
        val size=src.remaining()
        val dst=c.getInputBuffer(inIndex)?:return
        dst.clear()
        if(size>dst.remaining())return
        dst.put(src)
        c.queueInputBuffer(inIndex,0,size,ptsUs,0)
        drain(c)
    }

    private fun drain(codec:MediaCodec){
        val info=MediaCodec.BufferInfo()
        while(true){
            val out=codec.dequeueOutputBuffer(info,0)
            when{
                out>=0->{codec.releaseOutputBuffer(out,true);decodedCount++;lastDecodeError=null}
                out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{
                    // Surface output is already attached to the compositor-owned SurfaceTexture.
                }
                else->return
            }
        }
    }

    private fun negotiate(choice:EndpointChoice,f:Format){
        // UVC 1.1 probe/commit. The selected alternate setting's bandwidth becomes the
        // negotiated max payload transfer size, so isochronous endpoints are not starved.
        // Probe/commit size depends on the camera's UVC version: 26 bytes (1.0), 34 (1.1), 48 (1.5).
        val probeLen=probeLength(connection.rawDescriptors)
        val interval=10_000_000/f.fps.coerceAtLeast(1)
        val payload=choice.packetBytes.coerceAtLeast(1024)
        val probe=ByteArray(probeLen)
        u16le(probe,0,1) // bmHint: keep the requested frame interval
        u8(probe,2,f.index);u8(probe,3,f.frameIndex);u32le(probe,4,interval.toLong());
        u32le(probe,18,f.maxFrameSize.toLong());u32le(probe,22,payload.toLong())
        runCatching{controlSet(choice.intf.id,1,probe)}.getOrElse{error("The camera rejected the video format request (UVC probe, ${probeLen} bytes): ${it.message}")}
        val returned=ByteArray(probeLen)
        val got=controlGet(choice.intf.id,1,returned)
        val commit=if(got>=probeLen)returned.copyOf() else probe
        runCatching{controlSet(choice.intf.id,2,commit)}.getOrElse{error("The camera rejected the video format commit: ${it.message}")}
    }

    private fun applyConfiguredVideoControls(){
        val root=runCatching{org.json.JSONObject(sourceConfigJson)}.getOrDefault(org.json.JSONObject())
        val settings=root.optJSONObject("settings")?:root
        val controls=settings.optJSONObject("uvcControls")?:return
        controls.keys().forEach{key->
            if(!controls.isNull(key))runCatching{UvcVideoControls.set(connection,key,controls.optInt(key))}
        }
    }

    private fun controlSet(interfaceNumber:Int,selector:Int,data:ByteArray){
        val r=connection.controlTransfer(0x21,0x01,selector shl 8,interfaceNumber,data,0,data.size,1000)
        check(r>=0){"UVC SET_CUR failed ($r)"}
    }
    private fun controlGet(interfaceNumber:Int,selector:Int,data:ByteArray):Int = connection.controlTransfer(0xA1,0x81,selector shl 8,interfaceNumber,data,0,data.size,1000)

    companion object {
        /** Requested mode if advertised; otherwise the closest mode, preferring the requested codec, then MJPEG, H.264, HEVC, raw. */
        fun chooseFormat(formats:List<Format>,width:Int,height:Int,fps:Int,codec:String):Format{
            formats.filter{it.width==width&&it.height==height&&it.fps==fps&&it.codec.equals(codec,true)}.minByOrNull{it.maxFrameSize}?.let{return it}
            val preference=listOf(codec.uppercase(),"MJPEG","H264","HEVC","YUYV","NV12","UYVY")
            val codecs=formats.map{it.codec.uppercase()}.toSet()
            val best=preference.firstOrNull{it in codecs}?:formats.first().codec.uppercase()
            return formats.filter{it.codec.equals(best,true)}.minByOrNull{abs(it.width-width)+abs(it.height-height)+abs(it.fps-fps)*8}!!
        }
        /** bcdUVC from the VideoControl interface header. */
        fun probeLength(bytes:ByteArray):Int{
            var i=0;var subclass=-1
            while(i+2<bytes.size){
                val len=bytes[i].toInt() and 0xff;if(len<2||i+len>bytes.size)break
                val type=bytes[i+1].toInt() and 0xff
                if(type==0x04&&len>=7)subclass=if((bytes[i+5].toInt() and 0xff)==14)(bytes[i+6].toInt() and 0xff) else -1
                if(type==0x24&&subclass==1&&len>=5&&(bytes[i+2].toInt() and 0xff)==0x01){
                    val bcd=u16(bytes,i+3)
                    return when{bcd>=0x0150->48;bcd>=0x0110->34;else->26}
                }
                i+=len
            }
            return 26
        }
        fun listFormats(device:UsbDevice,connection:UsbDeviceConnection):List<Format> = parseFormats(connection.rawDescriptors)
        internal fun parseFormats(bytes:ByteArray):List<Format>{
            val result=mutableListOf<Format>();var i=0;var formatIndex=0;var codec=""
            while(i+2<bytes.size){
                val len=bytes[i].toInt() and 0xff;if(len<2||i+len>bytes.size)break
                val type=bytes[i+1].toInt() and 0xff
                if(type==0x24&&len>=3){
                    when(bytes[i+2].toInt() and 0xff){
                        0x06->{formatIndex=bytes.getOrZero(i+3);codec="MJPEG"}
                        0x04->{formatIndex=bytes.getOrZero(i+3);codec=guidFourcc(bytes,i+5)}
                        0x10->{formatIndex=bytes.getOrZero(i+3);codec=guidFourcc(bytes,i+5).ifBlank{"H264"}}
                        // UVC 1.5 H.264 (VS_FORMAT_H264 / VS_FRAME_H264), used by newer webcams such as the Insta360 Link.
                        0x13->{formatIndex=bytes.getOrZero(i+3);codec="H264"}
                        0x14->if(formatIndex>0&&len>=44){
                            // wWidth@4, wHeight@6, dwDefaultFrameInterval@39, bNumFrameIntervals@43, intervals @44.
                            val frameIndex=bytes.getOrZero(i+3);val w=u16(bytes,i+4);val h=u16(bytes,i+6)
                            val maxFrame=(w*h*2L).toInt().coerceAtLeast(256*1024)
                            val count=bytes.getOrZero(i+43)
                            val intervals=if(count==0)listOf(u32(bytes,i+39)) else List(count.coerceAtMost(32)){n->u32(bytes,i+44+n*4)}
                            intervals.filter{it>0&&i+44<=bytes.size}.forEach{interval->result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                        }
                        0x07,0x05,0x11->{
                            if(formatIndex>0&&len>=26){
                                // MJPEG/uncompressed: dwMaxVideoFrameBufferSize@17, bFrameIntervalType@25.
                                // Frame-based (H.264/HEVC, 0x11): no buffer size, bFrameIntervalType@21. Intervals start @26 in both.
                                val frameBased=(bytes[i+2].toInt() and 0xff)==0x11
                                val frameIndex=bytes.getOrZero(i+3);val w=u16(bytes,i+5);val h=u16(bytes,i+7)
                                val maxFrame=(if(frameBased)w*h*2L else u32(bytes,i+17)).toInt().coerceAtLeast(256*1024)
                                val count=bytes.getOrZero(if(frameBased)i+21 else i+25)
                                if(count==0){val interval=u32(bytes,i+26);if(interval>0)result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                                else repeat(count.coerceAtMost(32)){n->val interval=u32(bytes,i+26+n*4);if(interval>0)result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                            }
                        }
                    }
                }
                i+=len
            }
            return result.distinctBy{listOf(it.index,it.frameIndex,it.width,it.height,it.fps,it.codec)}
        }
        private fun guidFourcc(b:ByteArray,o:Int):String{
            if(o+3>=b.size)return "UNKNOWN"
            val chars=String(byteArrayOf(b[o],b[o+1],b[o+2],b[o+3]),Charsets.US_ASCII).trim('\u0000',' ')
            return when(chars.uppercase()){"YUY2"->"YUYV";"YUYV"->"YUYV";"UYVY"->"UYVY";"NV12"->"NV12";"H264","AVC1"->"H264";"HEVC","H265"->"HEVC";else->chars.ifBlank{"UNKNOWN"}}
        }
        private fun ByteArray.getOrZero(i:Int)=getOrNull(i)?.toInt()?.and(0xff)?:0
        private fun u16(b:ByteArray,i:Int)=if(i+1<b.size)(b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) else 0
        private fun u32(b:ByteArray,i:Int):Long{if(i+3>=b.size)return 0;return (b[i].toLong() and 255) or ((b[i+1].toLong() and 255) shl 8) or ((b[i+2].toLong() and 255) shl 16) or ((b[i+3].toLong() and 255) shl 24)}
        private fun u8(b:ByteArray,i:Int,v:Int){if(i<b.size)b[i]=v.toByte()}
        private fun u16le(b:ByteArray,i:Int,v:Int){u8(b,i,v);u8(b,i+1,v ushr 8)}
        private fun u32le(b:ByteArray,i:Int,v:Long){for(n in 0..3)u8(b,i+n,(v ushr (8*n)).toInt())}
    }
}
