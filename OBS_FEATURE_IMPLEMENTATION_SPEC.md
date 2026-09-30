# Stream4k60 — OBS Feature Inventory + Android/RedMagic Astra Implementation Specification

**Research baseline:** OBS Studio 32.2.2 stable plus the OBS 33.0.0 beta/master source tree available on 2026-09-28.

**Target:** REDMAGIC Astra (Snapdragon 8 Elite, Android 15 / REDMAGIC OS 10.5), external monitor, keyboard/mouse, USB-C dock, multiple live USB media devices, YouTube up to its documented 4K60 ingest limit, and custom RTMP(S) output capability-driven up to 4K120.

**Primary requirement:** reproduce OBS functionality and workflow, but implement the engine natively for Android instead of porting the desktop frontend. No simulated media sources, fake transports, placeholder success states, or CPU video-processing hot paths.

---

## 0. Ground rules

### 0.1 Functional parity vs. desktop implementation

We want **functional and usability parity**, not binary compatibility with the desktop OBS executable.

- A desktop OBS scene/source/filter must have an equivalent result in Stream4k.
- Android-native APIs are preferred when they produce the same observable behavior with lower latency or lower power.
- Desktop-only mechanisms (DirectShow, WASAPI, Game Capture hooks, Win32 window hooks, CEF binaries, OBS DLL plugins) are replaced by Android equivalents.
- Desktop OBS plugin binaries cannot be loaded directly on Android. Their configuration must be imported, translated where possible, and otherwise preserved as an explicit unsupported-plugin object instead of silently disappearing.
- Stream4k itself should have a stable Android plugin API for third-party extensions.

### 0.2 Real-time media rule

Every live media source is a continuous, timestamped stream. There are no UI polling loops, periodic screenshots, synchronous one-transfer USB loops, or delayed “source refresh” mechanisms in the live path.

### 0.3 No fake completion

A feature may only enter a successful/running state after the underlying native subsystem has actually opened and produced/consumed media or established the required service connection.

### 0.4 Hardware-first rule

The runtime must query actual device capabilities. The app must not assume that every Snapdragon/MediaCodec build exposes every codec, profile, level, pixel format, rate-control mode or 10-bit path merely because the silicon family is capable of it.

---

# 1. OBS FEATURE INVENTORY

**Scope clarification (2026-09-29): OBS compatibility is import-only.** Stream4k must import OBS profiles/settings and scene collections, then use supported fields such as source positions, sizes, visibility, order, transforms and assets in its Android scene graph. Exporting Stream4k projects back into OBS format and OBS round-trip compatibility are not readiness requirements.

**Astra acceptance scope:** use external UVC webcams/capture cards and their available USB audio. The Astra's built-in camera, Android screen/display capture and screen recording, and virtual-camera output are not required. Preserve imported desktop capture definitions and report them as unsupported without activating a screen-capture flow.

OBS is more than the central Studio window. The feature inventory is divided into the following functional layers.

## 1.1 Project/session management

### Required behavior

- Profiles
  - New
  - Duplicate
  - Rename
  - Delete
  - Import
  - Profile switching without destroying scenes
  - Profile-specific streaming/recording/output configuration
- Scene Collections
  - New
  - Duplicate
  - Rename
  - Delete
  - Import
  - Automatic discovery of known collections
  - Scene Collection switching
- Project recovery
  - Atomic saves
  - Crash-safe persistence
  - Recovery after process death
  - Backup copies
  - Versioned migrations
- Safe startup
  - Safe mode / extension isolation equivalent
  - Ability to open a damaged project without running risky third-party extensions
- Optional native Stream4k backup package (separate from OBS compatibility)
  - Settings
  - Profile
  - Scene collection
  - Source definitions
  - Filters
  - Transitions
  - Media assets
  - Browser source metadata
  - Plugin/source compatibility metadata
- Project validation
  - Missing files
  - Missing sources
  - Missing plugins
  - Missing fonts
  - Unsupported codecs
  - Missing USB devices
  - Changed device identifiers
  - Broken URLs

### Android implementation

Room/DataStore for metadata, atomic file writes, project manifests, versioned migrations, app-private asset packages, SAF import/export, ZIP package support, hash-based asset deduplication.

Profiles and Scene Collections remain independent exactly as OBS treats them. A full import wizard can take a whole OBS export directory/ZIP and reconstruct both at once.

---

# 2. OBS STUDIO WORKSPACE / FRONTEND

## 2.1 Main studio layout

Reproduce the agreed OBS-like basic homepage/workspace:

- Preview
- Program
- Scenes
- Sources
- Audio Mixer
- Scene Transitions
- Controls
- Status/statistics
- Menu/toolbar
- Context menus
- Dock-like panels
- Resizable panels
- Detachable/secondary views where Android/display APIs permit

### Android adaptation

- Landscape-first external-monitor layout.
- Touch-capable fallback for tablet use.
- Full keyboard navigation.
- Full mouse pointer, wheel, drag, right-click/context menu behavior.
- High-density desktop UI on the Astra's 2.4K/165 Hz display.
- External-display projector support through Android DisplayManager/Presentation/virtual-display paths.
- UI scaling independent from canvas/output resolution.
- Portrait studio layout as an optional mode.

---

# 3. COMMAND/FEATURE SEARCH

This is a Stream4k-native improvement over raw OBS menus.

## 3.1 Global Search / Command Palette

Search across:

- Actions
- Settings
- Sources
- Filters
- Transitions
- Encoders
- Outputs
- Scenes
- Scene Collections
- Profiles
- Hotkeys
- Devices
- YouTube broadcasts
- Help/feature documentation

