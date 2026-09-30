package com.stream4k60.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.content.ContextCompat
import com.stream4k60.app.engine.NativeEngine
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScreenCaptureService:Service(){
    private var projection:android.media.projection.MediaProjection?=null;private var display:android.hardware.display.VirtualDisplay?=null;private var surface:Surface?=null;private var sourceId:String?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopCapture();return START_NOT_STICKY}
        val n=ServiceNotifications.notification(this,ServiceNotifications.SCREEN_CHANNEL,ServiceNotifications.SCREEN_ID,"Screen capture","Screen source is active")
        if(Build.VERSION.SDK_INT>=29)startForeground(ServiceNotifications.SCREEN_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ServiceNotifications.SCREEN_ID,n)
        val result=intent?.getIntExtra(EXTRA_RESULT,android.app.Activity.RESULT_CANCELED)?:return START_NOT_STICKY
        val data=intent.getParcelableExtra<Intent>(EXTRA_DATA)?:return START_NOT_STICKY
        sourceId=intent.getStringExtra(EXTRA_SOURCE)?:"screen"
        val w=intent.getIntExtra(EXTRA_WIDTH,1920);val h=intent.getIntExtra(EXTRA_HEIGHT,1080);val dpi=intent.getIntExtra(EXTRA_DPI,resources.displayMetrics.densityDpi)
        runCatching{startProjection(result,data,w,h,dpi)}.onFailure{stopCapture()}
        return START_STICKY
    }
    private fun startProjection(result:Int,data:Intent,w:Int,h:Int,dpi:Int){stopCapture(false);val pm=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;projection=pm.getMediaProjection(result,data);val id=sourceId?:return;surface=NativeEngine.createSourceSurface(id)?:error("Could not create screen source surface");NativeEngine.setSourceBufferSize(id,w,h);display=projection!!.createVirtualDisplay("Stream4k60",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)}
    private fun stopCapture(stopService:Boolean=true){display?.release();display=null;projection?.stop();projection=null;surface?.release();surface=null;sourceId?.let{NativeEngine.releaseSourceSurface(it)};sourceId=null;if(stopService){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
    override fun onDestroy(){stopCapture(false);super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
    companion object{fun stop(context:android.content.Context){context.stopService(Intent(context,ScreenCaptureService::class.java))}
    const val ACTION_START="com.stream4k60.START_SCREEN";const val ACTION_STOP="com.stream4k60.STOP_SCREEN";const val EXTRA_RESULT="result";const val EXTRA_DATA="data";const val EXTRA_SOURCE="source";const val EXTRA_WIDTH="width";const val EXTRA_HEIGHT="height";const val EXTRA_DPI="dpi";fun start(context:android.content.Context,result:Int,data:Intent,sourceId:String,w:Int=1920,h:Int=1080,dpi:Int=context.resources.displayMetrics.densityDpi){ContextCompat.startForegroundService(context,Intent(context,ScreenCaptureService::class.java).apply{action=ACTION_START;putExtra(EXTRA_RESULT,result);putExtra(EXTRA_DATA,data);putExtra(EXTRA_SOURCE,sourceId);putExtra(EXTRA_WIDTH,w);putExtra(EXTRA_HEIGHT,h);putExtra(EXTRA_DPI,dpi)})}}
}
