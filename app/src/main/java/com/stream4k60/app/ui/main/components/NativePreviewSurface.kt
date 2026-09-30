package com.stream4k60.app.ui.main.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.ScreenshotController

/**
 * The compositor's preview output. A TextureView (not SurfaceView) so it is clipped and layered like any other
 * view: when the preview is zoomed, the canvas stays inside the preview area instead of drawing over the docks.
 */
@Composable
fun NativePreviewSurface(modifier:Modifier=Modifier){
    AndroidView(modifier=modifier,factory={ctx->TextureView(ctx).apply{
        ScreenshotController.register(this)
        var surface:Surface?=null
        surfaceTextureListener=object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){surface=Surface(t);NativeEngine.setPreviewSurface(surface);NativeEngine.startRenderer()}
            override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){NativeEngine.setPreviewSurface(surface)}
            override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean{NativeEngine.setPreviewSurface(null);surface?.release();surface=null;return true}
            override fun onSurfaceTextureUpdated(t:SurfaceTexture){}
        }
    }},update={ScreenshotController.register(it)})
    DisposableEffect(Unit){onDispose{NativeEngine.setPreviewSurface(null)}}
}