Search results must be actionable:

`Search → select result → execute action OR open exact setting/source/filter panel.`

### Required capabilities

- Fuzzy matching
- Synonyms
- Natural terms (“mic delay”, “make webcam round”, “stream 4K”, “capture game audio”)
- Keyboard navigation
- Recent searches
- Recently used commands
- Context-aware results
- Disabled/unavailable features explain why
- Missing-device results link directly to device setup
- Search inside advanced settings without exposing advanced complexity by default

### Android implementation

Local indexed command registry, settings registry and feature metadata; no network dependency. Use the command palette as the single discoverability layer for advanced functionality.

---

# 4. SCENES AND SOURCES

OBS describes sources as the fundamental audio/video inputs used to construct scenes. Sources can provide video, audio, or both and can also implement filters and transitions. citeturn423176search1turn423176search2

## 4.1 Scene operations

- Create/delete/rename scene
- Duplicate scene
- Reorder scenes
- Scene hotkeys
- Scene transition assignment
- Scene as source
- Nested scenes
- Scene visibility
- Scene-level filters
- Scene-level audio routing
- Scene-level transition behavior

## 4.2 Groups

- Create group
- Rename group
- Nest groups
- Collapse/expand
- Move source into/out of group
- Group visibility
- Group locking
- Group transforms
- Group filters where supported
- Group behavior in Studio Mode

## 4.3 Source list behavior

- Add source
- Add existing source
- Duplicate source
- Rename source
- Delete source
- Reorder z-index
- Hide/show
- Lock/unlock
- Select source
- Multi-select where practical
- Copy/paste
- Copy/paste transform
- Copy/paste filters
- Reset transform
- Fit to canvas
- Stretch to canvas
- Center to canvas
- Transform dialog
- Crop via modifier input
- Snapping
- Pixel alignment guides
- Source bounding boxes
- Source overflow indicators
- Source toolbar
- Right-click/context actions

OBS's current source snapping has visual guides and full-axis snapping behavior; this is part of the target behavior rather than an optional convenience. citeturn720348search0turn298013search2

---

# 5. SOURCE TYPES — COMPLETE TARGET SET

The implementation must expose a source catalog rather than a raw technical menu. Internally every entry still has a stable source type ID.

## 5.1 Video capture / live devices

- USB webcam
- USB capture card
- HDMI capture card
- UVC camera
- UVC H.264 camera
- UVC HEVC camera
- UVC MJPEG camera
- Raw UVC YUYV/UYVY/NV12 sources
- Built-in front camera
- Built-in rear camera
- External Android camera when exposed by Camera2/CameraX
- Capture-card audio linked to the video source
- External/custom audio device linked to the video source

OBS's desktop Video Capture Device source supports webcams/capture cards and device format, frame rate, color-space/range, buffering and optional linked audio configuration. Stream4k must expose the equivalent controls where the Android/UVC device reports them. citeturn650092search5

### Android implementation

Native UVC transport:

- USB descriptor parser
- UVC interface/alternate-setting selection
- UVC PROBE/COMMIT negotiation
- ISO endpoint engine
- Bulk endpoint engine
- Multiple outstanding transfers
- Immediate re-submission
- Packet status/error accounting
- Frame-ID/EOF reassembly
- Device/host timestamping
- Hot-unplug recovery
- Bandwidth-aware format negotiation

Compressed video should be hardware-decoded with MediaCodec into a Surface-backed path. Raw formats must use GPU upload/format conversion rather than CPU per-pixel conversion whenever possible.

Astra's USB 3.2 Gen 2 interface is advertised at up to 10 Gbps. That is ample for compressed multi-camera workflows, but a shared dock cannot magically carry arbitrary numbers of uncompressed 4K60 streams; negotiated UVC formats and aggregate hub bandwidth must be shown to the user before activation. citeturn768434search0turn768434search2

## 5.2 Screen/display capture

- Entire Android display
- Selected display when multiple displays exist
- External monitor display
- App/window-equivalent capture where Android exposes a capturable surface/window
- Cursor capture
- Touch/pointer indicator optionally
- Crop region
- Orientation handling
- HDR/SDR handling

### Android implementation

MediaProjection + VirtualDisplay/Surface pipeline, foreground service ownership, GPU-backed surface consumption, permission/revocation handling. Android requires media-projection capture sessions to be maintained by an appropriate foreground service on modern releases. citeturn393132search11

## 5.3 Game capture

Desktop OBS's Game Capture uses OS-specific accelerated capture hooks and is not portable to Android. citeturn650092search6

Android equivalent:

- MediaProjection screen/game capture
- Game application selection metadata where available
- Full-screen game capture profile
- Optional Android/window/task capture where public APIs permit
- Game audio through AudioPlaybackCapture
- Per-game capture templates

No fake “Game Capture” hook should be presented where Android cannot expose it.

## 5.4 Window/application capture

Android equivalent of a desktop window is a capturable application/display surface. Implement the closest legally/publicly available Android capture mechanism and expose the capability clearly. Avoid pretending to support arbitrary hidden application surfaces that Android prevents.

## 5.5 Browser source

OBS Browser Source is a full embedded browser capable of HTML/CSS/JS, URL or local content, custom viewport size, custom FPS, CSS, shutdown when hidden and refresh-on-activation. citeturn650092search1

Stream4k target:

- URL
- local HTML package
- viewport width/height
- custom FPS
- alpha/transparent background
- custom CSS
- JavaScript
- local assets
- refresh
- cache refresh
- pause/unload when hidden
- refresh when scene activates
- browser source audio as a true source
- browser source interaction mode
- safe page permission model
- cookies/storage per browser-source profile

