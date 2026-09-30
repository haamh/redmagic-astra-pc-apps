package com.stream4k60.app.util

object BitrateFormatter {
    fun formatBitrate(bitrateKbps: Int): String {
        return if (bitrateKbps >= 1000) {
            String.format("%.1f Mbps", bitrateKbps / 1000f)
        } else {
            "$bitrateKbps kbps"
        }
    }
}
