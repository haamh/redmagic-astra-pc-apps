package com.stream4k60.app.youtube

/** Process-local OAuth session shared by the picker and streaming engine. */
object YouTubeAuthSession {
    @Volatile var accessToken:String?=null
}