### Android implementation

Use the system Chromium/WebView hardware-accelerated renderer for the browser engine, isolated from the media engine. Integrate its rendered surface into the compositor using a GPU/hardware-backed interop path (Surface/SurfaceTexture/SurfaceControl/HardwareBuffer where available) rather than per-frame CPU screenshots. Use PixelCopy/HardwareBuffer only as a controlled fallback for platforms where direct surface interop is unavailable.

## 5.6 Images

- Single image
- Animated image formats supported by Android decoder stack where practical
- Opacity
- Transform
- Crop
- Color correction
- Refresh/reload
- Asset relinking

## 5.7 Image slideshow

- Folder/asset list
- Random/sequential ordering
- Duration
- Transition mode
- Loop
- Pause
- Slide selection
- Reload

## 5.8 Color source

- Solid color
- Alpha
- HDR-aware color interpretation
- Transform/crop/filter support

## 5.9 Text source

- Font family
- Font size
- Weight/style
- Alignment
- Outline
- Drop shadow
- Color
- Opacity
- Word wrapping
- Bounding box
- Vertical alignment
- Text from file
- Text from live value/data
- Right-to-left/language support
- Emoji/unicode fallback
- Scrolling text

Android implementation: Android/Skia font stack, GPU text atlas where possible, avoid rerasterizing unchanged text every frame.

## 5.10 Media source

- Local video
- Local audio
- Network media URL
- Loop
- Restart when scene becomes active
- Seek
- Pause
- Speed where supported
- Hardware decode
- Audio track selection
- Subtitle support where practical
- Frame synchronization

Use Android Media3/ExoPlayer or direct MediaCodec pipelines for compatible media; use surface-based decode for video.

## 5.11 VLC source equivalent

Implement a broad media source using Android Media3 plus native hardware-decoding fallbacks. A “VLC-compatible source” label should not imply the desktop VLC library itself is being embedded.

## 5.12 Scene source

- Scene-as-source
- Recursive/nested scene detection
- Transform
- Filters
- Audio routing
- Cycle detection

## 5.13 Group source

Groups behave as transformable collections and must preserve OBS group semantics during import.

---

# 6. VIDEO COMPOSITION

## 6.1 Canvas/output model

- Base/canvas resolution
- Output resolution
- Aspect ratio
- FPS
- Common presets
- Custom FPS
- Orientation
- Preview scaling
- Downscale filter
- Integer/fit/stretch rules
- HDR/SDR mode
- Color space
- Color range
- Bit depth
- Alpha handling

OBS separates Base/Canvas Resolution from Output/Scaled Resolution. citeturn298013search2

## 6.2 Source transform

- Position X/Y
- Scale X/Y
- Rotation
- Crop left/right/top/bottom
- Bounding box
- Alignment
- Anchor point
- Flip horizontal
- Flip vertical
- Opacity
- Blend mode
- Pixel aspect handling
- Reset
- Fit
- Stretch
- Center
- Snap
- Manual numeric entry

## 6.3 GPU compositor architecture

Preferred implementation:

`live source → GPU/hardware-backed texture → filter chain → scene graph → program texture → encoder input Surface`

Use Vulkan where it provides measurable benefit or required extensions, otherwise GLES 3.2 is acceptable and already supported by Snapdragon 8 Elite. Qualcomm documents OpenGL ES 3.2 and Vulkan 1.3 support on Snapdragon 8 Elite. citeturn201153search34

The renderer must:

- batch compatible draw work
- cache shaders
- reuse GPU buffers
- avoid per-frame allocations
- avoid GPU/CPU synchronization except explicit fences
- avoid readbacks
- keep preview rendering independent from output encoding
- support multiple output canvases where needed

---

# 7. FILTERS

OBS has both effect filters and audio/video filters. Current OBS documentation lists LUT, Chroma Key, Color Correction, Color Key, Crop/Pad, Image Mask/Blend, Luma Key, Render Delay, Scaling/Aspect Ratio, Scroll, Sharpen, Compressor, Expander, Gain, Invert Polarity, Limiter, Noise Gate, Noise Suppression and VST 2.x among its built-in filter families. citeturn650092search2

## 7.1 Video effect filters

- LUT
- Chroma Key
- Color Correction
- Color Key
- Crop/Pad
- Image Mask/Blend
- Luma Key
- Render Delay
- Scaling/Aspect Ratio
- Scroll
- Sharpen
- Blur equivalent where part of current/installed OBS feature set
- Blend modes
- HDR/SDR conversion/composition
- Alpha manipulation

### Android implementation

GPU shader/filter graph. Filter parameters must update live without recreating the source pipeline.

## 7.2 Audio filters

- Gain
- Compressor
- Expander
- Limiter
- Noise Gate
- Noise Suppression
- 3-band/parametric equalization where present
- Invert polarity
- VST-equivalent extension model
- Ducking/sidechain
- Delay
- Channel routing

Audio filters run on float PCM in native C++ using NEON/SIMD where appropriate. Do not block AAudio callback threads with disk/network/UI work.

---

# 8. AUDIO ENGINE / MIXER

OBS's mixer exposes source status, names, fader level, fader, meters, mute and monitor controls. OBS internally processes audio using floating-point calculations and exposes peak/VU-style meter behavior. citeturn298013search1turn298013search0

## 8.1 Live audio sources

- USB microphones
- USB audio interfaces
- USB mixer inputs
- Capture-card HDMI/USB audio
- Built-in microphone
- Camera microphone where exposed
- Android system/playback audio
- Media source audio
- Browser source audio
- Per-source generated audio

