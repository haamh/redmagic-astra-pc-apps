# Stream4k60 — Canonical Project Context

## 1. Product definition

Stream4k60 is an Android-native OBS-equivalent production studio targeted first at the REDMAGIC Astra Gaming Tablet and its Snapdragon 8 Elite platform. The product goal is **functional and usability parity with OBS Studio**, not a desktop UI port.

The app must expose the full OBS feature surface through a minimal, organized Android/desktop-style UI with a global search/command system. Advanced functionality must remain available; it is not removed merely because the default UI is simple.

Primary target workflow:

- Original REDMAGIC Astra internal display as the only device target; do not optimize for Astra 2 or generic Android devices
- Physical keyboard, mouse and USB-C accessories where available, as Astra workflows
- Multiple simultaneous external USB video devices, including UVC webcams and HDMI capture cards; the Astra's internal camera is not required
- Multiple USB Audio Class microphones/interfaces/line inputs
- Android playback audio where useful; screen/display capture and screen recording are out of scope
- YouTube as the first fully supported streaming service
- Up to 4K60 as the primary production target
- Hardware-first GPU/video/audio processing on Snapdragon 8 Elite

The canonical detailed feature inventory and Android implementation specification are in:

`OBS_FEATURE_IMPLEMENTATION_SPEC.md`

Do not silently reduce the implementation scope below that specification.

---

## 2. Non-negotiable engineering rules

### 2.1 No simulation

There are no simulated webcams, fake capture cards, fake audio sources, fake network connections, fake encoder success, fake LIVE state, or placeholder media generators in the production architecture.

A feature is only reported as running after its actual native/Android subsystem has opened and produced/consumed the required media or established the required network/service state.

### 2.2 Every live media source is real-time

Webcams, capture cards, microphone inputs, line inputs, capture-card audio, Android playback audio, and screen capture are continuous timestamped media streams.

Never implement live media using UI polling, periodic screenshots, one-transfer-at-a-time USB reads, or a Java/Kotlin loop that blocks the transport.

### 2.3 USB transport is endpoint-driven

UVC streaming must support both:

- true isochronous USB transfers when the device exposes an ISO endpoint;
- asynchronous bulk USB transfers when the device exposes a bulk video endpoint.

ISO is not optional for the real-time design. Bulk is not a replacement for ISO. The device descriptors determine the transport.

USB completion threads must immediately recycle transfers and enqueue packets/frames into bounded native queues. They must not wait on Compose, MediaCodec, disk, network, or Java callbacks.

### 2.4 Audio is a multi-source live graph

The audio engine must behave like OBS's mixer:

- independent sources;
- gain;
- mute;
- solo;
- pan/balance;
- per-source sync offset;
- monitor routing;
- track/bus routing;
- filters;
- real-time metering;
- playback/device monitoring;
- multiple hardware inputs active simultaneously.

Physical USB interfaces and Android playback capture feed the same native graph. A session may legitimately have no audio track, but this never means that video capture devices are treated as video-only devices.

### 2.5 Hardware-first, capability-verified

The runtime must query actual device capabilities. Never assume that a Snapdragon 8 Elite device exposes every codec/profile/level, rate-control mode, 10-bit path, frame rate, color format, HDR mode, or hardware buffer path.

Software codec fallback must never happen silently for the production 4K60 path.

### 2.6 Android-native implementation

Prefer Android/NDK/Qualcomm hardware paths that preserve OBS-observable behavior while reducing latency and power:

- Vulkan/OpenGL ES GPU composition as appropriate;
- AHardwareBuffer and Surface/SurfaceTexture paths where supported;
- MediaCodec hardware encoder/decoder;
- AAudio/native low-latency audio;
- Android USB Host + native usbfs UVC transport;
- Camera2/CameraX/NDK camera paths;
- MediaProjection for screen capture;
- AudioPlaybackCapture for Android playback audio;
- foreground services for long-running capture/stream sessions;
- DisplayManager/secondary-display APIs for external monitor workflows.

---

## 3. Current implementation checkpoint

The current tree builds an installable **debug APK** on the configured Windows Android SDK/NDK environment. It is not a production release and has not been installed or validated on a REDMAGIC Astra. Do not describe it as finished until the remaining work below is closed and verified on physical hardware.

