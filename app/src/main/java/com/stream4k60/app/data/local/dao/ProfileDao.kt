package com.stream4k60.app.data.local.dao
import androidx.room.*
import com.stream4k60.app.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow
@Dao interface ProfileDao{
 @Query("SELECT * FROM profiles ORDER BY name") fun all():Flow<List<ProfileEntity>>
 @Query("SELECT * FROM profiles WHERE id=:id LIMIT 1") suspend fun get(id:String):ProfileEntity?
 @Query("SELECT * FROM profiles WHERE isActive=1 LIMIT 1") suspend fun active():ProfileEntity?
 @Query("SELECT * FROM profiles WHERE isActive=1 LIMIT 1") fun observeActive():Flow<ProfileEntity?>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(x:ProfileEntity)
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertIfMissing(x:ProfileEntity)
 @Query("UPDATE profiles SET isActive = CASE WHEN id=:id THEN 1 ELSE 0 END") suspend fun setActive(id:String)
 @Query("UPDATE profiles SET configJson=:configJson WHERE id=:id") suspend fun updateConfigJson(id:String,configJson:String)
 @Delete suspend fun delete(x:ProfileEntity)
}
