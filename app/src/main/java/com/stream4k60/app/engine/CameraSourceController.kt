package com.stream4k60.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.stream4k60.app.data.model.SourceType
import com.stream4k60.app.ui.main.SourceItem
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class CameraSourceController(private val context:Context){
 private val cameraManager=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
 private val thread=HandlerThread("Stream4k-Camera").apply{start()};private val handler=Handler(thread.looper);private val active=ConcurrentHashMap<String, CameraDevice>()
 private val sessions=ConcurrentHashMap<String, CameraCaptureSession>()
 private val sourceConfigs=ConcurrentHashMap<String,String>()
 @SuppressLint("MissingPermission")
 fun sync(sources:List<SourceItem>){
  val wanted=sources.filter{it.isVisible&&it.type.equals("CAMERA",true)}
  val ids=wanted.map{it.id}.toSet()
  sourceConfigs.keys.filter{it !in ids}.forEach(::stop)
  wanted.forEach{source->
   if(sourceConfigs.containsKey(source.id) && sourceConfigs[source.id]!=source.configJson)stop(source.id)
   if(!sourceConfigs.containsKey(source.id))start(source.id,source.configJson)
  }
 }
 @SuppressLint("MissingPermission")
 private fun start(id:String,sourceConfigJson:String){
  SourceRuntimeErrors.clear(id)
  val ids=runCatching{cameraManager.cameraIdList.toList()}.getOrElse{SourceRuntimeErrors.report(id,"Android could not enumerate cameras: ${it.message ?: "camera service error"}");return};if(ids.isEmpty()){SourceRuntimeErrors.report(id,"Android reports no available camera devices.");return}
  val config=runCatching{org.json.JSONObject(sourceConfigJson).optJSONObject("settings")?:org.json.JSONObject(sourceConfigJson)}.getOrDefault(org.json.JSONObject())
  val facing=when(config.optString("facing","BACK").uppercase()){"FRONT"->CameraCharacteristics.LENS_FACING_FRONT;"EXTERNAL"->CameraCharacteristics.LENS_FACING_EXTERNAL;else->CameraCharacteristics.LENS_FACING_BACK}
  val requestedCameraId=config.optString("cameraId").takeIf(String::isNotBlank)
  val cameraId=requestedCameraId?.takeIf{it in ids} ?: ids.firstOrNull{cameraId->runCatching{cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)==facing}.getOrDefault(false)}?:run{SourceRuntimeErrors.report(id,"No Android camera matches the selected device or lens facing.");return}
  val characteristics=runCatching{cameraManager.getCameraCharacteristics(cameraId)}.getOrElse{SourceRuntimeErrors.report(id,"Could not read the selected camera capabilities: ${it.message ?: "camera error"}");return}
  val map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:run{SourceRuntimeErrors.report(id,"The selected camera did not report supported output sizes.");return}
  val fpsRanges=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?:emptyArray()
  val target=android.util.Size(config.optInt("width",1920).coerceAtLeast(160),config.optInt("height",1080).coerceAtLeast(120))
  val size=map.getOutputSizes(SurfaceTexture::class.java)?.minByOrNull{abs(it.width-target.width)+abs(it.height-target.height)}?:run{SourceRuntimeErrors.report(id,"The selected camera has no compatible SurfaceTexture output format.");return}
  val fps=config.optInt("fps",30).coerceIn(1,240)
  val surface=runCatching{NativeEngine.createSourceSurface(id)}.getOrNull()?:run{SourceRuntimeErrors.report(id,"Could not create the camera's compositor surface.");return}
  NativeEngine.setSourceEffectsFromConfig(id,sourceConfigJson)
  NativeEngine.setSourceBufferSize(id,size.width,size.height)
  sourceConfigs[id]=sourceConfigJson
  runCatching {
   cameraManager.openCamera(cameraId,object:CameraDevice.StateCallback(){
    override fun onOpened(d:CameraDevice){active[id]=d;runCatching{d.createCaptureSession(listOf(surface),object:CameraCaptureSession.StateCallback(){
     override fun onConfigured(s:CameraCaptureSession){runCatching{sessions[id]=s;val req=d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{addTarget(surface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);if(fpsRanges.isNotEmpty())set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chooseFps(fpsRanges,fps))};s.setRepeatingRequest(req.build(),null,handler);SourceRuntimeErrors.clear(id)}.onFailure{stop(id,"Camera capture request failed: ${it.message ?: "unknown camera error"}")}}
     override fun onConfigureFailed(s:CameraCaptureSession){stop(id,"Android could not configure a capture session for this camera.")}
    },handler)}.onFailure{stop(id,"Could not create a camera capture session: ${it.message ?: "camera error"}")}}
    override fun onDisconnected(d:CameraDevice){stop(id,"The Android camera disconnected.")}
    override fun onError(d:CameraDevice,e:Int){stop(id,"Android camera error ($e).")}
   },handler)
  }.onFailure{stop(id,"Could not open the selected Android camera: ${it.message ?: "permission or device error"}")}
 }
 private fun chooseFps(r:Array<android.util.Range<Int>>,target:Int)=r.minByOrNull{range->if(target in range.lower..range.upper)0 else minOf(abs(target-range.lower),abs(target-range.upper))}?:android.util.Range(30,30)
 fun stop(id:String,error:String?=null){sourceConfigs.remove(id);sessions.remove(id)?.close();active.remove(id)?.close();NativeEngine.releaseSourceSurface(id);if(error==null)SourceRuntimeErrors.clear(id)else SourceRuntimeErrors.report(id,error)}
 fun stopAll(){active.keys.toList().forEach(::stop);thread.quitSafely()}
}