### 3.1 Implemented/actively wired

#### Project and persistence

- Room-backed profiles, scene collections, scenes and sources.
- Persistent transforms/source state.
- Filter entities and filter persistence.
- Hotkey binding persistence.
- Recovery-oriented persistence model.
- OBS profile and scene-collection import infrastructure. OBS compatibility is import-only; no OBS-format export is required.

#### OBS compatibility

- Import of OBS profile/scene data from JSON/folder/ZIP style inputs.
- Basic profile settings extraction, including video/output fields.
- Scene/source reconstruction.
- Transform preservation.
- Audio property preservation for common fields including volume, balance, sync, monitoring and mixer data.
- Filter preservation with original IDs/settings/raw definitions.
- Asset discovery/copy/relinking where files are available.
- Unsupported desktop OBS source/plugin IDs are preserved as explicit `OBS:<id>` objects instead of being silently discarded.
- Export path for Stream4k-owned project/profile/scene information.

#### Search/organization

- Canonical searchable feature catalog.
- Search across production, sources, audio, filters, encoding, streaming, output, controls, profiles, diagnostics, hardware and performance capabilities.
- Search-first access to advanced functionality.

The catalog is deliberately broader than the visible basic UI. Missing implementation must be reported by status rather than removed from the catalog.

#### Studio UI

- OBS-style main studio layout.
- Preview/program concepts.
- Scenes panel.
- Sources panel.
- Audio mixer panel.
- Transition controls.
- Controls/status areas.
- Studio mode/multiview-oriented UI components.
- Source property screens.
- Filter editor screens.
- Profile manager.
- Scene collection manager.
- Settings areas.
- Statistics/diagnostics screens.
- USB device manager.
- YouTube broadcast selection UI.
- Global feature search UI.

The preview canvas now supports source selection from the canvas or Sources list, a visible selection outline, live native-compositor updates for drag-to-position and corner resizing, snapping to canvas/source edges and centers with alignment guides, arrow-key nudging (Shift for 10-pixel steps), Ctrl+Up/Down stack-order changes, Tab source cycling, and transform persistence. The Sources panel presents the front-most source first. Imported OBS positions, scale, visibility, bounds and crop fields feed the canvas/compositor where mapped. Mouse drag-reordering, context menus, bounds/crop editing and distinct Preview/Program scene routing remain open; canvas interaction still needs physical hardware validation.

The UI is intentionally more discoverable than raw OBS settings while retaining OBS-level capability.

#### Video model/settings

The persistent/settings model includes the OBS-style separation between:

- Base/Canvas resolution;
- Output/Scaled resolution;
- FPS;
- downscale filter;
- color format;
- color space;
- color range;
- HDR.

The video settings screen exposes custom canvas/output dimensions through 3840×2160 and common/integer FPS through 120. Output size, FPS, bitrate (up to 100,000 Kbps) and H.264/HEVC are profile-persisted. Custom RTMP(S) can request up to 4K120 using the exact selected Astra MediaCodec capability query; OBS-imported server/key settings prefill its destination dialog. YouTube Live remains limited to 60 FPS. A capability declaration is not proof of sustained encoding or ingest-service acceptance. Exact GPU downscale filters, color format/range, HDR signaling, broader encoder controls and Astra capability/performance validation remain incomplete. The settings search only indexes settings that currently have working controls; unfinished controls are not shown as if they worked.

#### GPU/compositor

- EGL/GL initialization.
- GLES scene compositor.
- Per-source GPU transforms.
- Layer ordering.
- Opacity.
- Crop/flip handling in the scene model.
- External `SurfaceTexture` source path.
- RGBA/raw upload path.
- Preview surface path.
- Encoder input surface path.
- GPU shader cache infrastructure.
- Texture/buffer pools.
- Filter-pipeline infrastructure.
- AHardwareBuffer/GPU interoperability scaffolding.

The compositor is real native rendering code; remaining work is primarily feature-completeness, synchronization, HDR/color correctness, performance validation, and full filter parity.

#### Native USB video

