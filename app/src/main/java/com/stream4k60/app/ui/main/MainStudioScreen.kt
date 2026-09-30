package com.stream4k60.app.ui.main

import androidx.compose.runtime.saveable.rememberSaveable

import com.stream4k60.app.data.model.HotkeyAction
import androidx.compose.runtime.rememberUpdatedState
import com.stream4k60.app.ui.dialogs.ConfirmStopDialog
import androidx.hilt.navigation.compose.hiltViewModel
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
    vm:MainStudioViewModel=hiltViewModel()
){
    val streaming by vm.streamState.collectAsState();val streamStats by vm.streamStats.collectAsState();val streamError by vm.streamError.collectAsState();val recording by vm.recordState.collectAsState();val studio by vm.isStudioModeEnabled.collectAsState();val selectedTransition by vm.selectedTransition.collectAsState();val scenes by vm.scenes.collectAsState();val sceneCollections by vm.sceneCollections.collectAsState();val activeCollectionId by vm.activeSceneCollectionId.collectAsState();val active by vm.activeScene.collectAsState();val sources by vm.sources.collectAsState();val sourceErrors by SourceRuntimeErrors.errors.collectAsState();val videoConfig by vm.videoConfig.collectAsState();val importedRtmpEndpoint by vm.importedRtmpEndpoint.collectAsState();var selectedSourceId by remember{mutableStateOf<String?>(null)};val canvasFocusRequester=remember{FocusRequester()};var search by remember{mutableStateOf(false)};var yt by remember{mutableStateOf(false)};var customRtmp by remember{mutableStateOf(false)};var addSource by remember{mutableStateOf(false)};var editingSource by remember{mutableStateOf<SourceItem?>(null)};var filteringSource by remember{mutableStateOf<SourceItem?>(null)};var broadcastTitle by rememberSaveable{mutableStateOf<String?>(null)}
    LaunchedEffect(active?.id) { selectedSourceId = null }
    val general by vm.generalSettings.collectAsState()
    var confirmStop by remember { mutableStateOf<String?>(null) }
    // Everything the program shows, including the contents of nested scenes and groups.
    val renderSources by vm.renderSources.collectAsState()
    val renderItems = remember(renderSources) { renderSources.map { it.item } }
    // Scene sources plus the global Desktop Audio / Mic sources from Settings → Audio.
    val audioItems by vm.audioItems.collectAsState()
    val groupChildren by vm.groupChildren.collectAsState()
    // Sources that can be opened for editing: the active scene's items and the contents of its groups.
    fun editable(id: String?): SourceItem? = id?.let { key -> sources.firstOrNull { it.id == key } ?: groupChildren.values.flatten().firstOrNull { it.id == key } }
    /** Canvas a source is positioned on: its group's canvas for group contents, otherwise the program canvas. */
    fun canvasOf(id: String): Pair<Int, Int> {
        val group = groupChildren.entries.firstOrNull { (_, items) -> items.any { it.id == id } }?.key?.let { gid -> sources.firstOrNull { it.id == gid } }
        return group?.let { sourceSettings(it.configJson).let { c -> c.optInt("width", videoConfig.baseResWidth) to c.optInt("height", videoConfig.baseResHeight) } }
            ?: (videoConfig.baseResWidth to videoConfig.baseResHeight)
    }
    var pickingNestedScene by remember { mutableStateOf(false) }
    val ctx=androidx.compose.ui.platform.LocalContext.current
    val thermalStatus by AstraDeviceMonitor.thermalStatus.collectAsState()
    var projectionRequested by remember{mutableStateOf(false)}
    var projectionGrantedInSession by remember{mutableStateOf(false)}
    var projectionDeniedForSources by remember{mutableStateOf(false)}
    var showProjectionGuideDialog by remember{mutableStateOf(false)}
    var showProjectionRetryDialog by remember{mutableStateOf(false)}
    val screenIds=renderItems.filter{it.type.equals("SCREEN_CAPTURE",true)&&it.isVisible}.map{it.id}
    val playbackIds=audioItems.filter{it.type.equals("PLAYBACK_AUDIO",true)&&it.isVisible}.map{it.id}
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
    val captureSources = renderItems.map { it.copy(transformJson = "{}") }
    DisposableEffect(Unit){usb.initialize();browser.attachHost((ctx as Activity).findViewById(android.R.id.content) as ViewGroup);onDispose{camera.stopAll();bitmap.stop();media.stopAll();browser.stopAll();usb.shutdown();NativeEngine.setPreviewSurface(null)}}
    LaunchedEffect(captureSources,videoConfig,usbDevices,playbackIds){
        camera.sync(renderItems);bitmap.sync(renderItems);media.sync(renderItems);browser.sync(renderItems)
        if(screenIds.isEmpty() && !playback){
            com.stream4k60.app.service.ProjectionCaptureService.stop(ctx)
            projectionGrantedInSession=false
            projectionDeniedForSources=false
        } else if(com.stream4k60.app.service.ProjectionCaptureService.isActive()) {
            com.stream4k60.app.service.ProjectionCaptureService.sync(ctx,screenIds,playback,playbackIds.firstOrNull())
        } else if(!projectionGrantedInSession && !projectionDeniedForSources && !projectionRequested){
            requestProjectionPermission()
        }
        val wantedUsb=renderItems.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible}.mapNotNull{sourceSettings(it.configJson).optInt("deviceId",-1).takeIf{v->v>=0}}.toSet()
        usbDevices.filter{it.isCapturing&&it.deviceId !in wantedUsb}.forEach{usb.stopCapture(it.deviceId)}
        val visibleUsbSources=renderItems.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible}
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
                else SourceRuntimeErrors.report(src.id,"USB capture did not start: ${usb.lastError(deviceId)?:"check the selected format, USB permission and bandwidth"}")
            }
        }
    }
    var shownContainerIds by remember { mutableStateOf(emptySet<String>()) }
    val nativeSizes by com.stream4k60.app.engine.SourceNativeSizes.sizes.collectAsState()
    LaunchedEffect(renderSources,videoConfig,nativeSizes){
        val base=videoConfig.baseResWidth to videoConfig.baseResHeight
        // Nested scenes use the program canvas size; groups have their own canvas.
        val canvasFor=renderSources.mapNotNull{rs->rs.containerKey?.let{key->key to if(rs.item.type.equals("GROUP",true)){
            val c=sourceSettings(rs.item.configJson);c.optInt("width",base.first).coerceAtLeast(16) to c.optInt("height",base.second).coerceAtLeast(16)
        }else base}}.toMap()
        renderSources.forEach{rs->
            NativeEngine.setSourceOwner(rs.item.id,rs.owner)
            rs.containerKey?.let{key->val (w,h)=canvasFor.getValue(key);NativeEngine.setSceneTarget(key,w,h);NativeEngine.setSourceSceneRef(rs.item.id,key)}
            val (cw,ch)=if(rs.owner.isEmpty())base else canvasFor[rs.owner]?:base
            applySourceTransformToNative(rs.item,rs.z,cw,ch)
        }
        NativeEngine.retainSceneTargets(canvasFor.keys.toTypedArray())
        val containerIds=renderSources.filter{it.containerKey!=null}.map{it.item.id}.toSet()
        (shownContainerIds-containerIds).forEach(NativeEngine::removeSourceLayer)
        shownContainerIds=containerIds
    }
    val currentScenes = rememberUpdatedState(scenes)
    DisposableEffect(Unit) {
        HotkeyDispatcher.attach { action, pressed ->
            when (action) {
                HotkeyAction.START_STREAM -> vm.startStreaming()
                HotkeyAction.STOP_STREAM -> vm.stopStreaming()
                HotkeyAction.TOGGLE_STREAM -> if (streaming == StudioStreamState.IDLE || streaming == StudioStreamState.ERROR) vm.startStreaming() else vm.stopStreaming()
                HotkeyAction.STUDIO_MODE -> vm.toggleStudioMode()
                HotkeyAction.MUTE_MIC -> vm.togglePrimaryMicMute()
                HotkeyAction.PUSH_TO_TALK -> vm.setPushToTalk(pressed)
                else -> {
                    // SCENE_1..SCENE_9 switch to the scene at that position in the Scenes dock.
                    val index = action.name.removePrefix("SCENE_").toIntOrNull()?.minus(1)
                    index?.let { currentScenes.value.getOrNull(it) }?.let { vm.setActiveScene(it.id) }
                }
            }
        }
        onDispose { HotkeyDispatcher.detach() }
    }

    val entries = FeatureCatalog.build(
        openYouTube = { yt = true },
        openCustomStream = { customRtmp = true },
        openProfiles = onOpenProfiles,
        openSettings = onOpenSettings,
        addSource = { type -> vm.addSource(type) { created -> selectedSourceId = created.id; editingSource = created } },
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
            isStreaming = (streaming == StudioStreamState.LIVE || streaming == StudioStreamState.RECONNECTING),
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
        // OBS layout: Scenes/Sources docks down the left; preview, source toolbar and the
        // Audio Mixer / Scene Transitions / Controls docks on the right; status bar below.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val m = studioLayoutMetrics(maxWidth, maxHeight)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(m.leftWidth).fillMaxHeight()) {
                    Dock("Scenes", Modifier.weight(1f).fillMaxWidth()) {
                        ScenePanel(scenes,active?.id,{vm.setActiveScene(it)},{vm.addScene("Scene ${scenes.size+1}")},{vm.removeScene()},Modifier.fillMaxSize(),showHeader=false)
                    }
                    Dock("Sources", Modifier.weight(1f).fillMaxWidth()) {
                        SourcePanel(
                            sources = sources,
                            sourceErrors = sourceErrors,
                            onToggleVisibility = vm::toggleSourceVisibility,
                            onToggleLock = vm::toggleSourceLock,
                            onAdd = { addSource = true },
                            onRemove = { selectedSourceId?.let(vm::removeSource) },
                            onProperties = { editable(selectedSourceId)?.let { editingSource = it } },
                            onFilters = { id -> editable(id)?.let { filteringSource = it } },
                            onTransform = { id -> editable(id)?.takeIf { !it.isLocked }?.let { transformingSource = it } },
                            onOpenProperties = { id -> editable(id)?.let { editingSource = it } },
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
                            groupChildren = groupChildren,
                            onMoveIntoGroup = vm::moveSourceIntoGroup,
                            onMoveOutOfGroup = vm::moveSourceOutOfGroup,
                            modifier = Modifier.fillMaxSize(),
                            showHeader = false
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.background).padding(6.dp)) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val aspect = videoConfig.baseResWidth.toFloat() / videoConfig.baseResHeight.coerceAtLeast(1)
                            val canvasWidth = minOf(maxWidth, maxHeight * aspect)
                            val canvasHeight = canvasWidth / aspect
                            Box(Modifier.size(canvasWidth, canvasHeight).background(Color.Black)) {
                                EditablePreview(
                                    sources,
                                    selectedSourceId,
                                    videoConfig.baseResWidth,
                                    videoConfig.baseResHeight,
                                    { selectedSourceId = it },
                                    { id, transform -> vm.updateSourceTransform(id, transform) },
                                    vm::moveSourceInStack,
                                    canvasFocusRequester,
                                    Modifier.fillMaxSize(),
                                    snapping = general.snappingEnabled,
                                    snapToSources = general.snapToSources
                                )
                            }
                        }
                        if (studio) {
                            Spacer(Modifier.width(8.dp))
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                val aspect = videoConfig.baseResWidth.toFloat() / videoConfig.baseResHeight.coerceAtLeast(1)
                                val canvasWidth = minOf(maxWidth, maxHeight * aspect)
                                val canvasHeight = canvasWidth / aspect
                                Box(Modifier.size(canvasWidth, canvasHeight).background(Color.Black)) {
                                    NativePreviewSurface(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    SourceToolbar(
                        selectedName = editable(selectedSourceId)?.name,
                        onProperties = { editable(selectedSourceId)?.let { editingSource = it } },
                        onFilters = { editable(selectedSourceId)?.let { filteringSource = it } }
                    )
                    Row(Modifier.fillMaxWidth().height(m.bottomHeight)) {
                        Dock("Audio Mixer", Modifier.weight(1f).fillMaxHeight()) {
                            AudioMixerPanel(audioItems, { src, cfg -> vm.updateSourceConfig(src.id, cfg) }, vm::audioPeak, Modifier.fillMaxSize(), showHeader = false, onFilters = { filteringSource = it })
                        }
                        Dock("Scene Transitions", Modifier.width(m.transitionsWidth).fillMaxHeight()) {
                            TransitionsDockContent(selectedTransition, vm::selectTransition, studio) { active?.id?.let { vm.setActiveScene(it) } }
                        }
                        Dock("Controls", Modifier.width(m.controlsWidth).fillMaxHeight()) {
                            ControlsDockContent(
                                isStreaming = (streaming == StudioStreamState.LIVE || streaming == StudioStreamState.RECONNECTING),
                                isStudioMode = studio,
                                onStartStreaming = { vm.startStreaming() },
                                onStopStreaming = { if (general.confirmStopStreaming) confirmStop = "streaming" else vm.stopStreaming() },
                                onToggleStudio = { vm.toggleStudioMode() },
                                onSettings = { onOpenSettings("General") },
                                onManageBroadcast = { yt = true },
                                broadcastTitle = broadcastTitle
                            )
                        }
                    }
                }
            }
        }
        StudioStatusBar(
            isStreaming = streaming == StudioStreamState.LIVE,
            targetFps = videoConfig.frameRate,
            thermal = AstraDeviceMonitor.label(thermalStatus),
            reconnecting = streaming == StudioStreamState.RECONNECTING,
            bitrateBps = streamStats.bitrate
        )
    }
    confirmStop?.let { what ->
        ConfirmStopDialog(
            actionType = what,
            onDismiss = { confirmStop = null },
            onConfirm = { vm.stopStreaming(); confirmStop = null }
        )
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
    if(yt)YouTubeBroadcastPicker({cfg,title->vm.setStreamConfig(cfg);broadcastTitle=title;yt=false},{yt=false})
    if(customRtmp)CustomRtmpDialog(videoConfig,importedRtmpEndpoint,{vm.setStreamConfig(it);customRtmp=false},{customRtmp=false})
    streamError?.let { message ->
        AlertDialog(onDismissRequest = vm::dismissStreamError, title = { Text("Streaming could not start") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissStreamError) { Text("OK") } })
    }
    // New sources open their properties straight away so the device/file/URL can be chosen, like OBS.
    if(addSource)SourceTypePicker(onAdd={type->addSource=false;if(type=="SCENE")pickingNestedScene=true else vm.addSource(type){created->selectedSourceId=created.id;editingSource=created}},onDismiss={addSource=false})
    if(pickingNestedScene)NestedScenePicker(vm,onDismiss={pickingNestedScene=false})
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
            canvasWidth = canvasOf(source.id).first,
            canvasHeight = canvasOf(source.id).second,
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
    val types=listOf("USB_CAPTURE" to "USB camera / capture card","BROWSER" to "Browser","MEDIA" to "Media","IMAGE" to "Image","IMAGE_SLIDESHOW" to "Image slideshow","TEXT" to "Text","COLOR" to "Color","AUDIO_INPUT" to "Audio input","PLAYBACK_AUDIO" to "Android app audio","AUDIO_OUTPUT" to "Audio monitor output","SCENE" to "Scene (show another scene)","GROUP" to "Group")
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add source")},text={Column{types.forEach{(id,name)->TextButton(onClick={onAdd(id)},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=4.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name);Text("+")}}}}},confirmButton={})
}

/** Chooses a scene to show inside the active one; scenes that would create a loop are not offered. */
@Composable private fun NestedScenePicker(vm: MainStudioViewModel, onDismiss: () -> Unit) {
    var choices by remember { mutableStateOf<List<SceneItem>?>(null) }
    LaunchedEffect(Unit) { choices = vm.nestableScenes() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add scene") },
        text = {
            Column {
                when {
                    choices == null -> Text("Loading scenes…")
                    choices!!.isEmpty() -> Text("No other scene can be added here. Create another scene first; a scene that already shows this one cannot be added, because that would loop.")
                    else -> choices!!.forEach { scene ->
                        TextButton(onClick = { vm.addSceneSource(scene.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(scene.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
