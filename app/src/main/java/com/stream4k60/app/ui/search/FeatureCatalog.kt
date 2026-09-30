package com.stream4k60.app.ui.search

/** Canonical searchable map of the OBS parity surface. Status reflects this Android build, not OBS. */
object FeatureCatalog {
    fun build(
        openYouTube: () -> Unit,
        openCustomStream: () -> Unit,
        openProfiles: () -> Unit,
        openSettings: (String) -> Unit,
        addSource: (String) -> Unit,
        startReplay: () -> Unit,
        toggleStudio: () -> Unit
    ): List<FeatureEntry> = listOf(
        e("Scenes", "Production", "Create, rename, reorder and switch unlimited scenes", "scene collection"),
        e("Scene Collections", "Production", "Separate scene sets for different productions", "scene collection scenes"),
        e("Sources", "Production", "Layer multiple live and static sources in z-order", "layers sources"),
        e("Groups", "Production", "Group sources for shared transforms and visibility", "group folder nesting"),
        e("Nested Scenes", "Production", "Use one scene as a source inside another scene", "scene as source nesting"),
        e("Studio Mode", "Production", "Preview an edit before sending it to program", "preview program studio"),
        e("Transitions", "Production", "Cut and GPU fade transitions", "cut fade transition"),
        e("Stinger Transitions", "Production", "Video transition overlays with alpha", "stinger"),
        e("Luma Wipe", "Production", "Mask-driven scene transition", "luma wipe"),
        e("Multiview", "Monitoring", "Monitor multiple scenes and program output", "multiview monitor"),
        e("Preview / Program", "Monitoring", "Separate composition and output monitoring", "preview program"),
        e("Source Ordering", "Production", "Reorder layers and control z-order", "layer z order"),
        e("Transform", "Sources", "Position, scale, rotation, opacity and flip", "transform move rotate scale"),
        e("Crop", "Sources", "Crop any visual source independently", "crop trim"),
        e("Blend Modes", "Sources", "Normal and GPU blend operations", "blend additive multiply screen"),
        e("Camera", "Sources", "Camera2 hardware-surface camera source", "webcam camera"),
        e("USB Capture", "Sources", "Native UVC capture with ISO and BULK endpoints", "capture card webcam uvc usb-c isochronous bulk"),
        e("UVC H.264 / HEVC", "Sources", "Hardware-decoded compressed USB video", "usb h264 h265 hevc decoder"),
        e("Raw UVC", "Sources", "GPU upload path for raw USB formats", "yuyv uyvy nv12"),
        e("Audio Input", "Audio", "Real-time AAudio input device source", "mic microphone line in usb audio"),
        e("Audio Output Monitor", "Audio", "Low-latency native monitor output device", "headphones monitor listen"),
        e("Multi-Input Mixer", "Audio", "Independent native streams mixed into program and monitor buses", "mixer multiple microphones"),
        e("Volume", "Audio", "Per-source gain control", "gain fader volume"),
        e("Balance / Pan", "Audio", "Per-source stereo balance", "pan balance left right"),
        e("Mute", "Audio", "Per-source mute", "mute"),
        e("Solo", "Audio", "Solo one or more inputs", "solo"),
        e("Monitor Routing", "Audio", "Output-only, monitor-only, or both", "monitor monitoring route"),
        e("Audio Sync Offset", "Audio", "Per-input clock alignment offset", "sync delay latency"),
        e("Noise Gate", "Audio Filters", "Per-source gate processing", "gate filter"),
        e("Compressor", "Audio Filters", "Dynamic-range control", "compressor dynamics"),
        e("Limiter", "Audio Filters", "Peak protection", "limiter clipping"),
        e("Noise Suppression", "Audio Filters", "Noise reduction for microphones", "noise suppression rnnoise"),
        e("Gain Filter", "Audio Filters", "Filter-level gain", "gain filter"),
        e("Video Filter Chain", "Video Filters", "Ordered per-source GPU filters (up to 8), applied top to bottom like OBS; edit from a source's Video Filters… menu", "filter order stack chain"),
        e("Chroma Key", "Video Filters", "GPU CbCr chroma key with spill reduction", "green screen chroma"),
        e("Color Key", "Video Filters", "GPU RGB color-distance key", "color key transparent"),
        e("Luma Key", "Video Filters", "GPU luminance key", "luma key brightness transparent"),
        e("Color Correction", "Video Filters", "GPU gamma/contrast/brightness/saturation/hue/opacity/color multiply/add", "color correction"),
        e("LUT", "Video Filters", "GPU lookup-table color grading", "lut grade color"),
        e("Sharpen", "Video Filters", "GPU edge enhancement", "sharpen"),
        e("Blur", "Video Filters", "GPU blur effect", "blur"),
        e("Mask", "Video Filters", "GPU alpha/mask composition", "mask alpha"),
        e("Image", "Sources", "GPU texture image source", "png jpg photo"),
        e("Image Slideshow", "Sources", "Timed multi-image source", "slideshow"),
        e("Text", "Sources", "GPU-rendered text overlays", "text title font"),
        e("Color", "Sources", "Solid GPU color source", "color background"),
        e("Media Source", "Sources", "Hardware-decoded local media", "mp4 video media"),
        e("Browser Source", "Sources", "Astra-targeted hardware-backed WebView render path (not device-validated); system chooses video decoder; browser audio is unavailable", "web html webpage browser hardware acceleration gpu decoder"),
        e("Browser Audio", "Audio", "Browser-originated audio routed into the mixer", "web audio"),
        e("Audio Mixer", "Monitoring", "OBS-style source strips and routing", "mixer audio"),
        e("4K60 H.264", "Encoding", "Hardware MediaCodec AVC at up to 3840x2160/60", "h264 avc 4k 2160 60fps"),
        e("4K60 HEVC", "Encoding", "Hardware MediaCodec HEVC where exposed by the device", "h265 hevc 4k 2160"),
        e("4K120 hardware output", "Encoding", "Custom RTMP(S) can request 3840x2160/120 when Astra MediaCodec exposes the exact mode; sustained output is unverified", "4k120 120fps custom rtmp hardware encode"),
        e("Hardware Encoding", "Encoding", "MediaCodec surface-input hardware encoding", "nvenc equivalent hardware codec encoder"),
        e("CBR", "Encoding", "Constant-bitrate live output", "bitrate cbr"),
        e("Keyframe Interval", "Encoding", "Configurable GOP/keyframe interval", "gop keyframe"),
        e("HDR", "Encoding", "HDR-aware color pipeline and encoder configuration where device exposes it", "hdr pq hlg bt2020"),
        e("YouTube Account", "Streaming", "Google authorization instead of manual stream keys", "youtube google oauth login"),
        e("YouTube Broadcast Picker", "Streaming", "Select an existing YouTube broadcast", "youtube live broadcast event"),
        e("YouTube RTMPS", "Streaming", "Secure RTMP YouTube publishing", "rtmp rtmps live"),
        e("YouTube HLS / HEVC", "Streaming", "HEVC/HLS publishing path when target configuration supports it", "hls hevc h265"),
        e("Custom RTMP / RTMPS", "Streaming", "Configure a custom server URL and stream key; use compatible ingest services for up to 120 FPS", "custom endpoint server key stream rtmp rtmps 4k120"),
        e("Auto Reconnect", "Streaming", "Bounded reconnect state machine", "reconnect network"),
        e("Live Statistics", "Monitoring", "Bitrate, frames, duration and dropped-frame telemetry", "stats bitrate dropped"),
        e("Simultaneous Stream + Record", "Output", "One encoded frame path feeding independent sinks", "record stream simultaneously"),
        e("Screenshot", "Output", "Program-frame capture", "screenshot snapshot"),
        e("Hotkeys", "Controls", "USB/Bluetooth keyboard actions", "keyboard shortcuts"),
        e("Push-to-Talk", "Controls", "Keyboard-triggered microphone routing", "ptt talk"),
        e("Push-to-Mute", "Controls", "Keyboard-triggered microphone mute", "ptm mute"),
        e("Global Search", "UX", "Search settings, sources, tools and actions", "search command palette settings"),
        e("OBS Profile Import", "Profiles", "Import profile settings and use compatible RTMP server/key values to prefill a custom destination", "profile basic.ini service.json"),
        e("OBS Scene Collection Import", "Profiles", "Import scenes, sources, transforms, filters and assets", "scene collection obs json"),
        e("OBS Asset Relinking", "Profiles", "Copy/bundle referenced files into managed storage", "assets paths media relink"),
        e("OBS Plugin Compatibility Report", "Profiles", "Preserve unsupported plugin sources with explicit diagnostics", "plugin unsupported compatibility"),
        e("Imported Canvas Layout", "Profiles", "Use OBS source positions, sizes, order, visibility, crop and transforms", "import positions size visibility canvas transform"),
        e("Templates", "Production", "Reusable scene/source layouts", "template"),
        e("Accessibility", "UX", "Keyboard navigation, large controls and readable labels", "accessibility"),
        e("Thermal Policy", "Performance", "Adapt workloads to sustained mobile thermal limits", "thermal temperature performance"),
        e("Power Policy", "Performance", "Streaming-focused foreground-service lifetime", "battery power foreground"),
        e("Perfetto Telemetry", "Diagnostics", "Trace USB, audio, GPU, codec and network timing", "perfetto trace profiling"),
        e("USB Device Manager", "Hardware", "Inspect connected cameras, capture cards and audio devices", "usb devices"),
        e("Isochronous USB", "Hardware", "Continuous scheduled USB transfer path", "iso usb real-time"),
        e("Bulk USB", "Hardware", "Continuous asynchronous bulk endpoint path when required", "bulk usb"),
        e("A/V Clocking", "Performance", "Monotonic timestamp alignment across sources", "clock sync av sync timestamps"),
        e("Native ARM64 Engine", "Performance", "C++20 native media path optimized for arm64", "arm64 native c++ snapdragon"),
        e("GPU Compositor", "Performance", "GLES scene composition with encoder surface output", "gpu opengl egl compositor"),
        e("Zero-Copy Surfaces", "Performance", "Surface/AHardwareBuffer paths where Android exposes them", "zero copy ahardwarebuffer surface"),
        e("Android Foreground Capture", "Platform", "Long-running media capture/stream services", "foreground service"),
        e("PC-only Window Capture", "Platform", "Not directly reproducible on Android; replaced by MediaProjection/display capture", "window capture desktop"),
        e("VST/Lua/Python Plugins", "Extensions", "Desktop OBS plugin ABI is not loadable on Android; migration layer required", "vst lua python plugin"),
        e("NDI", "Extensions", "Network video ecosystem adapter", "ndi"),
        e("SRT", "Streaming", "Secure reliable UDP transport adapter", "srt"),
        e("RIST", "Streaming", "Reliable internet streaming transport adapter", "rist"),
        e("Virtual Camera", "Outputs", "Expose program output as an Android camera-compatible producer", "virtual camera webcam"),
    ).map { entry ->
        val state = featureState(entry.title)
        val details = when (state) {
            "Partial" -> "Partially implemented; broader parity remains and physical-device behavior is unverified. ${entry.detail}"
            "Android alternative" -> "The desktop feature is unavailable on Android. Use the Android capture alternative. ${entry.detail}"
            else -> "Not implemented in this build. ${entry.detail}"
        }
        entry.copy(detail = details, action = actionFor(entry, openYouTube, openCustomStream, openProfiles, openSettings, addSource, startReplay, toggleStudio), status = state)
    }

