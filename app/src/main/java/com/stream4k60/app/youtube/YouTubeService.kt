package com.stream4k60.app.youtube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

@kotlinx.serialization.Serializable
data class YouTubeBroadcast(val id:String,val title:String,val scheduledStart:String?,val lifeCycle:String?,val streamId:String?,val streamStatus:String?,val ingestionType:String?,val ingestionUrl:String?,val rtmpsUrl:String?,val backupUrl:String?,val streamName:String?,val thumbnail:String?)

class YouTubeService(private val accessTokenProvider:suspend ()->String?) {
    suspend fun listBroadcasts():List<YouTubeBroadcast> = withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        val uri="https://www.googleapis.com/youtube/v3/liveBroadcasts?part=id,snippet,contentDetails,status&mine=true&maxResults=50"
        val root=get(uri,token)
        val items=root["items"]?.jsonArray ?: JsonArray(emptyList())
        items.map{element->
            val b=element.jsonObject
            val id=b["id"]?.jsonPrimitive?.content.orEmpty();val sn=b["snippet"]?.jsonObject;val cd=b["contentDetails"]?.jsonObject;val st=b["status"]?.jsonObject;val streamId=cd?.get("boundStreamId")?.jsonPrimitive?.contentOrNull
            val stream=streamId?.let{get("https://www.googleapis.com/youtube/v3/liveStreams?part=id,snippet,cdn,status&id=$it",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject}
            val cdn=stream?.get("cdn")?.jsonObject;val ii=cdn?.get("ingestionInfo")?.jsonObject
            YouTubeBroadcast(id,sn?.get("title")?.jsonPrimitive?.content.orEmpty(),sn?.get("scheduledStartTime")?.jsonPrimitive?.contentOrNull,st?.get("lifeCycleStatus")?.jsonPrimitive?.contentOrNull,streamId,stream?.get("status")?.jsonObject?.get("streamStatus")?.jsonPrimitive?.contentOrNull,cdn?.get("ingestionType")?.jsonPrimitive?.contentOrNull,ii?.get("ingestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("rtmpsIngestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("rtmpsBackupIngestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("streamName")?.jsonPrimitive?.contentOrNull,sn?.get("thumbnails")?.jsonObject?.get("default")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        }
    }
    suspend fun transitionBroadcast(id:String,status:String){withContext(Dispatchers.IO){val token=accessTokenProvider()?:error("YouTube not connected");post("https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?id=$id&broadcastStatus=$status&part=id,status",token,"POST", "")}}
    suspend fun streamIsActive(streamId:String):Boolean = listBroadcasts().any { it.streamId == streamId && it.streamStatus.equals("active", true) }
    private fun get(url:String,token:String):JsonObject{val c=open(url,token,"GET");return Json.parseToJsonElement(c.inputStream.bufferedReader().use{it.readText()}).jsonObject.also{c.disconnect()}}
    private fun post(url:String,token:String,method:String,body:String):JsonObject{val c=open(url,token,method);c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(body.isNotEmpty())c.outputStream.use{it.write(body.toByteArray())};val text=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};val code=c.responseCode;c.disconnect();if(code !in 200..299)error("YouTube API $code: $text");return if(text.isBlank()) buildJsonObject{ } else Json.parseToJsonElement(text).jsonObject}
    private fun open(url:String,token:String,method:String):HttpURLConnection{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod=method;c.connectTimeout=15_000;c.readTimeout=20_000;c.setRequestProperty("Authorization","Bearer $token");c.setRequestProperty("Accept","application/json");return c}
}
