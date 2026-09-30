package com.stream4k60.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.stream4k60.app.R

object ServiceNotifications{
    const val STREAMING_CHANNEL="stream4k_streaming";const val RECORDING_CHANNEL="stream4k_recording";const val SCREEN_CHANNEL="stream4k_screen";const val AUDIO_CHANNEL="stream4k_audio"
    const val STREAMING_ID=1101;const val RECORDING_ID=1102;const val SCREEN_ID=1103;const val AUDIO_ID=1104
    /**
     * Foreground-service types for streaming/recording. Android 14+ throws if a service claims camera or
     * microphone without that permission granted; USB capture needs neither, so "connected device" is always claimed.
     */
    fun captureServiceTypes(context:Context):Int{
        var types=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if(context.checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)types=types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)types=types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }
    fun notification(context:Context,channel:String,id:Int,title:String,text:String):Notification{
        val nm=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(channel,title,NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.presence_video_online).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()
    }
}
