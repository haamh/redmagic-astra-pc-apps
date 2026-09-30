# Stream4k60 — Build Status

## Current checkpoint

`assembleDebug` succeeds on this Windows workspace with the installed Android SDK/NDK and Gradle 8.11.1. The current debug APK is:

`app/build/outputs/apk/debug/app-debug.apk`

Gradle 8.11.1 wrapper scripts and the wrapper JAR are included for Android Studio/command-line builds.

This is a development build, not an Astra-ready app. The latest build proves compilation and packaging only. ADB now detects the connected tablet as `NP05J` / `PQ84P01-EEA`, Android 15 (API 35); the app has not been installed or launched yet. No production/release build has been verified.

**Astra test readiness: NO.** The app has not been installed/launched or runtime-validated on Astra. Major OBS-parity and source/output behavior remains incomplete or unverified.

Linux/CI builds also work (2026-09-30): Android platform 35, build-tools 34.0.0, NDK 27.0.12077973, CMake 3.22.1. JVM unit tests run with `./gradlew :app:testDebugUnitTest`.

## Implemented in source

- Apply LUT (.cube/PNG) and Sharpen video filters; per-source Noise Gate audio filter with a simple "Start listening at" control and Advanced options; a Filters editor with Video/Audio tabs.
- Canvas editing: aspect-locked corner resize, edge stretch, crop, rotation knob, two-finger pinch/twist, and resizing of OBS bounds items.
- OBS import activates the imported profile and collection so layouts appear on the imported canvas immediately.
- Ordered per-source GPU video filter chain: Color Correction, Chroma Key, Color Key and Luma Key, up to 8 enabled stages applied top to bottom, with add/remove/reorder/enable and live preview. OBS filters of these types import in order. Legacy single-stage `effects` configs migrate automatically.

