package com.stream4k60.app.ui.main

import com.stream4k60.app.engine.HotkeyDispatcher
import com.stream4k60.app.engine.AudioFilterChain
import com.stream4k60.app.profile.ImportSelection
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.local.entity.SceneEntity
import com.stream4k60.app.data.local.entity.SceneCollectionEntity
import com.stream4k60.app.data.local.entity.SourceEntity
import com.stream4k60.app.data.model.*
import com.stream4k60.app.data.repository.SceneRepository
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.StreamEngine
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.NativeAudioGraph
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.engine.SourceRuntimeErrors
import com.stream4k60.app.engine.StreamState
import com.stream4k60.app.engine.RecordState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class SceneItem(val id:String,val name:String,val isActive:Boolean)
data class SourceItem(val id:String,val name:String,val type:String,val isVisible:Boolean,val isLocked:Boolean,val configJson:String,val transformJson:String="{}")
/**
 * A source as the compositor draws it. [owner] is "" for the program canvas, or the key of the nested scene /
 * group canvas it draws into. [containerKey] is set for SCENE and GROUP sources: the canvas they show.
 */
data class RenderSource(val item:SourceItem,val owner:String,val z:Int,val containerKey:String?)
object NestedSources {
 const val GROUP_PREFIX="group:"
 const val MAX_DEPTH=6
 val CONTAINER_TYPES=setOf("SCENE","GROUP")
 /** Canvas key a SCENE/GROUP source shows; group children are stored under this key as their sceneId. */
 fun containerKey(item:SourceItem):String? = when(item.type.uppercase()){
  "GROUP"->GROUP_PREFIX+item.id
  "SCENE"->runCatching{org.json.JSONObject(item.configJson).let{it.optJSONObject("settings")?:it}.optString("sceneId")}.getOrNull()?.takeIf{it.isNotBlank()}
  else->null
 }
}
enum class StudioStreamState{IDLE,CONNECTING,LIVE,RECONNECTING,STOPPING,ERROR}
enum class StudioRecordState{IDLE,RECORDING,PAUSED,STOPPING,ERROR}

@HiltViewModel class MainStudioViewModel @Inject constructor(private val repo:SceneRepository,private val engine:StreamEngine,private val settingsRepository:SettingsRepository,@ApplicationContext private val context:Context):ViewModel(){
 private val sourceTransformMutex=Mutex()
 private val audioSettingsState=settingsRepository.audioSettings.stateIn(viewModelScope,SharingStarted.Eagerly,AudioSettings())
 val advancedSettings=settingsRepository.advancedSettings.stateIn(viewModelScope,SharingStarted.Eagerly,AdvancedSettings())
 @Volatile private var pushToTalkHeld=false
 private val _audioItems=MutableStateFlow<List<SourceItem>>(emptyList())
 /** Everything with audio the program uses: scene sources, nested content and the global Desktop/Mic sources. */
 val audioItems=_audioItems.asStateFlow()

