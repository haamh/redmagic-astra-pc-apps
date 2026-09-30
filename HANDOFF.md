# Stream4k60 implementation handoff

**Checkpoint:** 2026-09-30 (streaming-studio pass)  
**Repository:** `haamh/redmagic-astra-pc-apps`, branch `main` (work branch `claude/relaxed-lamport-zzejhc`)  
**State:** Installed and being tested on the Astra. Launch crashes are fixed; the Insta360 camera and an audio input still show errors on the device.

## START HERE (latest, 2026-09-30)

### Direction from the user (most recent first)
- **Streaming only.** Recording and the replay buffer are removed from the UI; their engine code is left in place, unused.
- Match OBS's layout and behaviour (the user compares against OBS screenshots). Every feature: simple controls first, the rest under "Advanced" with explanations.
- Every page/dialog must have an X close button (shared `ui/common/ClosableTitle.kt`).
- All commits must be authored and committed as `haamh <haamthelord@gmail.com>` with no co-author or tool trailers.

### Build, install, debug (the user's Windows PC, PowerShell)
```
git pull origin main
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s Stream4k60 AndroidRuntime
adb shell cat /sdcard/Android/data/com.stream4k60.app/files/source-errors.txt
adb shell cat /sdcard/Android/data/com.stream4k60.app/files/last-crash.txt
adb shell cat /sdcard/Android/data/com.stream4k60.app/files/native-crash.txt
```
- Debug builds are signed with the checked-in `app/stream4k60-debug.keystore` (SHA-1 `07:88:C4:F1:2C:7B:D5:90:70:AB:6C:EB:98:3D:DF:FB:1B:E1:17:E9`), so every machine produces installable updates and Google sign-in keeps working. The first install after this change needed one `adb uninstall com.stream4k60.app`.
- Linux/cloud: `ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`; after native changes run `app/src/test/native/run_compositor_test.sh` (Mesa).
- The APK is ~76 MB. Build outputs are not committed.

### Open problems (do these next)
1. **Insta360 (direct USB).** Android does NOT list it in the camera service on the Astra, so only direct UVC is possible. The last device error was our own bandwidth pre-check wrongly refusing 1080p60 MJPEG: it estimated 4 bits/pixel, and the budget used the camera's USB 2.0 descriptor speed. **That pre-check has been removed entirely** (`NativeUsbManager.startCapture`). Whether frames now arrive is untested; the next error, if any, will be the real UVC negotiation or transfer failure (see Properties / `source-errors.txt`; code in `engine/UvcCaptureSession.kt`, `cpp/usb/uvc_iso_stream.cpp`, `uvc_bulk_stream.cpp`).
   - **Missing webcams:** USB permission requests were fired for all devices at once and Android dropped all but one. They are now queued one at a time, and USB source Properties has a **Rescan USB devices** button.
