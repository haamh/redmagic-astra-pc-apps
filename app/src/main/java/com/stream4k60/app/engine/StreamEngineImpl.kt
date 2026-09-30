package com.stream4k60.app.engine

import android.content.Context
import com.stream4k60.app.data.model.*
import com.stream4k60.app.youtube.YouTubeAuthSession
import com.stream4k60.app.youtube.YouTubeService
import com.stream4k60.app.service.RecordingService
import com.stream4k60.app.service.StreamingService
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamEngineImpl @Inject constructor(@ApplicationContext private val context:Context):StreamEngine{
    private val _streamState=MutableStateFlow(StreamState.IDLE);override val streamState:StateFlow<StreamState> = _streamState.asStateFlow()
    private val _recordState=MutableStateFlow(RecordState.IDLE);override val recordState:StateFlow<RecordState> = _recordState.asStateFlow()
    private val _streamStats=MutableStateFlow(StreamStats());override val streamStats:StateFlow<StreamStats> = _streamStats.asStateFlow()
    private val _recordStats=MutableStateFlow(RecordStats());override val recordStats:StateFlow<RecordStats> = _recordStats.asStateFlow()
    private val _replayBufferActive=MutableStateFlow(false);override val replayBufferActive:StateFlow<Boolean> = _replayBufferActive.asStateFlow()
    private var session:StreamOutputSession?=null
    private var activeStream:StreamConfig?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var statsJob:Job?=null

    /** Publisher events after the stream went live: a dropped connection shows as RECONNECTING, giving up as ERROR. */
    private fun onPublisherState(state:RtmpPublisher.State,message:String){
        val live=_streamState.value==StreamState.LIVE||_streamState.value==StreamState.RECONNECTING
        if(!live)return
        when(state){
            RtmpPublisher.State.CONNECTING->_streamState.value=StreamState.RECONNECTING
            RtmpPublisher.State.PUBLISHING->_streamState.value=StreamState.LIVE
            RtmpPublisher.State.ERROR->{
                Timber.w("Stream ended: %s",message)
                scope.launch{
                    statsJob?.cancel()
                    runCatching{session?.stop()};session=null;activeStream=null
                    StreamingService.stop(context)
                    _streamState.value=StreamState.ERROR
                }
            }
            else->Unit
        }
    }

    private fun startStats(){
        statsJob?.cancel()
        statsJob=scope.launch{
            val started=System.currentTimeMillis()
            var last=session?.encodedBytes()?:0L
            while(isActive){
                delay(1000)
                val s=session?:break
                val now=s.encodedBytes()
                _streamStats.value=StreamStats(bitrate=(now-last)*8,duration=System.currentTimeMillis()-started,totalBytes=now)
                last=now
            }
        }
    }

    override suspend fun startStreaming(config:StreamConfig){
        require(config.service==StreamService.YOUTUBE || config.service==StreamService.CUSTOM){"This release supports YouTube and custom RTMP(S) destinations"}
        require(config.ingestionUrl.isNotBlank()){if(config.service==StreamService.YOUTUBE)"Select a YouTube broadcast first" else "Enter a custom RTMP(S) ingest server URL"}
        require(if(config.service==StreamService.YOUTUBE) config.protocol==StreamProtocol.RTMPS||config.protocol==StreamProtocol.HLS else config.protocol==StreamProtocol.RTMP||config.protocol==StreamProtocol.RTMPS){"Unsupported streaming protocol for this destination"}
        require(config.outputWidth in 320..3840 && config.outputHeight in 240..2160 && config.fps in 1..120){"Streaming output is limited to 3840 × 2160 at 120 FPS"}
        if(config.service==StreamService.YOUTUBE) require(config.fps<=60){"YouTube Live supports up to 60 FPS. Choose a custom RTMP(S) destination for a service that accepts 120 FPS."}
        if(_streamState.value==StreamState.LIVE)return
        _streamState.value=StreamState.CONNECTING
        ContextCompat.startForegroundService(context,android.content.Intent(context,StreamingService::class.java))
        withContext(Dispatchers.IO){
            runCatching{
                val s=session
                if(s==null){session=StreamOutputSession(context,::onPublisherState);session!!.prepareAndStart(config)} else error("An output session is already running; stop the existing stream/recording before changing the encoder format")
                check(session!!.awaitPublisherReady(if(config.protocol==StreamProtocol.HLS)15_000 else 20_000)){"YouTube ingestion did not become ready"}
                val token=YouTubeAuthSession.accessToken
                if(config.service==StreamService.YOUTUBE&&!config.broadcastId.isNullOrBlank()&&token!=null){
                    val yt=YouTubeService{YouTubeAuthSession.accessToken}
                    runCatching{yt.transitionBroadcast(config.broadcastId,"testing")}.onFailure{Timber.w(it,"YouTube testing transition failed")}
                    val streamId=yt.listBroadcasts().firstOrNull{it.id==config.broadcastId}?.streamId
                    if(!streamId.isNullOrBlank()){
                        var active=false
                        repeat(15){
                            if(yt.streamIsActive(streamId)){active=true;return@repeat}
                            delay(1000)
                        }
                        check(active){"YouTube did not report the live stream as active"}
                    }
                    yt.transitionBroadcast(config.broadcastId,"live")
                }
                activeStream=config;_streamState.value=StreamState.LIVE;startStats()
            }.onFailure{
                Timber.e(it,"Stream start failed");session?.stop();session=null;activeStream=null;StreamingService.stop(context);_streamState.value=StreamState.ERROR;throw it
            }
        }
    }

    override suspend fun stopStreaming(){
        _streamState.value=StreamState.STOPPING
        statsJob?.cancel();_streamStats.value=StreamStats()
        withContext(Dispatchers.IO){
            val id=activeStream?.broadcastId
            val s=session
            if(_recordState.value==RecordState.RECORDING||_recordState.value==RecordState.PAUSED){s?.stopStreamingOnly()}else{s?.stop();session=null}
            activeStream=null
            if(!id.isNullOrBlank()&&YouTubeAuthSession.accessToken!=null)runCatching{YouTubeService{YouTubeAuthSession.accessToken}.transitionBroadcast(id,"complete")}
            StreamingService.stop(context)
        }
        _streamState.value=StreamState.IDLE
    }

    override suspend fun startRecording(config:RecordingConfig){
        val path=config.outputPath.ifBlank{java.io.File(context.getExternalFilesDir("recordings"),"recording_${System.currentTimeMillis()}.mp4").apply{parentFile?.mkdirs()}.absolutePath}
        val actual=config.copy(outputPath=path)
        withContext(Dispatchers.IO){
            runCatching{
                if(session==null){session=StreamOutputSession(context);session!!.prepareAndStart(actual)}else{session!!.enableRecording(actual)}
                ContextCompat.startForegroundService(context,android.content.Intent(context,RecordingService::class.java))
                _recordState.value=RecordState.RECORDING
            }.onFailure{_recordState.value=RecordState.ERROR;Timber.e(it,"Recording start failed");throw it}
        }
    }

    override suspend fun stopRecording(){
        _recordState.value=RecordState.STOPPING
        withContext(Dispatchers.IO){session?.disableRecording();if(_streamState.value==StreamState.IDLE){session?.stop();session=null} ; RecordingService.stop(context)}
        _recordState.value=RecordState.IDLE
    }
    override suspend fun pauseRecording(){if(_recordState.value==RecordState.RECORDING){session?.pauseRecording();_recordState.value=RecordState.PAUSED}}
    override suspend fun resumeRecording(){if(_recordState.value==RecordState.PAUSED){session?.resumeRecording();_recordState.value=RecordState.RECORDING}}
    override suspend fun startReplayBuffer(maxSeconds:Int,maxSizeMb:Int){if(session==null)error("Start streaming or recording before enabling Replay Buffer");session!!.startReplayBuffer(maxSeconds,maxSizeMb);_replayBufferActive.value=true}
    override suspend fun stopReplayBuffer(){session?.stopReplayBuffer()?:ReplayBufferController.stop();_replayBufferActive.value=false}
    override suspend fun saveReplayBuffer(){ReplayBufferController.save(context)}
    override suspend fun takeScreenshot(outputPath:String){ScreenshotController.request(outputPath)}
    override fun updateAudioRoute(route:AudioInputRoute):Boolean = session?.updateAudioRoute(route) ?: false
    override fun audioPeak(sourceId:String):Float = session?.audioPeak(sourceId) ?: NativeAudioGraph.peak(sourceId)
}
