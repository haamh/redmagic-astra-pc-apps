package com.stream4k60.app.engine

import java.io.ByteArrayOutputStream

/**
 * Standards-conformant 188-byte MPEG-TS muxer for YouTube HLS ingestion.
 * Produces muxed M2TS with H.264/H.265 video, AAC/ADTS audio, PAT/PMT and PCR.
 */
class MpegTsMuxer(
    private val videoCodec: String,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private var videoCodecConfig: ByteArray? = null
) {
    private val out = ByteArrayOutputStream()
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0
    private var wroteTables = false
    private val programNumber = 1
    private val pmtPid = 0x1000
    private val videoPid = 0x101
    private val audioPid = 0x102

    fun setVideoCodecConfig(config: ByteArray?) { videoCodecConfig = config?.copyOf() }

    fun addVideo(s: HardwareVideoEncoder.Sample) {
        if (!wroteTables || s.keyframe) {
            writePat()
            writePmt()
            wroteTables = true
        }
        val pts90k = (s.ptsUs * 90L / 1_000L) and 0x1FFFFFFFFL
        var payload = toAnnexB(s.data)
        if (s.keyframe && !containsParameterSets(payload)) {
            val cfg = videoCodecConfig?.let(::toAnnexB)
            if (cfg != null && cfg.isNotEmpty()) payload = cfg + payload
        }
        writePes(videoPid, pts90k, payload, video = true, pcr90k = pts90k)
    }

    fun addAudio(s: HardwareVideoEncoder.Sample) {
        if (!wroteTables) {
            writePat()
            writePmt()
            wroteTables = true
        }
        val pts90k = (s.ptsUs * 90L / 1_000L) and 0x1FFFFFFFFL
        val raw = s.data
        val adts = if (raw.size >= 7 && (raw[0].toInt() and 0xff) == 0xff) raw else addAdts(raw)
        writePes(audioPid, pts90k, adts, video = false, pcr90k = -1L)
    }

    fun bytes(): ByteArray = out.toByteArray()

    fun resetSegment() {
        out.reset()
        wroteTables = false
        // Continuity counters intentionally continue across segment boundaries.
    }

    private fun writePat() {
        val section = ByteArrayOutputStream()
        section.write(0x00) // table_id
        writeU16(section, 0xB000 or 13) // syntax indicator + section length
        writeU16(section, 0x0001) // transport_stream_id
        section.write(0xC1) // reserved=3, version=0, current_next=1
        section.write(0x00) // section number
        section.write(0x00) // last section number
        writeU16(section, programNumber)
        writeU16(section, 0xE000 or pmtPid)
        val body = section.toByteArray()
        writeSection(0, body)
    }

    private fun writePmt() {
        val videoType = if (videoCodec.equals("video/hevc", true) || videoCodec.equals("hevc", true)) 0x24 else 0x1B
        // section_length = bytes after the section_length field through CRC.
        val sectionLength = 9 + 5 + 5 + 4
        val section = ByteArrayOutputStream()
        section.write(0x02) // PMT
        writeU16(section, 0xB000 or sectionLength)
        writeU16(section, programNumber)
        section.write(0xC1)
        section.write(0x00)
        section.write(0x00)
        writeU16(section, 0xE000 or videoPid) // PCR PID
        writeU16(section, 0xF000) // program_info_length = 0
        section.write(videoType)
        writeU16(section, 0xE000 or videoPid)
        writeU16(section, 0xF000)
        section.write(0x0F) // AAC ADTS
        writeU16(section, 0xE000 or audioPid)
        writeU16(section, 0xF000)
        writeSection(pmtPid, section.toByteArray())
    }

    private fun writeSection(pid: Int, sectionWithoutCrc: ByteArray) {
        val full = ByteArrayOutputStream()
        full.write(0x00) // pointer_field
        full.write(sectionWithoutCrc)
        full.write(mpegCrc32(sectionWithoutCrc))
        writeTs(pid, full.toByteArray(), payloadUnitStart = true, pcr90k = -1L)
    }

    private fun writePes(pid: Int, pts90k: Long, payload: ByteArray, video: Boolean, pcr90k: Long) {
        val pes = ByteArrayOutputStream()
        pes.write(0x00); pes.write(0x00); pes.write(0x01)
        pes.write(if (video) 0xE0 else 0xC0)
        val packetLength = if (video) 0 else (8 + payload.size).coerceAtMost(0xFFFF)
        writeU16(pes, packetLength)
        pes.write(0x80) // '10'
        pes.write(0x80) // PTS only
        pes.write(0x05)
        writePts(pes, pts90k)
        pes.write(payload)
        writeTs(pid, pes.toByteArray(), payloadUnitStart = true, pcr90k = pcr90k)
    }

    private fun writeTs(pid: Int, data: ByteArray, payloadUnitStart: Boolean, pcr90k: Long) {
        var off = 0
        var first = true
        while (off < data.size) {
            val remaining = data.size - off
            val addPcr = first && pcr90k >= 0
            val maxPayload = if (addPcr) 176 else minOf(184, remaining)
            val needsAdaptation = addPcr || remaining < 184
            val payload = minOf(maxPayload, remaining)
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first && payloadUnitStart) 1 else 0) shl 6) or ((pid ushr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            val cc = nextContinuity(pid)
            if (needsAdaptation) {
                packet[3] = (0x30 or cc).toByte() // adaptation + payload
                val adaptationLength = 183 - payload
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = if (addPcr) 0x10 else 0x00 // PCR_flag
                    if (addPcr && adaptationLength >= 7) writePcr(packet, 6, pcr90k)
                }
            } else {
                packet[3] = (0x10 or cc).toByte()
            }
            val dst = 188 - payload
            System.arraycopy(data, off, packet, dst, payload)
            out.write(packet)
            off += payload
            first = false
        }
    }

    private fun nextContinuity(pid: Int): Int = when (pid) {
        0 -> ccPat.also { ccPat = (ccPat + 1) and 0x0F }
        pmtPid -> ccPmt.also { ccPmt = (ccPmt + 1) and 0x0F }
        videoPid -> ccVideo.also { ccVideo = (ccVideo + 1) and 0x0F }
        audioPid -> ccAudio.also { ccAudio = (ccAudio + 1) and 0x0F }
        else -> 0
    }

    private fun writePts(o: ByteArrayOutputStream, pts: Long) {
        val p = pts and 0x1FFFFFFFFL
        o.write(0x20 or ((((p ushr 30) and 0x07).toInt()) shl 1) or 1)
        o.write((p ushr 22).toInt() and 0xFF)
        o.write((((p ushr 15) and 0x7F).toInt() shl 1) or 1)
        o.write((p ushr 7).toInt() and 0xFF)
        o.write((((p and 0x7F).toInt()) shl 1) or 1)
    }

    private fun writePcr(packet: ByteArray, off: Int, p90k: Long) {
        val base = p90k and 0x1FFFFFFFFL
        packet[off] = (base ushr 25).toByte()
        packet[off + 1] = (base ushr 17).toByte()
        packet[off + 2] = (base ushr 9).toByte()
        packet[off + 3] = (base ushr 1).toByte()
        packet[off + 4] = (((base and 1L).toInt() shl 7) or 0x7E).toByte()
        packet[off + 5] = 0
    }

    private fun containsParameterSets(data: ByteArray): Boolean {
        val nalus = RtmpPublisher.AnnexB.nalus(data)
        if (videoCodec.equals("video/hevc", true) || videoCodec.equals("hevc", true)) {
            return nalus.any { it.isNotEmpty() && (((it[0].toInt() and 0xFF) ushr 1) and 0x3F) in 32..34 }
        }
        return nalus.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } &&
            nalus.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
    }

    private fun addAdts(raw: ByteArray): ByteArray {
        val profile = 2 // AAC LC
        val srIndex = sampleRateIndex(sampleRate)
        val chan = channels.coerceIn(1, 7)
        val length = raw.size + 7
        require(length <= 0x1FFF) { "AAC frame too large for ADTS" }
        val h = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (srIndex shl 2) or (chan ushr 2)).toByte(),
            (((chan and 3) shl 6) or (length ushr 11)).toByte(),
            (length ushr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
        return h + raw
    }

    private fun sampleRateIndex(sr: Int): Int = when (sr) {
        96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
        32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
        11_025 -> 10; 8_000 -> 11; else -> 4
    }

    private fun mpegCrc32(data: ByteArray): ByteArray {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun writeU16(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4) {
            for (i in 0 until minOf(data.size - 3, 8)) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                    (data[i + 2].toInt() == 1 || (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) return data
            }
        }
        val nalus = RtmpPublisher.AnnexB.nalus(data)
        val outNalus = if (nalus.size == 1 && nalus[0] === data) listOf(data) else nalus
        val out = ByteArrayOutputStream(data.size + outNalus.size * 4)
        outNalus.forEach { n -> out.write(byteArrayOf(0, 0, 0, 1)); out.write(n) }
        return out.toByteArray()
    }
}
