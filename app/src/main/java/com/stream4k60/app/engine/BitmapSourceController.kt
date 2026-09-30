package com.stream4k60.app.engine

import android.graphics.*
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError

/** CPU-bound only for small/overlay sources; frame is uploaded once into the GPU compositor. */
class BitmapSourceController(private val context: Context, private val scope:CoroutineScope){
    private val jobs=ConcurrentHashMap<String,Job>()
    private val sourceFingerprints=ConcurrentHashMap<String,String>()
    private val sourceStateLock=Any()

    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>){
        val wanted=sources.filter{it.isVisible && it.type.uppercase() in setOf("IMAGE","TEXT","COLOR","IMAGE_SLIDESHOW")}
        val ids=wanted.map{it.id}.toSet()
        synchronized(sourceStateLock){
            (sourceFingerprints.keys + jobs.keys).filterNot{it in ids}.toSet().forEach{id->
                sourceFingerprints.remove(id)
                jobs.remove(id)?.cancel()
                NativeEngine.removeSourceLayer(id)
                SourceRuntimeErrors.clear(id)
            }
            wanted.forEach{src->
                val fingerprint="${src.type}:${src.name}:${src.configJson}"
                if(sourceFingerprints[src.id]!=fingerprint){
                    sourceFingerprints[src.id]=fingerprint
                    jobs.remove(src.id)?.cancel()
                    jobs[src.id]=scope.launch(Dispatchers.Default){renderOnce(src,fingerprint)}
                }
            }
        }
    }
    fun stop(){
        synchronized(sourceStateLock){
            val ids=(sourceFingerprints.keys+jobs.keys).toSet()
            jobs.values.forEach{it.cancel()};jobs.clear();sourceFingerprints.clear()
            ids.forEach(NativeEngine::removeSourceLayer)
            ids.forEach(SourceRuntimeErrors::clear)
        }
    }

    private suspend fun renderOnce(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String){
        val cfg=runCatching{JSONObject(src.configJson).optJSONObject("settings")?:JSONObject(src.configJson)}.getOrElse{JSONObject()}
        var frame:Pair<ByteArray,Pair<Int,Int>>?=null
        when(src.type.uppercase()){
            "COLOR"->{
                val color=runCatching{Color.parseColor(cfg.optString("color","#FF000000"))}.getOrNull()
                if(color==null){SourceRuntimeErrors.report(src.id,"The color source contains an invalid color. Edit its properties and choose a valid color.");removeLayerIfCurrent(src.id,fingerprint);return}
                val w=cfg.optInt("width",1280).coerceIn(1,7680);val h=cfg.optInt("height",720).coerceIn(1,4320)
                frame=rgbaBitmap(src.name,w,h){canvas->canvas.drawColor(color)}
            }
            "TEXT"->{
                val text=cfg.optString("text",src.name);val w=cfg.optInt("width",1280).coerceIn(1,4096);val h=cfg.optInt("height",720).coerceIn(1,4096)
                val rawTextColor=cfg.optString("textColor","#FFFFFFFF")
                val rawBackgroundColor=cfg.optString("backgroundColor","#00000000")
                val textColor=runCatching{Color.parseColor(rawTextColor)}.getOrNull()
                val background=if(rawBackgroundColor.equals("transparent",true))Color.TRANSPARENT else runCatching{Color.parseColor(rawBackgroundColor)}.getOrNull()
                if(textColor==null||background==null){SourceRuntimeErrors.report(src.id,"The text source contains an invalid color. Edit its properties and choose valid colors.");removeLayerIfCurrent(src.id,fingerprint);return}
                frame=rgbaBitmap(text,w,h){canvas->
                    canvas.drawColor(background,PorterDuff.Mode.SRC)
                    val style=(if(cfg.optBoolean("bold",false))Typeface.BOLD else Typeface.NORMAL) or (if(cfg.optBoolean("italic",false))Typeface.ITALIC else 0)
                    val align=when(cfg.optString("alignment","LEFT").uppercase()){"CENTER"->Paint.Align.CENTER;"RIGHT"->Paint.Align.RIGHT;else->Paint.Align.LEFT}
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=cfg.optDouble("fontSize",64.0).toFloat().coerceIn(1f,512f);typeface=Typeface.create(cfg.optString("fontFamily","sans-serif"),style);color=textColor;textAlign=align}
                    val x=when(align){Paint.Align.CENTER->w/2f;Paint.Align.RIGHT->w-24f;else->24f}
                    val lines=text.split('\n');val lineHeight=paint.fontSpacing
                    lines.forEachIndexed{index,line->canvas.drawText(line,x,24f-paint.ascent()+index*lineHeight,paint)}
                }
            }
            "IMAGE_SLIDESHOW"->{
                renderSlideshow(src,fingerprint,cfg)
                return
            }
            "IMAGE"->{
                val path=cfg.optString("file").ifBlank{cfg.optString("path")}
                if(path.isBlank()){SourceRuntimeErrors.report(src.id,"Choose an image file in source properties.");removeLayerIfCurrent(src.id,fingerprint);return}
                val bitmap=decodeBitmap(path)
                if(bitmap==null){SourceRuntimeErrors.report(src.id,"The image file could not be opened. Re-select it or relink the asset.");removeLayerIfCurrent(src.id,fingerprint);return}
                frame=bitmap.toRgba();bitmap.recycle()
            }
        }
        frame?.let{(rgba,size)->uploadIfCurrent(src.id,fingerprint,src.configJson,rgba,size.first,size.second)}
    }

    private fun uploadIfCurrent(id:String,fingerprint:String,configJson:String,rgba:ByteArray,width:Int,height:Int){
        synchronized(sourceStateLock){
            if(sourceFingerprints[id]==fingerprint){
                if(NativeEngine.updateSourceRgba(id,rgba,width,height)) {
                    NativeEngine.setSourceEffectsFromConfig(id,configJson)
                    SourceRuntimeErrors.clear(id)
                } else SourceRuntimeErrors.report(id,"The image or overlay could not be uploaded to the compositor.")
            }
        }
    }

    private fun removeLayerIfCurrent(id:String,fingerprint:String){
        synchronized(sourceStateLock){if(sourceFingerprints[id]==fingerprint)NativeEngine.removeSourceLayer(id)}
    }

    private suspend fun renderSlideshow(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String,cfg:JSONObject){
        val configured=cfg.optJSONArray("files")
        val paths=if(configured!=null)(0 until configured.length()).mapNotNull{configured.optString(it).takeIf(String::isNotBlank)} else emptyList()
        if(paths.isEmpty()){
            val path=cfg.optString("file")
            if(path.isNotBlank()) {
                val bitmap = decodeBitmap(path)
                if (bitmap != null) {
                    val frame = bitmap.toRgba()
                    bitmap.recycle()
                    uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,frame.second.first,frame.second.second)
                } else SourceRuntimeErrors.report(src.id,"The slideshow image could not be opened. Re-select it or relink the asset.")
            } else SourceRuntimeErrors.report(src.id,"Add at least one slideshow image in source properties.")
            removeLayerIfCurrent(src.id,fingerprint)
            return
        }
        val ordered=paths.toMutableList()
        if(cfg.optBoolean("randomize",false))ordered.shuffle()
        var index=0
        while(kotlinx.coroutines.currentCoroutineContext().isActive && sourceFingerprints[src.id]==fingerprint){
            val bitmap=withContext(Dispatchers.IO){decodeBitmap(ordered[index])}
            if(bitmap!=null){val frame=bitmap.toRgba();bitmap.recycle();uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,frame.second.first,frame.second.second)}
            else SourceRuntimeErrors.report(src.id,"A slideshow image could not be opened. Re-select or relink the file.")
            index++
            if(index>=ordered.size){
                if(!cfg.optBoolean("loop",true))return
                index=0
                if(cfg.optBoolean("randomize",false))ordered.shuffle()
            }
            kotlinx.coroutines.delay(cfg.optLong("slideIntervalSeconds",5L).coerceIn(1L,3600L)*1000L)
        }
    }

    private fun decodeBitmap(path:String):Bitmap? = runCatching {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        openBitmapInput(path)?.use{BitmapFactory.decodeStream(it,null,bounds)}
            ?: return@runCatching null
        if(bounds.outWidth<=0||bounds.outHeight<=0)return@runCatching null
        var sample=1
        while((bounds.outWidth.toLong()/(sample*2L))*(bounds.outHeight.toLong()/(sample*2L))>MAX_DECODE_PIXELS)sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        openBitmapInput(path)?.use{BitmapFactory.decodeStream(it,null,options)}
    }.getOrNull()

    private fun openBitmapInput(path:String):InputStream? {
        val uri=Uri.parse(path)
        return when(uri.scheme){
            "content"->context.contentResolver.openInputStream(uri)
            "file"->uri.path?.let(::File)?.takeIf(File::isFile)?.let(::FileInputStream)
            else->File(path).absoluteFile.takeIf(File::isFile)?.let(::FileInputStream)
        }
    }

    private companion object { const val MAX_DECODE_PIXELS=3_145_728L }

    private fun rgbaBitmap(tag:String,w:Int,h:Int,draw:(Canvas)->Unit):Pair<ByteArray,Pair<Int,Int>>{val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b);draw(c);return b.toRgba().also{b.recycle()}}

    private fun Bitmap.toRgba():Pair<ByteArray,Pair<Int,Int>>{val px=IntArray(width*height);getPixels(px,0,width,0,0,width,height);val out=ByteArray(px.size*4);var o=0;for(v in px){out[o++]=(v ushr 24).toByte();out[o++]=(v ushr 16).toByte();out[o++]=(v ushr 8).toByte();out[o++]=v.toByte()};return out to (width to height)}
}

