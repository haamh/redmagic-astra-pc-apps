package com.stream4k60.app.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class UsbDeviceType { VIDEO_CAMERA,CAPTURE_CARD,AUDIO_INPUT,AUDIO_OUTPUT,COMPOSITE_AV,HID_CONTROLLER,UNKNOWN }
enum class UsbSpeed(val bandwidthMbps:Int,val displayName:String){USB_1_1(12,"USB 1.1 (12 Mbps)"),USB_2_0(480,"USB 2.0 (480 Mbps)"),USB_3_0(5000,"USB 3.x (5 Gbps+)"),UNKNOWN(0,"Unknown")}
data class UsbDeviceInfo(val deviceId:Int,val deviceName:String,val displayName:String,val vendorId:Int,val productId:Int,val manufacturerName:String?,val productName:String?,val serialNumber:String?,val deviceType:UsbDeviceType,val usbSpeed:UsbSpeed,val nativeHandle:Long=0,val isConnected:Boolean=false,val isCapturing:Boolean=false,val currentFormat:String="",val transport:String="",val supportedFormats:List<String> = emptyList(),val estimatedBandwidthMbps:Int=0,val hasAudio:Boolean=false,val audioSourceId:String?=null)
data class UsbBandwidthBudget(val totalBandwidthMbps:Int,val usedBandwidthMbps:Int,val availableBandwidthMbps:Int,val devices:List<UsbDeviceInfo>)

@Singleton
class NativeUsbManager @Inject constructor(@ApplicationContext private val context:Context){
    private val usb=context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val _devices=MutableStateFlow<List<UsbDeviceInfo>>(emptyList());val connectedDevices:StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()
    private val _budget=MutableStateFlow(UsbBandwidthBudget(5000,0,5000,emptyList()));val bandwidthBudget:StateFlow<UsbBandwidthBudget> = _budget.asStateFlow()
    val videoCameras:StateFlow<List<UsbDeviceInfo>> = _devices.map{it.filter{d->d.deviceType==UsbDeviceType.VIDEO_CAMERA||d.deviceType==UsbDeviceType.CAPTURE_CARD||d.deviceType==UsbDeviceType.COMPOSITE_AV}}.stateIn(scope,SharingStarted.Eagerly,emptyList())
    val audioDevices:StateFlow<List<UsbDeviceInfo>> = _devices.map{it.filter{d->d.deviceType==UsbDeviceType.AUDIO_INPUT||d.deviceType==UsbDeviceType.AUDIO_OUTPUT||d.deviceType==UsbDeviceType.COMPOSITE_AV}}.stateIn(scope,SharingStarted.Eagerly,emptyList())

    private val connections=mutableMapOf<Int,UsbDeviceConnection>()
    private val sessions=mutableMapOf<Int,UvcCaptureSession>()
    private val sessionSignatures=mutableMapOf<Int,String>()
    private var receiverRegistered=false

