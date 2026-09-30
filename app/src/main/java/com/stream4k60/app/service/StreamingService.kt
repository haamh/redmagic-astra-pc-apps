package com.stream4k60.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.stream4k60.app.engine.StreamEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class StreamingService:Service(){
    @Inject lateinit var engine:StreamEngine
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        val n=ServiceNotifications.notification(this,ServiceNotifications.STREAMING_CHANNEL,ServiceNotifications.STREAMING_ID,"Stream4k60","YouTube stream is running")
        if(Build.VERSION.SDK_INT>=29)startForeground(ServiceNotifications.STREAMING_ID,n,ServiceNotifications.captureServiceTypes(this)) else startForeground(ServiceNotifications.STREAMING_ID,n)
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val ACTION_STOP="com.stream4k60.STOP_STREAM";fun start(context:android.content.Context){ContextCompat.startForegroundService(context,Intent(context,StreamingService::class.java))};fun stop(context:android.content.Context){context.stopService(Intent(context,StreamingService::class.java))}}
}
