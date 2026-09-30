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
class RecordingService:Service(){
    @Inject lateinit var engine:StreamEngine
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        val n=ServiceNotifications.notification(this,ServiceNotifications.RECORDING_CHANNEL,ServiceNotifications.RECORDING_ID,"Stream4k60","Recording is running")
        if(Build.VERSION.SDK_INT>=29)startForeground(ServiceNotifications.RECORDING_ID,n,ServiceNotifications.captureServiceTypes(this)) else startForeground(ServiceNotifications.RECORDING_ID,n)
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val ACTION_STOP="com.stream4k60.STOP_RECORDING";fun start(context:android.content.Context){ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java))};fun stop(context:android.content.Context){context.stopService(Intent(context,RecordingService::class.java))}}
}