    private fun e(title: String, category: String, detail: String, keywords: String = "") = FeatureEntry(title, category, detail, null, keywords)

    private val partialFeatures = setOf(
        "Scenes", "Scene Collections", "Sources", "Studio Mode", "Transitions", "Preview / Program", "Source Ordering",
        "Transform", "Crop", "Camera", "USB Capture", "UVC H.264 / HEVC", "Raw UVC",
        "Screen Capture", "Playback Capture", "Audio Input", "Audio Output Monitor", "Multi-Input Mixer", "Volume",
        "Balance / Pan", "Mute", "Solo", "Monitor Routing", "Audio Sync Offset", "Video Filter Chain", "Chroma Key", "Color Key", "Luma Key", "Color Correction",
        "Image", "Image Slideshow", "Text", "Color", "Media Source", "Browser Source", "Audio Mixer",
        "YouTube Account", "YouTube Broadcast Picker", "YouTube RTMPS", "YouTube HLS / HEVC", "Custom RTMP / RTMPS", "4K120 hardware output", "Auto Reconnect",
        "Live Statistics", "Screenshot", "Hotkeys", "Push-to-Talk", "Push-to-Mute",
        "Global Search", "OBS Profile Import", "OBS Scene Collection Import", "OBS Asset Relinking", "Imported Canvas Layout",
        "Accessibility", "USB Device Manager", "Isochronous USB", "Bulk USB",
        "A/V Clocking", "Native ARM64 Engine", "GPU Compositor", "Android Foreground Capture", "4K60 H.264",
        "4K60 HEVC", "Hardware Encoding", "CBR", "Keyframe Interval", "HDR"
    )