- Native UVC bulk transfer engine.
- Native UVC isochronous transfer engine.
- Multiple outstanding transfers.
- Continuous re-submission.
- UVC frame reassembly path.
- Timestamp/error accounting infrastructure.
- Decoupling between USB completion and downstream processing.
- Hot-plug/device lifetime handling.
- USB device discovery/permission layer.

Actual device interoperability still requires physical validation against representative 4K60 webcams and capture cards, including their descriptor/alternate-setting quirks.

#### Native audio

- Native C++ multi-input mixer.
- AAudio input streams per configured physical input.
- Native monitor/output stream.
- Independent per-source volume/balance/mute/solo/monitor/sync properties.
- Source add/remove/update while the mixer is running.
- Bounded source rings.
- Oldest-data dropping under overload to preserve live latency.
- Native peak/meter state.
- 48 kHz program bus architecture.
- Input device/rate negotiation architecture.
- Android playback capture integration path.

This is the intended OBS-style multi-source audio architecture; DSP/filter breadth and physical-device validation remain incomplete.

#### Hardware encoding

- MediaCodec surface-input hardware video encoder abstraction.
- Hardware capability discovery.
- H.264/HEVC-oriented encoder configuration.
- 4K60-oriented configuration.
- CBR/GOP configuration model.
- Codec configuration extraction.
- Stream output path separation.

Hardware-only success must be verified at runtime; no software fallback should be silently accepted for the production path.

#### Streaming/YouTube

- Google/YouTube account authorization architecture.
- YouTube broadcast listing/selection architecture.
- Existing broadcast selection rather than stream-key-only workflow.
- Ingestion configuration model.
- RTMP publisher foundation.
- RTMPS target architecture.
- YouTube HLS/HEVC architecture.
- Reconnect/state handling architecture.

These paths still require end-to-end validation against live YouTube and completion of the final transport/codec permutations.

#### Android services/platform integration

- Foreground streaming/recording service structure.
- Screen capture service structure.
- Projection/playback capture service structure.
- Notifications/lifecycle handling.
- USB permission/device lifetime handling.
- External keyboard/hotkey dispatcher infrastructure.

---

## 4. Current canonical architecture

```text
                        STREAM4K STUDIO
                              │
          ┌───────────────────┼────────────────────┐
          │                   │                    │
       UI/Search          Project State         Services
          │                   │                    │
       Compose        Room/DataStore       foreground lifetime
          │                   │                    │
          └───────────────────┼────────────────────┘
                              │
                       Native Engine (C++)
                              │
        ┌─────────────────────┼─────────────────────────┐
        │                     │                         │
   LIVE VIDEO             LIVE AUDIO              CONTROL/STATE
        │                     │                         │
 Camera2 / UVC         AAudio / playback         JNI + native graph
 MediaProjection       USB Audio HAL            hotkeys/devices
        │                     │
        ▼                     ▼
   timestamped             timestamped
   media streams           PCM blocks
        │                     │
        ▼                     ▼
    GPU compositor        Native audio mixer
        │                     │
        │                buses/monitor/tracks
        │                     │
        └───────────────┬─────┘
                        ▼
              Hardware MediaCodec
                        │
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
       YouTube        Record        Replay
       RTMPS/HLS       muxer         buffer
```

---

## 5. OBS feature parity scope

The project is not allowed to stop at the features already coded. The implementation target includes the full researched OBS inventory in `OBS_FEATURE_IMPLEMENTATION_SPEC.md`.

Major required areas include:

- Profiles and Scene Collections.
- Full source catalog and source properties.
- Groups and nested scenes.
- Scene transforms, crop, alignment and snapping.
- Studio Mode.
- Transitions, including GPU and media/stinger styles.
- Multiview.
- Audio Mixer and Advanced Audio Properties.
- Audio filters and routing.
- Video filters/effects.
- Browser source and browser audio.
- Media source and image/slideshow/text/color sources.
- USB webcams/capture cards and Android cameras.
- Screen/display capture.
- Android playback audio capture.
- Hardware encoding.
- Stream/record/replay/screenshot outputs.
- YouTube account/broadcast management.
- Hotkeys and push-to-talk/push-to-mute.
- Search/command palette.
- OBS import compatibility.
- Diagnostics/statistics/logging.
- Accessibility and desktop-style keyboard/mouse workflows.
- Extension/plugin compatibility architecture.
- OBS project validation and missing-resource reporting.
- Output reliability and crash recovery.

