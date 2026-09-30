package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="scenes")
data class SceneEntity(@PrimaryKey val id:String,val collectionId:String,val name:String,val sortOrder:Int=0,val active:Boolean=false)
