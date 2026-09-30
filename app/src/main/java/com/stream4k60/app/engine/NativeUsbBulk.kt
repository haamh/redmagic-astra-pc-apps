package com.stream4k60.app.engine

import android.hardware.usb.UsbEndpoint

/** Native usbfs bulk transport. Used only for UVC devices whose descriptor exposes a bulk video endpoint. ISO is preferred whenever the device advertises it. */
object NativeUsbBulk {
    init { System.loadLibrary("stream4k60_engine") }
    external fun start(fd: Int, endpointAddress: Int, packetBytes: Int, transferBytes: Int, urbCount: Int, callback: Any): Long
    external fun stop(handle: Long)
    external fun frames(handle: Long): Long
    external fun errors(handle: Long): Long
    external fun droppedFrames(handle: Long): Long

    fun transferSize(endpoint: UsbEndpoint): Int = (endpoint.maxPacketSize.coerceAtLeast(1024) * 16).coerceAtMost(16 * 1024)
}