    companion object{
        private const val ACTION_USB_PERMISSION="com.stream4k60.USB_PERMISSION"
        val CAPTURE_CARD_VENDORS=mapOf(0x0FD9 to "Elgato",0x07CA to "AVerMedia",0x2935 to "Magewell",0x534D to "Blackmagic",0x1B80 to "Hauppauge",0x0572 to "Conexant",0x04F2 to "Chicony")
        val AUDIO_INTERFACE_VENDORS=mapOf(0x1235 to "Focusrite",0x0582 to "Roland",0x0763 to "M-Audio",0x1397 to "BEHRINGER",0x07FD to "MOTU",0x0644 to "TEAC",0x2573 to "ESI",0x17CC to "Native Instruments",0x0D8C to "C-Media",0x08BB to "Texas Instruments")
        fun estimateBandwidth(width:Int,height:Int,fps:Int,format:String):Int{
            val bpp=when(format.uppercase()){"MJPEG"->0.5f;"H264","HEVC","H265"->0.18f;"YUY2","YUYV"->2f;"NV12"->1.5f;else->2f}
            return ((width.toLong()*height*fps*bpp*8)/1_000_000L).toInt().coerceAtLeast(1)
        }
    }

    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){when(i.action){UsbManager.ACTION_USB_DEVICE_ATTACHED->extra(i)?.let{requestOrOpen(it)};UsbManager.ACTION_USB_DEVICE_DETACHED->extra(i)?.let{remove(it)};ACTION_USB_PERMISSION->extra(i)?.let{if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))openAndRegister(it)}}}}
    private fun extra(i:Intent):UsbDevice?=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun initialize(){if(!receiverRegistered){val f=IntentFilter().apply{addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);addAction(ACTION_USB_PERMISSION)};context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);receiverRegistered=true};usb.deviceList.values.forEach(::requestOrOpen)}
    @Synchronized fun shutdown(){sessions.values.forEach{runCatching{it.stop()}};sessions.clear();sessionSignatures.clear();connections.values.forEach{runCatching{it.close()}};connections.clear();_devices.value=emptyList();updateBudget();if(receiverRegistered){runCatching{context.unregisterReceiver(receiver)};receiverRegistered=false}}

    private fun requestOrOpen(device:UsbDevice){if(!isInteresting(device))return;if(usb.hasPermission(device))openAndRegister(device)else{val pi=PendingIntent.getBroadcast(context,device.deviceId,Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE);usb.requestPermission(device,pi)}}
    private fun openAndRegister(device:UsbDevice){if(_devices.value.any{it.deviceId==device.deviceId})return;val c=usb.openDevice(device)?:return;val type=classify(device);if(type==UsbDeviceType.UNKNOWN){c.close();return};connections[device.deviceId]=c;val formats=if(type==UsbDeviceType.AUDIO_INPUT)emptyList() else UvcCaptureSession.listFormats(device,c).map{"${it.width}x${it.height}@${it.fps}:${it.codec}"}.distinct();val info=UsbDeviceInfo(device.deviceId,device.deviceName,displayName(device,type),device.vendorId,device.productId,device.manufacturerName,device.productName,runCatching{device.serialNumber}.getOrNull(),type,detectSpeed(c),0,true,false,"","",formats,0,type==UsbDeviceType.COMPOSITE_AV||type==UsbDeviceType.AUDIO_INPUT);_devices.value=_devices.value+info;updateBudget();Timber.i("USB registered: ${info.displayName}; formats=${formats.size}")}
    @Synchronized private fun remove(device:UsbDevice){sessions.remove(device.deviceId)?.stop();sessionSignatures.remove(device.deviceId);connections.remove(device.deviceId)?.close();_devices.value=_devices.value.filterNot{it.deviceId==device.deviceId};updateBudget()}

    private fun isInteresting(d:UsbDevice):Boolean{for(i in 0 until d.interfaceCount){val c=d.getInterface(i).interfaceClass;if(c==14||c==1||c==3)return true};return d.vendorId in CAPTURE_CARD_VENDORS.keys||d.vendorId in AUDIO_INTERFACE_VENDORS.keys}
    private fun classify(d:UsbDevice):UsbDeviceType{var v=false;var a=false;var h=false;for(i in 0 until d.interfaceCount){when(d.getInterface(i).interfaceClass){14->v=true;1->a=true;3->h=true}};return when{v&&a&&d.vendorId in CAPTURE_CARD_VENDORS->UsbDeviceType.COMPOSITE_AV;v&&d.vendorId in CAPTURE_CARD_VENDORS->UsbDeviceType.CAPTURE_CARD;v->UsbDeviceType.VIDEO_CAMERA;a->UsbDeviceType.AUDIO_INPUT;h->UsbDeviceType.HID_CONTROLLER;d.vendorId in AUDIO_INTERFACE_VENDORS->UsbDeviceType.AUDIO_INPUT;else->UsbDeviceType.UNKNOWN}}
    private fun displayName(d:UsbDevice,t:UsbDeviceType)=d.productName?:when(t){UsbDeviceType.CAPTURE_CARD->"USB Capture Card";UsbDeviceType.VIDEO_CAMERA->"USB Camera";UsbDeviceType.COMPOSITE_AV->"USB A/V Capture Device";UsbDeviceType.AUDIO_INPUT->"USB Audio Input";UsbDeviceType.HID_CONTROLLER->"USB Controller";else->d.deviceName}
    private fun detectSpeed(c:UsbDeviceConnection):UsbSpeed{val r=c.rawDescriptors;if(r.size>=4){val bcd=(r[2].toInt() and 0xFF) or ((r[3].toInt() and 0xFF) shl 8);return when{bcd>=0x0300->UsbSpeed.USB_3_0;bcd>=0x0200->UsbSpeed.USB_2_0;else->UsbSpeed.USB_1_1}};return UsbSpeed.UNKNOWN}

    @Synchronized fun startCapture(deviceId:Int,width:Int,height:Int,fps:Int,format:String,sourceId:String="usb_$deviceId",sourceConfigJson:String="{}"):Boolean{
        val info=_devices.value.find{it.deviceId==deviceId}?:return false;val conn=connections[deviceId]?:return false;if(info.deviceType==UsbDeviceType.AUDIO_INPUT)return false
        val cfg=runCatching{JSONObject(sourceConfigJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(JSONObject())
        val signature="$sourceId|$width|$height|$fps|$format|${cfg.optString("videoDecoderPreference","hardware")}"
        if(sessions[deviceId]!=null&&sessionSignatures[deviceId]==signature)return true
        val bw=estimateBandwidth(width,height,fps,format)
        val replacingBandwidth=if(sessions[deviceId]!=null)info.estimatedBandwidthMbps else 0
        if(bw>_budget.value.availableBandwidthMbps+replacingBandwidth)return false
        return runCatching{
            sessions.remove(deviceId)?.stop()
            val s=UvcCaptureSession(usb.deviceList.values.first{it.deviceId==deviceId},conn,sourceId,width,height,fps,format,sourceConfigJson)
            try{s.start()}catch(error:Throwable){runCatching{s.stop()};throw error}
            sessions[deviceId]=s
            sessionSignatures[deviceId]=signature
            _devices.value=_devices.value.map{if(it.deviceId==deviceId)it.copy(isCapturing=true,currentFormat="${width}x${height}@${fps}:$format",transport=s.transport(),estimatedBandwidthMbps=bw)else it};updateBudget();true
        }.onFailure{Timber.e(it,"UVC capture start failed for $deviceId");sessions.remove(deviceId)?.let{runCatching{it.stop()}};sessionSignatures.remove(deviceId);_devices.value=_devices.value.map{if(it.deviceId==deviceId)it.copy(isCapturing=false,currentFormat="",transport="",estimatedBandwidthMbps=0)else it};updateBudget()}.getOrDefault(false)
    }

    @Synchronized fun videoControls(deviceId:Int):List<UvcVideoControl>{
        val device=usb.deviceList.values.firstOrNull{it.deviceId==deviceId}?:return emptyList()
        val connection=connections[deviceId]?:return emptyList()
        return runCatching{UvcVideoControls.list(connection)}.getOrDefault(emptyList())
    }

    @Synchronized fun setVideoControl(deviceId:Int,key:String,value:Int):Boolean{
        val device=usb.deviceList.values.firstOrNull{it.deviceId==deviceId}?:return false
        val connection=connections[deviceId]?:return false
        return runCatching{UvcVideoControls.set(connection,key,value)}.getOrDefault(false)
    }

    private fun UvcCaptureSession.transport():String = currentTransport()

    @Synchronized fun stopCapture(deviceId:Int){sessions.remove(deviceId)?.stop();sessionSignatures.remove(deviceId);_devices.value=_devices.value.map{if(it.deviceId==deviceId)it.copy(isCapturing=false,currentFormat="",transport="",estimatedBandwidthMbps=0)else it};updateBudget()}
    private fun updateBudget(){val used=_devices.value.sumOf{it.estimatedBandwidthMbps};val total=when(_devices.value.maxOfOrNull{it.usbSpeed.bandwidthMbps}?:5000){in 10000..Int.MAX_VALUE->10000;in 5000..9999->5000;else->480};_budget.value=UsbBandwidthBudget(total,used,(total-used).coerceAtLeast(0),_devices.value)}
}
