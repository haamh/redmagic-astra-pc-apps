package com.stream4k60.app.data.repository
import com.stream4k60.app.data.local.dao.SceneDao
import com.stream4k60.app.data.local.dao.SourceDao
import com.stream4k60.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
class SceneRepository @Inject constructor(private val dao:SceneDao, private val sourceDao:SourceDao){fun collections()=dao.collections();fun scenes(id:String)=dao.scenes(id);suspend fun saveCollection(x:SceneCollectionEntity)=dao.upsertCollection(x);suspend fun saveScene(x:SceneEntity)=dao.upsertScene(x);suspend fun saveSources(xs:List<SourceEntity>)=dao.upsertSources(xs);suspend fun loadSources(sceneId:String)=dao.getSources(sceneId);suspend fun loadScenes(id:String)=dao.getScenes(id);suspend fun deleteSource(id:String)=dao.deleteSource(id);suspend fun saveFilters(xs:List<FilterEntity>)=sourceDao.upsertFilters(xs)}