- Room-backed profiles, scene collections, scenes, sources and filters.
- OBS profile/scene-collection import foundations and asset relinking metadata. OBS compatibility is import-only; OBS-format export and round-trip compatibility are outside the readiness scope.
- OBS-style studio workspace with preview, scenes, sources, mixer, transitions and controls.
- Canvas source selection, direct drag-to-position editing, corner resizing with the opposite edge anchored, and snapping to canvas/source edges and centers with alignment guides. Arrow keys nudge by 1 canvas pixel, Shift+Arrow nudges by 10, Ctrl+Up/Down changes source stack order, and Tab cycles visible visual sources. The source list supports mouse drag-handle reordering and persists the final front-to-back order. Source context actions work by mouse right-click, keyboard context-menu/Shift+F10, touch long-press and a visible menu button: hide/show, lock/unlock, properties, rename, duplicate, remove, transform, copy/paste transform, reset transform and move to top/bottom. Transform changes update the native compositor live and persist to the active scene; imported OBS nested position/scale fields are understood.
- Selected-source transform dialog with editable canvas-pixel position/size, item anchor, rotation, opacity, flips, per-edge source-pixel crop, all seven OBS bounds modes, bounds size/alignment, and crop-to-bounds. Center, aspect-preserving Fit, Stretch and Reset persist to compositor transforms. The native compositor resolves crop/bounds geometry, normalized UV insets, and rotation around the selected OBS anchor. OBS scene-item import recognizes canonical `rot`, `align`, `bounds_align`, `crop_*` and `crop_to_bounds` fields as well as the app's prior aliases.
- Native GLES/EGL compositor and encoder-surface path.
- Native UVC ISO and bulk transfer engines.
- Native multi-input AAudio mixer and Android playback-capture path.
- Hardware MediaCodec encoder abstractions and capability checks.
- YouTube account/broadcast-selection flow and RTMP/HLS publisher foundations.
- Video canvas/output/FPS profile settings; custom output is bounded to 3840×2160, common/integer FPS can reach 120, and the target bitrate slider reaches 100,000 Kbps. H.264/HEVC, exact selected size/FPS encoder checks and the Android-reported bitrate range feed output configuration.
- Custom RTMP/RTMPS streaming can use the active output profile up to 3840×2160/120 FPS when the selected Astra MediaCodec encoder reports that exact mode. OBS-imported RTMP server/key settings prefill the destination form for confirmation. YouTube remains separately capped at its documented 60 FPS ingest limit.
- Video and Output settings now reset independently, with a confirmation that explains which values will be restored and which values stay unchanged.
- Settings search indexes every currently editable Video/Output field, including custom width/height and FPS, and supports multi-term keyword searches. Controls include concise explanations, examples and applicable cautions; reset actions are category-specific. Inert controls were removed from the visible settings pages; incomplete areas are identified in the category pages and remaining-work ledger.
- The supplied black-and-white logo is included in the adaptive launcher icon and Studio toolbar.
- Studio toolbar Scenes/Sources/Tools menus are wired to scene switching/creation, source creation/visibility, search, Studio Mode, replay buffer and settings.
- Color sources have a real HSV/alpha/hex picker and persist color changes. Static color, text and image overlays now render once per configuration change instead of running an idle one-second update loop.
- The source context menu opens working GPU video controls for brightness, contrast, saturation, gamma, hue and chroma key. Settings persist in the source configuration and are passed to the GLES compositor; this is a first filter subset, not complete OBS filter-chain parity.
- Main-studio source editing uses typed controls: external UVC device/advertised formats and device-supported controls, compressed-video hardware/software decoder preference, optional linked USB capture audio, browser URL/local HTML/custom CSS/refresh/size/FPS/JavaScript, image/media documents, multi-file media playlists, media decoder/speed/loop/start-position/audio settings, text font/alignment/dimensions, color and slideshow settings. File URIs use persistable Storage Access Framework grants.
- Imported scene collections can be selected from the Studio toolbar. Import preserves raw scene-item fields, imports visible/locked state and mapped transforms, rewrites nested asset paths, maps desktop capture/audio sources to Android remapping choices, translates common OBS profile video/output settings, and displays an import compatibility report. Common color-correction and chroma-key filters are translated approximately to the available compositor stage; unsupported filters and repeated extra stages remain preserved and are warned about. Imported source volume, pan, sync offset and monitoring settings feed the Android audio graph. These import behaviors have not been runtime-verified.
- Unsupported imported OBS plugin sources can be remapped to a built-in Android source in source properties where a practical substitute exists. Groups are preserved with a specific explanation because flat remapping would change scene composition.
- New-source and OBS-remap flows do not offer the Astra's built-in camera or Android screen capture. Imported OBS desktop window/display-capture definitions are retained as unsupported and inactive.
- Source failures for camera, USB, image/slideshow, media, browser, screen/playback capture, audio graph and compositor are stored in a shared source-error state and shown in the source list. Projection-permission errors have an explicit retry path. USB setup responds to changes in enumerated devices.
- Camera capture now honors selected lens facing and requested size/FPS; media sources restart when their configuration changes; browser-source configuration changes no longer create duplicate scheduled frame-capture loops. Image slideshow rotates through configured images while visible.
- Browser WebView has an unverified hardware-backed Surface/Canvas compositor path up to 2400×1504/60 FPS, plus a software fallback capped at 1280×720/10 FPS. Local HTML, custom CSS and refresh controls are present. Android WebView selects video decoders automatically; browser audio and page interaction remain absent. Still-image decoding downsamples to a 3,145,728-pixel budget.
- Media3 decoded audio is routed through the shared native mixer and exposed with a live peak meter. UVC properties offer device-reported standard image controls, compressed-media decoder preference/fallback and optional linked USB Audio Class input. These paths compile but have not been exercised with physical accessories.
- Astra-targeted performance work: the app requests the panel's highest supported refresh mode for UI responsiveness; the native compositor reports frame work and target cadence through Android NDK ADPF; the Android thermal-status listener displays system thermal state. These are public API integrations, not direct control of Oryon/Adreno clocks, RedCore, ICE-X fan speed or OEM gaming modes. Thermal-driven output adaptation is not implemented.
- `HardwareVideoEncoder` uses Android `MediaCodec` hardware encoders and feeds the compositor through an encoder `Surface`. Encoder capability reporting checks exact size/FPS support and reports the Android-declared bitrate range. The custom RTMP(S) path passes up to 3840×2160/120 FPS to MediaCodec; app-accessible Astra modes, sustained 4K120, USB scene composition throughput, and remote ingest compatibility are still unverified. YouTube's official ingest profile documents up to 60 FPS.
- Output settings now asynchronously list Android-reported hardware encoder components and report H.264/HEVC support, the declared bitrate range and the vendor's achievable-FPS estimate for the currently selected size/rate. These are codec-level reports, not proof of GLES/multi-source throughput or remote service compatibility.
- Read-only inspection of this attached Astra's codec XML found AVC and HEVC 4K120 performance-point declarations and declared encoder bitrate range maxima of 220/160 Mbps, respectively (not a per-mode bitrate guarantee). Separate vendor measured-performance metadata lists 4K HEVC at 65–93 FPS and HDR HEVC at 60–95 FPS; it provides no measured 4K AVC entry in the sampled file. This firmware metadata raises a real sustained-rate concern for HEVC 4K120; app `MediaCodec` queries and end-to-end encode/stream behavior remain unverified.
- App minSdk is 33 for the Astra-targeted NDK ADPF C API. The official Astra spec lists Android 15.
- Astra NPU-based background segmentation is optional and is not a readiness requirement. REDMAGIC lists Snapdragon 8 Elite and the RedCore R3 Pro gaming chip; Qualcomm provides the app-facing Hexagon NPU path, while REDMAGIC does not document a general-purpose app inference API for RedCore. This app has no segmentation model/backend, and its NPU execution path is not validated. Classic chroma key runs separately in the GPU compositor.
- Global feature search now shows `Partial`, `Not implemented`, or `Android alternative` status. Features without a working action are disabled instead of being routed to unrelated settings. Working settings links open their selected category directly.
- Removed two unused placeholder APIs that returned no result: `NativeUsbManager.getTextureId()` and the empty `SettingsViewModel.saveSettings()`.

