package com.stream4k60.app.data.local.dao
import androidx.room.*
import com.stream4k60.app.data.local.entity.*
@Dao interface SourceDao{
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(x:SourceEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertFilters(xs:List<FilterEntity>)
 @Query("SELECT * FROM sources WHERE sceneId=:sceneId ORDER BY sortOrder") suspend fun byScene(sceneId:String):List<SourceEntity>
 @Query("SELECT * FROM filters WHERE sourceId=:sourceId ORDER BY sortOrder") suspend fun filters(sourceId:String):List<FilterEntity>
}
