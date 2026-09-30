package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class UvcDescriptorTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }
    private fun u16(v: Int) = bytes(v and 0xff, v shr 8 and 0xff)
    private fun u32(v: Int) = bytes(v and 0xff, v shr 8 and 0xff, v shr 16 and 0xff, v ushr 24 and 0xff)

    private fun interfaceDesc(subclass: Int) = bytes(9, 0x04, 0, 0, 0, 14, subclass, 0, 0)
    private fun vcHeader(bcd: Int) = bytes(13, 0x24, 0x01) + u16(bcd) + u16(0) + u32(0) + bytes(1, 1)
    private fun mjpegFormat(index: Int) = bytes(11, 0x24, 0x06, index, 1, 0, 1, 0, 0, 0, 0)
    private fun mjpegFrame(frame: Int, w: Int, h: Int, fps: Int, maxBuf: Int) =
        bytes(30, 0x24, 0x07, frame, 0) + u16(w) + u16(h) + u32(0) + u32(0) + u32(maxBuf) + u32(10_000_000 / fps) + bytes(1) + u32(10_000_000 / fps)
    // Frame-based (H.264): GUID "H264" at offset 5, frame descriptors use subtype 0x11.
    private fun h264Format(index: Int) = bytes(28, 0x24, 0x10, index, 1) + bytes('H'.code, '2'.code, '6'.code, '4'.code) + List(12) { 0.toByte() } + bytes(16, 1, 0, 0, 0, 0, 0)
    private fun h264Frame(frame: Int, w: Int, h: Int, fps: Int) =
        bytes(30, 0x24, 0x11, frame, 0) + u16(w) + u16(h) + u32(0) + u32(0) + u32(10_000_000 / fps) + bytes(1) + u32(0) + u32(10_000_000 / fps)

    @Test
    fun parsesMjpegAndFrameBasedH264() {
        val d = (interfaceDesc(2) + mjpegFormat(1) + mjpegFrame(1, 1920, 1080, 30, 4_147_200) +
            h264Format(2) + h264Frame(1, 3840, 2160, 30)).toByteArray()
        val formats = UvcCaptureSession.parseFormats(d)
        val mjpeg = formats.single { it.codec == "MJPEG" }
        assertEquals(listOf(1920, 1080, 30, 4_147_200), listOf(mjpeg.width, mjpeg.height, mjpeg.fps, mjpeg.maxFrameSize))
        val h264 = formats.single { it.codec == "H264" }
        assertEquals(listOf(3840, 2160, 30), listOf(h264.width, h264.height, h264.fps))
    }

    // UVC 1.5 H.264: VS_FORMAT_H264 (0x13) and VS_FRAME_H264 (0x14), width at offset 4, intervals from 44.
    private fun uvc15H264Format(index: Int) = bytes(52, 0x24, 0x13, index, 1) + List(47) { 0.toByte() }
    private fun uvc15H264Frame(frame: Int, w: Int, h: Int, vararg fps: Int) =
        bytes(44 + fps.size * 4, 0x24, 0x14, frame) + u16(w) + u16(h) + List(31) { 0.toByte() } + u32(10_000_000 / fps[0]) + bytes(fps.size) + fps.flatMap { u32(10_000_000 / it) }

    @Test
    fun parsesUvc15H264() {
        val d = (interfaceDesc(2) + uvc15H264Format(1) + uvc15H264Frame(1, 1920, 1080, 60, 30) + uvc15H264Frame(2, 3840, 2160, 30)).toByteArray()
        val formats = UvcCaptureSession.parseFormats(d).filter { it.codec == "H264" }
        assertEquals(setOf(Triple(1920, 1080, 60), Triple(1920, 1080, 30), Triple(3840, 2160, 30)), formats.map { Triple(it.width, it.height, it.fps) }.toSet())
    }

    @Test
    fun probeLengthFollowsUvcVersion() {
        assertEquals(26, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0100)).toByteArray()))
        assertEquals(34, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0110)).toByteArray()))
        assertEquals(48, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0150)).toByteArray()))
        // A VideoStreaming input header (also subtype 0x01) must not be mistaken for the VC header.
        assertEquals(26, UvcCaptureSession.probeLength((interfaceDesc(2) + vcHeader(0x0150)).toByteArray()))
    }

    @Test
    fun fallsBackToAnAdvertisedFormat() {
        val formats = listOf(
            UvcCaptureSession.Format(1, 1, 1920, 1080, 30, "H264", 1),
            UvcCaptureSession.Format(2, 1, 1280, 720, 30, "YUYV", 1)
        )
        val chosen = UvcCaptureSession.chooseFormat(formats, 3840, 2160, 60, "MJPEG")
        assertEquals("H264", chosen.codec)
        assertEquals(1920, chosen.width)
    }
}
