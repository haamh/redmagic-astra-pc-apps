package com.stream4k60.app.ui.sources

import com.stream4k60.app.ui.common.ClosableTitle

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.NativeUsbManager
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.ui.util.showImeOnFocus
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class AndroidCameraChoice(
    val id: String,
    val label: String,
    val facing: String,
    val sizes: List<Pair<Int, Int>>,
    val fpsValues: List<Int>
)

/** Typed, source-specific editor. JSON is kept as the storage format, never exposed as the UI. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SourcePropertiesDialog(
    source: SourceItem,
    usbManager: NativeUsbManager,
    onSave: (SourceItem) -> Unit,
    onRemapSource: (SourceItem, String) -> Unit = { _, _ -> },
    runtimeError: String? = null,
    peakProvider: (String) -> Float = { 0f },
    header: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var config by remember(source.id, source.configJson) {
        mutableStateOf(runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject()))
    }
    val usbVideo by usbManager.videoCameras.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var cameraSizeMenuExpanded by remember { mutableStateOf(false) }
    var cameraFpsMenuExpanded by remember { mutableStateOf(false) }
    var formatMenuExpanded by remember { mutableStateOf(false) }
    var mediaDecoderMenuExpanded by remember { mutableStateOf(false) }
    var mediaSpeedMenuExpanded by remember { mutableStateOf(false) }
    var captureAudioMenuExpanded by remember { mutableStateOf(false) }
    var uvcDecoderMenuExpanded by remember { mutableStateOf(false) }
    var textAlignmentMenuExpanded by remember { mutableStateOf(false) }
    var pickerError by remember(source.id) { mutableStateOf<String?>(null) }
    var invalidNumberFields by remember(source.id) { mutableStateOf(emptySet<String>()) }
    var uvcControlError by remember(source.id) { mutableStateOf<String?>(null) }
    var remapMenuExpanded by remember(source.id) { mutableStateOf(false) }
    var remapType by remember(source.id) { mutableStateOf("USB_CAPTURE") }
    val cameraChoices = remember(context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            manager.cameraIdList.mapNotNull { id ->
                runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }.getOrNull()?.let { lensFacing ->
                    val facing = when (lensFacing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> null
                    } ?: return@let null
                    val characteristics = manager.getCameraCharacteristics(id)
                    val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                        ?.map { it.width to it.height }
                        ?.distinct()
                        ?.sortedWith(compareBy<Pair<Int, Int>> { it.first.toLong() * it.second }.thenBy { it.first })
                        .orEmpty()
                    val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                    val fpsValues = (1..240).filter { fps -> fpsRanges.any { fps in it.lower..it.upper } }
                    AndroidCameraChoice(id, "${facing.lowercase().replaceFirstChar(Char::uppercase)} · ID $id", facing, sizes, fpsValues)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    fun values(): JSONObject = config.optJSONObject("settings") ?: config
    fun updateValues(update: (JSONObject) -> Unit) {
        val next = JSONObject(config.toString())
        update(next.optJSONObject("settings") ?: next)
        config = next
    }
    fun set(key: String, value: Any?) = updateValues { it.put(key, value) }
    fun str(key: String, default: String = "") = values().optString(key, default)
    fun int(key: String, default: Int) = values().optInt(key, default)
    fun bool(key: String, default: Boolean) = values().optBoolean(key, default)
    fun rememberUri(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { set("file", uri.toString()); pickerError = null }
            .onFailure { pickerError = "Android did not grant lasting access to this file. Choose it again and allow document access." }
    }
    fun updateNumberValidity(key: String, valid: Boolean) {
        invalidNumberFields = if (valid) invalidNumberFields - key else invalidNumberFields + key
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::rememberUri) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val granted = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
            if (granted) { updateValues { it.put("file", uri.toString()); it.put("url", ""); it.remove("playlist") }; pickerError = null }
            else pickerError = "Android did not grant lasting access to this media file. Choose it again and allow document access."
        }
    }
    val mediaPlaylistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val grantedUris = uris.mapNotNull { uri ->
                val granted = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                if (!granted) pickerError = "Android did not grant lasting access to one selected media file. Choose it again and allow document access."
                uri.toString().takeIf { granted }
            }
            if (grantedUris.isNotEmpty()) updateValues { settings ->
                val existing = settings.optJSONArray("playlist") ?: JSONArray()
                val merged = (0 until existing.length()).mapNotNull { existing.optString(it).takeIf(String::isNotBlank) }.toMutableList()
                grantedUris.forEach { if (it !in merged) merged += it }
                settings.put("playlist", JSONArray(merged)); settings.put("file", ""); settings.put("url", "")
            }
        }
    }
    val slidesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            updateValues { settings ->
                val existing = settings.optJSONArray("files") ?: JSONArray()
                val merged = (0 until existing.length()).mapNotNull { existing.optString(it).takeIf(String::isNotBlank) }.toMutableList()
                uris.forEach { uri ->
                    val granted = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                    if (granted) uri.toString().takeIf { it !in merged }?.let(merged::add)
                    else pickerError = "Android did not grant lasting access to one selected slideshow image. Choose it again and allow document access."
                }
                settings.put("files", JSONArray(merged))
            }
        }
    }
    val browserFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::rememberUri) }

    val type = source.type.uppercase()
    val configError = remember(context, type, config.toString(), cameraChoices, usbVideo) {
        // Mic/Aux may use Android's default input (-1), chosen in Settings → Audio.
        if (source.id == "global:mic" && type == "AUDIO_INPUT" && values().optInt("deviceId", -1) < 0) null
        else validateSourceConfig(context, type, values(), cameraChoices, usbVideo)
    }
    val canApply = configError == null && pickerError == null && invalidNumberFields.isEmpty()
    val title = "${source.name} properties"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle(title, onDismiss) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text(sourceTypeDescription(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                header?.invoke()
                SourceLivePreview(source, runtimeError, peakProvider)
                if (configError != null || pickerError != null || invalidNumberFields.isNotEmpty()) {
                    Text(
                        pickerError ?: configError ?: "Enter valid numbers for: ${invalidNumberFields.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when (type) {
                    "IMAGE" -> {
                        Text("Image file", style = MaterialTheme.typography.labelLarge)
                        Text(str("file", "No image selected"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("Choose image…") }
                    }
                    "IMAGE_SLIDESHOW" -> {
                        val files = values().optJSONArray("files") ?: JSONArray()
                        Text("${files.length()} images selected", style = MaterialTheme.typography.bodyMedium)
                        for (index in 0 until files.length()) {
                            val uri = files.optString(index)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(uri.substringAfterLast('/').ifBlank { uri }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    updateValues { settings ->
                                        val current = settings.optJSONArray("files") ?: JSONArray()
                                        settings.put("files", JSONArray((0 until current.length()).map { current.optString(it) }.filterIndexed { itemIndex, _ -> itemIndex != index }))
                                    }
                                }) { Text("Remove") }
                            }
                        }
                        OutlinedButton(onClick = { slidesPicker.launch(arrayOf("image/*")) }) { Text("Add images…") }
                        NumberField("Seconds per slide", "slideIntervalSeconds", int("slideIntervalSeconds", 5), 1..3600, ::set) { valid -> updateNumberValidity("slideIntervalSeconds", valid) }
                        SwitchField("Loop slideshow", bool("loop", true)) { set("loop", it) }
                        SwitchField("Shuffle order", bool("randomize", false)) { set("randomize", it) }
                    }
                    "MEDIA" -> {
                        Text("Media file or URL", style = MaterialTheme.typography.labelLarge)
                        val playlist = values().optJSONArray("playlist") ?: JSONArray()
                        val selectedMedia = str("url").ifBlank { str("file") }
                        Text(selectedMedia.ifBlank { if (playlist.length() > 0) "Playlist · ${playlist.length()} files" else "No media selected" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) }) { Text("Choose media…") }
                        if (playlist.length() > 0) {
                            Text("Playlist · ${playlist.length()} files", style = MaterialTheme.typography.labelLarge)
                            for (index in 0 until playlist.length()) {
                                val item = playlist.optString(index)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(item.substringAfterLast('/').ifBlank { item }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { updateValues { settings ->
                                        val current = settings.optJSONArray("playlist") ?: JSONArray()
                                        settings.put("playlist", JSONArray((0 until current.length()).map { current.optString(it) }.filterIndexed { itemIndex, _ -> itemIndex != index }))
                                    } }) { Text("Remove") }
                                }
                            }
                        }
                        OutlinedButton(onClick = { mediaPlaylistPicker.launch(arrayOf("video/*", "audio/*")) }) { Text("Add playlist files…") }
                        Field("Media URL", str("url")) { value ->
                            updateValues { settings ->
                                settings.put("url", value)
                                if (value.isNotBlank()) { settings.put("file", ""); settings.remove("playlist") }
                            }
                        }
                        SwitchField("Loop playback", bool("loop", true)) { set("loop", it) }
                        SwitchField("Route media audio to the mixer", bool("audioEnabled", true)) { set("audioEnabled", it) }
                        ExposedDropdownMenuBox(expanded = mediaDecoderMenuExpanded, onExpandedChange = { mediaDecoderMenuExpanded = !mediaDecoderMenuExpanded }) {
                            val preference = str("decoderPreference", "hardware")
                            val decoderLabel = when (preference) { "automatic" -> "Automatic"; "software" -> "Prefer software"; else -> "Prefer hardware" }
                            OutlinedTextField(value = decoderLabel, onValueChange = {}, readOnly = true, label = { Text("Video decoder") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mediaDecoderMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = mediaDecoderMenuExpanded, onDismissRequest = { mediaDecoderMenuExpanded = false }) {
                                listOf("hardware" to "Prefer hardware", "automatic" to "Automatic", "software" to "Prefer software").forEach { (key, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { set("decoderPreference", key); mediaDecoderMenuExpanded = false })
                                }
                            }
                        }
                        Text("Hardware mode tries Android hardware decoders first and falls back when a format is unsupported. Software-only codecs may not be present on Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ExposedDropdownMenuBox(expanded = mediaSpeedMenuExpanded, onExpandedChange = { mediaSpeedMenuExpanded = !mediaSpeedMenuExpanded }) {
                            val speed = int("playbackSpeedPercent", (values().optDouble("playbackSpeed", 1.0) * 100).roundToInt())
                            OutlinedTextField(value = "${speed / 100f}×", onValueChange = {}, readOnly = true, label = { Text("Playback speed") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mediaSpeedMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = mediaSpeedMenuExpanded, onDismissRequest = { mediaSpeedMenuExpanded = false }) {
                                listOf(25, 50, 75, 100, 125, 150, 200, 300, 400).forEach { percent ->
                                    DropdownMenuItem(text = { Text("${percent / 100f}×") }, onClick = { set("playbackSpeed", percent / 100.0); mediaSpeedMenuExpanded = false })
                                }
                            }
                        }
                        NumberField("Start position (ms)", "startPositionMs", int("startPositionMs", 0), 0..86_400_000, ::set) { valid -> updateNumberValidity("media.startPositionMs", valid) }
                        NumberField("Output width", "width", int("width", 1920), 16..7680, ::set) { valid -> updateNumberValidity("media.width", valid) }
                        NumberField("Output height", "height", int("height", 1080), 16..4320, ::set) { valid -> updateNumberValidity("media.height", valid) }
                    }
                    "BROWSER" -> {
                        val gpuRendering = bool("hardwareAccelerated", true)
                        Text("Local HTML file: ${str("file", "None selected")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { browserFilePicker.launch(arrayOf("text/html", "application/xhtml+xml")) }) { Text("Choose local HTML…") }
                        Field("Web page URL", str("url")) { set("url", it) }
                        Field("Custom CSS", str("customCss"), minLines = 4) { set("customCss", it) }
                        OutlinedButton(onClick = { set("refreshToken", int("refreshToken", 0) + 1) }) { Text("Refresh page") }
                        SwitchField("GPU-accelerated browser rendering", gpuRendering) { set("hardwareAccelerated", it) }
                        NumberField("Source width", "width", int("width", 1280), 16..(if (gpuRendering) 2400 else 1280), ::set) { valid -> updateNumberValidity("browser.width", valid) }
                        NumberField("Source height", "height", int("height", 720), 16..(if (gpuRendering) 1504 else 720), ::set) { valid -> updateNumberValidity("browser.height", valid) }
                        NumberField("Capture rate (FPS)", "fps", int("fps", 30).coerceAtMost(if (gpuRendering) 60 else 10), 1..if (gpuRendering) 60 else 10, ::set) { valid -> updateNumberValidity("browser.fps", valid) }
                        SwitchField("Allow JavaScript", bool("javaScript", true)) { set("javaScript", it) }
                        Text(
                            if (gpuRendering) {
                            "Astra-targeted GPU-backed WebView drawing through a Surface/Canvas, up to 2400 × 1504 at 60 FPS; pixel delivery and performance have not been tested on the tablet. This switch changes the rendering path, not the video decoder: WebView/Android choose the codec automatically. Turn it off for the slower 1280 × 720, 10 FPS software fallback. Browser audio is not routed yet. Only open pages you trust."
                            } else {
                                "CPU fallback: snapshots are limited to 1280 × 720 at 10 FPS. Android WebView still chooses video decoding automatically. Browser audio routing is not available yet. Only open pages you trust."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    "TEXT" -> {
                        Field("Text", str("text", source.name), minLines = 3) { set("text", it) }
                        NumberField("Font size", "fontSize", int("fontSize", 64), 1..512, ::set) { valid -> updateNumberValidity("text.fontSize", valid) }
                        Field("Font family", str("fontFamily", "sans-serif")) { set("fontFamily", it) }
                        ExposedDropdownMenuBox(expanded = textAlignmentMenuExpanded, onExpandedChange = { textAlignmentMenuExpanded = !textAlignmentMenuExpanded }) {
                            val alignment = str("alignment", "LEFT").uppercase()
                            OutlinedTextField(value = alignment.lowercase().replaceFirstChar(Char::uppercase), onValueChange = {}, readOnly = true, label = { Text("Text alignment") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(textAlignmentMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = textAlignmentMenuExpanded, onDismissRequest = { textAlignmentMenuExpanded = false }) {
                                listOf("LEFT", "CENTER", "RIGHT").forEach { value -> DropdownMenuItem(text = { Text(value.lowercase().replaceFirstChar(Char::uppercase)) }, onClick = { set("alignment", value); textAlignmentMenuExpanded = false }) }
                            }
                        }
                        NumberField("Text canvas width", "width", int("width", 1280), 1..4096, ::set) { valid -> updateNumberValidity("text.width", valid) }
                        NumberField("Text canvas height", "height", int("height", 720), 1..4096, ::set) { valid -> updateNumberValidity("text.height", valid) }
                        Field("Text color (hex)", str("textColor", "#FFFFFFFF")) { set("textColor", it) }
                        Field("Background color (hex or transparent)", str("backgroundColor", "#00000000")) { set("backgroundColor", it) }
                        SwitchField("Bold", bool("bold", false)) { set("bold", it) }
                        SwitchField("Italic", bool("italic", false)) { set("italic", it) }
                    }
                    "GROUP" -> {
                        Text("A group is its own canvas. Items inside it are positioned on this canvas, and the whole group moves, scales, crops and filters as one layer.", style = MaterialTheme.typography.bodySmall)
                        NumberField("Group canvas width", "width", int("width", 1920), 16..8192, ::set) { valid -> updateNumberValidity("group.width", valid) }
                        NumberField("Group canvas height", "height", int("height", 1080), 16..8192, ::set) { valid -> updateNumberValidity("group.height", valid) }
                    }
                    "SCENE" -> {
                        Text("Shows another scene live, including its filters and nested scenes. Edit that scene's sources by switching to it; to show a different scene, add another Scene source.", style = MaterialTheme.typography.bodySmall)
                    }
                    "COLOR" -> {
                        Field("Color (hex)", str("color", "#FF000000")) { set("color", it) }
                        NumberField("Width", "width", int("width", 1280), 1..7680, ::set) { valid -> updateNumberValidity("color.width", valid) }
                        NumberField("Height", "height", int("height", 720), 1..4320, ::set) { valid -> updateNumberValidity("color.height", valid) }
                    }
                    "CAMERA" -> {
                        val selectedCamera = cameraChoices.firstOrNull { it.id == str("cameraId") }
                            ?: cameraChoices.firstOrNull { it.facing.equals(str("facing", "BACK"), true) }
                        ExposedDropdownMenuBox(expanded = sourceMenuExpanded, onExpandedChange = { sourceMenuExpanded = !sourceMenuExpanded }) {
                            OutlinedTextField(
                                value = selectedCamera?.label ?: "Select Android camera", onValueChange = {}, readOnly = true,
                                label = { Text("Android camera device") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                                cameraChoices.forEach { camera ->
                                    DropdownMenuItem(text = { Text(camera.label) }, onClick = {
                                        set("cameraId", camera.id)
                                        set("facing", camera.facing)
                                        sourceMenuExpanded = false
                                    })
                                }
                            }
                        }
                        if (cameraChoices.isEmpty()) Text("Android reports no available cameras.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        val selectedSize = int("width", 1920) to int("height", 1080)
                        ExposedDropdownMenuBox(expanded = cameraSizeMenuExpanded, onExpandedChange = { cameraSizeMenuExpanded = !cameraSizeMenuExpanded }) {
                            OutlinedTextField(
                                value = "${selectedSize.first} × ${selectedSize.second}", onValueChange = {}, readOnly = true,
                                label = { Text("Camera resolution") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraSizeMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = cameraSizeMenuExpanded, onDismissRequest = { cameraSizeMenuExpanded = false }) {
                                selectedCamera?.sizes?.forEach { (width, height) ->
                                    DropdownMenuItem(text = { Text("$width × $height") }, onClick = {
                                        set("width", width); set("height", height); cameraSizeMenuExpanded = false
                                    })
                                }
                            }
                        }
                        ExposedDropdownMenuBox(expanded = cameraFpsMenuExpanded, onExpandedChange = { cameraFpsMenuExpanded = !cameraFpsMenuExpanded }) {
                            OutlinedTextField(
                                value = "${int("fps", 30)} FPS", onValueChange = {}, readOnly = true,
                                label = { Text("Requested frame rate") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraFpsMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = cameraFpsMenuExpanded, onDismissRequest = { cameraFpsMenuExpanded = false }) {
                                selectedCamera?.fpsValues?.forEach { fps ->
                                    DropdownMenuItem(text = { Text("$fps FPS") }, onClick = { set("fps", fps); cameraFpsMenuExpanded = false })
                                }
                            }
                        }
                        Text("Resolution and frame-rate choices come from this camera's Camera2 capabilities. Android may still pace frames below the requested rate under load.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "USB_CAPTURE" -> {
                        val selected = usbVideo.firstOrNull { it.deviceId == int("deviceId", -1) }
                        val externalCameras = remember { com.stream4k60.app.engine.UsbCameraRouting.externalCameraIds(context) }
                        var driverMenu by remember { mutableStateOf(false) }
                        val driverLabels = linkedMapOf(
                            "AUTO" to "Automatic (recommended)",
                            "ANDROID" to "Android camera driver",
                            "USB" to "Direct USB (UVC)"
                        )
                        ExposedDropdownMenuBox(expanded = driverMenu, onExpandedChange = { driverMenu = !driverMenu }) {
                            OutlinedTextField(
                                value = driverLabels[str("driver", "AUTO")] ?: "Automatic (recommended)", onValueChange = {}, readOnly = true,
                                label = { Text("Driver") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(driverMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = driverMenu, onDismissRequest = { driverMenu = false }) {
                                driverLabels.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { set("driver", id); driverMenu = false }) }
                            }
                        }
                        Text(
                            if (externalCameras.isEmpty()) "Android's camera service doesn't list any USB camera right now, so capture goes directly over USB."
                            else "Android lists ${externalCameras.size} USB camera(s). Automatic uses Android's driver, which works with most webcams (including ones that stay black over direct USB). Pick Direct USB for capture cards or formats Android doesn't offer.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        val uvcControls by produceState(emptyList<com.stream4k60.app.engine.UvcVideoControl>(), selected?.deviceId) {
                            value = withContext(Dispatchers.IO) { selected?.let { usbManager.videoControls(it.deviceId) }.orEmpty() }
                        }
                        ExposedDropdownMenuBox(expanded = sourceMenuExpanded, onExpandedChange = { sourceMenuExpanded = !sourceMenuExpanded }) {
                            OutlinedTextField(
                                value = selected?.displayName ?: "Select USB video device", onValueChange = {}, readOnly = true,
                                label = { Text("Capture device") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                                usbVideo.forEach { device ->
                                    DropdownMenuItem(text = { Text(device.displayName) }, onClick = {
                                        set("deviceId", device.deviceId)
                                        device.supportedFormats.firstOrNull()?.let { applyUsbFormat(it, ::set) }
                                        sourceMenuExpanded = false
                                    })
                                }
                            }
                        }
                        TextButton(onClick = { usbManager.rescan() }) { Text("Rescan USB devices") }
                        if (selected == null) Text("Connect a UVC camera or capture card and allow each USB permission prompt (one appears per device), then select it here. Missing a camera? Tap Rescan.", style = MaterialTheme.typography.bodySmall)
                        else {
                            UsbModePickers(selected.supportedFormats, int("width", 1920), int("height", 1080), int("fps", 60), str("format", "MJPEG"), ::set)
                            if (selected.isCapturing && selected.currentFormat.isNotBlank() && selected.currentFormat != usbFormatLabel(config))
                                Text("Running now: ${selected.currentFormat} (the closest mode the device accepted).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            ExposedDropdownMenuBox(expanded = formatMenuExpanded, onExpandedChange = { formatMenuExpanded = !formatMenuExpanded }) {
                                OutlinedTextField(value = usbFormatLabel(config), onValueChange = {}, readOnly = true, label = { Text("Quick pick: modes this device lists") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(formatMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = formatMenuExpanded, onDismissRequest = { formatMenuExpanded = false }) {
                                    selected.supportedFormats.forEach { format -> DropdownMenuItem(text = { Text(format) }, onClick = { applyUsbFormat(format, ::set); formatMenuExpanded = false }) }
                                }
                            }
                            Text("USB ${selected.usbSpeed.displayName} · ${selected.supportedFormats.size} advertised formats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected.usbSpeed == com.stream4k60.app.engine.UsbSpeed.USB_2_0) Text(
                                "Connected at USB 2.0 speed. Capture cards like the Elgato 4K S only offer their 4K/60 modes over a USB 3 link, so they are missing from the list. " +
                                    "Plug it straight into the tablet, or into a dock port marked USB 3 / 10 Gbps. Many docks fall back to USB 2.0 for data while they also run a display (DisplayPort Alt Mode), " +
                                    "and USB-C cables without SuperSpeed wires do the same.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                            )
                            Text("${selected.manufacturerName.orEmpty()} ${selected.productName.orEmpty()} · VID ${selected.vendorId.toString(16).padStart(4, '0')} / PID ${selected.productId.toString(16).padStart(4, '0')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (str("format", "MJPEG").uppercase() in setOf("MJPEG", "H264", "AVC", "AVC1", "HEVC", "H265")) {
                                ExposedDropdownMenuBox(expanded = uvcDecoderMenuExpanded, onExpandedChange = { uvcDecoderMenuExpanded = !uvcDecoderMenuExpanded }) {
                                    val preference = str("videoDecoderPreference", "hardware")
                                    val decoderLabel = when (preference) { "automatic" -> "Android automatic selection"; "software" -> "Prefer software"; else -> "Prefer hardware" }
                                    OutlinedTextField(value = decoderLabel, onValueChange = {}, readOnly = true, label = { Text("Compressed-video decoder") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(uvcDecoderMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                    ExposedDropdownMenu(expanded = uvcDecoderMenuExpanded, onDismissRequest = { uvcDecoderMenuExpanded = false }) {
                                        listOf("hardware" to "Prefer hardware", "automatic" to "Android automatic selection", "software" to "Prefer software").forEach { (key, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { set("videoDecoderPreference", key); uvcDecoderMenuExpanded = false })
                                        }
                                    }
                                }
                                Text("The preferred MediaCodec is tried first; other Android decoders are attempted if it cannot configure. Changing this restarts the USB capture session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val captureAudioInputs = androidAudioDevices(context, AudioManager.GET_DEVICES_INPUTS).filter { it.second.contains("USB audio", true) }
                            ExposedDropdownMenuBox(expanded = captureAudioMenuExpanded, onExpandedChange = { captureAudioMenuExpanded = !captureAudioMenuExpanded }) {
                                val audioDeviceId = int("audioDeviceId", -1)
                                val audioLabel = captureAudioInputs.firstOrNull { it.first == audioDeviceId }?.second ?: if (audioDeviceId < 0) "Disabled" else "Unavailable USB audio device"
                                OutlinedTextField(value = audioLabel, onValueChange = {}, readOnly = true, label = { Text("Linked capture-card audio") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(captureAudioMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = captureAudioMenuExpanded, onDismissRequest = { captureAudioMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("Disabled") }, onClick = { set("audioDeviceId", -1); captureAudioMenuExpanded = false })
                                    captureAudioInputs.forEach { (id, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { set("audioDeviceId", id); captureAudioMenuExpanded = false })
                                    }
                                }
                            }
                            Text("If this UVC device also exposes a USB audio input, link it here. Its gain, pan, mute, sync and monitoring appear in the studio mixer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (uvcControls.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Capture-device picture controls", style = MaterialTheme.typography.labelLarge)
                                if (uvcControlError != null) Text(uvcControlError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                uvcControls.forEach { control ->
                                    val settings = values()
                                    val saved = settings.optJSONObject("uvcControls")?.optInt(control.key, control.current) ?: control.current
                                    var current by remember(source.id, selected.deviceId, control.key, control.current, saved) { mutableFloatStateOf(saved.toFloat().coerceIn(control.minimum.toFloat(), control.maximum.toFloat())) }
                                    var controlMenuExpanded by remember(source.id, selected.deviceId, control.key) { mutableStateOf(false) }
                                    fun persistControl(value: Int) {
                                        uvcControlError = null
                                        updateValues { settings ->
                                            val savedControls = settings.optJSONObject("uvcControls") ?: JSONObject()
                                            savedControls.put(control.key, value)
                                            settings.put("uvcControls", savedControls)
                                        }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val applied = usbManager.setVideoControl(selected.deviceId, control.key, value)
                                            if (!applied) withContext(Dispatchers.Main) { uvcControlError = "${control.label} was saved for the source but this device did not accept the live control." }
                                        }
                                    }
                                    if (control.key == "powerLineFrequency") {
                                        val labels = mapOf(0 to "Off", 1 to "50 Hz", 2 to "60 Hz", 3 to "Auto")
                                        ExposedDropdownMenuBox(expanded = controlMenuExpanded, onExpandedChange = { controlMenuExpanded = !controlMenuExpanded }) {
                                            OutlinedTextField(value = labels[current.roundToInt()] ?: current.roundToInt().toString(), onValueChange = {}, readOnly = true, label = { Text(control.label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(controlMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                            ExposedDropdownMenu(expanded = controlMenuExpanded, onDismissRequest = { controlMenuExpanded = false }) {
                                                (control.minimum..control.maximum step control.step.coerceAtLeast(1)).forEach { value ->
                                                    DropdownMenuItem(text = { Text(labels[value] ?: value.toString()) }, onClick = { current = value.toFloat(); persistControl(value); controlMenuExpanded = false })
                                                }
                                            }
                                        }
                                    } else if (control.isToggle) {
                                        SwitchField(control.label, current.roundToInt() != 0) { enabled ->
                                            current = if (enabled) control.maximum.toFloat() else control.minimum.toFloat()
                                            persistControl(current.roundToInt())
                                        }
                                    } else {
                                        Text("${control.label}: ${current.roundToInt()}", style = MaterialTheme.typography.bodySmall)
                                        Slider(
                                            value = current,
                                            onValueChange = { current = it.coerceIn(control.minimum.toFloat(), control.maximum.toFloat()) },
                                            onValueChangeFinished = {
                                                val step = control.step.coerceAtLeast(1)
                                                val snapped = (control.minimum + ((current - control.minimum) / step).roundToInt() * step).coerceIn(control.minimum, control.maximum)
                                                current = snapped.toFloat()
                                                persistControl(snapped)
                                            },
                                            valueRange = control.minimum.toFloat()..control.maximum.toFloat(),
                                            steps = (((control.maximum - control.minimum) / control.step.coerceAtLeast(1)) - 1).coerceIn(0, 100),
                                            enabled = control.maximum > control.minimum
                                        )
                                    }
                                }
                            } else {
                                Text("This capture device does not report standard Android-accessible brightness, contrast, saturation, sharpness or gamma controls. Source video filters remain available separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    "AUDIO_INPUT" -> {
                        val devices = (if (source.id == "global:mic") listOf(-1 to "Default (Android's current input)") else emptyList()) + androidAudioDevices(context, AudioManager.GET_DEVICES_INPUTS)
                        DeviceDropdown("Audio input", devices, int("deviceId", -1), sourceMenuExpanded, { sourceMenuExpanded = it }) { set("deviceId", it) }
                        NumberField("Sync offset (ms)", "syncOffsetMs", int("syncOffsetMs", 0), -2000..2000, ::set) { valid -> updateNumberValidity("audio.syncOffsetMs", valid) }
                        Text("Volume, balance, mute and monitoring are available in the audio mixer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "AUDIO_OUTPUT" -> {
                        val devices = androidAudioDevices(context, AudioManager.GET_DEVICES_OUTPUTS)
                        DeviceDropdown("Monitor device", devices, int("deviceId", -1), sourceMenuExpanded, { sourceMenuExpanded = it }) { set("deviceId", it) }
                    }
                    "SCREEN_CAPTURE", "PLAYBACK_AUDIO" -> {
                        Text("Android will request screen-capture permission when this source becomes visible. The permission must be granted again after the app process is restarted.", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> if (type.startsWith("OBS:")) {
                        if (type == "OBS:GROUP") {
                            Text("OBS groups are preserved with their imported items and transforms. Nested Android scene groups are not implemented yet, so this group cannot be remapped without changing its composition.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        } else {
                            val options = listOf(
                                "USB_CAPTURE" to "USB camera or capture card",
                                "PLAYBACK_AUDIO" to "Android playback audio",
                                "AUDIO_INPUT" to "Android audio input", "AUDIO_OUTPUT" to "Android audio monitor",
                                "BROWSER" to "Browser page", "MEDIA" to "Media file", "IMAGE" to "Image",
                                "IMAGE_SLIDESHOW" to "Image slideshow", "TEXT" to "Text overlay", "COLOR" to "Color source"
                            )
                            Text("This OBS plugin source is preserved, but Android cannot load the desktop plugin. Remap it to a built-in Android source to continue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Box {
                                OutlinedButton(onClick = { remapMenuExpanded = true }) {
                                    Text(options.firstOrNull { it.first == remapType }?.second ?: remapType)
                                }
                                DropdownMenu(expanded = remapMenuExpanded, onDismissRequest = { remapMenuExpanded = false }) {
                                    options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { remapType = id; remapMenuExpanded = false }) }
                                }
                            }
                            Button(onClick = { onRemapSource(source, remapType) }) { Text("Remap source") }
                        }
                    } else Text("This source type has no editable settings on Android.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(source.copy(configJson = config.toString())) }, enabled = canApply) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun Field(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).showImeOnFocus(), label = { Text(label) }, minLines = minLines,
        supportingText = { Text(fieldDescription(label), style = MaterialTheme.typography.bodySmall) })
}

@Composable private fun NumberField(label: String, key: String, value: Int, range: IntRange, set: (String, Any?) -> Unit, onValidityChange: (Boolean) -> Unit = {}) {
    var text by remember(key, value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    LaunchedEffect(key, valid) { onValidityChange(valid) }
    OutlinedTextField(text, { next ->
        text = next
        val number = next.toIntOrNull()
        val isValid = number != null && number in range
        onValidityChange(isValid)
        if (isValid) set(key, number)
    }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).showImeOnFocus(), label = { Text(label) }, singleLine = true, isError = !valid,
        supportingText = { Text(if (valid) "${fieldDescription(label)} Range: ${range.first}–${range.last}. Example: $value." else "Enter a whole number from ${range.first} to ${range.last}.", style = MaterialTheme.typography.bodySmall) })
}

@Composable private fun SwitchField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(top = 9.dp)) {
            Text(label)
            Text("${fieldDescription(label)} Current: ${if (checked) "On" else "Off"}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

private fun fieldDescription(label: String): String = when {
    label.contains("URL", true) -> "Address used by this source. Example: https://example.com."
    label.contains("FPS", true) -> "Requested capture rate; Android uses the nearest supported rate."
    label.contains("Width", true) || label.contains("Height", true) -> "Pixel dimensions used by the source. Larger values use more memory."
    label.contains("Color", true) -> "Hex color value. Example: #FFFFFFFF for white."
    label.contains("Text", true) -> "Content displayed by this overlay. Line breaks are supported."
    label.contains("Font", true) -> "Text size in pixels; large text may exceed the source bounds."
    label.contains("Offset", true) -> "Shifts this input relative to the other audio sources. Example: 120 ms."
    label.contains("Seconds", true) -> "How long each image is shown before advancing. Example: 5 seconds."
    label.contains("Device", true) || label.contains("camera", true) -> "Selects the Android device used by this source."
    else -> "Controls how this source is rendered or played."
}

private fun sourceTypeDescription(type: String) = when (type) {
    "IMAGE" -> "A still image overlay. Android document access is retained for the selected file."
    "IMAGE_SLIDESHOW" -> "Rotate through selected images while the source is visible."
    "MEDIA" -> "Play a local audio/video file or a media URL."
    "BROWSER" -> "Render a web page as a scene source. Use trusted URLs only."
    "USB_CAPTURE" -> "Capture a real UVC USB camera or HDMI capture device."
    "CAMERA" -> "Use one of Android's built-in cameras."
    "AUDIO_INPUT" -> "Route an Android or USB audio input into the mixer."
    "AUDIO_OUTPUT" -> "Choose the Android output used for audio monitoring."
    else -> "Configure this source."
}

private fun applyUsbFormat(value: String, set: (String, Any?) -> Unit) {
    val match = Regex("(\\d+)x(\\d+)@(\\d+):(.+)").matchEntire(value) ?: return
    set("width", match.groupValues[1].toInt()); set("height", match.groupValues[2].toInt())
    set("fps", match.groupValues[3].toInt()); set("format", match.groupValues[4])
}

private fun usbFormatLabel(config: JSONObject): String {
    val values = config.optJSONObject("settings") ?: config
    return "${values.optInt("width", 0)}x${values.optInt("height", 0)}@${values.optInt("fps", 0)}:${values.optString("format", "MJPEG")}"
}

private fun androidAudioDevices(context: Context, direction: Int): List<Pair<Int, String>> {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return manager.getDevices(direction).map { it.id to "${it.productName} (${audioTypeName(it)})" }.sortedBy { it.second }
}

private fun audioTypeName(device: AudioDeviceInfo): String = when (device.type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired audio"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
    else -> "Audio device ${device.type}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceDropdown(label: String, devices: List<Pair<Int, String>>, selectedId: Int, expanded: Boolean, onExpanded: (Boolean) -> Unit, onSelect: (Int) -> Unit) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { onExpanded(!expanded) }) {
        OutlinedTextField(value = devices.firstOrNull { it.first == selectedId }?.second ?: "Select device", onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            devices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); onExpanded(false) }) }
        }
    }
}

private fun validateSourceConfig(
    context: Context,
    type: String,
    settings: JSONObject,
    cameraChoices: List<AndroidCameraChoice>,
    usbVideo: List<com.stream4k60.app.engine.UsbDeviceInfo>
): String? = when (type) {
    "IMAGE" -> {
        val path = settings.optString("file").ifBlank { settings.optString("path") }
        when {
            path.isBlank() -> "Choose an image before applying this source."
            !canReadSourceUri(context, path) -> "The selected image cannot be opened. Choose it again or relink the file."
            else -> null
        }
    }
    "IMAGE_SLIDESHOW" -> {
        val files = settings.optJSONArray("files") ?: JSONArray()
        when {
            files.length() == 0 -> "Add at least one readable image to the slideshow."
            (0 until files.length()).any { !canReadSourceUri(context, files.optString(it)) } -> "At least one slideshow image is unavailable. Re-select the missing files."
            else -> null
        }
    }
    "MEDIA" -> {
        val playlist = settings.optJSONArray("playlist")
        val playlistPaths = playlist?.let { items -> (0 until items.length()).mapNotNull { items.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        val paths = settings.optString("url").takeIf(String::isNotBlank)?.let(::listOf)
            ?: playlistPaths.takeIf { it.isNotEmpty() }
            ?: listOf(settings.optString("file").ifBlank { settings.optString("local_file") }).filter(String::isNotBlank)
        when {
            paths.isEmpty() -> "Choose a media file, add playlist files, or enter a supported media URL."
            paths.any { !canReadSourceUri(context, it, allowNetwork = true) } -> "A media file or URL cannot be opened. Check its access and format."
            else -> null
        }
    }
    "BROWSER" -> {
        val url = settings.optString("url").trim()
        val file = settings.optString("file").trim()
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        when {
            (url.isBlank() || url == "about:blank") && file.isBlank() -> "Choose a local HTML file or enter a web address."
            (url.isBlank() || url == "about:blank") && !canReadSourceUri(context, file) -> "The selected local HTML file cannot be opened. Choose it again."
            url.isBlank() || url == "about:blank" -> null
            parsed?.scheme?.lowercase() !in setOf("http", "https") || parsed?.host.isNullOrBlank() -> "Enter a complete http or https address."
            else -> null
        }
    }
    "TEXT" -> {
        val foregroundValid = runCatching { Color.parseColor(settings.optString("textColor", "#FFFFFFFF")) }.isSuccess
        val background = settings.optString("backgroundColor", "#00000000")
        val backgroundValid = background.equals("transparent", true) || runCatching { Color.parseColor(background) }.isSuccess
        if (!foregroundValid) "Text color must be a valid Android hex color, such as #FFFFFFFF."
        else if (!backgroundValid) "Background color must be a valid Android hex color or transparent."
        else null
    }
    "COLOR" -> if (runCatching { Color.parseColor(settings.optString("color", "#FF000000")) }.isFailure) {
        "Color must be a valid Android hex color, such as #FF336699."
    } else null
    "CAMERA" -> if (cameraChoices.none {
            val requestedId = settings.optString("cameraId")
            if (requestedId.isNotBlank()) it.id == requestedId
            else it.facing.equals(settings.optString("facing", "BACK"), true)
        }) {
        "The selected Android camera is unavailable. Choose a listed camera."
    } else {
        val requestedId = settings.optString("cameraId")
        val camera = cameraChoices.firstOrNull { if (requestedId.isNotBlank()) it.id == requestedId else it.facing.equals(settings.optString("facing", "BACK"), true) }
        val size = settings.optInt("width", 1920) to settings.optInt("height", 1080)
        when {
            camera?.sizes?.isNotEmpty() == true && size !in camera.sizes -> "Choose a resolution advertised by the selected Android camera."
            camera?.fpsValues?.isNotEmpty() == true && settings.optInt("fps", 30) !in camera.fpsValues -> "Choose a frame rate supported by the selected Android camera."
            else -> null
        }
    }
    "USB_CAPTURE" -> {
        val device = usbVideo.firstOrNull { it.deviceId == settings.optInt("deviceId", -1) }
        when {
            device == null -> "Select a connected USB video device that has Android USB permission."
            device.supportedFormats.isEmpty() -> "This USB device did not advertise a usable UVC video format."
            settings.optInt("width", 0) !in 160..7680 || settings.optInt("height", 0) !in 120..4320 -> "Enter a resolution between 160×120 and 7680×4320."
            settings.optInt("fps", 0) !in 1..480 -> "Enter a frame rate between 1 and 480."
            settings.optString("format").isBlank() -> "Choose a video format."
            else -> null
        }
    }
    "AUDIO_INPUT" -> {
        val ids = androidAudioDevices(context, AudioManager.GET_DEVICES_INPUTS).map { it.first }
        if (settings.optInt("deviceId", -1) !in ids) "Select a connected Android audio input device." else null
    }
    "AUDIO_OUTPUT" -> {
        val ids = androidAudioDevices(context, AudioManager.GET_DEVICES_OUTPUTS).map { it.first }
        if (settings.optInt("deviceId", -1) !in ids) "Select an available Android monitor output device." else null
    }
    else -> null
}

private fun canReadSourceUri(context: Context, path: String, allowNetwork: Boolean = false): Boolean {
    val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "content" -> runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        "file" -> uri.path?.let { java.io.File(it).isFile } == true
        "http", "https" -> allowNetwork && !uri.host.isNullOrBlank()
        null, "" -> java.io.File(path).isFile
        else -> false
    }
}

private data class UsbMode(val width: Int, val height: Int, val fps: Int, val codec: String)

/**
 * Separate Resolution, Frame rate and Format pickers for USB video devices. Each lists what the device advertises
 * first, then common values (marked "not listed by device"), then Custom… for any value.
 */
