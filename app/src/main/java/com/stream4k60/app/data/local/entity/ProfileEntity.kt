package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="profiles")
data class ProfileEntity(@PrimaryKey val id:String,val name:String,val configJson:String,val isActive:Boolean=false,val obsBasicIni:String="",val obsServiceJson:String="",val importedPath:String?=null)
