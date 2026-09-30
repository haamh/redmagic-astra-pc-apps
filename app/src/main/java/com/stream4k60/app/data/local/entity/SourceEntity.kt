package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="sources")
data class SourceEntity(@PrimaryKey val id:String,val sceneId:String,val name:String,val type:String,val sortOrder:Int=0,val visible:Boolean=true,val locked:Boolean=false,val configJson:String="{}",val transformJson:String="{}",val audioJson:String="{}")
