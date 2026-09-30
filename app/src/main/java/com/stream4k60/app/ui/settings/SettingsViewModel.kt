package com.stream4k60.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.model.VideoConfig
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.data.model.OutputCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _videoConfig = MutableStateFlow(VideoConfig())
    val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.videoConfig.collect { config ->
                _videoConfig.value = config
                NativeEngine.setVideoSettings(config.baseResWidth, config.baseResHeight, config.frameRate)
            }
        }
    }

    private val _theme = MutableStateFlow("Dark")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _language = MutableStateFlow("English")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _streamService = MutableStateFlow("Twitch")
    val streamService = _streamService.asStateFlow()
    
    private val _streamKey = MutableStateFlow("")
    val streamKey = _streamKey.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()

    // Add setters for all states
    fun setTheme(theme: String) { _theme.value = theme }
    fun setLanguage(language: String) { _language.value = language }
    fun setStreamService(service: String) { _streamService.value = service }
    fun setStreamKey(key: String) { _streamKey.value = key }
    fun setServerUrl(url: String) { _serverUrl.value = url }
    fun setVideoBitrate(bitrateKbps: Int) {
        setVideoConfig(_videoConfig.value.copy(videoBitrateKbps = bitrateKbps.coerceIn(1_000, 100_000)))
    }
    fun setEncoder(encoder: String) {
        val codec = if (encoder == "H.265 / HEVC") OutputCodec.HEVC else OutputCodec.H264
        setVideoConfig(_videoConfig.value.copy(outputCodec = codec))
    }

    fun setVideoConfig(config: VideoConfig) {
        val bounded = config.copy(
            baseResWidth = config.baseResWidth.coerceIn(320, 3840),
            baseResHeight = config.baseResHeight.coerceIn(240, 2160),
            outputResWidth = config.outputResWidth.coerceIn(320, 3840),
            outputResHeight = config.outputResHeight.coerceIn(240, 2160),
            fpsInt = config.fpsInt.coerceIn(1, 120),
            fpsNum = config.fpsNum.coerceIn(1, 120_000),
            videoBitrateKbps = config.videoBitrateKbps.coerceIn(1_000, 100_000)
        )
        _videoConfig.value = bounded
        NativeEngine.setVideoSettings(bounded.baseResWidth, bounded.baseResHeight, bounded.frameRate)
        viewModelScope.launch { settingsRepository.saveVideoConfig(bounded) }
    }

    fun resetSettings() {
        setVideoConfig(VideoConfig())
    }

    fun resetVideoSettings() {
        val defaults = VideoConfig()
        setVideoConfig(_videoConfig.value.copy(
            baseResWidth = defaults.baseResWidth,
            baseResHeight = defaults.baseResHeight,
            outputResWidth = defaults.outputResWidth,
            outputResHeight = defaults.outputResHeight,
            fpsType = defaults.fpsType,
            fpsCommon = defaults.fpsCommon,
            fpsInt = defaults.fpsInt,
            fpsNum = defaults.fpsNum,
            fpsDen = defaults.fpsDen,
            downscaleFilter = defaults.downscaleFilter,
            colorFormat = defaults.colorFormat,
            colorSpace = defaults.colorSpace,
            colorRange = defaults.colorRange,
            hdrEnabled = defaults.hdrEnabled
        ))
    }

    fun resetOutputSettings() {
        val defaults = VideoConfig()
        setVideoConfig(_videoConfig.value.copy(
            videoBitrateKbps = defaults.videoBitrateKbps,
            outputCodec = defaults.outputCodec
        ))
    }
}
