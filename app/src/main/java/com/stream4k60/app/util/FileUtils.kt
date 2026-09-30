package com.stream4k60.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {
    fun generateRecordingFilename(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "Recording_${formatter.format(Date())}.mp4"
    }

    fun getRecordingsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getScreenshotsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Screenshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024).toFloat()
        return String.format("%.2f MB", mb)
    }
}