## 8.2 Mixer behavior

Each source gets:

- Input level
- Fader dB
- Mute
- Solo/prelisten equivalent
- Monitor off
- Monitor only
- Monitor + output
- Balance/pan
- Sync offset
- Channel layout
- Downmix to mono
- Track routing
- Filters
- Peak hold
- VU/loudness indicator
- Device status

## 8.3 Audio architecture

AAudio is the native low-latency engine for high-performance streams. Android documents AAudio as a low-latency C API with data callbacks and an explicit LOW_LATENCY performance mode. Device routing uses Android audio-device APIs/device IDs. citeturn370990search1turn370990search4turn370990search5

Architecture:

`USB/built-in input → device-clocked timestamped PCM → native ring → resampler/channel mapper → source filter chain → mixer bus → master/monitor buses → AAC/recording tracks`

Use the native Android USB Audio HAL/AAudio path for UAC devices instead of implementing a second user-space USB audio driver. The USB audio scheduler remains below the application while Stream4k receives a real-time audio stream.

## 8.4 Monitoring

- Dedicated monitor device
- Monitor volume
- Per-source monitoring
- Separate stream/program mix
- Optional monitor delay compensation
- Prevent monitor feedback loops
- Headphone/device switching
- Device disconnect fallback

## 8.5 Android playback capture

For Android system/game/app audio, use MediaProjection + AudioPlaybackCapture where allowed. Android requires user approval and restricts capture to apps/usages permitted by the source application. citeturn187018search0turn187018search4

The UI must therefore show:

`System/Game Audio → available via Android Playback Capture`

rather than claiming arbitrary private/call audio can be captured.

---

# 9. AUDIO RECORDING TRACKS

Support separate mix assignments rather than one hard-coded stereo master.

Target:

- Tracks 1–6
- Per-source track matrix
- Stream mix
- Recording mix
- Optional isolation tracks
- Future service-specific tracks

For YouTube, use the program mix required by the selected ingest configuration. For local recording, retain independent tracks in compatible containers.

---

# 10. TRANSITIONS

## Required

- Cut
- Fade
- Fade to Color
- Swipe
- Slide
- Stinger
- Luma wipe/key transition
- Track-matte stinger
- Duration control
- Scene-specific transition
- Default transition
- Transition preview
- Transition hotkeys
- Transition audio continuity

Track Matte stingers in OBS use an animated mask to control the scene change and require synchronized video behavior. citeturn650092search0

### Android implementation

GPU transition shaders and a synchronized transition timeline. Stinger media is decoded to hardware surfaces; transition timing is based on presentation timestamps, not UI timers.

---

# 11. STUDIO MODE

- Preview scene
- Program scene
- Transition button
- Fade duration
- Preview/program labels
- Source editing in Preview without affecting Program
- Live Program stability while Preview is edited
- Hotkeys
- Transition types

OBS explicitly separates Preview and Program. citeturn856218view7

### Android implementation

Two scene graph instances sharing source objects where safe, with independent scene-item state. Program graph must have an immutable snapshot per frame while Preview can be edited.

---

# 12. MULTIVIEW

Support OBS-like multiview:

- Preview
- Program
- Scene thumbnails
- Scene names
- Click-to-switch
- Safe-area overlay
- Horizontal/vertical layout
- 4/9/16/25 scene layouts
- 8/18/24 scene layouts where screen space permits
- Fullscreen projector
- Windowed projector

OBS currently includes these multiview layout families and safe-area/name options. citeturn856218view0turn530733search0

### Android implementation

A dedicated GPU multiview renderer that samples the existing scene/program textures instead of rendering every scene again at full resolution.

---

# 13. PROJECTORS / OUTPUTS

OBS can project sources, scenes, Program, Preview or Multiview fullscreen/windowed, and can use projector outputs as auxiliary feeds. citeturn530733search0

Stream4k:

- External monitor program projector
- External monitor preview projector
- Multiview projector
- Individual scene projector
- Individual source projector
- Aux-output routing
- Internal preview projector
- Fullscreen/windowed
- Cursor visibility option
- Persistent projector assignments

Android implementation: DisplayManager + presentation/SurfaceControl-compatible routing where public APIs allow. The renderer must not require a second decode or capture pass.

---

# 14. STREAMING OUTPUT

## 14.1 Generic output model

OBS separates services, encoders and outputs. The output receives the encoded audio/video according to its capability. citeturn449825search6turn423176search3

Stream4k should preserve the same separation:

`Service → Output protocol → Video encoder + Audio encoder → Program tracks`

## 14.2 YouTube integration

No stream-key-first requirement.

Support:

- Google account connection
- Account selection
- Channel selection if account exposes multiple channels
- Upcoming broadcasts
- Active broadcasts
- Completed broadcasts where useful for history
- Create broadcast
- Edit title/description/category/privacy where allowed
- Select existing broadcast
- Create/select reusable live stream
- Bind broadcast to stream
- Query stream ingestion details
- Check stream health
- Testing transition
- Live transition
- End transition
- Live chat link
- Cuepoint/ad controls where authorized

YouTube's Live Streaming API supports list/insert/update/delete/bind/transition for broadcasts and list/insert/update/delete for live streams; live stream resources expose ingestion configuration and health information. citeturn393132search0turn393132search8turn393132search6

### Android authentication

Use Google Identity authorization for the YouTube API scopes needed by the action being performed, following Android's separation of authentication and authorization. citeturn393132search9

## 14.3 Protocol selection

