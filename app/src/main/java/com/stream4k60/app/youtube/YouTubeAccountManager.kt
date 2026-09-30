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
    suspend fun authorize(activity:Activity,onResolution:(IntentSenderRequest)->Unit,onDone:(Boolean,String?)->Unit){runCatching{val req=AuthorizationRequest.builder().setRequestedScopes(requested).build();val result=Identity.getAuthorizationClient(activity).authorize(req).await();if(result.hasResolution())onResolution(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}}.onFailure{onDone(false,it.message)}}
    fun handleAuthorizationResult(activity:Activity,intent:Intent?,onDone:(Boolean,String?)->Unit){runCatching{val result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent);_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}.onFailure{onDone(false,it.message)}}
    fun service()=YouTubeService{suspendToken()}
    private suspend fun suspendToken()=_token.value
}
