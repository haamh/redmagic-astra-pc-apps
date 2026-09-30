package com.stream4k60.app.data.local.dao
import androidx.room.*
import com.stream4k60.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow
@Dao interface SceneDao{
 @Query("SELECT * FROM scene_collections ORDER BY sortOrder,name") fun collections():Flow<List<SceneCollectionEntity>>
 @Query("SELECT * FROM scenes WHERE collectionId=:id ORDER BY sortOrder,name") fun scenes(id:String):Flow<List<SceneEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertCollection(x:SceneCollectionEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertScene(x:SceneEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSources(xs:List<SourceEntity>)
 @Query("DELETE FROM sources WHERE sceneId=:sceneId") suspend fun deleteSources(sceneId:String)
 @Query("DELETE FROM scenes WHERE collectionId=:id") suspend fun deleteScenes(id:String)
 @Query("DELETE FROM scene_collections WHERE id=:id") suspend fun deleteCollection(id:String)
 @Query("SELECT * FROM scenes WHERE id=:id LIMIT 1") suspend fun getScene(id:String):SceneEntity?
 @Query("SELECT * FROM sources WHERE sceneId=:id ORDER BY sortOrder") suspend fun getSources(id:String):List<SourceEntity>
 @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id:String)
 @Query("SELECT * FROM scenes WHERE collectionId=:id ORDER BY sortOrder") suspend fun getScenes(id:String):List<SceneEntity>
}
