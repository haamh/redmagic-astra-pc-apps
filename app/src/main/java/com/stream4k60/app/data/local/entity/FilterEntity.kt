package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="filters")
data class FilterEntity(@PrimaryKey val id:String,val sourceId:String,val name:String,val type:String,val enabled:Boolean=true,val sortOrder:Int=0,val configJson:String="{}")
