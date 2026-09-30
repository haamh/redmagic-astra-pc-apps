package com.stream4k60.app.engine

import android.hardware.usb.UsbDeviceConnection

/** Standard UVC controls exposed by a connected camera/capture card. */
data class UvcVideoControl(
    val selector: Int,
    val key: String,
    val label: String,
    val minimum: Int,
    val maximum: Int,
    val step: Int,
    val current: Int,
    val isToggle: Boolean = false
)

object UvcVideoControls {
    private data class ControlUnit(val interfaceNumber: Int, val unitId: Int, val bitmap: ByteArray, val cameraTerminal: Boolean)
    private data class Definition(
        val selector: Int,
        val key: String,
        val label: String,
        val bitmapBit: Int,
        val dataLength: Int = 2,
        val signed: Boolean = false,
        val isToggle: Boolean = false,
        val cameraTerminal: Boolean = false
    )

    private val definitions = listOf(
        Definition(0x02, "brightness", "Brightness", 0, signed = true),
        Definition(0x03, "contrast", "Contrast", 1),
        Definition(0x06, "hue", "Hue", 2, signed = true),
        Definition(0x07, "saturation", "Saturation", 3),
        Definition(0x08, "sharpness", "Sharpness", 4),
        Definition(0x09, "gamma", "Gamma", 5),
        Definition(0x0a, "whiteBalanceTemperature", "White balance temperature", 6),
        Definition(0x01, "backlightCompensation", "Backlight compensation", 8),
        Definition(0x04, "gain", "Gain", 9),
        Definition(0x05, "powerLineFrequency", "Power-line frequency (0=off, 1=50 Hz, 2=60 Hz, 3=auto)", 10, dataLength = 1),
        Definition(0x10, "hueAuto", "Automatic hue", 11, dataLength = 1, isToggle = true),
        Definition(0x0b, "whiteBalanceAuto", "Automatic white balance", 12, dataLength = 1, isToggle = true),
        Definition(0x0d, "whiteBalanceComponentAuto", "Automatic RGB white balance", 13, dataLength = 1, isToggle = true),
        Definition(0x04, "exposureTime", "Exposure time (100 μs units)", 3, dataLength = 4, cameraTerminal = true),
        Definition(0x06, "focus", "Focus", 5, cameraTerminal = true),
        Definition(0x08, "focusAuto", "Automatic focus", 7, dataLength = 1, isToggle = true, cameraTerminal = true),
        Definition(0x09, "iris", "Iris", 8, cameraTerminal = true),
        Definition(0x0b, "zoom", "Zoom", 10, cameraTerminal = true)
    )

    fun list(connection: UsbDeviceConnection): List<UvcVideoControl> =
        controlUnits(connection).flatMap { unit ->
            definitions.filter { it.cameraTerminal == unit.cameraTerminal }.mapNotNull { definition ->
                if (!unit.supports(definition.bitmapBit)) return@mapNotNull null
                val info = request(connection, unit, definition.selector, GET_INFO, 1)?.firstOrNull()?.toInt()?.and(0xff) ?: return@mapNotNull null
                if (info and INFO_GET == 0 || info and INFO_SET == 0) return@mapNotNull null
                val min = request(connection, unit, definition.selector, GET_MIN, definition.dataLength)?.let { decode(it, definition.signed) } ?: return@mapNotNull null
                val max = request(connection, unit, definition.selector, GET_MAX, definition.dataLength)?.let { decode(it, definition.signed) } ?: return@mapNotNull null
                val step = request(connection, unit, definition.selector, GET_RES, definition.dataLength)?.let { decode(it, definition.signed) }?.coerceAtLeast(1) ?: 1
                val current = request(connection, unit, definition.selector, GET_CUR, definition.dataLength)?.let { decode(it, definition.signed) } ?: return@mapNotNull null
                if (min >= max) return@mapNotNull null
                UvcVideoControl(definition.selector, definition.key, definition.label, min, max, step, current.coerceIn(min, max), definition.isToggle)
            }
        }.distinctBy(UvcVideoControl::key)

