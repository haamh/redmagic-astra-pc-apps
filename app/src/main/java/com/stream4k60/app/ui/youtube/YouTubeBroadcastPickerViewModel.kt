package com.stream4k60.app.ui.youtube

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.model.*
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.HardwareVideoEncoder
import com.stream4k60.app.engine.StreamOutputSession
import com.stream4k60.app.youtube.YouTubeAccountManager
import com.stream4k60.app.youtube.YouTubeBroadcast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeBroadcastPickerViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    private val manager = YouTubeAccountManager()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _broadcasts = MutableStateFlow<List<YouTubeBroadcast>>(emptyList())
    val broadcasts: StateFlow<List<YouTubeBroadcast>> = _broadcasts.asStateFlow()
    private val _videoConfig = MutableStateFlow(VideoConfig())
    val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.videoConfig.collect {
                _videoConfig.value = it
                _settingsLoaded.value = true
            }
        }
    }

    fun authorize(activity: Activity, onResolution: (IntentSenderRequest) -> Unit, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch { manager.authorize(activity, onResolution, onDone) }
    }

    fun handleResult(activity: Activity, intent: Intent?, onDone: (Boolean, String?) -> Unit) {
        manager.handleAuthorizationResult(activity, intent, onDone)
    }

    fun load() {
        viewModelScope.launch {
            runCatching { manager.service().listBroadcasts() }
                .onSuccess { _broadcasts.value = it }
        }
    }

    fun toConfig(broadcast: YouTubeBroadcast): StreamConfig {
        check(_settingsLoaded.value) { "Loading active profile video settings" }
        val video = _videoConfig.value
        val width = video.outputResWidth
        val height = video.outputResHeight
        val fps = video.frameRate

        require(!video.hdrEnabled) {
            "HDR output is not available until the 10-bit GPU composition path is complete. Turn off HDR in Video settings."
        }
        require(fps <= 60) {
            "YouTube output supports up to 60 FPS. Change the active profile frame rate before streaming."
        }
        require(width in 320..3840 && height in 240..2160 && width % 2 == 0 && height % 2 == 0) {
            "Choose an even output size no larger than 3840 × 2160 in Video settings."
        }

        val useHls = broadcast.ingestionType.equals("hls", true) && !broadcast.ingestionUrl.isNullOrBlank()
        val protocol = if (useHls) StreamProtocol.HLS else StreamProtocol.RTMPS
        val ingestUrl = if (protocol == StreamProtocol.RTMPS) broadcast.rtmpsUrl ?: broadcast.ingestionUrl else broadcast.ingestionUrl
        val codec = video.outputCodec
        val mime = if (codec == OutputCodec.HEVC) "video/hevc" else "video/avc"
        require(HardwareVideoEncoder.supportsResolution(mime, width, height, fps)) {
            "The selected ${codec.name} hardware encoder does not support ${width} × ${height} at $fps FPS on this device."
        }
        return StreamConfig(
            service = StreamService.YOUTUBE,
            protocol = protocol,
            ingestionUrl = ingestUrl.orEmpty(),
            streamName = broadcast.streamName.orEmpty(),
            broadcastId = broadcast.id,
            outputCodec = codec,
            outputWidth = width,
            outputHeight = height,
            fps = fps,
            bitrate = video.videoBitrateKbps * 1_000
        )
    }
}
