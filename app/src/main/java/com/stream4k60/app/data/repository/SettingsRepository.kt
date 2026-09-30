package com.stream4k60.app.data.repository

import com.stream4k60.app.data.local.dao.ProfileDao
import com.stream4k60.app.data.local.entity.ProfileEntity
import com.stream4k60.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.*

class SettingsRepository(private val profiles: ProfileDao) {
    val importedRtmpEndpoint: Flow<ImportedRtmpEndpoint?> = profiles.observeActive()
        .map { profile -> profile?.let(::parseImportedRtmpEndpoint) }
        .distinctUntilChanged()

    val videoConfig: Flow<VideoConfig> = profiles.observeActive()
        .map { entity -> parseVideoConfig(entity?.configJson) }
        .distinctUntilChanged()
        .onStart { emit(parseVideoConfig(ensureActiveProfile().configJson)) }

    /** Saved destination; falls back to the service imported from OBS until the user saves one. */
    val streamSettings: Flow<StreamSettings> = profiles.observeActive()
        .map { profile -> profile?.let(::parseStreamSettings) ?: StreamSettings() }
        .distinctUntilChanged()

    val generalSettings: Flow<GeneralSettings> = profiles.observeActive()
        .map { profile -> parseGeneralSettings(profile?.configJson) }
        .distinctUntilChanged()

    suspend fun saveStreamSettings(settings: StreamSettings) = updateSection("stream", buildJsonObject {
        put("service", settings.service.name)
        put("server", settings.server.trim())
        put("key", settings.streamKey.trim())
    })

    suspend fun saveGeneralSettings(settings: GeneralSettings) = updateSection("general", buildJsonObject {
        put("confirmStopStreaming", settings.confirmStopStreaming)
        put("confirmStopRecording", settings.confirmStopRecording)
        put("autoRecordWhenStreaming", settings.autoRecordWhenStreaming)
        put("snappingEnabled", settings.snappingEnabled)
        put("snapToSources", settings.snapToSources)
    })