Desktop-only mechanisms must receive Android-native functional equivalents, not simply disappear.

---

## 6. Remaining work required before production release

This section is the authoritative remaining-work list. Remove an item only after implementation and physical/device verification.

### A. Build and hardware validation

- Build and verify a release arm64 APK, including signing, shrinker rules and native library packaging.
- Establish reproducible builds and release artifact validation.
- Run instrumentation/unit tests.
- Run native sanitizer/debug builds where practical.
- Produce reproducible release builds.
- Verify signing, shrinker rules and native library packaging.

### B. USB/UVC production interoperability

- Complete UVC descriptor parser for realistic device variants.
- Robust alternate-setting/bandwidth selection.
- UVC PROBE/COMMIT negotiation across vendor quirks.
- Correct packet interval/max-packet handling.
- Better error/recovery behavior for stalled/disconnected devices.
- Validate MJPEG, H.264 and HEVC UVC devices.
- Validate raw YUYV/UYVY/NV12 devices.
- Validate multiple simultaneous 4K60 devices through real hubs/docks.
- Verify USB bus bandwidth behavior and thermal impact.
- Implement UVC controls/properties exposed by devices.
- Verify capture-card audio/video clock correlation.

### C. Audio production engine

- Finish native resampling implementation and clock-drift correction.
- Add full OBS-equivalent audio filters: EQ, expander, compressor, limiter, noise suppression, noise gate, gain and related processing.
- Finish multi-bus/track routing.
- Finish advanced audio properties UI.
- Complete monitoring-device selection and exclusive/compatibility behavior.
- Validate several USB UAC interfaces simultaneously.
- Validate line-in and capture-card audio.
- Validate Android playback capture synchronization.
- Verify underrun/overrun and device hot-unplug behavior.

### D. GPU/video parity

- Complete source transform/snap/alignment interactions.
- Finish full filter set from the researched inventory.
- Add correct blend modes and masks.
- Finish scene nesting semantics.
- Finish GPU scene transitions.
- Finish Studio Mode program/preview synchronization.
- Finish multiview rendering and input.
- Complete HDR/PQ/HLG/BT.2020 path where the hardware exposes it.
- Implement color conversion/scaling without unnecessary CPU copies.
- Validate 4K60 sustained render latency.
- Validate external monitor preview/program behavior.

### E. Video settings actually driving the engine

- Validate Base/Canvas resolution against the native compositor render size on hardware.
- Validate Output/Scaled resolution against the final hardware encoder size on hardware.
- Validate FPS pacing and encoder timestamps under sustained load.
- Wire downscale filter to GPU scaling.
- Wire color format/space/range to actual surfaces/encoder configuration.
- Wire HDR selection to surface/color metadata/encoder when supported.
- Add capability-aware validation and explanatory errors.
- Expand capability-aware validation for imported profiles and unsupported device/service combinations.

### F. Browser source

- Production WebView rendering path.
- Hardware-accelerated composition.
- Page lifecycle/sizing/focus handling.
- Browser audio extraction into the native audio graph.
- Reload/shutdown/recovery behavior.
- Local asset/content security model.
- Cookies/storage/permission handling where needed.
- GPU performance validation at 4K scene output.

### G. Streaming/YouTube

- Complete and test production RTMPS.
- Complete Enhanced-RTMP/HEVC path where the selected YouTube ingest supports it.
- Complete HLS/HEVC ingest path with independent segment/network workers.
- Ensure HEVC VPS/SPS/PPS configuration is represented correctly.
- Complete AAC audio transport and A/V timestamp mapping.
- Automatic reconnect with stream-state safety.
- Broadcast lifecycle controls.
- Existing broadcast selection and stream association.
- Stream metadata/title/privacy/latency configuration UI where supported by the YouTube API.
- Hardware/codec capability negotiation per selected output.
- Robust live-state confirmation based on actual publisher state.

### H. Recording and replay

