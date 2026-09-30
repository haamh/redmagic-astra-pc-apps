package com.stream4k60.app.ui.main.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.ScreenshotController

@Composable
fun NativePreviewSurface(modifier:Modifier=Modifier){
    AndroidView(modifier=modifier,factory={ctx->SurfaceView(ctx).apply{
        ScreenshotController.register(this)
        holder.addCallback(object:SurfaceHolder.Callback{
            override fun surfaceCreated(h:SurfaceHolder){NativeEngine.setPreviewSurface(h.surface);NativeEngine.startRenderer()}
            override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,hgt:Int){NativeEngine.setPreviewSurface(h.surface)}
            override fun surfaceDestroyed(h:SurfaceHolder){NativeEngine.setPreviewSurface(null)}
        })
    }},update={ScreenshotController.register(it)})
    DisposableEffect(Unit){onDispose{NativeEngine.setPreviewSurface(null)}}
}
