package com.stream4k60.app.profile

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stream4k60.app.data.local.entity.*
import kotlinx.serialization.json.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.Locale

/**
 * Imports OBS Profile exports, Scene Collection JSON, profile folders and ZIP bundles.
 * Every file is copied into app-private storage so imported media keeps working after
 * the original USB/storage location disappears. Unsupported OBS plugin sources are
 * preserved as OBS:<source-id> rather than silently dropped.
 */
class ObsProjectImporter(private val context: Context) {
    data class Result(val profile: ProfileEntity, val collections: List<ImportedCollection>, val warnings: List<String>, val importedFiles: Int)
    data class ImportedCollection(
        val collection: SceneCollectionEntity,
        val scenes: List<SceneEntity>,
        val sources: List<SourceEntity>,
        val filters: List<FilterEntity> = emptyList()
    )

    fun importUri(uri: Uri): Result {
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "obs_profiles/$id").apply { mkdirs() }
        val warnings = mutableListOf<String>()
        val copied = when {
            DocumentsContractCompat.isTreeUri(uri) -> copyTree(uri, root, warnings)
            isJsonUri(uri) -> copySingle(uri, File(root, displayName(uri) ?: "import.json"), warnings)
            else -> copyZip(uri, root, warnings)
        }

        val basicFile = root.walkTopDown().firstOrNull { it.isFile && it.name.equals("basic.ini", true) }
        val serviceFile = root.walkTopDown().firstOrNull { it.isFile && it.name.equals("service.json", true) }
        val streamEncoderFile = root.walkTopDown().firstOrNull { it.isFile && it.name.equals("streamEncoder.json", true) }
        val basic = basicFile?.readText().orEmpty()
        val service = serviceFile?.readText().orEmpty()
        val encoder = streamEncoderFile?.readText().orEmpty()

        val jsonFiles = root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.toList()
        val collections = mutableListOf<ImportedCollection>()
        var profileJson: JsonElement? = null
        for (file in jsonFiles) {
            val parsed = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() ?: continue
            if (isSceneCollection(parsed)) {
                runCatching { collections += parseSceneJson(parsed, root, warnings) }
                    .onFailure { warnings += "Could not import ${file.relativeTo(root).path}: ${it.message}" }
            } else if (file.name.equals("profile.json", true) || looksLikeProfileExport(parsed)) {
                profileJson = parsed
            }
        }

        // A single OBS scene JSON is frequently named arbitrarily, so also inspect every JSON
        // file even when it is outside the scenes/ directory.
        if (collections.isEmpty()) {
            val candidates = jsonFiles.filter { !it.name.equals("service.json", true) && !it.name.equals("streamEncoder.json", true) }
            for (file in candidates) {
                runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull()?.let { parsed ->
                    if (isSceneCollection(parsed)) runCatching { collections += parseSceneJson(parsed, root, warnings) }
                }
            }
        }
        val distinctCollections = collections.distinctBy { it.collection.name }
        if (distinctCollections.isEmpty()) warnings += "No OBS scene collection was found. The profile settings were imported, but scenes must be imported separately."
        if (basic.isBlank()) {
            warnings += if (profileJson != null) {
                "The selected OBS profile JSON was preserved, but no basic.ini settings were found to map into active Stream4k video/output controls."
            } else {
                "No OBS basic.ini was found. The profile entry was created with Stream4k defaults; add an OBS profile folder or ZIP to import profile settings."
            }
        }
        if (service.isNotBlank()) warnings += "OBS service configuration was preserved. A compatible RTMP server URL and stream key are prefilled in the Custom RTMP destination for review; verify credentials and service limits before streaming."
        val importedEncoder=iniValue(basic,"AdvOut","Encoder")?:iniValue(basic,"SimpleOutput","StreamEncoder").orEmpty()
        if (importedEncoder.isNotBlank() && !importedEncoder.contains("hevc",true) && !importedEncoder.contains("h265",true) && !importedEncoder.contains("264",true) && !importedEncoder.contains("x264",true)) {
            warnings += "OBS encoder '$importedEncoder' was preserved as metadata. Android hardware encoder selection is negotiated separately at runtime."
        }
        val importedColorFormat=iniValue(basic,"Video","ColorFormat")?.uppercase()
        if (!importedColorFormat.isNullOrBlank() && importedColorFormat !in setOf("NV12","I420","I444","P010","I010")) {
            warnings += "OBS color format '$importedColorFormat' is not mapped to an Android compositor format; Stream4k will use NV12 until configured otherwise."
        }

