package com.stream4k60.app.engine

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads the public Android thermal state used to protect sustained Astra workloads. */
object AstraDeviceMonitor {
    private val mutableThermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = mutableThermalStatus.asStateFlow()

    @Volatile private var powerManager: PowerManager? = null
    @Volatile private var listener: PowerManager.OnThermalStatusChangedListener? = null

    @Synchronized
    fun start(context: Context) {
        if (listener != null) return
        val manager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        powerManager = manager
        runCatching { mutableThermalStatus.value = manager.currentThermalStatus }
        val next = PowerManager.OnThermalStatusChangedListener { status -> mutableThermalStatus.value = status }
        if (runCatching { manager.addThermalStatusListener(next) }.isSuccess) listener = next
    }

    @Synchronized
    fun stop() {
        val manager = powerManager
        val registered = listener
        if (manager != null && registered != null) runCatching { manager.removeThermalStatusListener(registered) }
        listener = null
        powerManager = null
    }

    fun label(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "COOL"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }
}