    private fun featureState(title: String): String = when {
        title == "PC-only Window Capture" -> "Android alternative"
        title in partialFeatures -> "Partial"
        else -> "Not implemented"
    }

    private fun actionFor(
        entry: FeatureEntry,
        openYouTube: () -> Unit,
        openCustomStream: () -> Unit,
        openProfiles: () -> Unit,
        openSettings: (String) -> Unit,
        addSource: (String) -> Unit,
        startReplay: () -> Unit,
        toggleStudio: () -> Unit
    ): (() -> Unit)? = when (entry.title) {
        "YouTube Account", "YouTube Broadcast Picker", "YouTube RTMPS", "YouTube HLS / HEVC" -> openYouTube
        "Custom RTMP / RTMPS" -> openCustomStream
        "OBS Profile Import", "OBS Scene Collection Import", "OBS Asset Relinking", "Imported Canvas Layout" -> openProfiles
        "Studio Mode" -> toggleStudio
        "Camera" -> {{ addSource("CAMERA") }}
        "USB Capture" -> {{ addSource("USB_CAPTURE") }}
        "Screen Capture" -> {{ addSource("SCREEN_CAPTURE") }}
        "PC-only Window Capture" -> {{ addSource("SCREEN_CAPTURE") }}
        "Browser Source" -> {{ addSource("BROWSER") }}
        "Media Source" -> {{ addSource("MEDIA") }}
        "Image" -> {{ addSource("IMAGE") }}
        "Image Slideshow" -> {{ addSource("IMAGE_SLIDESHOW") }}
        "Text" -> {{ addSource("TEXT") }}
        "Color" -> {{ addSource("COLOR") }}
        "Audio Input" -> {{ addSource("AUDIO_INPUT") }}
        "Audio Output Monitor" -> {{ addSource("AUDIO_OUTPUT") }}
        "4K60 H.264", "4K60 HEVC", "4K120 hardware output", "Hardware Encoding" -> {{ openSettings("Output") }}
        "Hotkeys", "Push-to-Talk", "Push-to-Mute" -> {{ openSettings("Hotkeys") }}
        "Accessibility" -> {{ openSettings("Accessibility") }}
        "Global Search" -> {{ openSettings("General") }}
        else -> null
    }
}