- Production MP4/MKV muxing.
- Crash-safe recording finalization.
- Simultaneous stream+record with shared encoded frames.
- Replay-buffer rolling storage and save-to-file.
- Multiple audio tracks.
- Recording quality presets.
- Output-directory/SAF management.
- File recovery after process death.

### I. OBS compatibility

- Broaden profile/scene collection parsing for current OBS JSON variants.
- Preserve more source-specific properties.
- Preserve scene filters/transitions/settings comprehensively.
- Preserve hotkeys.
- Preserve output/encoder service configuration.
- Bundle/relink assets reliably.
- Create a compatibility report for every unsupported plugin/source/filter.
- Provide native replacements for common desktop-only plugins where functionally feasible.
- OBS import-only scope: do not make OBS-format export or round-tripping a readiness requirement.

### J. Controls/UI/accessibility

- Full context menus.
- Drag/drop source manipulation with mouse.
- Multi-select and keyboard movement.
- Global hotkey conflict detection.
- Search result action routing.
- Advanced-settings progressive disclosure.
- Beginner mode vs advanced mode.
- External monitor/projector controls.
- Accessibility labels/navigation.
- User-configurable UI density/scaling.

### K. Diagnostics/performance

- Complete stats panel.
- Per-source latency/queue depth/drop counters.
- USB transfer error metrics.
- Audio XRUN/underrun/overrun counters.
- GPU/encoder timing.
- Network throughput/reconnect history.
- Thermal/battery telemetry.
- Perfetto-compatible tracing.
- Sustained 4K60 soak tests.
- Multi-device stress tests.

### L. Plugin/extension system

- Stable Stream4k Android extension API.
- Capability discovery.
- Sandboxed/isolated extension lifecycle.
- Source/filter/transition registration.
- Search integration for extensions.
- Import mapping for unsupported OBS plugin definitions.

---

## 7. Definition of production ready

The app is production-ready only when all of the following are true:

1. A physical Astra can build/install the release APK.
2. Real UVC ISO/BULK devices can be added as live video sources.
3. Real USB audio devices can be added as simultaneous live audio sources.
4. Android screen and playback capture work as real-time sources.
5. Multiple video/audio devices can run concurrently without UI-thread capture.
6. Canvas and output resolutions are independent and actually affect the native pipeline.
7. 4K60 hardware encoding is verified at runtime with no silent software fallback.
8. YouTube account login, existing broadcast selection and streaming are functional.
9. RTMPS and the applicable HEVC/HLS output paths are physically tested.
10. Recording and replay produce valid, recoverable files.
11. Scene/filter/transition/audio functionality required by the parity specification is implemented and testable.
12. OBS profiles and scene collections import with a compatibility report and asset relinking.
13. Search can locate and open/execute every implemented advanced feature.
14. Hotkeys, mouse, external display, project persistence and recovery work across app restarts.
15. Sustained 4K60 soak tests pass without unbounded latency growth, frame queue accumulation, audio drift, or thermal failure.

Until those checks pass, this is a development checkpoint, not a finished product.

---

## 8. Repository hygiene

- Keep `GEMINI.md` as the canonical project-context document.
- Keep `OBS_FEATURE_IMPLEMENTATION_SPEC.md` as the canonical research/feature specification.
- Keep `REALTIME_MEDIA.md` for the detailed live-media transport contract.
- Keep `BUILD_STATUS.md` for short build/release status.
- Do not commit generated APK/AAB/SO/object/build-cache artifacts.
- Do not keep superseded duplicate copies of the feature specification.
- Do not reintroduce fake/simulated media implementations.

---

## 9. Handoff checkpoint — 2026-09-29 (latest steering overrides older notes below)

### User's explicit testing workflow

- The user asked us to finish the app, wire its features, check OBS parity and check for runtime errors before calling it ready for Astra testing.
- OBS compatibility is import-only. Do not add OBS-format export or round-trip support as a readiness requirement. Imported profiles/settings/scenes should use compatible settings and scene-item positions, dimensions, order and visibility; unsupported values remain reported.
- The user now says to research and optimize specifically for the original Astra, implement every OBS feature that can work there, and get the app running on their Android first. This supersedes the earlier no-ADB instruction.
- ADB now detects the connected tablet as `NP05J` / `PQ84P01-EEA`, Android 15 (API 35). The user wants implementation and verification completed before installing a test build for them. No app install/launch has happened yet; do not call compile success runtime readiness.
- “Ready for device testing” is a development gate and is not the same as “production ready” in section 7.

