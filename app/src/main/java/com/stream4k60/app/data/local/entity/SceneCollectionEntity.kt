package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="scene_collections")
data class SceneCollectionEntity(@PrimaryKey val id:String,val name:String,val activeSceneId:String?=null,val sortOrder:Int=0)
