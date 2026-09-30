package com.stream4k60.app.engine

import android.content.Context

/** Compatibility facade. The production replay implementation is ReplayBufferController. */
class ReplayBuffer(private val maxSeconds:Int, private val maxSizeMb:Int){
    init { require(maxSeconds > 0 && maxSizeMb > 0) }
    fun start(){ ReplayBufferController.start(maxSeconds,maxSizeMb) }
    fun stop(){ ReplayBufferController.stop() }
    fun save(context:Context)=ReplayBufferController.save(context)
}