YouTube's current guidance supports RTMP/RTMPS with H.264, HEVC and AV1 and separately documents HLS ingest with TS segments and HEVC support. YouTube recommends RTMPS for secure streaming; HLS has higher latency because it is segment based. citeturn423176search0turn423176search4

Stream4k default decision logic:

1. **RTMPS + HEVC** when YouTube and runtime encoder capability support it.
2. **RTMPS + H.264** as broad compatibility fallback.
3. **HLS + HEVC** for workflows where HLS is selected/required, especially 4K/HDR paths supported by YouTube.
4. **AV1** only when the selected ingest path and device capability both support it.

Never claim HLS is inherently lower bitrate. HLS is a delivery protocol; codec efficiency and YouTube ingest limits determine bitrate requirements.

## 14.4 4K60 target

For YouTube 4K60:

- 3840×2160
- 60 fps
- CBR
- 2-second keyframe target
- Hardware HEVC/H.264 selection
- 10-bit only when the full source/compositor/encoder/YouTube path is known-good
- Rec.709 SDR by default
- HDR = HEVC/10-bit path with correct transfer/gamut metadata

YouTube currently lists 4K60 H.265/AV1 at 10–40 Mbps and H.264 at a 35 Mbps recommendation; it recommends CBR and 2-second keyframes. citeturn423176search0

## 14.5 Astra 4K120 custom RTMP(S)

The app may request custom output dimensions through 3840×2160 and compositor/encoder FPS through 120. Send rates above 60 FPS only to a custom RTMP(S) endpoint whose ingest configuration explicitly accepts the selected resolution, codec, frame rate and bitrate. YouTube Live's published ingest profile is up to 60 FPS, so the YouTube broadcast picker must reject 120 FPS. Astra's advertised codec ceiling does not prove that its installed MediaCodec exposes a usable 4K120 mode, nor that the GPU compositor can sustain it while mixing live sources. Validate exact capability queries, real-time frame delivery, network stability, audio sync and thermal behavior on the tablet. A larger bitrate is user-configurable, but its valid value is bounded by the encoder, endpoint and upload link.

---

# 15. HARDWARE VIDEO ENCODING

MediaCodec must be the default Android encoder layer.

Android's MediaCodec supports surface-input encoders, asynchronous callbacks, bitrate changes and sync-frame requests, all directly useful to a live compositor. citeturn370990search0

## Required capabilities

- H.264
- HEVC/H.265
- AV1 when hardware exposes an encoder
- 8-bit
- 10-bit where supported
- CBR
- VBR where useful for local recording
- Constant-quality modes where exposed
- B-frame control where exposed
- GOP/keyframe interval
- Dynamic bitrate
- Dynamic sync-frame request
- Resolution/fps negotiation
- Color metadata
- HDR metadata
- Codec configuration extraction
- Codec capability database per actual encoder
- Encoder health monitoring

## Astra-specific rule

REDMAGIC officially advertises the Astra's hardware video encoder ceiling up to 3840×2160 @ 120 fps and 7680×4320 @ 30 fps, plus a USB 3.2 Gen 2 interface. These are vendor ceilings, not proof of a particular app-accessible profile or sustained real-time performance. The app interrogates MediaCodec for the requested exact size/FPS, and the 4K120 stream path is available only for custom RTMP(S) endpoints; YouTube remains limited to 60 FPS. citeturn768434search1

## Pipeline

`GPU compositor → MediaCodec input Surface → hardware encoder → encoded packet ring → output protocol / recorder / replay`

No GPU frame readback to the CPU.

---

# 16. RECORDING

## Required

- Start/stop
- Pause/resume
- Auto-record when streaming
- Keep recording when stream stops
- Recording directory
- Filename templates
- Overwrite protection
- File splitting
- Container selection
- Video encoder selection
- Audio encoder selection
- Multiple audio tracks
- Recording quality presets
- Advanced rate-control controls
- Remux
- Recording status
- Disk-space monitoring
- Safe-stop / finalization
- Crash resilience

OBS recommends MKV for resilience and supports fragmented/hybrid MP4/MOV for compatibility. citeturn449825search4turn449825search1

### Android implementation

Use Android `MediaMuxer` where its supported container/codec matrix exactly covers the requested configuration. Use our own carefully tested lightweight muxers for formats/features not exposed by MediaMuxer.

Preferred default:

- Local master recording: fragmented MP4 where codec/container/device support is reliable.
- Crash-safe master: MKV.
- Stream-copy remux: no re-encode.

---

# 17. REPLAY BUFFER

Required behavior:

- Continuously encode/store recent program output
- Duration-based limit
- Memory/file-size limit
- Save replay
- Hotkey
- Optional auto-start when streaming
- Keep replay running after stream stop when configured
- Progress/status
- Crash-safe handling
- Audio/video sync

Replay frames should be encoded program output, not re-rendered after the event.

For memory efficiency, use a bounded circular encoded-packet/file-segment store and align saved replay start to a clean keyframe.

---

# 18. STREAM HEALTH / STATS

Provide a single diagnostic page modeled on OBS Stats but adapted to hardware/media concepts:

- Output FPS
- Actual encoder FPS
- Render time
- Render queue depth
- Encoder queue depth
- Encoder latency
- Network send rate
- Network RTT
- Bytes sent
- Reconnect count
- Dropped network frames
- Capture drops
- USB transfer errors
- UVC packet errors
- Audio underruns/overruns
- Audio/video drift
- Codec errors
- Decoder queue depth
- GPU utilization if exposed
- CPU utilization
- Memory
- thermal state
- battery/power state
- temperature
- per-source latency
- per-source frame drops