### Current code checkpoint

- Latest `:app:assembleDebug` succeeds on 2026-09-29 using the installed SDK at `C:\Users\haamh\AppData\Local\Android\Sdk`. Set `ANDROID_HOME` and `ANDROID_SDK_ROOT` for Gradle; no `local.properties` was added. Build success is compile/package evidence only.
- The newest active studio source-properties UI is `app/src/main/java/com/stream4k60/app/ui/sources/SourcePropertiesDialog.kt`. It replaced the JSON quick editor for common source types and persists edits through the existing source config JSON.
- The source editor supports content-URI image/media selection, timed image slideshows, text style, camera facing/size/FPS, UVC device and advertised format selection, browser URL/size/FPS/JavaScript, color dimensions, and Android audio input/output selection. These paths compile, but are not device-verified.
- `BitmapSourceController.kt` decodes content URIs with a 3,145,728-pixel budget and renders text/color/slideshow sources. Camera/media configuration is applied. Browser sources have an unverified hardware-backed Surface/Canvas path up to 2400×1504/60 FPS and a capped software fallback (1280×720/10 FPS); WebView chooses its decoder automatically. No browser audio, local HTML/CSS, interaction or full lifecycle parity.
- OBS source volume/pan/monitoring and common color-correction/chroma-key filter settings now translate into Android source controls; the import report states that visual filter behavior is approximate. Unsupported OBS plugin sources have a remap flow to built-in Android source types; groups stay preserved because flat remapping would alter layout.
- Camera, USB, image/slideshow, media, browser, screen/playback capture, audio graph and compositor failures now feed a source error state shown in the Studio list. Capture permission can be retried from a source menu. USB source setup re-runs after USB enumeration changes.
- `FeatureCatalog.kt` and `FeatureSearch.kt` now label entries as partial, not implemented, or Android alternative. Search actions with no implementation are disabled, and settings links select their category. The status labels are a conservative work index, not a completed OBS parity audit.
- `OBS_PARITY_LEDGER.md` records behavior, Android equivalents, code evidence, status, importance and platform constraints for the full feature inventory. It distinguishes unfinished Android engineering from desktop mechanisms unavailable to ordinary Android apps. GPU chroma key is implemented; optional NPU background segmentation is not an Astra-readiness requirement.
- The supplied launcher/studio logo, native pending GPU-effect state, toolbar menus, color picker and first per-source video-effect controls were implemented earlier; see `BUILD_STATUS.md` for the build checkpoint.
- The app has not been installed or launched. ADB now sees the Astra (Android 15/API 35), but no physical-device check has occurred. A successful APK build is not a runtime check.
- Astra-specific changes: app minSdk 33 for the NDK Android Dynamic Performance Framework API; compositor reports work and target duration to the Android scheduler; system thermal status is surfaced; encoder capability listing uses reported hardware profiles, exact size/FPS bounds, bitrate range and vendor achievable-FPS estimate. These codec-level ranges do not prove full compositor/stream performance. No fan, RedCore, GPU clock or decoder selection API is assumed.
- Read-only ADB inspection on the connected Astra found vendor 4K120 performance points for both AVC and HEVC, but the separate measured-rate XML lists 4K HEVC at 65–93 FPS (HDR HEVC at 60–95) and has no measured 4K AVC entry. This is device firmware metadata only; wait for the app MediaCodec query and real streaming test before stating that 4K120 is sustained.
- Current user acceptance scope: multiple external UVC camera/capture-card sources over USB-C, linked USB audio where exposed, app source mixing and one-way OBS import. The Astra camera, virtual-camera output and screen recording/capture are not required. User does not need chroma key or AI segmentation. Editable fields request Android's native keyboard on focus; this compiles but remains unverified on Astra.
- REDMAGIC publicly names Snapdragon 8 Elite, RedCore R3 Pro and its custom Synaptics touch chip. Snapdragon subsystems include Oryon CPU, Adreno GPU, Hexagon NPU and Spectra ISP; only the public Android API and actual runtime capabilities are app-reachable. The exact Astra board BOM, Synaptics model, PMIC and display controller are not published. Do not confuse the 8 Elite tablet with Astra 2.