@Composable
private fun UsbModePickers(advertised: List<String>, width: Int, height: Int, fps: Int, format: String, set: (String, Any?) -> Unit) {
    val modes = advertised.mapNotNull { Regex("(\\d+)x(\\d+)@(\\d+):(.+)").matchEntire(it)?.groupValues?.let { g -> UsbMode(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].uppercase()) } }
    val codec = format.uppercase()
    fun tag(listed: Boolean) = if (listed) "" else "  (not listed by device)"
    var customRes by remember { mutableStateOf(false) }
    var customFps by remember { mutableStateOf(false) }
    var customFormat by remember { mutableStateOf(false) }

    val resolutions = (modes.map { it.width to it.height } + listOf(7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720))
        .distinct().sortedByDescending { it.first * it.second }
    ChoiceField("Resolution", "$width×$height",
        resolutions.map { (w, h) -> "$w×$h" + tag(modes.any { it.width == w && it.height == h }) to { set("width", w); set("height", h); customRes = false } }
    ) { customRes = true }
    if (customRes) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IntField("Width", width, 160..7680, Modifier.weight(1f)) { set("width", it) }
        IntField("Height", height, 120..4320, Modifier.weight(1f)) { set("height", it) }
    }

    val listedFps = modes.filter { it.width == width && it.height == height && it.codec == codec }.map { it.fps }
    ChoiceField("Frame rate", "$fps FPS",
        (listedFps + listOf(240, 144, 120, 60, 50, 30, 25, 24)).distinct().sortedDescending()
            .map { f -> "$f FPS" + tag(f in listedFps) to { set("fps", f); customFps = false } }
    ) { customFps = true }
    if (customFps) IntField("Frame rate (FPS)", fps, 1..480, Modifier.fillMaxWidth()) { set("fps", it) }

    val listedCodecs = modes.filter { it.width == width && it.height == height }.map { it.codec }.toSet()
    ChoiceField("Format", codec,
        (modes.map { it.codec } + listOf("MJPEG", "H264", "HEVC", "NV12", "YUYV", "UYVY", "P010")).distinct()
            .map { c -> c + tag(c in listedCodecs) to { set("format", c); customFormat = false } }
    ) { customFormat = true }
    if (customFormat) {
        var text by remember { mutableStateOf(codec) }
        OutlinedTextField(text, { t -> text = t.uppercase().take(8); if (text.isNotBlank()) set("format", text) }, label = { Text("Format (FourCC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    val exact = modes.any { it.width == width && it.height == height && it.codec == codec && it.fps == fps }
    if (!exact) Text(
        if (modes.any { it.width == width && it.height == height && it.codec == codec })
            "This frame rate isn't in the device's list for $width×$height $codec; it will be requested anyway and the device uses the closest rate it supports."
        else "The device doesn't list $width×$height $codec; the closest mode it offers will be used and shown below once running.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String, options: List<Pair<String, () -> Unit>>, onCustom: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, pick) -> DropdownMenuItem(text = { Text(text) }, onClick = { pick(); open = false }) }
            DropdownMenuItem(text = { Text("Custom…") }, onClick = { onCustom(); open = false })
        }
    }
}

@Composable
private fun IntField(label: String, value: Int, range: IntRange, modifier: Modifier, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { t -> text = t.filter(Char::isDigit).take(5); text.toIntOrNull()?.takeIf { it in range }?.let(onValid) },
        label = { Text(label) }, singleLine = true, isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier
    )
}
