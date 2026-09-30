package com.stream4k60.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught crashes to `Android/data/com.stream4k60.app/files/last-crash.txt` (native crashes go to
 * `native-crash.txt`) so they can be read with
 * `adb shell cat` even when the device's logcat is restricted, then lets Android handle the crash as usual.
 */
object CrashReporter {
    private const val TAG = "Stream4k60Crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        // Native (C++) crashes bypass the Java handler below; the engine records those itself.
        runCatching { com.stream4k60.app.engine.NativeEngine.installCrashHandler(java.io.File(dir, "native-crash.txt").absolutePath) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Stream4k60 crash ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Thread ${thread.name}")
            appendLine()
            append(trace)
        }
        Log.e(TAG, report)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "last-crash.txt").writeText(report)
    }
}
