package com.stream4k60.app.youtube

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class YouTubeAccountManager : ViewModel(){
    // Starts from the process-wide session so a sign-in done earlier in this run still counts.
    private val _connected=MutableStateFlow(!YouTubeAuthSession.accessToken.isNullOrBlank());val connected:StateFlow<Boolean> = _connected.asStateFlow();private val _token=MutableStateFlow(YouTubeAuthSession.accessToken);val token:StateFlow<String?> = _token.asStateFlow()
    private val requested= listOf(Scope("https://www.googleapis.com/auth/youtube.force-ssl"))
    suspend fun authorize(activity:Activity,onResolution:(IntentSenderRequest)->Unit,onDone:(Boolean,String?)->Unit){runCatching{val req=AuthorizationRequest.builder().setRequestedScopes(requested).build();val result=Identity.getAuthorizationClient(activity).authorize(req).await();if(result.hasResolution())onResolution(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}}.onFailure{onDone(false,explain(it))}}
    fun handleAuthorizationResult(activity:Activity,intent:Intent?,onDone:(Boolean,String?)->Unit){runCatching{val result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent);_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}.onFailure{onDone(false,explain(it))}}
    /**
     * Google remembers an account that already granted access: this gets a fresh token without showing anything.
     * Returns false when the user has to sign in (first time, or access was revoked).
     */
    suspend fun restore(activity:Activity):Boolean=runCatching{
        val result=Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest.builder().setRequestedScopes(requested).build()).await()
        if(result.hasResolution())false else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();_connected.value}
    }.getOrDefault(false)
    fun disconnect(){_token.value=null;YouTubeAuthSession.accessToken=null;_connected.value=false}
    fun service()=YouTubeService{suspendToken()}
    private suspend fun suspendToken()=_token.value
    /** Google's errors are codes; say what to do instead. */
    private fun explain(t:Throwable):String{
        val m=t.message.orEmpty()
        return when{
            m.contains("UNREGISTERED_ON_API_CONSOLE",true)||m.startsWith("10:")->
                "This app isn't registered with Google yet. In Google Cloud Console create an Android OAuth client for package com.stream4k60.app with this APK's SHA-1, enable YouTube Data API v3, and add your Google account as a test user."
            m.contains("CANCELED",true)||m.startsWith("16:")->"Sign-in was cancelled."
            m.startsWith("7:")->"No internet connection."
            else->m.ifBlank{"YouTube sign-in failed."}
        }
    }
}