 private val _renderSources=MutableStateFlow<List<RenderSource>>(emptyList());val renderSources=_renderSources.asStateFlow()
 /** Items inside each group of the active scene (keyed by group source id), bottom layer first. */
 private val _groupChildren=MutableStateFlow<Map<String,List<SourceItem>>>(emptyMap());val groupChildren=_groupChildren.asStateFlow()
 @Volatile private var syncAudioGraphErrors: Set<String> = emptySet()
 private val collectionPrefs=context.getSharedPreferences("stream4k_studio",Context.MODE_PRIVATE)
 private val _currentSceneCollection=MutableStateFlow("Default");val currentSceneCollection=_currentSceneCollection.asStateFlow()
 private val _sceneCollections=MutableStateFlow<List<SceneCollectionEntity>>(emptyList());val sceneCollections=_sceneCollections.asStateFlow()
 private val _activeSceneCollectionId=MutableStateFlow<String?>(null);val activeSceneCollectionId=_activeSceneCollectionId.asStateFlow()
 private val _activeScene=MutableStateFlow<SceneItem?>(null);val activeScene=_activeScene.asStateFlow();private val _scenes=MutableStateFlow<List<SceneItem>>(emptyList());val scenes=_scenes.asStateFlow();private val _sources=MutableStateFlow<List<SourceItem>>(emptyList());val sources=_sources.asStateFlow();private val _isStudio=MutableStateFlow(false);val isStudioModeEnabled=_isStudio.asStateFlow();private val _transition=MutableStateFlow("Cut");val selectedTransition=_transition.asStateFlow();private val _streamConfig=MutableStateFlow(StreamConfig());val streamConfig=_streamConfig.asStateFlow();private val _recordConfig=MutableStateFlow(RecordingConfig());val recordConfig=_recordConfig.asStateFlow();private val _streamError=MutableStateFlow<String?>(null);val streamError=_streamError.asStateFlow()
 val streamState=engine.streamState.map{when(it){StreamState.IDLE->StudioStreamState.IDLE;StreamState.CONNECTING->StudioStreamState.CONNECTING;StreamState.LIVE->StudioStreamState.LIVE;StreamState.RECONNECTING->StudioStreamState.RECONNECTING;StreamState.STOPPING->StudioStreamState.STOPPING;StreamState.ERROR->StudioStreamState.ERROR}}.stateIn(viewModelScope,SharingStarted.Eagerly,StudioStreamState.IDLE)
 val recordState=engine.recordState.map{when(it){RecordState.IDLE->StudioRecordState.IDLE;RecordState.RECORDING->StudioRecordState.RECORDING;RecordState.PAUSED->StudioRecordState.PAUSED;RecordState.STOPPING->StudioRecordState.STOPPING;RecordState.ERROR->StudioRecordState.ERROR}}.stateIn(viewModelScope,SharingStarted.Eagerly,StudioRecordState.IDLE)
 val videoConfig=settingsRepository.videoConfig.stateIn(viewModelScope,SharingStarted.Eagerly,VideoConfig())
 val importedRtmpEndpoint=settingsRepository.importedRtmpEndpoint.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 init {
  // Global audio devices/levels and push-to-talk changes re-sync the native mixer.
  viewModelScope.launch { audioSettingsState.drop(1).collect { syncAudioGraph() } }
  // A live stream that ends on its own (reconnect gave up) explains why instead of silently going idle.
  viewModelScope.launch { var prev=StreamState.IDLE; engine.streamState.collect { st ->
   if(st==StreamState.ERROR&&(prev==StreamState.LIVE||prev==StreamState.RECONNECTING)) _streamError.value="Connection to the streaming server was lost and reconnecting failed. Check your internet, then start streaming again."
   prev=st } }
  viewModelScope.launch { settingsRepository.hotkeys.collect { HotkeyDispatcher.setBindings(it ?: HotkeyDispatcher.defaultBindings) } }
  viewModelScope.launch {
   ImportSelection.requestedCollectionId.filterNotNull().collect { id ->
    // The collections flow can lag the import's database writes.
    withTimeoutOrNull(5_000) { _sceneCollections.first { list -> list.any { it.id == id } } }
    selectSceneCollection(id)
    ImportSelection.requestedCollectionId.compareAndSet(id, null)
   }
  }
  viewModelScope.launch {
   if(repo.collections().first().isEmpty()) {
    val cid=UUID.randomUUID().toString()
    repo.saveCollection(SceneCollectionEntity(cid,"Default"))
    val sid=UUID.randomUUID().toString()
    repo.saveScene(SceneEntity(sid,cid,"Scene 1",0,true))
   }
   val collections=repo.collections().first()
   val preferredId=collectionPrefs.getString("activeCollectionId",null)
   val initial=collections.firstOrNull{it.id==preferredId} ?: collections.firstOrNull{it.name=="Default"} ?: collections.firstOrNull()
   initial?.let { _activeSceneCollectionId.value=it.id;_currentSceneCollection.value=it.name;collectionPrefs.edit().putString("activeCollectionId",it.id).apply();load(it) }
   repo.collections().collect { latest ->
    _sceneCollections.value=latest
    val selected=latest.firstOrNull{it.id==_activeSceneCollectionId.value} ?: latest.firstOrNull{it.name=="Default"} ?: latest.firstOrNull()
    if(selected!=null && selected.id!=_activeSceneCollectionId.value) {
     _activeSceneCollectionId.value=selected.id
     _currentSceneCollection.value=selected.name
     collectionPrefs.edit().putString("activeCollectionId",selected.id).apply()
     load(selected)
    }
   }
  }
 }
 private suspend fun load(collection:SceneCollectionEntity?=null){
  val all=repo.collections().first()
  _sceneCollections.value=all
  val c=collection ?: all.firstOrNull{it.id==_activeSceneCollectionId.value} ?: all.firstOrNull{it.name=="Default"} ?: all.firstOrNull() ?: return
  _activeSceneCollectionId.value=c.id;_currentSceneCollection.value=c.name
  val ss=repo.loadScenes(c.id);_scenes.value=ss.map{SceneItem(it.id,it.name,it.active)}
  val selectedScene=ss.firstOrNull{it.id==c.activeSceneId} ?: ss.firstOrNull{it.active} ?: ss.firstOrNull()
  _activeScene.value=selectedScene?.let{SceneItem(it.id,it.name,true)}
  val active=_activeScene.value;_sources.value=if(active!=null)repo.loadSources(active.id).map(::toItem)else emptyList()
  _renderSources.value=if(active!=null)expandRenderSources(_sources.value,active.id)else emptyList()
  _groupChildren.value=_sources.value.filter{it.type.equals("GROUP",true)}.associate{g->g.id to repo.loadSources(NestedSources.GROUP_PREFIX+g.id).map(::toItem)}
  syncAudioGraph()
 }
 private fun toItem(it:SourceEntity)=SourceItem(it.id,it.name,it.type,it.visible,it.locked,it.configJson,it.transformJson)
 /** Flattens the active scene plus every visible nested scene/group it shows, each nested canvas once. */
 private suspend fun expandRenderSources(top:List<SourceItem>,rootSceneId:String):List<RenderSource>{
  val out=mutableListOf<RenderSource>()
  val expanded=mutableSetOf<String>()
  suspend fun walk(items:List<SourceItem>,owner:String,depth:Int,path:Set<String>){
   items.forEachIndexed{z,item->
    val key=NestedSources.containerKey(item)
    out+=RenderSource(item,owner,z,key)
    if(key==null||!item.isVisible||depth>=NestedSources.MAX_DEPTH||key in path||!expanded.add(key))return@forEachIndexed
    walk(repo.loadSources(key).map(::toItem),key,depth+1,path+key)
   }
  }
  walk(top,"",0,setOf(rootSceneId))
  return out
 }
 /** Containers whose rows can be edited from the studio: the active scene and the groups it shows. */
 private fun editableContainers():List<String> = (listOfNotNull(_activeScene.value?.id)+
  _renderSources.value.mapNotNull{it.containerKey}+
  _groupChildren.value.keys.map{NestedSources.GROUP_PREFIX+it}).distinct()
 private suspend fun rowFor(id:String):SourceEntity?{
  for(container in editableContainers())repo.loadSources(container).firstOrNull{it.id==id}?.let{return it}
  return null
 }
 /** Deletes a source and, for groups, everything inside them. */
 private suspend fun deleteDeep(row:SourceEntity){
  if(row.type.equals("GROUP",true))repo.loadSources(NestedSources.GROUP_PREFIX+row.id).forEach{deleteDeep(it)}
  repo.deleteSource(row.id)
 }
 /** Copies a source into [container]; groups are copied with their contents. */
 private suspend fun copyDeep(row:SourceEntity,container:String,sortOrder:Int,name:String):SourceEntity{
  val copy=row.copy(id=UUID.randomUUID().toString(),sceneId=container,name=name,sortOrder=sortOrder,visible=true,locked=false)
  repo.saveSources(listOf(copy))
  if(row.type.equals("GROUP",true))repo.loadSources(NestedSources.GROUP_PREFIX+row.id).forEach{child->copyDeep(child,NestedSources.GROUP_PREFIX+copy.id,child.sortOrder,child.name)}
  return copy
 }
 /** Scenes that [sceneId] can show without creating a loop. */
 suspend fun nestableScenes():List<SceneItem>{
  val active=_activeScene.value?:return emptyList()
  val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return emptyList()
  val all=repo.loadScenes(c.id)
  suspend fun reaches(from:String,target:String,seen:MutableSet<String>):Boolean{
   if(from==target)return true
   if(!seen.add(from))return false
   return repo.loadSources(from).any{row->NestedSources.containerKey(toItem(row))?.let{reaches(it,target,seen)}==true}
  }
  return all.filter{it.id!=active.id&&!reaches(it.id,active.id,mutableSetOf())}.map{SceneItem(it.id,it.name,false)}
 }
 fun addSceneSource(sceneId:String){viewModelScope.launch{
  val s=_activeScene.value?:return@launch
  val name=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?.let{c->repo.loadScenes(c.id).firstOrNull{it.id==sceneId}?.name}?:"Scene"
  val rows=repo.loadSources(s.id)
  repo.saveSources(listOf(SourceEntity(UUID.randomUUID().toString(),s.id,name,"SCENE",rows.size,true,false,"{\"sceneId\":\"$sceneId\"}","{}","{}")))
  load()
 }}
 /** Moves a source into a group (keeping its canvas position when the group sits at the origin unscaled). */
 fun moveSourceIntoGroup(id:String,groupId:String){viewModelScope.launch{
  val row=rowFor(id)?:return@launch
  if(row.id==groupId)return@launch
  val container=NestedSources.GROUP_PREFIX+groupId
  // A group cannot contain itself or its ancestors.
  if(row.type.equals("GROUP",true)&&(container==NestedSources.GROUP_PREFIX+row.id||isInside(groupId,NestedSources.GROUP_PREFIX+row.id)))return@launch
  repo.saveSources(listOf(row.copy(sceneId=container,sortOrder=repo.loadSources(container).size)))
  load()
 }}
 fun moveSourceOutOfGroup(id:String){viewModelScope.launch{
  val row=rowFor(id)?:return@launch
  if(!row.sceneId.startsWith(NestedSources.GROUP_PREFIX))return@launch
  val s=_activeScene.value?:return@launch
  repo.saveSources(listOf(row.copy(sceneId=s.id,sortOrder=repo.loadSources(s.id).size)))
  load()
 }}
 private suspend fun isInside(groupId:String,container:String):Boolean{
  for(row in repo.loadSources(container)){
   if(row.id==groupId)return true
   if(row.type.equals("GROUP",true)&&isInside(groupId,NestedSources.GROUP_PREFIX+row.id))return true
  }
  return false
 }
 fun selectSceneCollection(id:String){
  val collection=_sceneCollections.value.firstOrNull{it.id==id}?:return
  _activeSceneCollectionId.value=id;_currentSceneCollection.value=collection.name
  collectionPrefs.edit().putString("activeCollectionId",id).apply()
  viewModelScope.launch{load(collection)}
 }
 fun setStreamConfig(c:StreamConfig){_streamConfig.value=c}
 fun dismissStreamError(){_streamError.value=null}
 val streamStats=engine.streamStats
 fun setRecordConfig(c:RecordingConfig){_recordConfig.value=c}
  fun addScene(name:String){viewModelScope.launch{val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch;val current=repo.loadScenes(c.id);val id=UUID.randomUUID().toString();repo.saveScene(SceneEntity(id,c.id,name.ifBlank{"Scene ${current.size+1}"},current.size,false));load(c)}}
  fun removeScene(){viewModelScope.launch{val a=_activeScene.value?:return@launch;val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch;val all=repo.loadScenes(c.id);if(all.size>1){val next=all.first{it.id!=a.id};repo.loadScenes(c.id).forEach{row->if(row.id==next.id)repo.saveScene(row.copy(active=true)) else if(row.active)repo.saveScene(row.copy(active=false))};repo.saveCollection(c.copy(activeSceneId=next.id));load(c.copy(activeSceneId=next.id))}}}
 fun setActiveScene(id:String){viewModelScope.launch{
   val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch
  val type=transitionCode(_transition.value)
  if(type==0){NativeEngine.setTransition(0,1);NativeEngine.setTransitionProgress(0f);activateScene(c.id,id);return@launch}
  val duration=transitionDurationMs();NativeEngine.setTransition(type,duration)
  val half=(duration/2).coerceAtLeast(1);val steps=16
  for(i in 0..steps){NativeEngine.setTransitionProgress(i.toFloat()/steps);kotlinx.coroutines.delay((half/steps).toLong().coerceAtLeast(1))}
  activateScene(c.id,id)
  for(i in steps downTo 0){NativeEngine.setTransitionProgress(i.toFloat()/steps);kotlinx.coroutines.delay((half/steps).toLong().coerceAtLeast(1))}
  NativeEngine.setTransitionProgress(0f)
}}
private suspend fun activateScene(collectionId:String,id:String){repo.loadScenes(collectionId).forEach{repo.saveScene(it.copy(active=it.id==id))};repo.saveCollection(repo.collections().first().firstOrNull{it.id==collectionId}?.copy(activeSceneId=id)?:return);load()}
private fun transitionDurationMs():Int=when(_transition.value){"Fast Fade"->180;"Slow Fade"->600;else->300}
private fun transitionCode(name:String):Int=when(name){"Cut"->0;else->1}
 /** Adds a source; [onCreated] receives it so the studio can open its properties, like OBS. */
 fun addSource(type:String,onCreated:(SourceItem)->Unit={}){viewModelScope.launch{val s=_activeScene.value?:return@launch;val rows=repo.loadSources(s.id);val id=UUID.randomUUID().toString()
  // A new group's canvas matches the program canvas, so items moved into it keep their positions.
  val config=if(type.equals("GROUP",true))settingsRepository.videoConfig.first().let{"{\"width\":${it.baseResWidth},\"height\":${it.baseResHeight}}"}else defaultConfigFor(type)
  repo.saveSources(listOf(SourceEntity(id,s.id,displayNameFor(type,rows.size),type,rows.size,true,false,config,"{}","{}")));load()
  _sources.value.firstOrNull{it.id==id}?.let(onCreated)}}
 fun removeSource(id:String?=null){viewModelScope.launch{val s=_activeScene.value?:return@launch;val target=id?.let{rowFor(it)}?:repo.loadSources(s.id).lastOrNull();target?.let{deleteDeep(it)};load()}}
 fun renameSource(id:String,name:String){viewModelScope.launch{val clean=name.trim().take(80);if(clean.isEmpty())return@launch;rowFor(id)?.let{repo.saveSources(listOf(it.copy(name=clean)))};load()}}
 fun duplicateSource(id:String){viewModelScope.launch{
  val original=rowFor(id)?:return@launch
  val rows=repo.loadSources(original.sceneId).sortedBy{it.sortOrder}
  val insertion=original.sortOrder+1
  repo.saveSources(rows.filter{it.sortOrder>=insertion}.map{it.copy(sortOrder=it.sortOrder+1)})
  copyDeep(original,original.sceneId,insertion,"${original.name} copy".take(80))
  load()
 }}
 fun resetSourceTransform(id:String){updateSourceTransform(id,"{}")}
 fun moveSourceInStack(id:String,displayDelta:Int){viewModelScope.launch{
  val container=rowFor(id)?.sceneId?:return@launch
  val displayed=repo.loadSources(container).sortedBy{it.sortOrder}.asReversed().toMutableList()
  val from=displayed.indexOfFirst{it.id==id};if(from<0)return@launch
  val to=(from+displayDelta).coerceIn(0,displayed.lastIndex);if(to==from)return@launch
  val moved=displayed.removeAt(from);displayed.add(to,moved)
  repo.saveSources(displayed.asReversed().mapIndexed{index,row->row.copy(sortOrder=index)})
  load()
 }}
 fun moveSourceToDisplayIndex(id:String,targetIndex:Int){viewModelScope.launch{
  val container=rowFor(id)?.sceneId?:return@launch
  val displayed=repo.loadSources(container).sortedBy{it.sortOrder}.asReversed().toMutableList()
  val from=displayed.indexOfFirst{it.id==id};if(from<0)return@launch
  val to=targetIndex.coerceIn(0,displayed.lastIndex);if(to==from)return@launch
  val moved=displayed.removeAt(from);displayed.add(to,moved)
  repo.saveSources(displayed.asReversed().mapIndexed{index,row->row.copy(sortOrder=index)})
  load()
 }}
 fun toggleSourceVisibility(id:String){viewModelScope.launch{rowFor(id)?.let{repo.saveSources(listOf(it.copy(visible=!it.visible)))};load()}}
 fun toggleSourceLock(id:String){viewModelScope.launch{rowFor(id)?.let{repo.saveSources(listOf(it.copy(locked=!it.locked)))};load()}}
 private fun defaultConfigFor(type:String)=when(type.uppercase()){
  "CAMERA"->"{\"facing\":\"BACK\",\"width\":1920,\"height\":1080,\"fps\":30}"
  "USB_CAPTURE"->"{\"deviceId\":-1,\"width\":1920,\"height\":1080,\"fps\":30,\"format\":\"MJPEG\"}"
  "BROWSER"->"{\"url\":\"about:blank\",\"width\":1280,\"height\":720,\"fps\":30,\"hardwareAccelerated\":true,\"javaScript\":true,\"customCss\":\"\",\"refreshToken\":0}"
  "MEDIA"->"{\"url\":\"\",\"file\":\"\",\"width\":1920,\"height\":1080,\"loop\":true,\"audioEnabled\":true,\"decoderPreference\":\"hardware\",\"playbackSpeed\":1.0,\"startPositionMs\":0,\"volume\":1.0,\"balance\":0.0,\"muted\":false,\"monitoring\":\"OUTPUT_ONLY\",\"syncOffsetMs\":0,\"solo\":false}"
  "IMAGE"->"{\"file\":\"\"}"
  "IMAGE_SLIDESHOW"->"{\"files\":[],\"slideIntervalSeconds\":5,\"loop\":true,\"randomize\":false}"
  "TEXT"->"{\"text\":\"Text Source\",\"fontSize\":64,\"textColor\":\"#FFFFFFFF\",\"backgroundColor\":\"#00000000\",\"bold\":false,\"italic\":false,\"alignment\":\"LEFT\"}"
  "COLOR"->"{\"color\":\"#FF000000\",\"width\":1280,\"height\":720}"
  "AUDIO_INPUT"->{
   val am=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   val id=am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull()?.id ?: -1
   "{\"deviceId\":$id,\"volume\":1.0,\"balance\":0.0,\"muted\":false,\"monitoring\":\"MONITOR_AND_OUTPUT\",\"syncOffsetMs\":0,\"solo\":false}"
  }
  "AUDIO_OUTPUT"->{
   val am=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   val id=am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()?.id ?: -1
   "{\"deviceId\":$id}"
  }
  "PLAYBACK_AUDIO"->"{\"enabled\":true,\"sourceId\":\"android_playback_audio\",\"volume\":1.0,\"monitoring\":\"OUTPUT_ONLY\"}"
  else->"{}"
 }
 private fun displayNameFor(type:String,n:Int)=when(type.uppercase()){"GROUP"->"Group ${n+1}";
  "CAMERA"->"Camera ${n+1}";"USB_CAPTURE"->"USB Capture ${n+1}";"SCREEN_CAPTURE"->"Screen Capture";"BROWSER"->"Browser ${n+1}";"IMAGE"->"Image ${n+1}";"IMAGE_SLIDESHOW"->"Image Slideshow ${n+1}";"MEDIA"->"Media ${n+1}";"TEXT"->"Text ${n+1}";"COLOR"->"Color ${n+1}";"AUDIO_INPUT"->"Audio Input ${n+1}";"AUDIO_OUTPUT"->"Monitor Output ${n+1}";"PLAYBACK_AUDIO"->"Android Playback Audio";else->"$type ${n+1}"
 }
 /** Every source the program actually shows, including the contents of nested scenes and groups. */
 private fun renderedItems():List<SourceItem> = _renderSources.value.map{it.item}+globalAudioItems()
 /** OBS global audio (Settings → Audio): present in every scene, shown in the mixer, filterable. */
 private fun globalAudioItems():List<SourceItem>{
  val a=audioSettingsState.value
  return buildList{
   if(a.desktopAudioEnabled)add(SourceItem(GlobalAudio.DESKTOP,"Desktop Audio","PLAYBACK_AUDIO",true,false,a.desktopConfig))
   if(a.micDeviceId!=AudioSettings.MIC_DISABLED){
    val cfg=runCatching{org.json.JSONObject(a.micConfig)}.getOrDefault(org.json.JSONObject()).put("deviceId",if(a.micDeviceId==AudioSettings.MIC_DEFAULT)-1 else a.micDeviceId)
    // Push-to-talk keeps the mic muted until the key is held; push-to-mute mutes while held.
    if(a.pushToTalk&&!pushToTalkHeld||a.pushToMute&&pushToTalkHeld)cfg.put("muted",true)
    add(SourceItem(GlobalAudio.MIC,"Mic/Aux","AUDIO_INPUT",true,false,cfg.toString()))
   }
  }
 }
 private fun refreshAudioItems(){_audioItems.value=renderedItems()}
 private fun syncAudioGraph() {
  refreshAudioItems()
  val routes=audioRoutes()
  val playbackSource=renderedItems().firstOrNull{it.type.equals("PLAYBACK_AUDIO",true)&&it.isVisible}
  val playbackRoute=playbackSource?.let(::parsePlaybackRoute)
  val monitor=monitorDeviceId()
  val audioSources=renderedItems().filter{it.isVisible&&(it.type.uppercase() in setOf("AUDIO_INPUT","AUDIO_OUTPUT","PLAYBACK_AUDIO") || it.type.equals("USB_CAPTURE",true)&&sourceAudioSettings(it.configJson).optInt("audioDeviceId",-1)>=0)}
  val reportIds=audioSources.map{it.id}.toSet()
  val missingDeviceIds=audioSources.filter{source->
   source.type.equals("AUDIO_INPUT",true)&&sourceAudioSettings(source.configJson).optInt("deviceId",-1)<0 ||
    source.type.equals("AUDIO_OUTPUT",true)&&sourceSettings(source.configJson).optInt("deviceId",-1)<0
  }.map{it.id}.toSet()
  val configuredReportIds=reportIds-missingDeviceIds
  val sourceNames=audioSources.associate{it.id to it.name}
  viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
    runCatching { NativeAudioGraph.configure(routes,playbackRoute,monitor,monitor!=null,mediaAudioRoutes()) }
     .onSuccess {
      syncAudioGraphErrors.forEach(SourceRuntimeErrors::clear); syncAudioGraphErrors=emptySet()
      configuredReportIds.forEach(SourceRuntimeErrors::clear)
      missingDeviceIds.forEach{id->SourceRuntimeErrors.report(id,"Select a connected Android audio device for ${sourceNames[id] ?: "this source"}.")}
     }
     .onFailure { error ->
      syncAudioGraphErrors.filterNot{it in configuredReportIds}.forEach(SourceRuntimeErrors::clear)
      configuredReportIds.forEach { id -> SourceRuntimeErrors.report(id,"Audio device or mixer could not start: ${error.message ?: "Android audio error"}.") }
      missingDeviceIds.forEach{id->SourceRuntimeErrors.report(id,"Select a connected Android audio device for ${sourceNames[id] ?: "this source"}.")}
      syncAudioGraphErrors=configuredReportIds
     }
  }
 }
 fun updateSourceConfig(id:String,config:String){
  if(id==GlobalAudio.DESKTOP||id==GlobalAudio.MIC){
   viewModelScope.launch{
    val a=audioSettingsState.value
    // The device and push-to-talk mute are derived, not stored.
    val json=runCatching{org.json.JSONObject(config)}.getOrNull()
    // Picking a device in Mic/Aux properties is the same as Settings → Audio → Mic/Aux device.
    val pickedDevice=json?.let{(it.optJSONObject("settings")?:it).optInt("deviceId",Int.MIN_VALUE)}?.takeIf{it!=Int.MIN_VALUE}
    val clean=json?.apply{remove("deviceId");optJSONObject("settings")?.remove("deviceId");if(id==GlobalAudio.MIC&&(a.pushToTalk||a.pushToMute))remove("muted")}?.toString()?:config
    val micDevice=if(id==GlobalAudio.MIC&&pickedDevice!=null)(if(pickedDevice<0)AudioSettings.MIC_DEFAULT else pickedDevice)else a.micDeviceId
    settingsRepository.saveAudioSettings(if(id==GlobalAudio.DESKTOP)a.copy(desktopConfig=clean)else a.copy(micConfig=clean,micDeviceId=micDevice))
   }
   return
  }
  viewModelScope.launch{rowFor(id)?.let{row->
  repo.saveSources(listOf(row.copy(configJson=config)))
  if(row.type.equals("AUDIO_INPUT",true)) runCatching { engine.updateAudioRoute(parseAudioRoute(SourceItem(row.id,row.name,row.type,row.visible,row.locked,config,row.transformJson))) }
   .onSuccess { SourceRuntimeErrors.clear(id) }
   .onFailure { SourceRuntimeErrors.report(id,"Android could not update this audio input: ${it.message ?: "audio route error"}.") }
 };load()}}
 fun remapImportedSource(id:String,targetType:String){
  val supported=setOf("USB_CAPTURE","SCREEN_CAPTURE","PLAYBACK_AUDIO","AUDIO_INPUT","AUDIO_OUTPUT","BROWSER","MEDIA","IMAGE","IMAGE_SLIDESHOW","TEXT","COLOR")
  val type=targetType.uppercase();if(type !in supported)return
  val sceneId=_activeScene.value?.id?:return
  viewModelScope.launch {
   val row=repo.loadSources(sceneId).firstOrNull{it.id==id}?:return@launch
   if(!row.type.startsWith("OBS:")||row.type=="OBS:GROUP")return@launch
   val root=runCatching{org.json.JSONObject(row.configJson)}.getOrDefault(org.json.JSONObject())
   val oldSettings=runCatching{org.json.JSONObject(root.optJSONObject("settings")?.toString()?:"{}")}.getOrDefault(org.json.JSONObject())
   root.optJSONObject("audio")?.let{audio->listOf("volume","balance","muted","monitoring","syncOffsetMs","solo").forEach{key->if(!oldSettings.has(key)&&audio.has(key))oldSettings.put(key,audio.opt(key))}}
   val merged=runCatching{org.json.JSONObject(defaultConfigFor(type))}.getOrDefault(org.json.JSONObject())
   oldSettings.keys().asSequence().toList().forEach{key->merged.put(key,oldSettings.opt(key))}
   root.put("settings",merged)
   root.put("remappedFromType",row.type)
   repo.saveSources(listOf(row.copy(type=type,configJson=root.toString())))
   load()
  }
 }
 private fun sourceSettings(configJson:String):org.json.JSONObject {
  val root=runCatching{org.json.JSONObject(configJson)}.getOrDefault(org.json.JSONObject())
  return root.optJSONObject("settings")?:root
 }
 private fun sourceAudioSettings(configJson:String):org.json.JSONObject {
  val root=runCatching{org.json.JSONObject(configJson)}.getOrDefault(org.json.JSONObject())
  val settings=org.json.JSONObject((root.optJSONObject("settings")?:root).toString())
  val audio=root.optJSONObject("audio")?:org.json.JSONObject()
  listOf("volume","balance","muted","monitoring","syncOffsetMs","solo").forEach{key->if(!settings.has(key)&&audio.has(key))settings.put(key,audio.opt(key))}
  return settings
 }
 fun updateSourceTransform(id:String,transform:String){
  val current=_renderSources.value.firstOrNull{it.item.id==id}?.item?:_groupChildren.value.values.flatten().firstOrNull{it.id==id}?:return
  if(current.isLocked)return
  _sources.value=_sources.value.map{if(it.id==id)it.copy(transformJson=transform)else it}
  _renderSources.value=_renderSources.value.map{if(it.item.id==id)it.copy(item=it.item.copy(transformJson=transform))else it}
  viewModelScope.launch{sourceTransformMutex.withLock{rowFor(id)?.let{row->if(!row.locked)repo.saveSources(listOf(row.copy(transformJson=transform)))}}}
 }
 private fun parseAudioRoute(src:SourceItem):AudioInputRoute{
  val j=sourceAudioSettings(src.configJson)
  return AudioInputRoute(src.id,j.optInt("deviceId",-1),j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",false),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","MONITOR_AND_OUTPUT"))}.getOrDefault(AudioMonitoring.MONITOR_AND_OUTPUT),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson))
 }
 private fun parsePlaybackRoute(src:SourceItem):AudioInputRoute{
  val j=sourceAudioSettings(src.configJson)
  return AudioInputRoute(NativeAudioBridge.PLAYBACK_SOURCE_ID,-1,j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",false),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","OUTPUT_ONLY"))}.getOrDefault(AudioMonitoring.OUTPUT_ONLY),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson))
 }
 private fun audioRoutes(): List<AudioInputRoute> = renderedItems().filter { (it.type.equals("AUDIO_INPUT", true) || it.type.equals("USB_CAPTURE", true)) && it.isVisible }.mapNotNull { src ->
  runCatching {
   val isCaptureCard=src.type.equals("USB_CAPTURE",true)
   val j=sourceAudioSettings(src.configJson); val id=j.optInt(if(isCaptureCard)"audioDeviceId" else "deviceId",-1)
   // -1 opens Android's default input; only the global Mic/Aux asks for that.
   if(id<0&&src.id!=GlobalAudio.MIC) null else AudioInputRoute(
    sourceId=if(isCaptureCard)"usb_audio_${src.id}" else src.id, deviceId=id, volume=j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f), balance=j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f), muted=j.optBoolean("muted",false),
    monitoring=runCatching{AudioMonitoring.valueOf(j.optString("monitoring","MONITOR_AND_OUTPUT"))}.getOrDefault(AudioMonitoring.MONITOR_AND_OUTPUT),
    syncOffsetMs=j.optInt("syncOffsetMs",0).coerceIn(-2000,2000), solo=j.optBoolean("solo",false),
    noiseGate=AudioFilterChain.noiseGate(src.configJson)
   )
  }.getOrNull()
 }
 private fun mediaAudioRoutes():List<AudioInputRoute> = renderedItems().filter{it.type.equals("MEDIA",true)&&it.isVisible}.mapNotNull{src->
  runCatching{
   val j=sourceSettings(src.configJson);val audioId="media_audio_${src.id}"
   AudioInputRoute(audioId,-1,j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",!j.optBoolean("audioEnabled",true)),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","OUTPUT_ONLY"))}.getOrDefault(AudioMonitoring.OUTPUT_ONLY),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson))
  }.getOrNull()
 }
 /** An Audio Output source wins; otherwise the monitoring device from Settings → Audio (-1 = Android default). */
 private fun monitorDeviceId(): Int? = renderedItems().firstOrNull { it.type.equals("AUDIO_OUTPUT",true) && it.isVisible }?.let { runCatching { sourceSettings(it.configJson).optInt("deviceId",-1).takeIf { id -> id>=0 } }.getOrNull() }
  ?: audioSettingsState.value.monitorDeviceId
 fun audioPeak(sourceId:String):Float = engine.audioPeak(sourceId)
 fun togglePrimaryMicMute(){
  val src=renderedItems().firstOrNull{it.id==GlobalAudio.MIC} ?: _sources.value.firstOrNull{it.type.equals("AUDIO_INPUT",true)} ?: return
  val root=runCatching{org.json.JSONObject(src.configJson)}.getOrDefault(org.json.JSONObject());val j=root.optJSONObject("settings")?:root
  val next=!j.optBoolean("muted",false)
  j.put("muted",next)
  updateSourceConfig(src.id,j.toString())
 }
 fun setPushToTalk(pressed:Boolean){
  val a=audioSettingsState.value
  if(a.pushToTalk||a.pushToMute){
   // Release waits for the configured delay so word endings are not clipped.
   viewModelScope.launch{if(!pressed)kotlinx.coroutines.delay(a.pushDelayMs.toLong());pushToTalkHeld=pressed;syncAudioGraph()}
   return
  }
  val src=_sources.value.firstOrNull{it.type.equals("AUDIO_INPUT",true)} ?: return
  val root=runCatching{org.json.JSONObject(src.configJson)}.getOrDefault(org.json.JSONObject());val j=root.optJSONObject("settings")?:root
  j.put("muted",!pressed)
  updateSourceConfig(src.id,j.toString())
 }
 val generalSettings=settingsRepository.generalSettings.stateIn(viewModelScope,SharingStarted.Eagerly,GeneralSettings())
 /** Destination saved in Settings → Stream, with the current Output/Video settings. */
 private suspend fun savedDestination():StreamConfig?{
  val s=settingsRepository.streamSettings.first()
  if(s.server.isBlank()||s.streamKey.isBlank())return null
  val v=settingsRepository.videoConfig.first()
  return StreamConfig(
   service=s.service,
   protocol=if(s.server.startsWith("rtmps",true))StreamProtocol.RTMPS else StreamProtocol.RTMP,
   ingestionUrl=s.server,streamName=s.streamKey,
   outputCodec=v.outputCodec,outputWidth=v.outputResWidth,outputHeight=v.outputResHeight,
   // YouTube, Twitch, Facebook and Kick ingest accept at most 60 FPS; only a custom server gets 120.
   fps=if(s.service!=StreamService.CUSTOM)v.frameRate.coerceAtMost(60)else v.frameRate,
   bitrate=v.videoBitrateKbps*1_000
  )
 }
 private fun withReconnect(c:StreamConfig):StreamConfig{val a=advancedSettings.value;return c.copy(autoReconnect=a.autoReconnect,reconnectDelayMs=a.reconnectDelaySec*1_000L,maxReconnectAttempts=if(a.autoReconnect)a.maxRetries else 0)}
 fun startStreaming()=viewModelScope.launch{
  val routes=audioRoutes(); val monitor=monitorDeviceId(); val playback=renderedItems().any{it.type.equals("PLAYBACK_AUDIO",true)&&it.isVisible}
  _streamError.value=null
  // A destination picked this session (YouTube picker / custom dialog) wins; otherwise use Settings → Stream.
  val destination=_streamConfig.value.takeIf{it.ingestionUrl.isNotBlank()}?:savedDestination()
  if(destination==null){_streamError.value="No stream destination yet. Open Settings → Stream, choose a service and paste your stream key.";return@launch}
  runCatching{engine.startStreaming(withReconnect(destination).copy(audioDeviceIds=routes.map{it.deviceId},audioInputs=routes,monitorDeviceId=monitor,monitorEnabled=monitor!=null,audioPlaybackCaptureEnabled=playback))}
   .onFailure{_streamError.value=it.message ?: "Streaming could not start."}
 }
 fun stopStreaming()=viewModelScope.launch{engine.stopStreaming()}
 fun startRecording()=viewModelScope.launch{
  val routes=audioRoutes(); val monitor=monitorDeviceId(); val playback=renderedItems().any{it.type.equals("PLAYBACK_AUDIO",true)&&it.isVisible}
  runCatching{engine.startRecording(_recordConfig.value.copy(audioDeviceIds=routes.map{it.deviceId},audioInputs=routes,monitorDeviceId=monitor,monitorEnabled=monitor!=null,audioPlaybackCaptureEnabled=playback))}
 }
 fun stopRecording()=viewModelScope.launch{engine.stopRecording()}
 override fun onCleared(){NativeAudioGraph.stop();super.onCleared()}
 fun pauseRecording()=viewModelScope.launch{engine.pauseRecording()};fun toggleStudioMode(){_isStudio.value=!_isStudio.value};fun selectTransition(v:String){_transition.value=v};fun saveReplay()=viewModelScope.launch{runCatching{engine.saveReplayBuffer()}};fun startReplay()=viewModelScope.launch{runCatching{engine.startReplayBuffer(30,256)}}
}

/** Ids of the OBS-style global audio sources (not stored as scene rows). */
object GlobalAudio {
 const val DESKTOP="global:desktop"
 const val MIC="global:mic"
}