2. **Audio input error** (the user's audio input source): full message not yet received. Only the UVC streaming interface is claimed, so the camera path should not steal the Insta360 mic. Errors come from `MainStudioViewModel` `syncAudioGraph`/`audioRoutes`.
3. The mixer's two meter bars show the same level: the engine reports one peak per source. Mono and the audio track checkboxes are not implemented.
4. YouTube: the app can pick existing scheduled broadcasts but cannot create one (OBS "Manage Broadcast → Create"). The access token lives in memory only; it is restored silently when the picker opens.

### What exists now (this pass)
- **Latest round:**
  - Every dock scales its contents with its size (`Dock(contentScale)`, from dragged size ÷ default size, clamped 0.75–1.6).
  - The mixer meter's dB labels adapt to the available height, and channels are 84 dp wide.
  - The mixer footer has **Properties** and **Filters** buttons. They and the channel menus open one window with a tab per audio source and a Properties/Filters switch (`AudioSourceTabs.kt`, a `header` slot on `SourcePropertiesDialog` and `FilterEditorScreen`).
  - Mic/Aux Properties can pick its device, which is saved as `AudioSettings.micDeviceId`; "Default" = Android's current input.
- **Layout (OBS):** Scenes/Sources docks on the left, preview, source toolbar, Audio Mixer / Scene Transitions / Controls docks, and a status bar. Every dock split is draggable (`ui/main/components/DockLayout.kt`, saved in SharedPreferences `studio_layout`; double-tap a bar to reset it).
- **Preview:** `PreviewViewport.kt` offers Scale to window / Canvas / Output, plus zoom and pan:
  - Ctrl+wheel or a pinch on empty space zooms.
  - The wheel, middle-button drag or a two-finger drag pans.
  - Gestures that start on a source still edit the source.
  - The preview is a **TextureView** (`NativePreviewSurface.kt`) so zoom is clipped; screenshots use `TextureView.getBitmap`.
- **Audio mixer (`AudioMixerPanel.kt`):**
  - Channels are laid out vertically with horizontal scroll, or horizontally; switchable.
  - Each channel has a Global/Active badge, a cubic dB fader, a meter with a peak hold, and mute and monitor buttons.
  - The channel menu has Hide, Lock volume, Rename, Filters, Properties and Advanced Audio Properties.
  - Hidden/locked state is stored in source config (`mixerHidden`, `volumeLocked`).
- **Global audio:** Desktop Audio (`global:desktop`, playback capture) and Mic/Aux (`global:mic`) exist in every scene. They are configured in Settings → Audio and their mixer/filter state is stored in `AudioSettings.desktopConfig/micConfig`.
- **Settings:** real pages for Stream, Output, Audio, Advanced, Hotkeys and Accessibility. Settings are stored as JSON sections in the active profile via `SettingsRepository`.
  - **Stream:** service/server/key.
  - **Output:** bitrate number field plus a recommended value per service.
  - **Audio:** devices, monitoring, push-to-talk/mute and delay.
  - **Advanced:** auto-reconnect.
  - **Hotkeys:** full rebinding with conflict detection (`HotkeyDispatcher`).
  - **Accessibility:** UI scale, applied in `MainActivity`.
- **Streaming:**
  - YouTube, Twitch, Facebook, Kick and custom RTMP(S) all work; previously only YouTube and Custom passed the engine check.
  - Platform services are capped at 60 FPS.
  - Reconnect is surfaced as RECONNECTING, and the live bitrate shows in the status bar.
  - The YouTube picker is "Manage Broadcast" in Controls.
- **Source errors:**
  - `SourceRuntimeErrors` logs errors to logcat and `source-errors.txt`.
  - The Sources list shows two lines; tapping shows the full text.
  - Properties shows the full error, a live solo preview of the source (native `setSoloPreview`, `GlCompositor::renderSolo`), and a level meter for audio inputs.
- **YouTube sign-in** needs the Google Cloud project the user created: YouTube Data API v3, an OAuth consent screen with test users, and an Android OAuth client with package `com.stream4k60.app` and the SHA-1 above.

## User direction

The user wants an Android-native OBS-equivalent studio, with OBS project/profile/scene import only. Target the original REDMAGIC Astra tablet itself: Snapdragon 8 Elite platform, RedCore R3 Pro, the custom Synaptics touch chip, its 2400 × 1504 / up-to-165 Hz display, 8,200 mAh dual-cell battery, ICE-X cooling and public Android codec capabilities. Do not assume Astra 2 hardware. Do not assume unpublished part numbers or privileged vendor APIs.

The latest user direction is to optimize specifically for the Astra and use external UVC webcams/capture cards and their audio. The Astra's built-in camera, screen recording/capture and virtual-camera output are not required. Desktop display-capture sources in OBS imports must remain preserved but inactive. ADB previously detected the connected tablet as `NP05J` / `PQ84P01-EEA`, Android 15 (API 35). Finish implementation and review before installing; the app is not installed/launched.

## Checkpoint 2026-09-30 (Linux build)

- Repository is now on GitHub (`haamh/redmagic-astra-pc-apps`) with a `.gitignore` excluding `.gradle/`, `.kotlin/` and build outputs.
- **Ordered video filter chain implemented.** Sources store `settings.videoFilters` (ordered array of `{id,type,name,enabled,settings}`); legacy `effects` objects are read and converted. Stage types: `COLOR_CORRECTION`, `CHROMA_KEY`, `COLOR_KEY`, `LUMA_KEY`. `NativeEngine.setSourceFilterChain` sends up to 8 enabled stages (16 floats each) to the compositor, which runs them in order in the layer fragment shader. `FilterEditorScreen` adds/removes/reorders/enables stages with a live preview; Cancel restores the saved chain.
- OBS import maps color correction, chroma key, color key and luma key filters (v1 and v2) in OBS order, including repeated and disabled filters. Fixed two import bugs: OBS gamma was applied inverted, and OBS integer colors (0xAABBGGRR) were read with red and blue swapped (affected key colors, color sources and text colors).
- Removed the unreferenced placeholder `VideoFilters.kt` / `AudioFilters.kt` composables.
- Added JVM unit tests (`app/src/test`, `./gradlew :app:testDebugUnitTest`): 8 tests for filter-chain storage, legacy migration and native packing, all passing. The compositor GLSL passes `glslangValidator` as ES 3.20.
- `:app:assembleDebug` succeeds on Linux (Android SDK platform 35, build-tools 34, NDK 27.0.12077973, CMake 3.22.1). Still not installed or run on the Astra.

## Checkpoint 2026-09-30 (third pass)

- Nested Scene and Group sources implemented end to end (renderer, studio, source list, properties, OBS import). See `OBS_PARITY_LEDGER.md` section 4.
- Renderer: per-frame `prepareFrame` → `renderSceneTargets` (offscreen canvases, dependencies first) → `renderTo` per surface. Fixed two device-breaking bugs found by the new desktop GPU tests: CPU-uploaded and raw USB sources rendered upside down, and sources without filters were not drawn at all (sampler unit conflict). Run `app/src/test/native/run_compositor_test.sh` (Mesa) after native changes.
- OBS import now matches versioned source ids (`color_source_v3`, `text_gdiplus_v3`, `slideshow_v2`…), which previously imported as unsupported; imports text extents.
- Auto-sized text and media report their real size (`SourceNativeSizes`) so OBS scales apply to the real size.
- 30 JVM unit tests + 14 GPU checks pass; `assembleDebug` succeeds. Not yet run on the Astra.

## Checkpoint 2026-09-30 (second pass)

- User direction: keep OBS's features but present each with the everyday control(s) first and the rest under "Advanced" with explanations. Chroma key is not a focus.
- Video filters added: Apply LUT (.cube / PNG LUT, `LutParser`, `LutLibrary`, native 3D textures, 2 per source) and Sharpen. Raw USB frames now honor crop/flip.
- Audio filters: `AudioFilterChain` + native Noise Gate (`NativeAudioMixer.setInputGate`). Simple control is "Start listening at"; Advanced has close threshold, attack, hold and release. Wired through `AudioInputRoute.noiseGate` so it stays active while streaming/recording. Live preview via `NativeAudioGraph.previewGate`.
- Filters editor: source menu "Filters…" with Video/Audio tabs, available for audio-only sources too.
- Canvas editing rewritten on `CanvasEditMath` (unit-tested): aspect-locked corners, edge stretch, crop, rotation knob, pinch/twist, bounds-aware resizing, "Crop" and "Free resize" chips for touch.
- OBS import: activates imported profile and switches to the imported collection (`ImportSelection`); imports LUT (with asset relink), sharpen and noise gate filters; carries desktop webcam resolution/FPS.
- Add Source now lists Android app audio. Still missing as source types: nested Scene and Group.
- 26 JVM unit tests pass; `assembleDebug` succeeds. Still not run on the Astra.

## Latest verification

Set `ANDROID_HOME` to `C:\Users\haamh\AppData\Local\Android\Sdk` for Gradle. The latest `:app:assembleDebug` succeeded on 2026-09-29 with the source-properties, USB-decoder and import-scope changes. It proves compilation/packaging only. No app install, launch or Astra runtime check was performed. No `local.properties` was added.

## Latest continuation checkpoint

- Media properties now include single files, multi-file playlists, URL, loop, playback speed, start position, output dimensions, audio routing and a per-source MediaCodec decoder preference. Media mixer edits update live without restarting ExoPlayer; decoded Media3 PCM enters the native audio graph and its meter.
- Browser properties include HTTP(S) or local HTML, custom CSS, explicit refresh, JavaScript, capture rate/size and GPU-backed drawing/software fallback choice. WebView still chooses video decoders itself; browser audio and page interaction are absent.
- UVC properties include advertised formats, standard controls reported by the device, per-source preferred hardware/software decoder for compressed formats, and optional linked UAC audio. Capture config changes restart/reconfigure sessions; failed partial startup cleans resources; source effects update live.
- Text sources expose font family/alignment and render dimensions. New source/remap choices exclude the Astra camera and Android screen capture. Imported desktop display-capture sources are preserved but not activated.
- Latest build: `:app:assembleDebug` succeeded. The APK is compile-verified only. No installation, USB accessory, browser, audio, stream or thermal runtime verification occurred.
- This is still not ready for Astra testing. Ordered video/audio filter chains, nested scenes/groups, independent Preview/Program and transitions, browser audio/interaction, multiple audio tracks, multiview/projectors, recording/replay/output recovery and Astra runtime checks remain.

## Work completed in this checkpoint

- Added a typed source-properties dialog and connected it to the studio source context action. The raw JSON editor is no longer the active properties UI.
- Wired SAF document selection and persistable `content://` references for image, media and slideshow sources. Image decoding now reads content URIs.
- Added actual timed slideshow rotation with loop/shuffle options; text overlays now use configured text, size, color, background, bold, italic and alignment; solid-color sources use configured dimensions.
- The Astra camera is not offered by new-source or OBS-remap flows; legacy Camera2 support remains only for previously saved source rows.
- USB source configuration selects a connected UVC device and advertised format, applies standard advertised UVC controls, supports preferred hardware/software MediaCodec decoding for compressed formats, and can link a separately enumerated USB audio input.
- Media source supports local/URL single media and multi-file playlists; decoder preference, speed, start position, loop, output size and audio mixer routing are editable. Mixer changes no longer restart playback. Browser supports remote URLs/local HTML, custom CSS, refresh, JavaScript and the existing render modes.
- OBS desktop screen/window-capture sources are preserved as unsupported and are not activated or offered as a remap target.
- Search results now state `Partial`, `Not implemented`, or `Android alternative`; results with no executable action are disabled. Settings-linked search results open the requested category.
- Removed the unused always-zero USB texture getter and an empty settings save method.
- OBS compatibility is import-only; OBS-format export and round-trip compatibility are out of scope per the user's clarification.
- OBS source volume, balance, sync offset and monitoring state are retained in the imported source config and mapped to the Android audio graph. Android playback audio is available as an audio mixer strip.
- Common OBS color-correction and chroma-key filters are translated into the available Android compositor controls. Their appearance is approximate; unsupported filters and repeated extra stages remain preserved and are reported.
- Unsupported OBS plugin sources can be remapped to built-in Android source types where a practical equivalent exists. OBS groups remain preserved because a flat remap would change their scene composition.
- Added visible per-source runtime errors for camera, USB, media, image/slideshow, browser, projection capture, audio graph and compositor failures. Screen/playback capture errors can be retried from the source menu.
- USB capture setup reacts to USB device-list changes. Legacy projection code uses Android display modes but screen capture remains outside this Astra acceptance workflow.

## Files changed in this checkpoint

- `GEMINI.md` — canonical current user workflow, checkpoint and next-task instructions added.
- `BUILD_STATUS.md` — implemented work and current verification caveats updated.
- `HANDOFF.md` — this continuation note.
- `app/src/main/java/com/stream4k60/app/ui/sources/SourcePropertiesDialog.kt`
- `app/src/main/java/com/stream4k60/app/ui/main/MainStudioScreen.kt`
- `app/src/main/java/com/stream4k60/app/ui/main/MainStudioViewModel.kt`
- `app/src/main/java/com/stream4k60/app/engine/BitmapSourceController.kt`
- `app/src/main/java/com/stream4k60/app/engine/CameraSourceController.kt`
- `app/src/main/java/com/stream4k60/app/engine/MediaSourceController.kt`
- `app/src/main/java/com/stream4k60/app/engine/MediaAudioProcessor.kt`
- `app/src/main/java/com/stream4k60/app/engine/MediaSourceRenderersFactory.kt`
- `app/src/main/java/com/stream4k60/app/engine/UvcVideoControls.kt`
- `app/src/main/java/com/stream4k60/app/engine/UvcCaptureSession.kt`
- `app/src/main/java/com/stream4k60/app/engine/NativeUsbManager.kt`
- `app/src/main/java/com/stream4k60/app/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/stream4k60/app/navigation/AppNavHost.kt`
- `app/src/main/java/com/stream4k60/app/navigation/NavRoutes.kt`
- `app/src/main/java/com/stream4k60/app/ui/search/FeatureCatalog.kt`
- `app/src/main/java/com/stream4k60/app/ui/search/FeatureSearch.kt`
- `app/src/main/java/com/stream4k60/app/engine/SourceRuntimeErrors.kt`
- `app/src/main/java/com/stream4k60/app/engine/NativeAudioGraph.kt`
- `app/src/main/java/com/stream4k60/app/service/ProjectionCaptureService.kt`
- `app/src/main/java/com/stream4k60/app/profile/ObsProjectImporter.kt`
- `app/src/main/java/com/stream4k60/app/ui/main/components/AudioMixerPanel.kt`
- `app/src/main/java/com/stream4k60/app/ui/main/components/CustomRtmpDialog.kt`
- `app/src/main/java/com/stream4k60/app/data/model/StreamConfig.kt`
- `app/src/main/java/com/stream4k60/app/data/repository/SettingsRepository.kt`
- `app/src/main/java/com/stream4k60/app/engine/EncoderConfig.kt`
- `app/src/main/java/com/stream4k60/app/engine/RtmpPublisher.kt`
- `app/src/main/java/com/stream4k60/app/engine/StreamEngineImpl.kt`
- `app/src/main/java/com/stream4k60/app/ui/settings/VideoSettingsPage.kt`
- `app/src/main/java/com/stream4k60/app/ui/settings/OutputSettingsPage.kt`
- `app/src/main/java/com/stream4k60/app/ui/search/FeatureCatalog.kt`

Earlier changes are documented in `BUILD_STATUS.md`, including the supplied logo, native GLES compositor, OBS transform fields, toolbar menus, color picker and first GPU filter subset.

## Known limitations and immediate work

1. Review `OBS_PARITY_LEDGER.md` against every section of `OBS_FEATURE_IMPLEMENTATION_SPEC.md`. It records the Android equivalent, current evidence and actual limitations. The feature spec remains scope; the user's clarification makes OBS compatibility import-only.
2. The source validation and runtime error paths compile, but none has been observed running. ADB sees the attached Astra; install/launch validation remains pending until the agreed implementation gate is reached.
3. Browser capture now has a hardware-backed Surface/Canvas compositor path up to the Astra panel dimensions, plus a capped software fallback. It is not runtime-proven, does not force a particular decoder (WebView/system chooses), and has no browser-audio path; do not describe it as production-quality yet.
4. UVC format negotiation/control/recovery, nested scenes/groups, ordered filter chains, DSP filters, multiview, independent Studio Mode program semantics, output/reconnect validation, safe recording/replay recovery, external-display routing, diagnostics and extensions remain incomplete or unverified. Import is one-way by design; OBS export and round-trip fidelity are out of scope.
5. Removed the unreferenced legacy source-property screens, inert Settings path picker and fake numbered-template grid after confirming there were no navigation call sites. Templates remain disabled/not implemented in feature search.
6. Do not say “ready for Astra testing” until the parity ledger is reviewed, remaining placeholders are removed or clearly disabled, unavailable features and reasons are recorded, and an install/launch check is completed. Build success alone is not that gate.
7. Output allows custom sizes through 3840×2160 and rates through 120 FPS. Custom RTMP(S) sends that profile through the hardware encoder if Astra MediaCodec exposes the exact mode. YouTube remains limited to 60 FPS. No app MediaCodec query or sustained 4K120 run has been observed yet.
8. Read-only ADB inspection of the connected Astra's vendor codec XML found 4K120 performance-point declarations for AVC and HEVC (declared bitrate range maxima 220/160 Mbps). The separate HEVC measured-rate metadata shows 65–93 FPS at 4K (HDR HEVC 60–95); AVC has no 4K measured entry in the sampled file. This device-side metadata is not a runtime app query or stream test, and it makes 4K120 HEVC a specific concern to verify.

## Continuation update

- Browser rendering now supports a hardware-backed Surface/Canvas path up to 2400 × 1504 at 60 FPS, with a 1280 × 720 / 10 FPS software fallback; it is compile-verified only and browser audio is absent.
- Still-image decoding now downsamples to a maximum 3,145,728-pixel bitmap budget.
- `OBS_PARITY_LEDGER.md` now has a dedicated explanation of unfinished engineering versus Android platform boundaries. “Impossible” applies to copying desktop hooks/drivers/plugin binaries into an ordinary Android app; it does not apply to most missing Stream4k features, which need implementation and verification.
- Astra NPU-based AI segmentation is optional and not a readiness gate. The official Astra spec lists Snapdragon 8 Elite and the RedCore R3 Pro gaming chip; Qualcomm provides a Hexagon NPU SDK/API, but REDMAGIC does not document a general-purpose app inference API for RedCore. The current GPU chroma-key path is separate. Stream4k has not integrated or device-validated an NPU segmentation backend.
- Removed the unreachable legacy source property composables and fake template screen, plus the unused inert Settings path picker. The active typed editor and importer compile successfully after this cleanup.
- Fixed the playback-capture loop's invalid `AudioRecord.ERROR_WOULD_BLOCK` reference: zero-byte blocking reads continue; negative results report capture failure and stop the session.
- Added NDK Android Dynamic Performance Framework hints for the native compositor thread. The app minSdk is now 33 to match this public NDK API; Astra's official product information lists Android 15.
- Corrected MediaCodec reporting: encoder profiles are populated, exact size/FPS support is queried, 4K60 checks no longer infer frame-rate support from dimensions, and audio encoder enumeration is implemented. Astra advertised codec ceilings remain vendor specifications, not proof of app-accessible modes; runtime MediaCodec queries on Astra and actual encode validation remain outstanding.
- Added custom output bounds through 3840×2160, 120 FPS common/integer selection and a target bitrate control up to 100,000 Kbps, with Android's declared bitrate range shown alongside exact size/FPS checks.
- Added custom RTMP(S) publishing so 120 FPS does not go through YouTube's up-to-60-FPS ingest path. Compatible RTMP server/key values imported from OBS can prefill the endpoint dialog. Custom endpoint limits and sustained Astra output remain unverified; stream-start errors now appear in Studio.
- Official public Astra hardware disclosures include Snapdragon 8 Elite (Oryon CPU, Adreno GPU and platform Hexagon/Spectra capabilities), RedCore R3 Pro and a custom Synaptics touch chip. The original tablet's complete board BOM, exact Synaptics model, display timing controller, PMIC and other part numbers are not publicly disclosed; no app optimization can target an unknown model or undocumented vendor interface.
- The user clarified the acceptance workflow: multiple live video sources (built-in/USB webcams and UVC capture cards over USB-C), mouse/touch text entry with Android's native keyboard, and no virtual-camera or screen-recording requirement. AI segmentation/chroma-key development is not needed; OBS import remains one-way.
- Added `ui/util/KeyboardInput.kt` and attached `showImeOnFocus()` to all editable Compose text fields (properties, transform, search, rename, color, settings and filter dialogs). Read-only device/format dropdowns are intentionally excluded. It compile-verifies; the keyboard still needs an Astra interaction check.

## Practical build command

```powershell
$env:ANDROID_HOME = 'C:\Users\haamh\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:assembleDebug
```

The project is tracked in Git on GitHub (`haamh/redmagic-astra-pc-apps`). Build caches and APKs are ignored by `.gitignore`.
