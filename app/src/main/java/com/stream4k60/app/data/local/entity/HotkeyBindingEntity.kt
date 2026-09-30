package com.stream4k60.app.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="hotkeys")
data class HotkeyBindingEntity(@PrimaryKey val id:String,val action:String,val keyCode:Int,val modifiers:Int,val enabled:Boolean=true)
