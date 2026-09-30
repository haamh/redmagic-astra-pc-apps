package com.stream4k60.app.data.repository
import com.stream4k60.app.data.local.dao.ProfileDao
import com.stream4k60.app.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
class ProfileRepository @Inject constructor(private val dao:ProfileDao){
    fun all():Flow<List<ProfileEntity>> = dao.all()
    suspend fun save(x:ProfileEntity)=dao.upsert(x)
    suspend fun activate(id:String){dao.setActive(id)}
    suspend fun delete(x:ProfileEntity)=dao.delete(x)
    suspend fun active():ProfileEntity?=dao.active()
}
