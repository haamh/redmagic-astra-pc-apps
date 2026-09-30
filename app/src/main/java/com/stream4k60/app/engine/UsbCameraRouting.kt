package com.stream4k60.app.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject

/**
 * A USB camera can be read two ways: through Android's own camera driver (when the system lists it as an
 * external camera) or directly over USB (UVC). Android's driver is the most compatible, so "Automatic" uses it
 * whenever it is available and falls back to direct USB otherwise.
 */
object UsbCameraRouting {
    const val AUTO = "AUTO"
    const val ANDROID = "ANDROID"
    const val USB = "USB"

    fun externalCameraIds(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL }
    }.getOrDefault(emptyList())

    private fun settings(config: String) = runCatching { JSONObject(config).let { it.optJSONObject("settings") ?: it } }.getOrDefault(JSONObject())

    fun usesAndroidDriver(source: SourceItem, externalIds: List<String>): Boolean =
        source.type.equals("USB_CAPTURE", true) && when (settings(source.configJson).optString("driver", AUTO)) {
            USB -> false
            ANDROID -> true
            else -> externalIds.isNotEmpty()
        }

    /** The same source expressed as an Android camera source, keeping its size, frame rate and filters. */
    fun asAndroidCamera(source: SourceItem, externalIds: List<String>): SourceItem {
        val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
        val s = root.optJSONObject("settings") ?: root
        s.put("facing", "EXTERNAL")
        val chosen = s.optString("androidCameraId").takeIf { it in externalIds } ?: externalIds.firstOrNull().orEmpty()
        s.put("cameraId", chosen)
        return source.copy(type = "CAMERA", configJson = root.toString())
    }
}