    fun set(connection: UsbDeviceConnection, key: String, value: Int): Boolean {
        val definition = definitions.firstOrNull { it.key == key } ?: return false
        val unit = controlUnits(connection).firstOrNull { it.cameraTerminal == definition.cameraTerminal && it.supports(definition.bitmapBit) } ?: return false
        val info = request(connection, unit, definition.selector, GET_INFO, 1)?.firstOrNull()?.toInt()?.and(0xff) ?: return false
        if (info and INFO_SET == 0) return false
        val min = request(connection, unit, definition.selector, GET_MIN, definition.dataLength)?.let { decode(it, definition.signed) } ?: return false
        val max = request(connection, unit, definition.selector, GET_MAX, definition.dataLength)?.let { decode(it, definition.signed) } ?: return false
        val bounded = value.coerceIn(min, max)
        val bytes = ByteArray(definition.dataLength) { offset -> (bounded ushr (offset * 8)).toByte() }
        val index = (unit.unitId shl 8) or unit.interfaceNumber
        return connection.controlTransfer(0x21, SET_CUR, definition.selector shl 8, index, bytes, 0, bytes.size, CONTROL_TIMEOUT_MS) >= 0
    }

    private fun controlUnits(connection: UsbDeviceConnection): List<ControlUnit> {
        val raw = connection.rawDescriptors
        val found = mutableListOf<ControlUnit>()
        var videoControlInterface: Int? = null
        var index = 0
        while (index + 2 < raw.size) {
            val length = raw[index].toInt() and 0xff
            if (length < 2 || index + length > raw.size) break
            val descriptorType = raw[index + 1].toInt() and 0xff
            if (descriptorType == INTERFACE_DESCRIPTOR && length >= 9) {
                val interfaceNumber = raw[index + 2].toInt() and 0xff
                val interfaceClass = raw[index + 5].toInt() and 0xff
                val interfaceSubclass = raw[index + 6].toInt() and 0xff
                videoControlInterface = interfaceNumber.takeIf { interfaceClass == VIDEO_CLASS && interfaceSubclass == VIDEO_CONTROL_SUBCLASS }
            } else if (descriptorType == CLASS_INTERFACE_DESCRIPTOR && length >= 9) {
                val interfaceNumber = videoControlInterface ?: -1
                when (raw[index + 2].toInt() and 0xff) {
                    PROCESSING_UNIT -> {
                        val controlSize = raw[index + 7].toInt() and 0xff
                        if (interfaceNumber >= 0 && controlSize > 0 && length >= 9 + controlSize) {
                            found += ControlUnit(interfaceNumber, raw[index + 3].toInt() and 0xff, raw.copyOfRange(index + 8, index + 8 + controlSize), false)
                        }
                    }
                    INPUT_TERMINAL -> {
                        val terminalType = u16(raw, index + 4)
                        if (interfaceNumber < 0 || terminalType != CAMERA_TERMINAL || length < 15) {
                            index += length
                            continue
                        }
                        val controlSize = raw[index + 14].toInt() and 0xff
                        if (controlSize > 0 && length >= 15 + controlSize) {
                            found += ControlUnit(interfaceNumber, raw[index + 3].toInt() and 0xff, raw.copyOfRange(index + 15, index + 15 + controlSize), true)
                        }
                    }
                }
            }
            index += length
        }
        return found
    }

    private fun ControlUnit.supports(bit: Int): Boolean =
        (((bitmap.getOrNull(bit / 8)?.toInt()?.and(0xff) ?: 0) and (1 shl (bit % 8))) != 0)

    private fun request(connection: UsbDeviceConnection, unit: ControlUnit, selector: Int, request: Int, length: Int): ByteArray? {
        val data = ByteArray(length)
        val index = (unit.unitId shl 8) or unit.interfaceNumber
        val count = connection.controlTransfer(0xA1, request, selector shl 8, index, data, 0, data.size, CONTROL_TIMEOUT_MS)
        return data.takeIf { count == length }
    }

    private fun decode(data: ByteArray, signed: Boolean): Int {
        var raw = data.indices.fold(0) { value, offset -> value or ((data[offset].toInt() and 0xff) shl (8 * offset)) }
        if (signed && data.isNotEmpty()) {
            val signBit = 1 shl (data.size * 8 - 1)
            if (raw and signBit != 0) raw = raw or (-1 shl (data.size * 8))
        }
        return raw
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private const val INTERFACE_DESCRIPTOR = 0x04
    private const val CLASS_INTERFACE_DESCRIPTOR = 0x24
    private const val VIDEO_CLASS = 0x0e
    private const val VIDEO_CONTROL_SUBCLASS = 0x01
    private const val INPUT_TERMINAL = 0x02
    private const val PROCESSING_UNIT = 0x05
    private const val CAMERA_TERMINAL = 0x0201
    private const val GET_CUR = 0x81
    private const val GET_MIN = 0x82
    private const val GET_MAX = 0x83
    private const val GET_RES = 0x84
    private const val GET_INFO = 0x86
    private const val SET_CUR = 0x01
    private const val INFO_GET = 0x01
    private const val INFO_SET = 0x02
    private const val CONTROL_TIMEOUT_MS = 350
}