Never conflate network drops with encoder or render lag.

---

# 19. NETWORK / OUTPUT RESILIENCE

Required:

- Automatic reconnect
- Backoff
- Connection timeout
- Keepalive
- Stream delay
- Dynamic bitrate where the output protocol supports it
- Network interface selection where Android exposes it
- IPv4/IPv6 policy
- TLS failure diagnostics
- DNS diagnostics
- Network health test
- Encoder/network mismatch diagnostics

OBS documents automatic reconnect and dynamic bitrate as separate tools for output resilience. citeturn449825search0turn720348search1

For YouTube, dynamic bitrate must never silently exceed the platform/device operating envelope.

---

# 20. COLOR / HDR

## Required

- SDR Rec.709
- HDR 10-bit
- Rec.2020
- HLG/PQ handling where Android/encoder/source exposes it
- Full vs limited range
- Source-specific color metadata
- HDR→SDR tone mapping
- SDR→HDR composition mode where meaningful
- Color conversion in GPU
- No unnecessary CPU conversion

YouTube currently recommends Rec.709 for SDR and HEVC 10-bit for HDR; AV1 is not currently supported for HDR ingest. citeturn423176search0

---

# 21. HOTKEYS / KEYBOARD-FIRST OPERATION

Required:

- Start/stop stream
- Start/stop recording
- Save replay
- Pause/resume recording
- Scene switching
- Source visibility
- Mute/unmute
- Push-to-talk
- Push-to-mute
- Audio monitoring control
- Preview/program transition
- Source navigation
- Delete/duplicate
- Search/command palette
- Show/hide docks
- Fullscreen projector
- Multiview
- Screenshot

Android supports attached hardware keyboards and keyboard navigation; this should be a first-class mode rather than a touch-only adaptation. citeturn393132search5

---

# 22. ACCESSIBILITY / UI ADAPTATION

- Font scaling
- Density
- High contrast theme
- Keyboard navigation
- Focus indicators
- Context menus
- Tooltips
- Explanatory text
- Beginner/Advanced modes
- Screen-reader semantics
- Color-blind-safe meter interpretation via labels/shapes
- Touch targets
- Mouse hover states
- Shortcut discovery

OBS's current UI also exposes density and font-size concepts. citeturn856218view1

---

# 23. SCRIPTING / EXTENSIONS / REMOTE CONTROL

OBS supports plugins, scripts and WebSocket control. Plugins can implement sources, outputs, encoders and services; scripts can be Python/Lua; WebSocket exposes remote control. citeturn720348search3turn650092search7turn650092search9

## Stream4k extension architecture

### Android plugin API

A safe plugin system should support:

- Sources
- Filters
- Transitions
- Encoders metadata adapters
- Outputs
- Services
- Commands
- Settings pages
- Browser-dock pages
- Automation hooks

Plugin binaries must target Android ARM64 and a documented Stream4k ABI. Plugins cannot require the desktop OBS ABI.

### WebSocket/remote API

Implement an OBS-like WebSocket-compatible command model where practical plus Stream4k extensions:

- Scene control
- Source control
- Filters
- Audio
- Streaming
- Recording
- Replay
- Stats
- Search/commands
- YouTube broadcast state

This permits stream-control tablets/phones/PCs to control Astra remotely.

---

# 24. OBS PROFILE / SCENE COLLECTION IMPORT

This is a core requirement, not a later convenience.

## 24.1 Import forms

Accept:

- OBS Profile folder
- OBS Scene Collection JSON
- OBS profile files/packages produced by OBS's own profile export workflow
- Whole OBS portable/config directory
- ZIP containing profile + scene collection + assets
- ZIP/folder with multiple profiles and collections

OBS profiles store stream/output/video-related settings while Scene Collections store scenes; they are intentionally separate in desktop OBS. citeturn423176search10

## 24.2 Importer pipeline

`discover → parse → validate → resolve IDs → resolve source types → copy assets → map devices → map filters → map transitions → import settings → report compatibility → activate`

## 24.3 Device remapping

OBS device identifiers will often not match Android device identifiers. Never preserve a desktop device path verbatim as though it were usable.

Instead show:

`OBS device: Elgato ... → Android candidates: [device list] → Match/Replace`

Match by:

- USB VID/PID
- serial number
- manufacturer
- product name
- channel count
- supported format
- remembered user mapping

## 24.4 Plugin source import

For a known desktop OBS plugin:

- recognize source ID
- parse settings
- translate into Stream4k equivalent if one exists
- import assets
- preserve unsupported parameters with warnings

For an unknown plugin:

- do not silently delete it
- preserve the source definition
- mark it “Needs Android implementation”
- preserve its scene position, filters, transform and assets
- let the user map it to another source type

The actual desktop DLL/.so cannot be executed inside the Android process.

## 24.5 Import report

Every import ends with:

- Imported successfully
- Imported with device remapping
- Imported with visual approximation
- Requires Android source implementation
- Missing asset
- Missing font
- Unsupported codec
- Unsupported service setting

No silent data loss.

---

# 25. MEDIA ASSET MANAGEMENT

- Asset library
- Relink missing assets
- Copy assets into project
- External asset references
- Hash/deduplication
- Project-relative paths
- Font detection
- Browser local files
- Image/video/audio preview
- Delete safety checks
- Storage usage

The project package should be movable between Astra devices without breaking relative media paths.

---

# 26. SCREENSHOT / SNAPSHOT

- Screenshot program
- Screenshot preview
- Screenshot source
- Screenshot multiview
- Save PNG/JPEG/WebP where appropriate
- Exact canvas resolution
- No CPU round trip when avoidable
- Atomic file write

