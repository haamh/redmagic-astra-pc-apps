package com.stream4k60.app.data.model

enum class SourceType(val displayName: String, val iconResource: Int = 0) {
    CAMERA("Camera"),
    SCREEN_CAPTURE("Screen Capture"),
    IMAGE("Image"),
    IMAGE_SLIDESHOW("Image Slideshow"),
    MEDIA("Media"),
    TEXT("Text"),
    BROWSER("Browser"),
    COLOR("Color Source"),
    AUDIO_INPUT("Audio Input"),
    AUDIO_OUTPUT("Audio Output"),
    PLAYBACK_AUDIO("Android Playback Audio"),
    WINDOW_CAPTURE("Window Capture"),
    NDI("NDI Source")
}