        val name = iniValue(basic, "General", "Name")?.ifBlank { null }
            ?: profileName(profileJson)
            ?: distinctCollections.firstOrNull()?.collection?.name
            ?: displayName(uri)?.substringBeforeLast('.', displayName(uri) ?: "Imported OBS")
            ?: "Imported OBS Profile"

        val derived = deriveProfileJson(name, basic, service, encoder, profileJson, root, distinctCollections)
        val profile = ProfileEntity(
            id = id,
            name = name,
            configJson = derived.toString(),
            isActive = false,
            obsBasicIni = basic,
            obsServiceJson = service,
            importedPath = root.absolutePath
        )
        return Result(profile, distinctCollections, warnings, copied)
    }

    private fun isJsonUri(uri: Uri): Boolean {
        val t = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val n = displayName(uri)?.lowercase().orEmpty()
        return t.contains("json") || n.endsWith(".json") || n.endsWith(".ini")
    }

    private fun copySingle(uri: Uri, dest: File, warnings: MutableList<String>): Int {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot read imported OBS file")
        return 1
    }

    private fun copyZip(uri: Uri, root: File, warnings: MutableList<String>): Int {
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val safe = File(root, entry.name).canonicalFile
                    if (!safe.path.startsWith(root.canonicalPath + File.separator)) {
                        warnings += "Skipped unsafe archive path: ${entry.name}"
                        continue
                    }
                    safe.parentFile?.mkdirs()
                    safe.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        } ?: error("Cannot read OBS archive")
        return count
    }

    private fun copyTree(treeUri: Uri, root: File, warnings: MutableList<String>): Int {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open selected OBS folder")
        var count = 0
        fun walk(node: DocumentFile, relative: File) {
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it, File(relative, it.name ?: "unnamed")) }
            } else {
                val out = File(root, relative.path)
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(node.uri)?.use { input -> out.outputStream().use { input.copyTo(it) }; count++ }
                    ?: warnings.add("Cannot read ${relative.path}")
            }
        }
        walk(tree, File(""))
        return count
    }

    private fun parseSceneJson(o: JsonElement, root: File, warnings: MutableList<String>): ImportedCollection {
        val obj = o.jsonObject
        require(isSceneCollection(o)) { "Not an OBS scene collection" }
        val collectionId = UUID.randomUUID().toString()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Imported Collection" } ?: "Imported Collection"
        val sourceDefs = obj["sources"]!!.jsonArray
        val sourceByUuid = sourceDefs.mapNotNull { e ->
            val x = e.jsonObject; x["uuid"]?.jsonPrimitive?.contentOrNull?.let { it to x }
        }.toMap()
        val sourceByName = sourceDefs.mapNotNull { e ->
            val x = e.jsonObject; x["name"]?.jsonPrimitive?.contentOrNull?.let { it to x }
        }.toMap()
        val sceneNames = sourceDefs.filter { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == "scene" }
        val current = obj["current_scene"]?.jsonPrimitive?.contentOrNull
        val sceneOrder = obj["scene_order"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
        val orderedNames = (sceneOrder + sceneNames.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }).distinct()
        val scenes = orderedNames.mapIndexed { idx, sceneName ->
            SceneEntity(UUID.randomUUID().toString(), collectionId, sceneName, idx, sceneName == current)
        }
        val sceneDefsByName = sceneNames.associateBy { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val sources = mutableListOf<SourceEntity>()
        val filters = mutableListOf<FilterEntity>()
        for (scene in scenes) {
            val sceneDef = sceneDefsByName[scene.name] ?: continue
            val items = sceneDef.jsonObject["settings"]?.jsonObject?.get("items")?.jsonArray ?: JsonArray(emptyList())
            items.forEachIndexed { order, itemEl ->
                val item = itemEl.jsonObject
                val uuid = item["uuid"]?.jsonPrimitive?.contentOrNull
                val sourceName = item["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val def = uuid?.let(sourceByUuid::get) ?: sourceByName[sourceName] ?: return@forEachIndexed
                val obsId = def["id"]?.jsonPrimitive?.contentOrNull ?: "unknown_source"
                val sourceId = UUID.randomUUID().toString()
                val mappedType = mapType(obsId)
                when {
                    obsId in setOf("dshow_input", "v4l2_input", "av_capture_input") -> warnings += "Source '$sourceName' uses OBS desktop camera capture ($obsId). It was imported as a USB capture source; select a connected UVC webcam or capture card in its properties. The Astra's built-in camera is not required."
                    obsId in setOf("window_capture", "screen_capture", "monitor_capture") -> warnings += "Source '$sourceName' uses desktop display/window capture ($obsId). It was preserved for import fidelity; screen capture is outside the Astra source workflow and was not activated."
                    obsId in setOf("wasapi_input_capture", "wasapi_output_capture", "jack_output_capture") -> warnings += "Source '$sourceName' uses desktop audio capture ($obsId). It was mapped to an Android audio source; choose a device and grant playback capture where required."
                    mappedType.startsWith("OBS:") -> warnings += "Source '$sourceName' uses unsupported OBS source '$obsId'. Its source definition, settings, filters, visibility and canvas transform were preserved for remapping."
                }
                if (obsId == "slideshow" || obsId == "slideshow_v2") {
                    val transition = def["settings"]?.jsonObject?.get("transition")?.jsonPrimitive?.contentOrNull
                    if (!transition.isNullOrBlank() && !transition.equals("cut", true)) {
                        warnings += "Slideshow '$sourceName' uses the OBS '$transition' transition. Images and timing were imported, but slide transitions currently switch immediately."
                    }
                    val mode = def["settings"]?.jsonObject?.get("slide_mode")?.jsonPrimitive?.contentOrNull
                    if (mode.equals("manual", true)) warnings += "Slideshow '$sourceName' uses manual slide control. The Android source currently advances automatically."
                }
                var settings = populateSourceDimensions(
                    obsId,
                    normalizeSourceSettings(obsId, rewriteAssetPaths(def["settings"]?.jsonObject ?: buildJsonObject {}, root, warnings))
                )
                if (mappedType.equals("BROWSER", true)) {
                    val width = settings["width"]?.jsonPrimitive?.intOrNull ?: 1280
                    val height = settings["height"]?.jsonPrimitive?.intOrNull ?: 720
                    val fps = settings["fps"]?.jsonPrimitive?.intOrNull ?: 10
                    val maxWidth = 2400
                    val maxHeight = 1504
                    val maxFps = 60
                    if (width > maxWidth || height > maxHeight || fps > maxFps) {
                        warnings += "Browser source '$sourceName' requests ${width}×${height} at ${fps} FPS. The Astra GPU-backed WebView surface path is capped at ${maxWidth}×${maxHeight} and ${maxFps} FPS; the imported page may render at a lower size/rate."
                    }
                }
                val importedFilters = def["filters"]?.jsonArray ?: JsonArray(emptyList())
                val mappedFilters = mapSupportedFilters(sourceName, importedFilters, warnings)
                if (mappedFilters.isNotEmpty()) {
                    settings = buildJsonObject {
                        for ((key, value) in settings) put(key, value)
                        put("videoFilters", mappedFilters)
                    }
                }
                val transform = buildJsonObject {
                    put("obsSceneItemRaw", item)
                    for (key in listOf("pos", "scale", "rot", "rotation", "crop", "bounds", "bounds_type", "bounds_align", "bounds_alignment", "alignment", "align", "crop_left", "crop_top", "crop_right", "crop_bottom", "crop_to_bounds", "width", "height")) {
                        item[key]?.let { put(key, it) }
                    }
                    item["rot"]?.let { put("rotation", it) }
                    item["align"]?.let { put("alignment", it) }
                    item["bounds_align"]?.let { put("boundsAlignment", it) }
                    item["bounds_alignment"]?.let { put("boundsAlignment", it) }
                    item["bounds_type"]?.let { put("boundsType", it) }
                    item["crop_left"]?.let { put("cropLeft", it) }
                    item["crop_top"]?.let { put("cropTop", it) }
                    item["crop_right"]?.let { put("cropRight", it) }
                    item["crop_bottom"]?.let { put("cropBottom", it) }
                    item["crop_to_bounds"]?.let { put("cropToBounds", it) }
                }
                val audio = buildJsonObject {
                    val rawAudio = buildJsonObject {
                        for (key in listOf("volume", "balance", "sync", "sync_offset", "monitoring_type", "mixers", "flags")) def[key]?.let { put(key, it) }
                    }
                    put("raw", rawAudio)
                    def["volume"]?.jsonPrimitive?.doubleOrNull?.let { put("volume", JsonPrimitive(it.coerceIn(0.0, 2.0))) }
                    def["balance"]?.jsonPrimitive?.doubleOrNull?.let { put("balance", JsonPrimitive((it * 2.0 - 1.0).coerceIn(-1.0, 1.0))) }
                    (def["sync"] ?: def["sync_offset"])?.jsonPrimitive?.intOrNull?.let { put("syncOffsetMs", JsonPrimitive(it.coerceIn(-2000, 2000))) }
                    def["monitoring_type"]?.jsonPrimitive?.intOrNull?.let { monitoring ->
                        put("monitoring", JsonPrimitive(when (monitoring) {
                            0 -> "OFF"
                            1 -> "MONITOR_ONLY"
                            2 -> "MONITOR_AND_OUTPUT"
                            else -> "OUTPUT_ONLY"
                        }))
                    }
                }
                sources += SourceEntity(
                    id = sourceId,
                    sceneId = scene.id,
                    name = sourceName,
                    type = mappedType,
                    sortOrder = order,
                    visible = item["visible"]?.jsonPrimitive?.booleanOrNull ?: item["show"]?.jsonPrimitive?.booleanOrNull ?: true,
                    locked = item["locked"]?.jsonPrimitive?.booleanOrNull ?: false,
                    configJson = buildJsonObject {
                        put("obsId", obsId)
                        put("uuid", def["uuid"] ?: JsonNull)
                        put("settings", settings)
                        put("audio", audio)
                        put("version", def["version"] ?: JsonPrimitive(1))
                        put("raw", def)
                    }.toString(),
                    transformJson = transform.toString(),
                    audioJson = audio.toString()
                )
                importedFilters.forEachIndexed { idx, filterEl ->
                    val fj = filterEl.jsonObject
                    val filterName = fj["name"]?.jsonPrimitive?.contentOrNull ?: "Filter ${idx + 1}"
                    filters += FilterEntity(
                        UUID.randomUUID().toString(), sourceId, filterName,
                        fj["id"]?.jsonPrimitive?.contentOrNull ?: "OBS_FILTER",
                        fj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        idx,
                        buildJsonObject {
                            put("obsUuid", fj["uuid"] ?: JsonNull)
                            put("settings", fj["settings"] ?: buildJsonObject {})
                            put("raw", fj)
                        }.toString()
                    )
                }
            }
        }
        val active = scenes.firstOrNull { it.active }?.id ?: scenes.firstOrNull()?.id
        return ImportedCollection(SceneCollectionEntity(collectionId, name, active, 0), scenes, sources, filters)
    }

    /**
     * Maps OBS video filters to the ordered Android GPU filter chain (see VideoFilterChain), keeping OBS order.
     * All originals, including unsupported filters, remain in FilterEntity.
     */
    private fun mapSupportedFilters(sourceName: String, filters: JsonArray, warnings: MutableList<String>): JsonArray {
        val stages = mutableListOf<JsonObject>()
        filters.forEachIndexed { index, element ->
            val filter = element as? JsonObject ?: return@forEachIndexed
            val id = filter["id"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val name = filter["name"]?.jsonPrimitive?.contentOrNull ?: "Filter ${index + 1}"
            val enabled = filter["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val settings = filter["settings"] as? JsonObject ?: JsonObject(emptyMap())
            val isV2 = id.endsWith("_v2") || filter["version"]?.jsonPrimitive?.intOrNull == 2
            // OBS v1 filters store opacity as an integer percentage; v2 uses 0..1.
            val opacity = if (isV2) settings.number("opacity", 1.0) else settings.number("opacity", 100.0) / 100.0
            val (type, mapped) = when (id.removeSuffix("_v2")) {
                "color_filter", "color_correction_filter" -> "COLOR_CORRECTION" to buildJsonObject {
                    put("gamma", obsGammaToAndroid(settings.number("gamma", 0.0)))
                    put("contrast", obsContrastToMultiplier(settings.number("contrast", 0.0), isV2))
                    put("brightness", settings.number("brightness", 0.0).coerceIn(-1.0, 1.0))
                    put("saturation", (settings.number("saturation", 0.0) + 1.0).coerceIn(0.0, 4.0))
                    put("hueDegrees", settings.number("hue_shift", 0.0).coerceIn(-180.0, 180.0))
                    put("opacity", opacity.coerceIn(0.0, 1.0))
                    val multiply = settings["color_multiply"] ?: settings["color"]
                    multiply?.jsonPrimitive?.contentOrNull?.let(::obsColorHex)?.let { put("colorMultiply", it) }
                    settings["color_add"]?.jsonPrimitive?.contentOrNull?.let(::obsColorHex)?.let { put("colorAdd", it) }
                }
                "chroma_key_filter" -> "CHROMA_KEY" to buildJsonObject {
                    put("keyColor", obsKeyColor(settings, "#FF00FF00"))
                    put("similarity", (settings.number("similarity", 400.0) / 1000.0).coerceIn(0.001, 1.0))
                    put("smoothness", (settings.number("smoothness", 80.0) / 1000.0).coerceIn(0.001, 1.0))
                    put("spill", (settings.number("spill", 100.0) / 1000.0).coerceIn(0.001, 1.0))
                    putKeyAdjustments(settings, opacity, isV2)
                }
                "color_key_filter" -> "COLOR_KEY" to buildJsonObject {
                    put("keyColor", obsKeyColor(settings, "#FF00FF00"))
                    put("similarity", (settings.number("similarity", 80.0) / 1000.0).coerceIn(0.001, 1.0))
                    put("smoothness", (settings.number("smoothness", 50.0) / 1000.0).coerceIn(0.001, 1.0))
                    putKeyAdjustments(settings, opacity, isV2)
                }
                "luma_key_filter" -> "LUMA_KEY" to buildJsonObject {
                    put("lumaMin", settings.number("luma_min", 0.0).coerceIn(0.0, 1.0))
                    put("lumaMax", settings.number("luma_max", 1.0).coerceIn(0.0, 1.0))
                    put("lumaMinSmooth", settings.number("luma_min_smooth", 0.0).coerceIn(0.0, 1.0))
                    put("lumaMaxSmooth", settings.number("luma_max_smooth", 0.0).coerceIn(0.0, 1.0))
                }
                else -> {
                    warnings += "Source '$sourceName' has OBS filter '$name' ($id). Its settings were preserved in the import, but this filter has no Android equivalent yet."
                    return@forEachIndexed
                }
            }
            stages += buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("type", type)
                put("name", name)
                put("enabled", enabled)
                put("settings", mapped)
            }
        }
        if (stages.size > 8) {
            warnings += "Source '$sourceName' has ${stages.size} supported OBS filters. The Android compositor renders the first 8 enabled filters; the rest are listed in the filter editor."
        }
        if (stages.isNotEmpty()) {
            warnings += "Source '$sourceName': ${stages.size} OBS filter(s) were mapped in order to the Android GPU filter chain. Chroma key uses a single sample per pixel, so key edges can differ slightly from OBS's box-filtered key."
        }
        return JsonArray(stages)
    }

    /** OBS stores gamma as a signed offset and applies pow(c, exponent); Android stores gamma as 1/exponent. */
    private fun obsGammaToAndroid(raw: Double): Double {
        val exponent = if (raw < 0.0) 1.0 - raw else 1.0 / (1.0 + raw)
        return (1.0 / exponent).coerceIn(0.1, 3.0)
    }

    private fun obsContrastToMultiplier(raw: Double, isV2: Boolean): Double =
        (if (isV2 && raw < 0.0) 1.0 / (1.0 - raw) else raw + 1.0).coerceIn(0.0, 4.0)

    private fun JsonObjectBuilder.putKeyAdjustments(settings: JsonObject, opacity: Double, isV2: Boolean) {
        put("opacity", opacity.coerceIn(0.0, 1.0))
        put("contrast", obsContrastToMultiplier(settings.number("contrast", 0.0), isV2))
        put("brightness", settings.number("brightness", 0.0).coerceIn(-1.0, 1.0))
        put("gamma", obsGammaToAndroid(settings.number("gamma", 0.0)))
    }

    private fun obsKeyColor(settings: JsonObject, fallback: String): String =
        when (settings["key_color_type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "green" -> "#FF00FF00"
            "blue" -> "#FF0000FF"
            "magenta" -> "#FFFF00FF"
            "red" -> "#FFFF0000"
            else -> settings["key_color"]?.jsonPrimitive?.contentOrNull?.let(::obsOpaqueColorHex) ?: fallback
        }

    private fun JsonObject.number(key: String, fallback: Double): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: fallback

    private fun obsOpaqueColorHex(value: String): String? = obsColorHex(value)?.let { "#FF" + it.substring(3) }

    private fun rewriteAssetPaths(settings: JsonObject, root: File, warnings: MutableList<String>): JsonObject =
        rewriteJsonValue(settings, "", root, warnings) as JsonObject

    private fun rewriteJsonValue(value: JsonElement, keyHint: String, root: File, warnings: MutableList<String>): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            for ((key, child) in value) {
                val childHint = if (looksLikePath(key, "")) key else keyHint
                put(key, rewriteJsonValue(child, childHint, root, warnings))
            }
        }
        is JsonArray -> JsonArray(value.map { rewriteJsonValue(it, keyHint, root, warnings) })
        is JsonPrimitive -> {
            if (!value.isString || !looksLikePath(keyHint, value.content)) value
            else {
                val raw = value.content
                val normalized = raw.replace('\\', '/')
                val direct = File(root, normalized.removePrefix("./")).takeIf { it.exists() }
                val fileName = normalized.substringAfterLast('/')
                val byName = if (direct == null) root.walkTopDown().firstOrNull { it.isFile && it.name == fileName } else null
                when {
                    direct != null -> JsonPrimitive(direct.absolutePath)
                    byName != null -> JsonPrimitive(byName.absolutePath)
                    normalized.startsWith("http://") || normalized.startsWith("https://") || normalized.startsWith("rtmp://") -> value
                    else -> { warnings += "Source asset not bundled: $raw"; value }
                }
            }
        }
    }

    private fun normalizeSourceSettings(obsId: String, settings: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in settings) put(key, value)
        if (obsId == "ffmpeg_source") {
            settings["local_file"]?.jsonPrimitive?.contentOrNull?.let { put("file", it) }
        }
        if (obsId == "browser_source" || obsId == "browser_source_v2") {
            if (settings["width"] == null) settings["custom_width"]?.jsonPrimitive?.intOrNull?.let { put("width", it) }
            if (settings["height"] == null) settings["custom_height"]?.jsonPrimitive?.intOrNull?.let { put("height", it) }
        }
        if (obsId == "color_source") {
            settings["color"]?.let { color ->
                val hex = color.jsonPrimitive.contentOrNull?.let(::obsColorHex)
                if (hex != null) put("color", hex)
            }
        }
        if (obsId == "text_gdiplus_v2" || obsId == "text_ft2_source") {
            val font = settings["font"] as? JsonObject
            font?.get("face")?.jsonPrimitive?.contentOrNull?.let { put("fontFamily", it) }
            font?.get("size")?.jsonPrimitive?.intOrNull?.let { put("fontSize", it) }
            val style = font?.get("style")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (style.isNotBlank()) {
                put("bold", style.contains("bold", true))
                put("italic", style.contains("italic", true))
            }
            val color = settings["color"] ?: settings["color1"]
            color?.jsonPrimitive?.contentOrNull?.let(::obsColorHex)?.let { put("textColor", it) }
            settings["bk_color"]?.jsonPrimitive?.contentOrNull?.let(::obsColorHex)?.let { put("backgroundColor", it) }
        }
        if (obsId == "slideshow" || obsId == "slideshow_v2") {
            val files = settings["files"] as? JsonArray
            val paths = files?.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.contentOrNull
                    is JsonObject -> listOf("value", "path", "file").firstNotNullOfOrNull { entry[it]?.jsonPrimitive?.contentOrNull }
                    else -> null
                }
            }?.filter(String::isNotBlank).orEmpty()
            if (paths.isNotEmpty()) put("files", JsonArray(paths.map { JsonPrimitive(it) }))
            settings["slide_time"]?.jsonPrimitive?.longOrNull?.let { millis ->
                put("slideIntervalSeconds", (millis / 1000L).coerceIn(1L, 3600L))
            }
        }
    }

    private fun obsColorHex(value: String): String? {
        value.removePrefix("#").takeIf { it.matches(Regex("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}")) }?.let {
            return if (it.length == 6) "#FF$it" else "#$it"
        }
        // OBS stores integer colors as 0xAABBGGRR; Android color strings are #AARRGGBB.
        val abgr = value.toLongOrNull()?.toInt() ?: return null
        val argb = (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr shr 16) and 0xFF)
        return String.format(Locale.US, "#%08X", argb)
    }

    private fun populateSourceDimensions(obsId: String, settings: JsonObject): JsonObject {
        val firstSlidePath = (settings["files"] as? JsonArray)?.firstOrNull()?.let { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                is JsonObject -> listOf("value", "path", "file").firstNotNullOfOrNull { entry[it]?.jsonPrimitive?.contentOrNull }
                else -> null
            }
        }
        val path = listOf("file", "local_file", "path")
            .asSequence()
            .mapNotNull { settings[it]?.jsonPrimitive?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
            ?: firstSlidePath?.takeIf(String::isNotBlank)
            ?: return settings
        val file = runCatching {
            val uri = Uri.parse(path)
            if (uri.scheme == "file") File(uri.path.orEmpty()) else File(path)
        }.getOrNull()?.takeIf(File::isFile) ?: return settings

        val dimensions = when (obsId) {
            "image_source", "slideshow", "slideshow_v2" -> runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                options.outWidth to options.outHeight
            }.getOrNull()
            "ffmpeg_source" -> runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    width to height
                } finally { retriever.release() }
            }.getOrNull()
            else -> null
        } ?: return settings
        val (width, height) = dimensions
        if (width <= 0 || height <= 0) return settings
        return buildJsonObject {
            for ((key, value) in settings) put(key, value)
            if ((settings["width"]?.jsonPrimitive?.intOrNull ?: 0) <= 0) put("width", width)
            if ((settings["height"]?.jsonPrimitive?.intOrNull ?: 0) <= 0) put("height", height)
        }
    }

    private fun looksLikePath(key: String, value: String): Boolean {
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("rtmp://")) return false
        val k = key.lowercase()
        return k.contains("file") || k.contains("path") || k == "local_file" || k.endsWith("_dir") || k.contains("image") || k.contains("media")
    }

    private fun mapType(id: String): String = when (id) {
        "browser_source", "browser_source_v2" -> "BROWSER"
        "image_source" -> "IMAGE"
        "ffmpeg_source" -> "MEDIA"
        "text_gdiplus_v2", "text_ft2_source" -> "TEXT"
        "color_source" -> "COLOR"
        "slideshow" -> "IMAGE_SLIDESHOW"
        "av_capture_input", "dshow_input", "v4l2_input" -> "USB_CAPTURE"
        "wasapi_input_capture" -> "AUDIO_INPUT"
        "wasapi_output_capture", "jack_output_capture" -> "PLAYBACK_AUDIO"
        "group" -> "OBS:GROUP"
        else -> "OBS:$id"
    }

    private fun isSceneCollection(e: JsonElement): Boolean {
        val o = e as? JsonObject ?: return false
        return o["sources"] is JsonArray && (o["scene_order"] is JsonArray || o["current_scene"] != null)
    }

    private fun looksLikeProfileExport(e: JsonElement): Boolean {
        val o = e as? JsonObject ?: return false
        return o["settings"] is JsonObject || o["profile"] != null || o["profile_name"] != null || o["type"]?.jsonPrimitive?.contentOrNull?.contains("profile", true) == true
    }

    private fun profileName(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        return listOf("name", "profile_name", "profileName").asSequence().mapNotNull { o[it]?.jsonPrimitive?.contentOrNull }.firstOrNull { it.isNotBlank() }
    }

    private fun deriveProfileJson(name:String,basic:String,service:String,encoder:String,profileJson:JsonElement?,root:File,collections:List<ImportedCollection>):JsonObject = buildJsonObject {
        put("name", name)
        put("obsBasicIni", basic)
        put("obsServiceJson", if(service.isBlank()) "{}" else service)
        put("obsStreamEncoderJson", if(encoder.isBlank()) "{}" else encoder)
        profileJson?.let { put("obsProfileExport", it) }
        put("storagePath", root.absolutePath)
        put("sceneCollections", JsonArray(collections.map { JsonPrimitive(it.collection.name) }))
        put("video", buildJsonObject {
            put("baseWidth", iniInt(basic,"Video","BaseCX",1920));put("baseHeight",iniInt(basic,"Video","BaseCY",1080))
            put("outputWidth",iniInt(basic,"Video","OutputCX",iniInt(basic,"Video","BaseCX",1920)))
            put("outputHeight",iniInt(basic,"Video","OutputCY",iniInt(basic,"Video","BaseCY",1080)))
            val fpsMode=iniInt(basic,"Video","FPSType",0)
            val fpsNum=iniInt(basic,"Video","FPSNum",30)
            val fpsDen=iniInt(basic,"Video","FPSDen",1).coerceAtLeast(1)
            val fpsInt=iniInt(basic,"Video","FPSInt",30)
            val fpsCommon=iniValue(basic,"Video","FPSCommon")?.toIntOrNull()?:30
            val importedFps=when(fpsMode){1->fpsInt;2->fpsNum/fpsDen;else->fpsCommon}
            val fpsType=when(fpsMode){1->"INTEGER";2->"FRACTIONAL";else->"COMMON"}
            put("fps",importedFps);put("fpsType",fpsType)
            put("fpsInt",fpsInt);put("fpsNum",fpsNum);put("fpsDen",fpsDen)
            val encoderName=iniValue(basic,"AdvOut","Encoder")?:iniValue(basic,"SimpleOutput","StreamEncoder").orEmpty()
            put("outputCodec",if(encoderName.contains("hevc",true)||encoderName.contains("h265",true)) "HEVC" else "H264")
            put("colorFormat",iniValue(basic,"Video","ColorFormat")?:"NV12")
            put("colorSpace",iniValue(basic,"Video","ColorSpace")?:"709");put("colorRange",iniValue(basic,"Video","ColorRange")?:"Partial")
            put("downscaleFilter",iniValue(basic,"Video","ScaleType")?:"BICUBIC")
        })
        put("output", buildJsonObject {
            put("mode",iniValue(basic,"Output","Mode")?:"Simple")
            val bitrate=iniInt(basic,"SimpleOutput","VBitrate",iniInt(basic,"AdvOut","VBitrate",35000))
            put("streamBitrateKbps",bitrate);put("videoBitrateKbps",bitrate)
            put("recordFormat",iniValue(basic,"SimpleOutput","RecFormat")?:iniValue(basic,"AdvOut","RecFormat")?:"mkv")
            put("recordPath",iniValue(basic,"SimpleOutput","FilePath")?:iniValue(basic,"AdvOut","RecFilePath")?:"")
            put("encoder",iniValue(basic,"AdvOut","Encoder")?:iniValue(basic,"SimpleOutput","StreamEncoder")?:"hardware")
        })
    }

    private fun displayName(uri:Uri):String?=runCatching{context.contentResolver.query(uri,arrayOf("_display_name"),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}}.getOrNull()
    private fun iniValue(text:String,section:String,key:String):String? {
        var currentSection=""
        for(line in text.lineSequence()) {
            val trimmed=line.trim()
            if(trimmed.startsWith("[")&&trimmed.endsWith("]")) currentSection=trimmed.substring(1,trimmed.length-1)
            else if(currentSection==section&&trimmed.startsWith("$key=")) return trimmed.substringAfter('=')
        }
        return null
    }
    private fun iniInt(text:String,section:String,key:String,def:Int):Int=iniValue(text,section,key)?.toIntOrNull()?:def
    private fun iniFps(text:String):Int {
        val mode=iniInt(text,"Video","FPSType",0)
        return when(mode) {
            1->iniInt(text,"Video","FPSInt",30)
            2->iniInt(text,"Video","FPSNum",30)/iniInt(text,"Video","FPSDen",1).coerceAtLeast(1)
            else->iniValue(text,"Video","FPSCommon")?.toIntOrNull()?:30
        }.coerceIn(1,240)
    }

    private object DocumentsContractCompat { fun isTreeUri(uri:Uri)=android.provider.DocumentsContract.isTreeUri(uri) }
}