Use GPU/PixelCopy/hardware-backed paths rather than copying the whole 4K frame through the UI layer.

---

# 27. VIRTUAL CAMERA / VIRTUAL OUTPUT

Desktop OBS offers a virtual camera. Android cannot provide the same arbitrary system-wide V4L2/DirectShow virtual-camera primitive to ordinary apps.

Target Android equivalents:

- Share Program as a Camera2-compatible provider where Android APIs permit it
- WebRTC/Android-compatible virtual camera service for supported consumers
- Export Program surface to another app through a controlled Android service/API
- Local network virtual camera protocol as an optional feature

The UI must clearly distinguish:

`Native Android Virtual Camera`
vs.
`Network/compatible virtual camera`

rather than claiming direct desktop-style camera-driver compatibility.

---

# 28. WEBRTC / REAL-TIME OUTPUT

OBS's current source tree includes an OBS WebRTC plugin/module among its core modules. citeturn449825search2

Stream4k should support a real-time WebRTC output for:

- Local preview on another device
- Remote control preview
- Low-latency monitoring
- Future browser-based remote studio

Use Android-native WebRTC-compatible APIs or a permissively licensed implementation; do not make WebRTC the required path for YouTube ingestion.

---

# 29. HARDWARE / RESOURCE MANAGEMENT

## 29.1 Performance controller

Automatically watch:

- encoder utilization
- GPU load
- CPU load
- memory pressure
- thermal throttling
- USB bandwidth
- decoder load
- audio buffer health
- network congestion

## 29.2 Quality adaptation

Only change quality when the user permits automatic adaptation or when the selected service requires it.

Example decision tree:

`4K60 HEVC → 4K60 H264 → 1440p60 HEVC → 1080p60 H264`

The decision must be capability/network/platform-driven, not arbitrary.

## 29.3 Power mode

Provide:

- Maximum-quality live
- Balanced
- Battery-aware
- External-power optimized

When operating on external USB-C power, permit higher sustained performance limits where the OS/device exposes them.

---

# 30. USB DEVICE MANAGER

The USB page must be a media-console view, not a raw Android USB list.

Per device:

- Friendly name
- VID/PID
- Serial
- USB speed
- Device class
- UVC/UAC capability
- Endpoint types
- Negotiated format
- Resolution/FPS
- Estimated bandwidth
- Current bandwidth
- USB errors
- Frame drops
- Audio clock state
- Power state
- Permission
- Active source
- Reconnect
- Release
- Device mapping

For a dock:

`USB-C Host → Hub → [Camera 1, Camera 2, Capture Card, Audio Interface, Keyboard, Mouse]`

Show aggregate bandwidth and warnings before enabling combinations that cannot fit the physical USB link.

---

# 31. THERMAL / DEVICE HEALTH

Because Astra is a tablet rather than a desktop tower, long-run thermal behavior is part of production reliability.

Monitor:

- skin/device temperature where accessible
- thermal throttling state
- battery temperature
- charging state
- external power
- CPU frequency/state where exposed
- GPU utilization/state where exposed
- encoder failures

When thermal throttling begins, display it as a real diagnostic event and optionally trigger controlled output adaptation.

---

# 32. ANDROID SERVICES / LIFETIME

The engine cannot be owned by the Compose Activity alone.

Use Android foreground services for long-running media operation:

- Streaming service
- Recording service
- Screen capture service
- Audio capture service where needed

Services own the native engine lifetime; Activities/Compose screens are controllers/observers.

---

# 33. REAL-TIME THREADING MODEL

Required thread classes:

### USB threads

- transfer completion
- packet assembly
- immediate resubmission

### Decoder threads

- MediaCodec callbacks
- frame availability

### GPU thread

- EGL/Vulkan context
- scene composition
- filter passes
- encoder submission

### Audio threads

- AAudio callback
- device-specific input
- mixer/resampler worker

### Encoder threads

- MediaCodec callback/drain
- codec configuration

### Output threads

- RTMPS network sender
- HLS segmenter/uploader
- recording writer
- replay store

### UI threads

- Compose only
- no blocking media operations

No real-time thread may:

- allocate large objects
- perform disk I/O
- perform network I/O
- wait for UI
- wait on arbitrary mutexes
- call Compose
- issue synchronous Java USB transfers

---

# 34. TIMESTAMPS / A/V SYNCHRONIZATION

Every source has a clock domain:

`source timestamp → normalized monotonic timeline → scene/output PTS`

Video:

- UVC host/device timestamp
- Camera2 timestamp
- MediaProjection timestamp
- Media3/MediaCodec presentation timestamp

Audio:

- AAudio/AudioRecord timestamp
- Android playback-capture timestamp
- media decoder timestamp

The mixer/compositor performs drift monitoring and controlled correction. Never solve sync problems by periodically dropping random frames or inserting arbitrary silence without recording the correction in diagnostics.

---

# 35. FAILURE BEHAVIOR

Every subsystem must fail visibly and recoverably.

Examples:

- USB camera unplugged → source shows disconnected; other sources continue.
- Camera returns → attempt reconnection with remembered format.
- Encoder failure → transition to encoder error; do not falsely report live.
- YouTube disconnect → reconnect according to policy; preserve program composition.
- Disk full → stop recording cleanly, keep stream running if possible.
- One audio interface disappears → remove its stream and keep other mixer channels alive.
- Browser source fails → display source error state while scene continues.
- Missing plugin → scene remains intact with replacement placeholder.

---

# 36. TESTING REQUIREMENTS

No “works because the UI says it works” testing.

