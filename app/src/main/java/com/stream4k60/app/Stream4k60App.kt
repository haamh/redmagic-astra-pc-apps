package com.stream4k60.app

import android.app.Application
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.AstraDeviceMonitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class Stream4k60App:Application(){
    @Inject lateinit var settingsRepository: SettingsRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(){
        super.onCreate()
        AstraDeviceMonitor.start(this)
        if(BuildConfig.DEBUG)Timber.plant(Timber.DebugTree())
        check(NativeEngine.initializeRenderer(3840,2160,60)){"Native GPU compositor initialization failed"}
        appScope.launch {
            settingsRepository.videoConfig.collectLatest { config ->
                NativeEngine.setVideoSettings(config.baseResWidth, config.baseResHeight, config.frameRate)
            }
        }
    }
}
