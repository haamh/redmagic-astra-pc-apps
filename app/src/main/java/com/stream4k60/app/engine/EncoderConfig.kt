package com.stream4k60.app.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList

data class EncoderInfo(
    val name: String,
    val displayName: String,
    val mimeType: String,
    val isHardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val supportedBitrateModes: List<Int>,
    val supportedProfiles: List<Int>
)

object EncoderCapabilities {
    fun achievableMaxFrameRate(mime: String, width: Int, height: Int, fps: Int): Int? = runCatching {
        val info = HardwareVideoEncoder.findHardwareEncoder(mime, width, height, fps) ?: return null
        val range = info.getCapabilitiesForType(mime).videoCapabilities?.getAchievableFrameRatesFor(width, height) ?: return null
        range.upper.toInt()
    }.getOrNull()

    fun supportedBitrateRangeKbps(mime: String, width: Int, height: Int, fps: Int): IntRange? = runCatching {
        val info = HardwareVideoEncoder.findHardwareEncoder(mime, width, height, fps) ?: return null
        val range = info.getCapabilitiesForType(mime).videoCapabilities?.bitrateRange ?: return null
        (range.lower / 1_000)..(range.upper / 1_000)
    }.getOrNull()

    fun getAvailableVideoEncoders(): List<EncoderInfo> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = mutableListOf<EncoderInfo>()
        
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                if (type.startsWith("video/")) {
                    val caps = codecInfo.getCapabilitiesForType(type)
                    val videoCaps = caps.videoCapabilities ?: continue
                    encoders.add(EncoderInfo(
                        name = codecInfo.name,
                        displayName = getDisplayName(codecInfo.name, type, codecInfo.isHardwareAccelerated && !codecInfo.isSoftwareOnly),
                        mimeType = type,
                        isHardware = codecInfo.isHardwareAccelerated && !codecInfo.isSoftwareOnly,
                        maxWidth = videoCaps.supportedWidths.upper,
                        maxHeight = videoCaps.supportedHeights.upper,
                        supportedBitrateModes = caps.encoderCapabilities?.let { 
                            listOfNotNull(
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else null,
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else null,
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ else null
                            )
                        } ?: emptyList(),
                        supportedProfiles = caps.profileLevels.map { it.profile }.distinct()
                    ))
                }
            }
        }
        return encoders
    }
    
    fun getAvailableAudioEncoders(): List<EncoderInfo> {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info -> info.supportedTypes.filter { it.startsWith("audio/") }.mapNotNull { type ->
                runCatching {
                    val caps = info.getCapabilitiesForType(type)
                    EncoderInfo(
                        name = info.name,
                        displayName = getDisplayName(info.name, type, info.isHardwareAccelerated && !info.isSoftwareOnly),
                        mimeType = type,
                        isHardware = info.isHardwareAccelerated && !info.isSoftwareOnly,
                        maxWidth = 0,
                        maxHeight = 0,
                        supportedBitrateModes = caps.encoderCapabilities?.let {
                            listOfNotNull(
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else null,
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else null,
                                if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ else null
                            )
                        } ?: emptyList(),
                        supportedProfiles = caps.profileLevels.map { it.profile }.distinct()
                    )
                }.getOrNull()
            } }
    }
    
    private fun getDisplayName(codecName: String, mimeType: String, isHardware: Boolean): String {
        val codec = when {
            mimeType.contains("avc") -> "H.264"
            mimeType.contains("hevc") -> "H.265/HEVC"
            mimeType.contains("av01") -> "AV1"
            mimeType.contains("vp9") -> "VP9"
            else -> mimeType
        }
        val hw = if (!isHardware) "Software" else if (codecName.contains("qcom", true) || codecName.contains("qti", true)) "Qualcomm" else "Hardware"
        return "$hw $codec"
    }
    
    fun supports4K60(): Boolean {
        return supportsVideo("video/avc", 3840, 2160, 60) || supportsVideo("video/hevc", 3840, 2160, 60)
    }
    
    fun supportsHDR(): Boolean {
        return getAvailableVideoEncoders().any {
            it.supportedProfiles.any { profile ->
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
            }
        }
    }

    fun supportsVideo(mime: String, width: Int, height: Int, fps: Int, hardwareOnly: Boolean = true): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && (!hardwareOnly || (info.isHardwareAccelerated && !info.isSoftwareOnly)) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                    if (!caps.isSizeSupported(width, height)) return@runCatching false
                    val rates = caps.getSupportedFrameRatesFor(width, height)
                    fps.toDouble() in rates.lower..rates.upper
                }.getOrDefault(false)
        }
}
