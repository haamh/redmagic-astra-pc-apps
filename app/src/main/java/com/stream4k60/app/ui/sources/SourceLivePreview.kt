package com.stream4k60.app.ui.sources

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.ui.main.SourceItem
import kotlinx.coroutines.delay
import kotlin.math.log10

/**
 * Top of the Properties dialog: what the source is producing right now (video, or a level meter for audio-only
 * sources) and, when it fails, the whole error message instead of the one clipped line in the Sources list.
 */
@Composable
fun SourceLivePreview(source: SourceItem, runtimeError: String?, peakProvider: (String) -> Float) {
    val type = source.type.uppercase()
    val audioOnly = type == "AUDIO_INPUT" || type == "PLAYBACK_AUDIO"
    Column(Modifier.fillMaxWidth()) {
        if (runtimeError != null) {
            SelectionContainer {
                Text(
                    runtimeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp)).padding(10.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (audioOnly) {
            val peakId = if (type == "PLAYBACK_AUDIO") NativeAudioBridge.PLAYBACK_SOURCE_ID else source.id
            var level by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(peakId) {
                while (true) {
                    val p = peakProvider(peakId).coerceIn(0f, 1f)
                    val db = if (p <= 0.00001f) -60f else (20f * log10(p)).coerceAtLeast(-60f)
                    level = maxOf((db + 60f) / 60f, level - 0.04f)
                    delay(50)
                }
            }
            Text("Input level", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(10.dp))
            Text(if (level <= 0.01f) "No sound reaching the app yet. Make some noise; if it stays empty, check the device and the error above." else "Receiving audio.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box(Modifier.fillMaxWidth().height(200.dp).background(Color(0xFF141419), RoundedCornerShape(6.dp))) {
                SoloSurface(source.id, Modifier.fillMaxSize())
            }
            Text("Live preview of this source (as currently applied; press Apply to see changes).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SoloSurface(sourceId: String, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { ctx ->
        TextureView(ctx).apply {
            var surface: Surface? = null
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { surface = Surface(t); NativeEngine.setSoloPreview(sourceId, surface) }
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) { NativeEngine.setSoloPreview(sourceId, surface) }
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { NativeEngine.setSoloPreview(null, null); surface?.release(); surface = null; return true }
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
            }
        }
    })
    DisposableEffect(sourceId) { onDispose { NativeEngine.setSoloPreview(null, null) } }
}