## Not production-ready

- No physical Astra validation: external UVC/capture-card interoperability, multiple USB sources, display behavior, thermals, audio timing, encoder capability and sustained 4K120 remain unverified. The Astra's built-in camera is not required.
- UVC device-variant negotiation, multi-device performance, recovery and device-control coverage remain incomplete/unverified; linked USB audio has no measured A/V sync evidence.
- OBS-level source/filter/transition, Studio Mode, multiview and transform interaction parity is incomplete. Ordered video filter chains exist for per-pixel filters (color correction, chroma/color/luma key); multi-sample or texture filters (sharpen, blur, LUT, mask/blend, scroll, render delay) and all audio filters remain.
- Copy/paste filters, multi-select/group operations, nested scenes and several OBS context actions remain incomplete. Canvas editing, crop/bounds rendering and drag reordering have not been runtime-validated yet; OBS import coverage remains incomplete. OBS-format export is not required.
- Audio DSP filters, advanced routing, resampling/drift correction and hardware-device validation are incomplete.
- Browser source runtime throughput/audio/security and page interaction remain. The Surface/Canvas path has compile evidence only; WebView does not expose an app-controlled per-source decoder selector.
- YouTube/RTMPS/HEVC/HLS interoperability and custom RTMP(S) endpoint compatibility, reconnect and live-state validation need service testing. YouTube ingest is limited to 60 FPS; custom endpoints must accept their own configured limits.
- Production recording/muxing, simultaneous stream-record, replay persistence and crash recovery need completion.
- OBS import compatibility reporting is partial; broad source/filter property fidelity and Android device remapping need completion. Reverse export/round-trip support is not in scope.
- Editable/conflict-checked hotkeys, external-display workflow, accessibility, diagnostics/telemetry and Android extension API need completion.
- Release APK/signing/shrinking/native packaging and soak/performance validation remain.
- Removed the unreferenced legacy camera/source-properties screens, fake numbered-template grid, and inert Settings path picker. The active typed source editor remains; Templates stay explicitly not implemented and disabled in feature search.
- No app launch or physical-device runtime check has been performed yet. ADB now sees the attached Astra; `assembleDebug` verifies packaging/compilation only and does not establish runtime safety or media interoperability. See `OBS_PARITY_LEDGER.md` for the feature-by-feature audit and readiness gaps.

See `GEMINI.md` section 6 and `OBS_FEATURE_IMPLEMENTATION_SPEC.md` for the full prioritized feature gap list. Do not remove an item from that ledger until implementation and device verification are complete.
