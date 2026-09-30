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
    private val _connected=MutableStateFlow(false);val connected:StateFlow<Boolean> = _connected.asStateFlow();private val _token=MutableStateFlow<String?>(null);val token:StateFlow<String?> = _token.asStateFlow()
    private val requested= listOf(Scope("https://www.googleapis.com/auth/youtube.force-ssl"))
    suspend fun authorize(activity:Activity,onResolution:(IntentSenderRequest)->Unit,onDone:(Boolean,String?)->Unit){runCatching{val req=AuthorizationRequest.builder().setRequestedScopes(requested).build();val result=Identity.getAuthorizationClient(activity).authorize(req).await();if(result.hasResolution())onResolution(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}}.onFailure{onDone(false,explain(it))}}
    fun handleAuthorizationResult(activity:Activity,intent:Intent?,onDone:(Boolean,String?)->Unit){runCatching{val result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent);_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}.onFailure{onDone(false,explain(it))}}
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
