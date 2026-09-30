package com.stream4k60.app.engine

/** DSP controls shared by the real HardwareAudioEncoder mixer. */
class AudioMixer {
    data class SourceState(var volume:Float=1f,var balance:Float=0f,var muted:Boolean=false)
    private val states=mutableMapOf<String,SourceState>()
    fun setVolume(sourceId:String,volume:Float){states.getOrPut(sourceId){SourceState()}.volume=volume.coerceAtLeast(0f)}
    fun setBalance(sourceId:String,balance:Float){states.getOrPut(sourceId){SourceState()}.balance=balance.coerceIn(-1f,1f)}
    fun setMuted(sourceId:String,muted:Boolean){states.getOrPut(sourceId){SourceState()}.muted=muted}
    fun state(sourceId:String)=states[sourceId]?:SourceState()
    fun remove(sourceId:String){states.remove(sourceId)}
}