### Checkpoint 2026-09-30

- Ordered video filter chain is implemented (see HANDOFF.md). Remaining filter work: multi-pass filters that need neighboring pixels or extra textures (sharpen, blur, LUT, image mask/blend, scroll, render delay, crop/pad), audio filter DSP, and on-device visual comparison with OBS.
- JVM unit tests now exist under `app/src/test`; add tests alongside new pure-logic code.

### Immediate continuation tasks

1. Review `OBS_PARITY_LEDGER.md` against the full `OBS_FEATURE_IMPLEMENTATION_SPEC.md`. Keep feature scope Android-native and OBS import-only.
2. Continue closing code gaps and ensure every incomplete path is labeled honestly; the latest successful build does not establish runtime behavior.
3. Check typed source validation, imported config edits, permission retries and controller recovery on the attached original Astra after the implementation gate; do not substitute a non-Astra result for Astra validation. Current USB-host status has no webcams/capture cards attached.
4. Runtime-validate browser Surface/Canvas, local HTML/CSS and refresh on Astra. Browser audio and page interaction remain missing. Do not claim a user-selectable browser hardware decoder: Android WebView selects codecs automatically.
5. Continue auditing for any other no-op paths. The unreachable legacy source-property screens, inert `SettingsPathPicker` and fake numbered Template Manager have been removed; Templates remain disabled in feature search.
6. Continue priority gaps: ordered video/audio filter chains, nested scenes/groups, independent Studio Mode semantics, robust media/record/replay, UVC negotiation/control/recovery, encoder/output validation, external-display support, diagnostics and extensions. OBS export/round-trip is out of scope.
7. Continue toward an installable Astra candidate. The current gate is not met: ordered filters, nested scenes/groups, Studio Mode semantics, browser audio/interaction and output/record recovery remain incomplete. Do not install until the user-facing implemented/missing report and agreed implementation gate are complete; then validate external USB sources, keyboard, encoder modes, streaming, thermals and stability on Astra.

### Latest continuation checkpoint — 2026-09-29

- Per-source properties were expanded for media playlists and decoder/speed/audio controls; browser local HTML, CSS and refresh; UVC decoder preference, device controls and optional linked UAC audio; and text font/alignment/dimensions. Media mixer edits no longer restart playback, and media audio is sent to the native mixer.
- OBS desktop display-capture imports remain preserved but inactive; no new Astra camera or screen-capture source is offered. OBS project compatibility remains import-only.
- The current `:app:assembleDebug` succeeds with the configured Android SDK. This is compile/package verification only. The app has not been installed/launched and no Astra or USB accessory behavior has been verified.
- Not ready for Astra testing. Remaining work includes ordered filters/audio DSP, nested groups/scenes, independent Preview/Program transitions, browser audio/interaction, richer audio routing, stream/record/replay recovery, multiview/projectors and Astra runtime validation. Use `OBS_PARITY_LEDGER.md` as the current gap list.

### Known Android-specific distinctions to preserve in the parity audit

- OBS's Windows/macOS/Linux capture backends (DirectShow, WASAPI, Win32 window/game hooks and desktop display APIs) are not portable binaries; Android equivalents are Camera2, UVC/USB Host, MediaProjection/Display APIs and AudioPlaybackCapture where the platform permits it.
- OBS desktop plugin binaries and ABIs cannot be loaded directly on Android. The useful plugin behaviors need a Stream4k Android extension API or native reimplementation; importing a desktop plugin must preserve its definition and explain the gap.
- NVENC, Quick Sync, AMF and desktop GPU-specific controls are implementation details, not proof that hardware encoding is useless on Android. Use MediaCodec and runtime capability queries where behavior can be matched.
- A normal Android app cannot assume system-privileged features such as registering itself as a system camera provider/virtual camera. Any requested virtual-camera parity needs a researched Android pathway or must be marked unavailable with its importance and reason.