class BrowserSourceController(private val context:android.content.Context){
    private val main=Handler(Looper.getMainLooper());private val views=ConcurrentHashMap<String,android.webkit.WebView>();private val running=ConcurrentHashMap.newKeySet<String>()
    private val surfaces=ConcurrentHashMap<String,android.view.Surface>()
    private val configs=ConcurrentHashMap<String,String>();private val tickers=ConcurrentHashMap<String,Runnable>()
    private val refreshCounters=ConcurrentHashMap<String,Int>()
    private val cssConfigs=ConcurrentHashMap<String,String>()
    private var host:android.widget.FrameLayout?=null
    fun attachHost(parent:android.view.ViewGroup){if(host!=null)return;host=android.widget.FrameLayout(context).apply{layoutParams=android.widget.FrameLayout.LayoutParams(1,1);alpha=0f};parent.addView(host)}
    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>){
        val wanted=sources.filter{it.isVisible&&it.type.equals("BROWSER",true)}.map{it.id}.toSet()
        views.keys.filterNot{it in wanted}.forEach(::stop)
        running.retainAll(wanted)
        sources.filter{it.id in wanted}.forEach{src->
            val c=runCatching{JSONObject(src.configJson).optJSONObject("settings")?:JSONObject(src.configJson)}.getOrDefault(JSONObject())
            val pageUrl=c.optString("url").takeIf{it.startsWith("http://",true)||it.startsWith("https://",true)}?:c.optString("file").ifBlank{c.optString("url","about:blank")}
            configure(src,pageUrl,c.optInt("width",1280),c.optInt("height",720),c.optInt("fps",30),c.optBoolean("javaScript",true),c.optBoolean("hardwareAccelerated",true),c.optString("customCss",""),c.optInt("refreshToken",0))
        }
    }
    fun configure(source:com.stream4k60.app.ui.main.SourceItem,url:String,width:Int=1280,height:Int=720,fps:Int=30,javaScript:Boolean=true,hardwareAccelerated:Boolean=true,customCss:String="",refreshToken:Int=0){
        val h=host?:return
        val requestedWidth=width.coerceAtLeast(16);val requestedHeight=height.coerceAtLeast(16)
        val maxWidth=if(hardwareAccelerated)2400 else 1280
        val maxHeight=if(hardwareAccelerated)1504 else 720
        val maxFps=if(hardwareAccelerated)60 else 10
        val scale=minOf(1f,maxWidth.toFloat()/requestedWidth,maxHeight.toFloat()/requestedHeight)
        val w=(requestedWidth*scale).toInt().coerceIn(16,maxWidth);val heightSafe=(requestedHeight*scale).toInt().coerceIn(16,maxHeight);val rate=fps.coerceIn(1,maxFps)
        val signature="$url|$w|$heightSafe|$rate|$javaScript|$hardwareAccelerated|$customCss|$refreshToken"
        if(configs[source.id]==signature&&views.containsKey(source.id))return
        configs[source.id]=signature
        val previousRefresh=refreshCounters.put(source.id,refreshToken)
        cssConfigs[source.id]=customCss
        tickers.remove(source.id)?.let(main::removeCallbacks)
        val currentSurface=surfaces[source.id]
        if(hardwareAccelerated&&currentSurface==null){
            NativeEngine.removeSourceLayer(source.id)
            val surface=NativeEngine.createSourceSurface(source.id)?:run{SourceRuntimeErrors.report(source.id,"Could not create the browser's GPU compositor surface.");return}
            NativeEngine.setSourceBufferSize(source.id,w,heightSafe)
            surfaces[source.id]=surface
        }else if(!hardwareAccelerated&&currentSurface!=null){
            surfaces.remove(source.id)?.release()
            NativeEngine.removeSourceLayer(source.id)
        }else if(hardwareAccelerated){
            NativeEngine.setSourceBufferSize(source.id,w,heightSafe)
        }
        val web=views[source.id]?:WebView(context).also{view->
            view.setBackgroundColor(Color.TRANSPARENT)
            view.layoutParams=android.widget.FrameLayout.LayoutParams(w,heightSafe)
            view.webViewClient=object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if(request.isForMainFrame)SourceRuntimeErrors.report(source.id,"Browser page failed to load: ${error.description}.")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    injectCustomCss(view, cssConfigs[source.id].orEmpty())
                    SourceRuntimeErrors.clear(source.id)
                }
            }
            h.addView(view);views[source.id]=view
        }
        web.layoutParams=android.widget.FrameLayout.LayoutParams(w,heightSafe)
        web.setLayerType(if(hardwareAccelerated)android.view.View.LAYER_TYPE_HARDWARE else android.view.View.LAYER_TYPE_SOFTWARE,null)
        web.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(heightSafe,android.view.View.MeasureSpec.EXACTLY))
        web.layout(0,0,w,heightSafe)
        web.settings.javaScriptEnabled=javaScript
        web.settings.domStorageEnabled=true
        web.settings.mediaPlaybackRequiresUserGesture=false
        if(web.url!=url)web.loadUrl(url)
        else if(previousRefresh!=null&&previousRefresh!=refreshToken)web.reload()
        else injectCustomCss(web,customCss)
        running.add(source.id)
        lateinit var ticker:Runnable
        ticker=Runnable {
            if(!running.contains(source.id)||tickers[source.id]!==ticker)return@Runnable
            web.post {
                if(running.contains(source.id)&&tickers[source.id]===ticker){
                    runCatching {
                        val outputSurface=surfaces[source.id]
                        if(outputSurface!=null){
                            val canvas=outputSurface.lockHardwareCanvas()
                            try {
                                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                                web.draw(canvas)
                            } finally {
                                outputSurface.unlockCanvasAndPost(canvas)
                            }
                            NativeEngine.setSourceEffectsFromConfig(source.id,source.configJson)
                        }else{
                            val bitmap=Bitmap.createBitmap(w,heightSafe,Bitmap.Config.ARGB_8888)
                            web.draw(Canvas(bitmap))
                            val rgba=bitmap.toRgba();bitmap.recycle()
                            if(NativeEngine.updateSourceRgba(source.id,rgba.first,w,heightSafe))NativeEngine.setSourceEffectsFromConfig(source.id,source.configJson)
                            else SourceRuntimeErrors.report(source.id,"Browser frames could not be uploaded to the compositor.")
                        }
                    }.onFailure { SourceRuntimeErrors.report(source.id,"Browser frame rendering failed: ${it.message ?: "WebView capture error"}.") }
                    if(running.contains(source.id)&&tickers[source.id]===ticker)main.postDelayed(ticker,(1000L/rate).coerceAtLeast(16))
                }
            }
        }
        tickers[source.id]=ticker
        main.post(ticker)
    }
    fun stop(sourceId:String){running.remove(sourceId);configs.remove(sourceId);refreshCounters.remove(sourceId);cssConfigs.remove(sourceId);tickers.remove(sourceId)?.let(main::removeCallbacks);views.remove(sourceId)?.let{v->(v.parent as? android.view.ViewGroup)?.removeView(v);v.destroy()};surfaces.remove(sourceId)?.release();NativeEngine.removeSourceLayer(sourceId);SourceRuntimeErrors.clear(sourceId)}
    fun stopAll(){views.keys.toList().forEach(::stop);host?.let{(it.parent as? android.view.ViewGroup)?.removeView(it)};host=null}
    private fun Bitmap.toRgba():Pair<ByteArray,Pair<Int,Int>>{val px=IntArray(width*height);getPixels(px,0,width,0,0,width,height);val out=ByteArray(px.size*4);var o=0;for(v in px){out[o++]=(v ushr 24).toByte();out[o++]=(v ushr 16).toByte();out[o++]=(v ushr 8).toByte();out[o++]=v.toByte()};return out to (width to height)}
    private fun injectCustomCss(view:WebView,css:String){
        val quoted=JSONObject.quote(css)
        view.evaluateJavascript("(function(){var s=document.getElementById('__stream4k_custom_css');if(!s){s=document.createElement('style');s.id='__stream4k_custom_css';document.head.appendChild(s);}s.textContent=$quoted;})();",null)
    }
}
