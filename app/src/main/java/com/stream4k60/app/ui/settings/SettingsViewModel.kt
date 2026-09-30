package com.stream4k60.app.ui.settings

import com.stream4k60.app.data.model.AccessibilitySettings
import com.stream4k60.app.data.model.AdvancedSettings
import com.stream4k60.app.data.model.AudioSettings
import com.stream4k60.app.data.model.GeneralSettings
import com.stream4k60.app.data.model.HotkeyAction
import com.stream4k60.app.data.model.HotkeyBinding
import com.stream4k60.app.engine.HotkeyDispatcher
import kotlinx.coroutines.flow.map
import com.stream4k60.app.data.model.StreamSettings
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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

    val streamSettings: StateFlow<StreamSettings> = settingsRepository.streamSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamSettings())
    val generalSettings: StateFlow<GeneralSettings> = settingsRepository.generalSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GeneralSettings())

    fun saveStreamSettings(settings: StreamSettings) { viewModelScope.launch { settingsRepository.saveStreamSettings(settings) } }
    fun saveGeneralSettings(settings: GeneralSettings) { viewModelScope.launch { settingsRepository.saveGeneralSettings(settings) } }

    val audioSettings: StateFlow<AudioSettings> = settingsRepository.audioSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioSettings())
    val advancedSettings: StateFlow<AdvancedSettings> = settingsRepository.advancedSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdvancedSettings())
    /** Effective bindings: the saved set, or the defaults when the profile has never customised hotkeys. */
    val hotkeys: StateFlow<Map<HotkeyAction, HotkeyBinding>> = settingsRepository.hotkeys
        .map { it ?: HotkeyDispatcher.defaultBindings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HotkeyDispatcher.defaultBindings)
    val accessibilitySettings: StateFlow<AccessibilitySettings> = settingsRepository.accessibilitySettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccessibilitySettings())

    fun saveAudioSettings(settings: AudioSettings) { viewModelScope.launch { settingsRepository.saveAudioSettings(settings) } }
    fun saveAdvancedSettings(settings: AdvancedSettings) { viewModelScope.launch { settingsRepository.saveAdvancedSettings(settings) } }
    fun saveHotkeys(bindings: Map<HotkeyAction, HotkeyBinding>) { viewModelScope.launch { settingsRepository.saveHotkeys(bindings) } }
    fun saveAccessibilitySettings(settings: AccessibilitySettings) {
        viewModelScope.launch { settingsRepository.saveAccessibilitySettings(settings.copy(uiScale = settings.uiScale.coerceIn(0.75f, 1.5f))) }
    }

    // Add setters for all states
    fun setTheme(theme: String) { _theme.value = theme }
    fun setLanguage(language: String) { _language.value = language }
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
