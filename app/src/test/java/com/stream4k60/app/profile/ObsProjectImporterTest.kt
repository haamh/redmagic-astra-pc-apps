package com.stream4k60.app.profile

import android.content.ContextWrapper
import com.stream4k60.app.data.local.entity.SourceEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ObsProjectImporterTest {
    // Trimmed from a real OBS 30 scene collection: a nested scene, a group with two items, filters and colors.
    private val collection = """
    {
      "name": "Stream",
      "current_scene": "Main",
      "scene_order": [{"name": "Main"}, {"name": "Overlay"}],
      "sources": [
        {"id": "scene", "name": "Main", "uuid": "m", "settings": {"items": [
          {"name": "Overlay", "source_uuid": "o", "pos": {"x": 0.0, "y": 0.0}, "scale": {"x": 1.0, "y": 1.0}, "visible": true},
          {"name": "Cam Group", "source_uuid": "g", "pos": {"x": 100.0, "y": 50.0}, "scale": {"x": 0.5, "y": 0.5}, "rot": 10.0, "visible": true}
        ]}},
        {"id": "scene", "name": "Overlay", "uuid": "o", "settings": {"items": [
          {"name": "Bar", "pos": {"x": 0.0, "y": 980.0}, "scale": {"x": 1.0, "y": 1.0}, "visible": true}
        ]}},
        {"id": "group", "name": "Cam Group", "uuid": "g", "settings": {"cx": 800, "cy": 600, "items": [
          {"name": "Webcam", "pos": {"x": 0.0, "y": 0.0}, "scale": {"x": 0.625, "y": 0.625}, "visible": true},
          {"name": "Frame", "pos": {"x": 10.0, "y": 20.0}, "scale": {"x": 1.0, "y": 1.0}, "visible": false}
        ]}},
        {"id": "color_source_v3", "name": "Bar", "settings": {"color": 4278190335, "width": 1920, "height": 100},
         "filters": [
           {"id": "color_filter_v2", "name": "Grade", "enabled": true, "settings": {"gamma": 1.0, "brightness": 0.1}},
           {"id": "clut_filter", "name": "Look", "enabled": false, "settings": {"image_path": "C:/luts/film.cube", "clut_amount": 0.7}},
           {"id": "sharpen_filter", "name": "Crisp", "settings": {"sharpness": 0.2}}
         ]},
        {"id": "dshow_input", "name": "Webcam", "settings": {"resolution": "1280x720", "frame_interval": 333333},
         "filters": [{"id": "noise_gate_filter", "name": "Gate", "settings": {"open_threshold": -40.0, "close_threshold": -46.0}}]},
        {"id": "color_source", "name": "Frame", "settings": {"color": 4294901760, "width": 820, "height": 620}}
      ]
    }
    """.trimIndent()

    private fun parse(json: String): Pair<ObsProjectImporter.ImportedCollection, List<String>> {
        val importer = ObsProjectImporter(ContextWrapper(null))
        val method = ObsProjectImporter::class.java.getDeclaredMethod("parseSceneJson", JsonElement::class.java, File::class.java, MutableList::class.java)
        method.isAccessible = true
        val warnings = mutableListOf<String>()
        val root = Files.createTempDirectory("obs").toFile()
        val result = method.invoke(importer, Json.parseToJsonElement(json), root, warnings) as ObsProjectImporter.ImportedCollection
        return result to warnings
    }

    private fun settings(row: SourceEntity) = JSONObject(row.configJson).getJSONObject("settings")

    @Test
    fun importsNestedScenesAndGroups() {
        val (c, _) = parse(collection)
        val main = c.scenes.first { it.name == "Main" }
        val overlay = c.scenes.first { it.name == "Overlay" }
        val mainRows = c.sources.filter { it.sceneId == main.id }.sortedBy { it.sortOrder }
        assertEquals(listOf("SCENE", "GROUP"), mainRows.map { it.type })
        assertEquals(overlay.id, settings(mainRows[0]).getString("sceneId"))

        val group = mainRows[1]
        assertEquals(800, settings(group).getInt("width"))
        assertEquals(600, settings(group).getInt("height"))
        assertTrue(!settings(group).has("items"))
        val groupTransform = JSONObject(group.transformJson)
        assertEquals(10.0, groupTransform.getDouble("rotation"), 0.0)

        val children = c.sources.filter { it.sceneId == "group:${group.id}" }.sortedBy { it.sortOrder }
        assertEquals(listOf("Webcam", "Frame"), children.map { it.name })
        assertEquals(listOf("USB_CAPTURE", "COLOR"), children.map { it.type })
        assertEquals(false, children[1].visible)
        assertEquals(1280, settings(children[0]).getInt("width"))
        assertEquals(30, settings(children[0]).getInt("fps"))
    }

    @Test
    fun importsFiltersInOrderAndConvertsObsColors() {
        val (c, _) = parse(collection)
        val bar = c.sources.first { it.name == "Bar" }
        val s = settings(bar)
        // OBS 0xAABBGGRR 4278190335 = opaque red.
        assertEquals("#FFFF0000", s.getString("color"))
        val filters = s.getJSONArray("videoFilters")
        assertEquals(listOf("COLOR_CORRECTION", "LUT", "SHARPEN"), (0 until filters.length()).map { filters.getJSONObject(it).getString("type") })
        assertEquals(false, filters.getJSONObject(1).getBoolean("enabled"))
        assertEquals(0.7, filters.getJSONObject(1).getJSONObject("settings").getDouble("amount"), 1e-9)
        // OBS gamma +1 means exponent 0.5, which Android stores as gamma 2 (brighter).
        assertEquals(2.0, filters.getJSONObject(0).getJSONObject("settings").getDouble("gamma"), 1e-9)

        val gate = settings(c.sources.first { it.name == "Webcam" }).getJSONArray("audioFilters").getJSONObject(0)
        assertEquals("NOISE_GATE", gate.getString("type"))
        assertEquals(-40.0, gate.getJSONObject("settings").getDouble("openDb"), 0.0)
        assertEquals(true, gate.getJSONObject("settings").getBoolean("closeFollowsOpen"))
    }

    @Test
    fun keepsSceneItemTransforms() {
        val (c, _) = parse(collection)
        val group = c.sources.first { it.type == "GROUP" }
        val t = JSONObject(group.transformJson)
        assertEquals(100.0, t.getJSONObject("pos").getDouble("x"), 0.0)
        assertEquals(0.5, t.getJSONObject("scale").getDouble("x"), 0.0)
    }
}
