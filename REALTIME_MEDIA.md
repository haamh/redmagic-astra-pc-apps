# Real-time media transport contract

Stream4k treats every live source as a continuous clocked stream.

## USB video

UVC devices are enumerated from their interface/endpoint descriptors. The streaming interface is claimed, negotiated with UVC PROBE/COMMIT, and the highest suitable alternate setting is selected.

- Isochronous IN endpoints use native `usbfs` URBs with multiple transfers queued ahead of time.
- Bulk IN endpoints use native asynchronous `usbfs` URBs only when the device exposes bulk video.
- USB completion threads only parse UVC packet boundaries, reassemble a frame, timestamp it, and enqueue it into a bounded native queue.
- MediaCodec/Kotlin callbacks run outside the USB completion thread.
- When downstream processing falls behind, the queue drops the oldest frame instead of starving the USB scheduler.

Compressed UVC formats (MJPEG/H.264/HEVC) are decoded with hardware MediaCodec into a compositor Surface. Raw formats (YUYV/UYVY/NV12) use one streaming upload into the GPU texture path; no software per-pixel conversion is performed in the hot path.

## USB audio

USB microphones, mixers, interfaces, and capture-card audio are treated as real-time audio inputs through Android's USB Audio / AudioRecord path. Each input has its own blocking capture thread and device selection, with short PCM blocks and source timestamps from `AudioRecord.getTimestamp(..., TIMEBASE_MONOTONIC)`. The mixer combines the active sources and feeds hardware AAC encoding.

This avoids polling the UI or using ordinary bulk USB reads for audio data. The Android audio stack retains the device's USB Audio Class scheduling and clock handling.

## Synchronization

Video timestamps originate from the native USB monotonic clock; audio timestamps originate from the selected audio device's monotonic timestamp. Downstream encoding and mux/stream layers preserve these presentation timestamps rather than synthesizing time from a frame counter.
