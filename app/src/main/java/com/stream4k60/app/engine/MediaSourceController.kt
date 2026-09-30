package com.stream4k60.app.engine

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MediaSourceController(private val context:Context,private val scope:CoroutineScope){
    private val players=ConcurrentHashMap<String,ExoPlayer>()
    /** The media URL alone determines whether the decoder/player has to be rebuilt. Mixer and
     * presentation controls are applied live and must not interrupt playback while a slider moves. */
    private val mediaUris=ConcurrentHashMap<String,String>()
    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>){
        val wanted=sources.filter{it.isVisible&&it.type.equals("MEDIA",true)}
        val ids=wanted.map{it.id}.toSet()
        (mediaUris.keys+players.keys).filterNot{it in ids}.toSet().forEach(::stop)
        wanted.forEach{src->
            val cfg=settings(src.configJson)
            val media=mediaItemsFromConfig(cfg)
            val identity="${org.json.JSONArray(media)}|decoder=${cfg.optString("decoderPreference","hardware")}"
            if(mediaUris[src.id]!=null && mediaUris[src.id]!=identity) stop(src.id)
            val player=players[src.id]
            if(player==null && mediaUris[src.id]==null)start(src,cfg,media,identity)
            else if(player!=null){
                player.repeatMode=if(cfg.optBoolean("loop",true))Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                player.playbackParameters=PlaybackParameters(cfg.optDouble("playbackSpeed",1.0).toFloat().coerceIn(0.25f,4f))
                NativeEngine.setSourceBufferSize(src.id,cfg.optInt("width",1920),cfg.optInt("height",1080))
                NativeEngine.setSourceEffectsFromConfig(src.id,src.configJson)
            }
        }
    }
    private fun settings(configJson:String)=runCatching{JSONObject(configJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(JSONObject())
    private fun mediaItemsFromConfig(cfg:JSONObject):List<String>{
        cfg.optString("url").takeIf(String::isNotBlank)?.let{return listOf(it)}
        val playlist=cfg.optJSONArray("playlist")?.let{items->(0 until items.length()).mapNotNull{items.optString(it).takeIf(String::isNotBlank)}}.orEmpty()
        return playlist.ifEmpty{listOf(cfg.optString("file").ifBlank{cfg.optString("local_file")}).filter(String::isNotBlank)}
    }
    private fun start(src:com.stream4k60.app.ui.main.SourceItem,cfg:JSONObject,media:List<String>,identity:String){
        mediaUris[src.id]=identity
        scope.launch(Dispatchers.Main.immediate){
            if(media.isEmpty()){SourceRuntimeErrors.report(src.id,"Choose a media file or enter a media URL in source properties.");return@launch}
            if(mediaUris[src.id]!=identity)return@launch
            val surface=NativeEngine.createSourceSurface(src.id)?:run{SourceRuntimeErrors.report(src.id,"Could not create the media source compositor surface.");return@launch}
            NativeEngine.setSourceEffectsFromConfig(src.id,src.configJson)
            NativeEngine.setSourceBufferSize(src.id,cfg.optInt("width",1920),cfg.optInt("height",1080))
            val p=runCatching{
                val audioId="media_audio_${src.id}"
                val audioProcessor=MediaAudioProcessor(audioId)
                val decoderPreference=cfg.optString("decoderPreference","hardware")
                ExoPlayer.Builder(context,MediaSourceRenderersFactory(context,audioProcessor,decoderPreference)).build().apply{
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            SourceRuntimeErrors.report(src.id,"Media playback failed: ${error.errorCodeName}.")
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) SourceRuntimeErrors.clear(src.id)
                        }
                    })
                    setMediaItems(media.map(MediaItem::fromUri))
                    repeatMode=if(cfg.optBoolean("loop",true))Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    playbackParameters=PlaybackParameters(cfg.optDouble("playbackSpeed",1.0).toFloat().coerceIn(0.25f,4f))
                    // Decoded PCM is routed through the studio mixer instead of playing directly twice.
                    volume=0f
                    val startPosition=cfg.optLong("startPositionMs",0).coerceAtLeast(0)
                    if(startPosition>0)seekTo(startPosition)
                    setVideoSurface(surface);prepare();playWhenReady=true
                }
            }.getOrElse{NativeEngine.releaseSourceSurface(src.id);SourceRuntimeErrors.report(src.id,"Could not start media playback: ${it.message ?: "unsupported media or unavailable file"}.");return@launch}
            if(mediaUris[src.id]==identity)players[src.id]=p else {p.release();NativeEngine.releaseSourceSurface(src.id)}
        }
    }
    fun stop(id:String){mediaUris.remove(id);players.remove(id)?.let{runCatching{it.stop()};runCatching{it.release()}};NativeEngine.releaseSourceSurface(id);SourceRuntimeErrors.clear(id)}
    fun stopAll(){players.keys.toList().forEach(::stop)}
}