## Automated

- Scene serialization round-trip
- Profile round-trip
- Asset relocation
- UVC descriptor parsing
- UVC frame reassembly
- ISO/BULK transfer stress
- Hot unplug/reconnect
- Audio drift
- Resampler accuracy
- Filter parameter updates
- Transition timing
- MediaCodec capability probing
- H.264 packetization
- HEVC packetization
- AV1 packetization where supported
- FLV/TS/MP4/MKV structural validation
- HLS playlist rules
- RTMPS reconnect
- YouTube API state transitions
- Replay buffer keyframe integrity

## Hardware

Required matrix on Astra:

- 1080p30
- 1080p60
- 1440p60
- 4K30
- 4K60
- 4K HDR10 where source/encoder allow
- 1 USB camera
- 2 USB video inputs
- 3+ USB video inputs where aggregate bandwidth is sufficient
- USB audio interface + multiple inputs
- system/game audio capture
- simultaneous preview + stream + record
- long-run thermal test
- monitor projection
- keyboard/mouse workflow

---

# 37. IMPLEMENTATION PRIORITY

The implementation order is now driven by dependency, not by UI appearance.

## P0 — media foundation

1. USB ISO/BULK engine
2. UVC control/negotiation/parsing
3. UAC real-time input
4. Camera2/MediaProjection sources
5. Surface-backed decoder paths
6. GPU compositor
7. MediaCodec H.264/HEVC/AV1 capability engine
8. Native A/V timeline
9. Hardware AAC
10. RTMPS
11. YouTube API lifecycle
12. Real recording/muxing
13. Replay buffer

## P1 — OBS composition parity

1. Scene graph
2. Groups/nested scenes
3. transforms/crop/snapping
4. filters
5. audio mixer
6. transitions
7. Studio Mode
8. multiview
9. projectors
10. browser source
11. image/text/media sources

## P2 — project portability

1. OBS profile importer
2. Scene Collection importer
3. full bundled asset importer
4. device remapping
5. plugin configuration translation
6. missing-source preservation
7. import compatibility report and Android device remapping

## P3 — control and automation

1. hotkeys
2. command palette/search
3. WebSocket API
4. scripting/extension API
5. remote control

## P4 — polish/production operations

1. diagnostics
2. thermal monitoring
3. performance adaptation
4. accessibility
5. onboarding
6. templates
7. backup/recovery
8. project validation

---

# 38. FEATURE COMPLETENESS GATE

The app is **not considered OBS-equivalent / production-ready** until all of the following are true:

- No simulated live source paths remain.
- No fake transport success remains.
- No fake recording success remains.
- USB ISO and BULK paths are both operational.
- USB audio is continuously clocked.
- Multiple simultaneous media inputs work without UI polling.
- GPU compositor feeds the hardware encoder directly.
- Hardware encoder selection is runtime capability-driven.
- YouTube account/broadcast/stream selection works through the API.
- RTMPS output works end-to-end.
- HLS/HEVC output works end-to-end when selected.
- Local recording works and survives abnormal termination according to the chosen container's guarantees.
- Replay buffer works from encoded program output.
- OBS profile + scene collection + assets import without silent loss.
- Known source/filter/transition types map to functional Android equivalents.
- Unknown plugin definitions are retained and reported.
- Audio mixer supports multiple simultaneous sources with live control.
- Studio Mode and transitions operate independently of Preview edits.
- Multiview/projectors operate from existing rendered surfaces without duplicate capture/encode paths.
- Browser source works as a hardware-accelerated live source with audio.
- Command search can reach every advanced feature without forcing users to browse raw settings.
- External keyboard/mouse can operate the entire studio.
- Long-run 4K60 stability is validated on Astra hardware.
- Custom RTMP(S) 4K120 capability, sustained encode/stream, audio synchronization, and thermal behavior are validated on Astra when the installed encoder reports support.
- Thermal and bandwidth failure modes are visible and recoverable.

---

# 39. IMPORTANT PLATFORM REALITY

Some desktop OBS features are explicitly tied to desktop operating-system mechanisms. Examples include DirectX/OpenGL game hooks, DirectShow, WASAPI, unrestricted background-window enumeration/capture and desktop virtual-camera drivers. These are not portable APIs. Android MediaProjection is a distinct, user-consented substitute: supported Android versions let the user share the full display or one app window, and the user can revoke capture. AudioPlaybackCapture is also policy-gated by the app producing audio. Stream4k must not claim silent arbitrary-window or protected-app capture.

The parity target is therefore:

**same user-visible capability → Android-native implementation → same source/scene/mixer/output semantics**

not:

**same internal desktop API → impossible on Android.**

This distinction lets Stream4k remain a native Android production application while still importing and reproducing OBS projects.

---

# 40. RESEARCH SOURCES

- OBS GitHub repository and current source tree
- OBS Studio 32.2.2 release / current 33.0 beta source
- OBS Sources Guide
- OBS Filters Guide
- OBS Audio Mixer Guide / Technical Details
- OBS Overview / Profiles / Recording / Projectors documentation
- OBS Developer / Plugin / Scripting documentation
- YouTube Live Streaming API documentation
- YouTube encoder settings and HLS ingestion requirements
- Android MediaCodec documentation
- Android AAudio/Audio NDK documentation
- Android MediaProjection and AudioPlaybackCapture documentation
- Android USB Host documentation
- libusb Android documentation for native USB host support
- REDMAGIC Astra official specifications
- Qualcomm Snapdragon 8 Elite product documentation

The implementation must re-check these sources periodically because OBS, Android, YouTube and device firmware evolve.