    private suspend fun updateSection(key: String, value: JsonObject) {
        val profile = ensureActiveProfile()
        val root = runCatching { Json.parseToJsonElement(profile.configJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        profiles.updateConfigJson(profile.id, JsonObject(root + (key to value)).toString())
    }

    private fun parseStreamSettings(profile: ProfileEntity): StreamSettings {
        val root = runCatching { Json.parseToJsonElement(profile.configJson) as? JsonObject }.getOrNull()
        val stream = root?.get("stream") as? JsonObject
        if (stream != null) {
            fun text(k: String) = stream[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            return StreamSettings(
                StreamService.entries.firstOrNull { it.name == text("service") } ?: StreamService.CUSTOM,
                text("server"), text("key")
            )
        }
        val imported = parseImportedRtmpEndpoint(profile) ?: return StreamSettings()
        val service = when {
            imported.serverUrl.contains("youtube", true) -> StreamService.YOUTUBE
            imported.serverUrl.contains("twitch", true) -> StreamService.TWITCH
            imported.serverUrl.contains("facebook", true) -> StreamService.FACEBOOK
            else -> StreamService.CUSTOM
        }
        return StreamSettings(service, imported.serverUrl, imported.streamKey)
    }

    private fun parseGeneralSettings(configJson: String?): GeneralSettings {
        val general = (runCatching { Json.parseToJsonElement(configJson ?: "{}") as? JsonObject }.getOrNull()?.get("general") as? JsonObject)
            ?: return GeneralSettings()
        fun bool(k: String, d: Boolean) = general[k]?.jsonPrimitive?.booleanOrNull ?: d
        val defaults = GeneralSettings()
        return GeneralSettings(
            bool("confirmStopStreaming", defaults.confirmStopStreaming), bool("confirmStopRecording", defaults.confirmStopRecording),
            bool("autoRecordWhenStreaming", defaults.autoRecordWhenStreaming), bool("snappingEnabled", defaults.snappingEnabled),
            bool("snapToSources", defaults.snapToSources)
        )
    }

    private fun parseImportedRtmpEndpoint(profile: ProfileEntity): ImportedRtmpEndpoint? = runCatching {
        val root = Json.parseToJsonElement(profile.obsServiceJson) as? JsonObject ?: return null
        val settings = root["settings"] as? JsonObject ?: root
        fun value(obj: JsonObject, key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        val server = value(settings, "server")?.takeIf { it.isNotBlank() } ?: return null
        val key = value(settings, "key")?.takeIf { it.isNotBlank() }
            ?: value(root, "key")?.takeIf { it.isNotBlank() }
            ?: return null
        val protocol = if (server.startsWith("rtmps://", true)) StreamProtocol.RTMPS else StreamProtocol.RTMP
        ImportedRtmpEndpoint(server, key, protocol)
    }.getOrNull()

    suspend fun saveVideoConfig(config: VideoConfig) {
        val profile = ensureActiveProfile()
        val root = runCatching { Json.parseToJsonElement(profile.configJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())
        val video = buildJsonObject {
            put("baseWidth", config.baseResWidth)
            put("baseHeight", config.baseResHeight)
            put("outputWidth", config.outputResWidth)
            put("outputHeight", config.outputResHeight)
            put("videoBitrateKbps", config.videoBitrateKbps)
            put("outputCodec", config.outputCodec.name)
            put("fps", config.frameRate)
            put("fpsType", config.fpsType.name)
            put("fpsInt", config.fpsInt)
            put("fpsNum", config.fpsNum)
            put("fpsDen", config.fpsDen)
            put("downscaleFilter", config.downscaleFilter.name)
            put("colorFormat", config.colorFormat.name)
            put("colorSpace", when (config.colorSpace) {
                ColorSpace.SRGB -> "sRGB"
                ColorSpace.REC709 -> "709"
                ColorSpace.REC2100PQ -> "2100PQ"
                ColorSpace.REC2100HLG -> "2100HLG"
            })
            put("colorRange", if (config.colorRange == ColorRange.FULL) "Full" else "Partial")
            put("hdrEnabled", config.hdrEnabled)
        }
        profiles.updateConfigJson(profile.id, JsonObject(root + ("video" to video)).toString())
    }

    private suspend fun ensureActiveProfile(): ProfileEntity {
        profiles.active()?.let { return it }
        profiles.insertIfMissing(
            ProfileEntity(
                id = DEFAULT_PROFILE_ID,
                name = "Default",
                configJson = JsonObject(mapOf("video" to encodeVideoConfig(VideoConfig()))).toString(),
                isActive = true
            )
        )
        profiles.active()?.let { return it }
        profiles.setActive(DEFAULT_PROFILE_ID)
        return profiles.active() ?: error("Could not initialize the active profile")
    }

    private fun parseVideoConfig(configJson: String?): VideoConfig {
        val profile = runCatching { Json.parseToJsonElement(configJson ?: "{}") as? JsonObject }.getOrNull() ?: return VideoConfig()
        val video = profile["video"] as? JsonObject ?: return VideoConfig()
        val output = profile["output"] as? JsonObject ?: JsonObject(emptyMap())

        fun int(key: String, default: Int) = video[key]?.jsonPrimitive?.intOrNull ?: default
        fun text(key: String, default: String) = video[key]?.jsonPrimitive?.contentOrNull ?: default
        fun <T : Enum<T>> enumValue(values: Array<T>, key: String, default: T): T =
            values.firstOrNull { it.name.equals(text(key, default.name), ignoreCase = true) } ?: default

        val fpsNum = int("fpsNum", 0)
        val fpsDen = int("fpsDen", 1).coerceAtLeast(1)
        val fps = int("fps", if (fpsNum > 0) fpsNum / fpsDen else 60)
        val common = FpsCommon.entries.firstOrNull { it.value == fps } ?: FpsCommon.FPS_60
        val fpsType = enumValue(FpsType.entries.toTypedArray(), "fpsType", if (fps in FpsCommon.entries.map { it.value }) FpsType.COMMON else FpsType.INTEGER)
        val codecText = text("outputCodec", output["encoder"]?.jsonPrimitive?.contentOrNull ?: "H264")
        val codec = if (codecText.contains("hevc", true) || codecText.contains("h265", true)) OutputCodec.HEVC else OutputCodec.H264
        val colorFormatText = text("colorFormat", "NV12").uppercase()
        val colorFormat = when {
            colorFormatText.contains("P010") -> ColorFormat.P010
            colorFormatText.contains("I010") -> ColorFormat.I010
            colorFormatText.contains("I444") -> ColorFormat.I444
            colorFormatText.contains("I420") || colorFormatText.contains("YV12") -> ColorFormat.I420
            else -> ColorFormat.NV12
        }
        val scaleText = text("downscaleFilter", "BICUBIC").uppercase()
        val scaleFilter = when {
            scaleText.contains("LANCZOS") || scaleText == "2" -> DownscaleFilter.LANCZOS
            scaleText.contains("BILINEAR") || scaleText == "0" -> DownscaleFilter.BILINEAR
            scaleText.contains("AREA") || scaleText == "3" -> DownscaleFilter.AREA
            else -> DownscaleFilter.BICUBIC
        }
        val colorSpace = when (text("colorSpace", "709").replace(".", "").lowercase()) {
            "srgb", "s rgb" -> ColorSpace.SRGB
            "2100pq", "rec2100pq", "pq" -> ColorSpace.REC2100PQ
            "2100hlg", "rec2100hlg", "hlg" -> ColorSpace.REC2100HLG
            else -> ColorSpace.REC709
        }
        val colorRange = if (text("colorRange", "Partial").equals("Full", true)) ColorRange.FULL else ColorRange.PARTIAL
        return VideoConfig(
            baseResWidth = int("baseWidth", 3840).coerceIn(320, 7680),
            baseResHeight = int("baseHeight", 2160).coerceIn(240, 4320),
            outputResWidth = int("outputWidth", 3840).coerceIn(320, 3840),
            outputResHeight = int("outputHeight", 2160).coerceIn(240, 2160),
            videoBitrateKbps = int("videoBitrateKbps", output["streamBitrateKbps"]?.jsonPrimitive?.intOrNull ?: 40_000).coerceIn(1_000, 100_000),
            outputCodec = codec,
            fpsType = fpsType,
            fpsCommon = common,
            fpsInt = int("fpsInt", fps).coerceIn(1, 120),
            fpsNum = int("fpsNum", fps).coerceIn(1, 120_000),
            fpsDen = fpsDen.coerceIn(1, 1001),
            downscaleFilter = scaleFilter,
            colorFormat = colorFormat,
            colorSpace = colorSpace,
            colorRange = colorRange,
            hdrEnabled = video["hdrEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun encodeVideoConfig(config: VideoConfig) = buildJsonObject {
        put("baseWidth", config.baseResWidth)
        put("baseHeight", config.baseResHeight)
        put("outputWidth", config.outputResWidth)
        put("outputHeight", config.outputResHeight)
        put("videoBitrateKbps", config.videoBitrateKbps)
        put("outputCodec", config.outputCodec.name)
        put("fps", config.frameRate)
        put("fpsType", config.fpsType.name)
        put("fpsInt", config.fpsInt)
        put("fpsNum", config.fpsNum)
        put("fpsDen", config.fpsDen)
        put("downscaleFilter", config.downscaleFilter.name)
        put("colorFormat", config.colorFormat.name)
        put("colorSpace", "709")
        put("colorRange", "Partial")
        put("hdrEnabled", config.hdrEnabled)
    }

    private companion object {
        const val DEFAULT_PROFILE_ID = "stream4k-default-profile"
    }
}
