package com.stream4k60.app.ui.main

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import com.stream4k60.app.engine.*
import com.stream4k60.app.ui.main.components.*
import com.stream4k60.app.ui.dialogs.ColorPickerDialog
import com.stream4k60.app.ui.filters.FilterEditorScreen
import com.stream4k60.app.ui.search.*
import com.stream4k60.app.ui.sources.TransformDialog
import com.stream4k60.app.ui.sources.SourcePropertiesDialog
import com.stream4k60.app.ui.youtube.YouTubeBroadcastPicker
import org.json.JSONObject

@Composable
fun MainStudioScreen(
    onOpenSettings:(String)->Unit,
    onOpenProfiles:()->Unit,
    vm:MainStudioViewModel=viewModel()
){
    val streaming by vm.streamState.collectAsState();val streamError by vm.streamError.collectAsState();val recording by vm.recordState.collectAsState();val studio by vm.isStudioModeEnabled.collectAsState();val selectedTransition by vm.selectedTransition.collectAsState();val scenes by vm.scenes.collectAsState();val sceneCollections by vm.sceneCollections.collectAsState();val activeCollectionId by vm.activeSceneCollectionId.collectAsState();val active by vm.activeScene.collectAsState();val sources by vm.sources.collectAsState();val sourceErrors by SourceRuntimeErrors.errors.collectAsState();val videoConfig by vm.videoConfig.collectAsState();val importedRtmpEndpoint by vm.importedRtmpEndpoint.collectAsState();var selectedSourceId by remember{mutableStateOf<String?>(null)};val canvasFocusRequester=remember{FocusRequester()};var search by remember{mutableStateOf(false)};var yt by remember{mutableStateOf(false)};var customRtmp by remember{mutableStateOf(false)};var addSource by remember{mutableStateOf(false)};var editingSource by remember{mutableStateOf<SourceItem?>(null)};var filteringSource by remember{mutableStateOf<SourceItem?>(null)}
    LaunchedEffect(active?.id) { selectedSourceId = null }
    val ctx=androidx.compose.ui.platform.LocalContext.current
    val thermalStatus by AstraDeviceMonitor.thermalStatus.collectAsState()
    var projectionRequested by remember{mutableStateOf(false)}
    var projectionGrantedInSession by remember{mutableStateOf(false)}
    var projectionDeniedForSources by remember{mutableStateOf(false)}
    var showProjectionGuideDialog by remember{mutableStateOf(false)}
    var showProjectionRetryDialog by remember{mutableStateOf(false)}
    val screenIds=sources.filter{it.type.equals("SCREEN_CAPTURE",true)&&it.isVisible}.map{it.id}
    val playbackIds=sources.filter{it.type.equals("PLAYBACK_AUDIO",true)&&it.isVisible}.map{it.id}
    val playback=playbackIds.isNotEmpty()
    val projectionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        projectionRequested=false
        if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
            projectionGrantedInSession=true
            projectionDeniedForSources=false
            showProjectionRetryDialog=false
            (screenIds + playbackIds).forEach(SourceRuntimeErrors::clear)
            com.stream4k60.app.service.ProjectionCaptureService.start(ctx,result.resultCode,result.data!!,screenIds,playback,playbackIds.firstOrNull())
        } else {
            projectionGrantedInSession=false
            projectionDeniedForSources=true
            showProjectionRetryDialog=true
            screenIds.forEach { SourceRuntimeErrors.report(it,"Grant Android screen-capture permission to use this source.") }
            playbackIds.forEach { SourceRuntimeErrors.report(it,"Grant Android playback-capture permission to use this source.") }
        }
    }
    fun launchProjectionConsent() {
        val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            pm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
    fun requestProjectionPermission() {
        if (!projectionRequested && (screenIds.isNotEmpty() || playback)) {
            projectionRequested = true
            projectionDeniedForSources = false
            showProjectionGuideDialog = true
        }
    }
    var transformingSource by remember { mutableStateOf<SourceItem?>(null) }
    val scope=rememberCoroutineScope();val camera=remember{CameraSourceController(ctx)};val bitmap=remember{BitmapSourceController(ctx,scope)};val media=remember{MediaSourceController(ctx,scope)};val browser=remember{BrowserSourceController(ctx)};val usb=remember{com.stream4k60.app.engine.NativeUsbManager(ctx.applicationContext)};val usbDevices by usb.connectedDevices.collectAsState()
    // Transform-only edits should update the compositor without restarting capture sources.
    val captureSources = sources.map { it.copy(transformJson = "{}") }
    DisposableEffect(Unit){usb.initialize();browser.attachHost((ctx as Activity).findViewById(android.R.id.content) as ViewGroup);onDispose{camera.stopAll();bitmap.stop();media.stopAll();browser.stopAll();usb.shutdown();NativeEngine.setPreviewSurface(null)}}
    LaunchedEffect(captureSources,videoConfig,usbDevices){
        camera.sync(sources);bitmap.sync(sources);media.sync(sources);browser.sync(sources)
        if(screenIds.isEmpty() && !playback){
            com.stream4k60.app.service.ProjectionCaptureService.stop(ctx)
            projectionGrantedInSession=false
            projectionDeniedForSources=false
        } else if(com.stream4k60.app.service.ProjectionCaptureService.isActive()) {
            com.stream4k60.app.service.ProjectionCaptureService.sync(ctx,screenIds,playback,playbackIds.firstOrNull())
        } else if(!projectionGrantedInSession && !projectionDeniedForSources && !projectionRequested){
            requestProjectionPermission()
        }
        val wantedUsb=sources.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible}.mapNotNull{sourceSettings(it.configJson).optInt("deviceId",-1).takeIf{v->v>=0}}.toSet()
        usbDevices.filter{it.isCapturing&&it.deviceId !in wantedUsb}.forEach{usb.stopCapture(it.deviceId)}
        val visibleUsbSources=sources.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible}
        val duplicateUsbIds=visibleUsbSources.map{sourceSettings(it.configJson).optInt("deviceId",-1)}.filter{it>=0}.groupingBy{it}.eachCount().filterValues{it>1}.keys
        duplicateUsbIds.filter{id->usbDevices.any{it.deviceId==id&&it.isCapturing}}.forEach{usb.stopCapture(it)}
        visibleUsbSources.filter{sourceSettings(it.configJson).optInt("deviceId",-1) in duplicateUsbIds}.forEach{src->
            SourceRuntimeErrors.report(src.id,"This USB camera is assigned to more than one visible source. Each UVC device can run one capture session; select a different device for each source.")
        }
        visibleUsbSources.filter{sourceSettings(it.configJson).optInt("deviceId",-1) !in duplicateUsbIds}.forEach{src->
            val c=sourceSettings(src.configJson);val deviceId=c.optInt("deviceId",-1);val device=usbDevices.firstOrNull{it.deviceId==deviceId}
            if(deviceId<0 || device==null) SourceRuntimeErrors.report(src.id,"Connect and select an Android USB video device in source properties.")
            else {
                val w=c.optInt("width",3840);val h=c.optInt("height",2160);val fps=c.optInt("fps",60);val fmt=c.optString("format","MJPEG")
                if(usb.startCapture(deviceId,w,h,fps,fmt,src.id,src.configJson))SourceRuntimeErrors.clear(src.id)
                else SourceRuntimeErrors.report(src.id,"USB capture did not start. Check the selected format, USB permission, and available bandwidth.")
            }
        }
    }
    LaunchedEffect(sources,videoConfig){
        sources.forEachIndexed{index,src->
            NativeEngine.setSourceEffectsFromConfig(src.id,src.configJson)
            applySourceTransformToNative(src,index,videoConfig.baseResWidth,videoConfig.baseResHeight)
        }
    }
    DisposableEffect(Unit) {
        HotkeyDispatcher.attach(
            startStream = { vm.startStreaming() },
            stopStream = { vm.stopStreaming() },
            startRecord = { vm.startRecording() },
            stopRecord = { vm.stopRecording() },
            replay = { vm.startReplay() },
            studio = { vm.toggleStudioMode() },
            toggleMic = { vm.togglePrimaryMicMute() },
            ptt = { pressed -> vm.setPushToTalk(pressed) }
        )
        onDispose { HotkeyDispatcher.detach() }
    }

    val entries = FeatureCatalog.build(
        openYouTube = { yt = true },
        openCustomStream = { customRtmp = true },
        openProfiles = onOpenProfiles,
        openSettings = onOpenSettings,
        addSource = { vm.addSource(it) },
        startReplay = vm::startReplay,
        toggleStudio = vm::toggleStudioMode
    )

    Column(Modifier.fillMaxSize()){
        TopBar(
            scenes = scenes,
            sceneCollections = sceneCollections,
            activeCollectionId = activeCollectionId,
            activeSceneId = active?.id,
            sources = sources,
            isStreaming = streaming == StudioStreamState.LIVE,
            isRecording = recording == StudioRecordState.RECORDING,
            isStudioMode = studio,
            thermalStatus = thermalStatus,
            onSelectScene = vm::setActiveScene,
            onSelectCollection = vm::selectSceneCollection,
            onAddScene = { vm.addScene("Scene ${scenes.size + 1}") },
            onAddSource = { addSource = true },
            onToggleSourceVisibility = vm::toggleSourceVisibility,
            onToggleStudioMode = vm::toggleStudioMode,
            onReplayBuffer = vm::startReplay,
            onSearch = { search = true },
            onYouTube = { yt = true },
            onCustomStream = { customRtmp = true },
            onProfiles = onOpenProfiles,
            onSettings = { onOpenSettings("General") }
        )
        Row(Modifier.fillMaxWidth().weight(1f)) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val aspect = videoConfig.baseResWidth.toFloat() / videoConfig.baseResHeight.coerceAtLeast(1)
                val canvasWidth = minOf(maxWidth, maxHeight * aspect)
                val canvasHeight = canvasWidth / aspect
                Box(Modifier.size(canvasWidth, canvasHeight)) {
                    EditablePreview(
                        sources,
                        selectedSourceId,
                        videoConfig.baseResWidth,
                        videoConfig.baseResHeight,
                        { selectedSourceId = it },
                        { id, transform -> vm.updateSourceTransform(id, transform) },
                        vm::moveSourceInStack,
                        canvasFocusRequester,
                        Modifier.fillMaxSize()
                    )
                }
            }
            if (studio) {
                Spacer(Modifier.width(12.dp))
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val aspect = videoConfig.baseResWidth.toFloat() / videoConfig.baseResHeight.coerceAtLeast(1)
                    val canvasWidth = minOf(maxWidth, maxHeight * aspect)
                    val canvasHeight = canvasWidth / aspect
                    Box(Modifier.size(canvasWidth, canvasHeight)) {
                        NativePreviewSurface(Modifier.fillMaxSize())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(210.dp)){
            ScenePanel(scenes,active?.id,{vm.setActiveScene(it)},{vm.addScene("Scene ${scenes.size+1}")},{vm.removeScene()},Modifier.weight(1f).fillMaxHeight())
            SourcePanel(
                sources = sources,
                sourceErrors = sourceErrors,
                onToggleVisibility = vm::toggleSourceVisibility,
                onToggleLock = vm::toggleSourceLock,
                onAdd = { addSource = true },
                onRemove = { selectedSourceId?.let(vm::removeSource) },
                onProperties = { sources.firstOrNull { it.id == selectedSourceId }?.let { editingSource = it } },
                onFilters = { id -> sources.firstOrNull { it.id == id }?.let { filteringSource = it } },
                onTransform = { id -> sources.firstOrNull { it.id == id && !it.isLocked }?.let { transformingSource = it } },
                onOpenProperties = { id -> sources.firstOrNull { it.id == id }?.let { editingSource = it } },
                onRequestCapturePermission = { requestProjectionPermission() },
                onRenameSource = vm::renameSource,
                onDuplicateSource = vm::duplicateSource,
                onDeleteSource = vm::removeSource,
                onResetTransform = vm::resetSourceTransform,
                onPasteTransform = vm::updateSourceTransform,
                selectedSourceId = selectedSourceId,
                onSelectSource = { selectedSourceId = it; canvasFocusRequester.requestFocus() },
                onMoveSource = vm::moveSourceInStack,
                onMoveSourceToIndex = vm::moveSourceToDisplayIndex,
                modifier = Modifier.weight(1.2f).fillMaxHeight()
            )
            AudioMixerPanel(sources, { src, cfg -> vm.updateSourceConfig(src.id, cfg) }, vm::audioPeak, Modifier.weight(1.4f).fillMaxHeight());TransitionPanel(studio,selectedTransition,vm::selectTransition,{active?.id?.let{vm.setActiveScene(it)}},Modifier.weight(.9f).fillMaxHeight())
            ControlsPanel(streaming==StudioStreamState.LIVE,recording==StudioRecordState.RECORDING,studio,{vm.startStreaming()},{vm.stopStreaming()},{vm.startRecording()},{vm.stopRecording()},{vm.startReplay()},{vm.toggleStudioMode()},{onOpenSettings("General")},Modifier.weight(1f).fillMaxHeight())
        }
    }
    if(search)FeatureSearchSheet(entries){search=false}
    if(showProjectionGuideDialog) AlertDialog(
        onDismissRequest = {
            showProjectionGuideDialog = false
            projectionRequested = false
            projectionDeniedForSources = true
        },
        title = { Text("Choose what Stream4k can capture") },
        text = {
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "Android will show its screen-sharing prompt next. To capture one app, choose “A single app” or “Share an app”, select the app, then tap Start. Choose the entire display if you need the Astra screen. Android may not offer app sharing on older system updates."
                } else {
                    "Android will ask you to share the display. This Astra software version offers full-display capture only; the app cannot select another app window silently."
                }
            )
        },
        confirmButton = {
            Button(onClick = {
                showProjectionGuideDialog = false
                launchProjectionConsent()
            }) { Text("Continue to Android") }
        },
        dismissButton = {
            TextButton(onClick = {
                showProjectionGuideDialog = false
                projectionRequested = false
                projectionDeniedForSources = true
            }) { Text("Cancel") }
        }
    )
    if(showProjectionRetryDialog) AlertDialog(
        onDismissRequest = { showProjectionRetryDialog = false },
        title = { Text("Capture permission needed") },
        text = { Text("Android did not grant screen or playback capture. These sources stay visible in the scene list, but they cannot capture until permission is granted.") },
        confirmButton = { Button(onClick = { requestProjectionPermission() }) { Text("Try again") } },
        dismissButton = { TextButton(onClick = { showProjectionRetryDialog = false }) { Text("Later") } }
    )
    if(yt)YouTubeBroadcastPicker({vm.setStreamConfig(it);yt=false},{yt=false})
    if(customRtmp)CustomRtmpDialog(videoConfig,importedRtmpEndpoint,{vm.setStreamConfig(it);customRtmp=false},{customRtmp=false})
    streamError?.let { message ->
        AlertDialog(onDismissRequest = vm::dismissStreamError, title = { Text("Streaming could not start") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissStreamError) { Text("OK") } })
    }
    if(addSource)SourceTypePicker(onAdd={vm.addSource(it);addSource=false},onDismiss={addSource=false})
    filteringSource?.let { source ->
        FilterEditorScreen(
            source = source,
            onApply = { config -> vm.updateSourceConfig(source.id, config); filteringSource = null },
            onCancel = { filteringSource = null }
        )
    }
    editingSource?.let { source ->
        if (source.type.equals("COLOR", true)) {
            val initialColor = remember(source.id, source.configJson) {
                val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
                val settings = root.optJSONObject("settings") ?: root
                runCatching { android.graphics.Color.parseColor(settings.optString("color", "#FF000000")) }
                    .getOrDefault(android.graphics.Color.BLACK)
            }
            ColorPickerDialog(
                initialColor = initialColor,
                onDismiss = { editingSource = null },
                onColorSelected = { color ->
                    val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
                    val settings = root.optJSONObject("settings")
                    val colorValue = String.format(java.util.Locale.US, "#%08X", color)
                    if (settings != null) settings.put("color", colorValue) else root.put("color", colorValue)
                    vm.updateSourceConfig(source.id, root.toString())
                    editingSource = null
                }
            )
        } else {
            SourcePropertiesDialog(
                source = source,
                usbManager = usb,
                onSave = { vm.updateSourceConfig(it.id, it.configJson); editingSource = null },
                onRemapSource = { item, targetType -> vm.remapImportedSource(item.id, targetType); editingSource = null },
                onDismiss = { editingSource = null }
            )
        }
    }
    transformingSource?.let { source ->
        TransformDialog(
            source = source,
            canvasWidth = videoConfig.baseResWidth,
            canvasHeight = videoConfig.baseResHeight,
            onApply = { transform -> vm.updateSourceTransform(source.id, transform); transformingSource = null },
            onDismiss = { transformingSource = null }
        )
    }
}

private fun sourceSettings(configJson:String):JSONObject {
    val root=runCatching{JSONObject(configJson)}.getOrDefault(JSONObject())
    return root.optJSONObject("settings")?:root
}

@Composable private fun SourceTypePicker(onAdd:(String)->Unit,onDismiss:()->Unit){
    val types=listOf("USB_CAPTURE" to "USB camera / capture card","BROWSER" to "Browser","MEDIA" to "Media","IMAGE" to "Image","IMAGE_SLIDESHOW" to "Image slideshow","TEXT" to "Text","COLOR" to "Color","AUDIO_INPUT" to "Audio input","AUDIO_OUTPUT" to "Audio monitor output")
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add source")},text={Column{types.forEach{(id,name)->TextButton(onClick={onAdd(id)},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=4.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name);Text("+")}}}}},confirmButton={})
}

