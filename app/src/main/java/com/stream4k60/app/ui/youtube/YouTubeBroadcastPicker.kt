package com.stream4k60.app.ui.youtube

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stream4k60.app.data.model.*
import com.stream4k60.app.youtube.*

@Composable fun YouTubeBroadcastPicker(onSelected:(StreamConfig)->Unit,onDismiss:()->Unit,vm:YouTubeBroadcastPickerViewModel=hiltViewModel()){
 val ctx=LocalContext.current as Activity;val connected by vm.connected.collectAsState();val broadcasts by vm.broadcasts.collectAsState();val video by vm.videoConfig.collectAsState();val settingsLoaded by vm.settingsLoaded.collectAsState();var error by remember{mutableStateOf<String?>(null)}
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){r->vm.handleResult(ctx,r.data){ok,msg->error=msg;if(ok)vm.load()}}
 LaunchedEffect(Unit){if(connected)vm.load()}
 AlertDialog(onDismissRequest=onDismiss,title={Text("YouTube")},text={Column{if(!connected){Text("Connect this Android device to your YouTube account to use existing broadcasts without entering a stream key.");Spacer(Modifier.height(8.dp));Button(onClick={vm.authorize(ctx,{launcher.launch(it)}){ok,msg->error=msg}}){Text("Connect Google / YouTube")}}else{Text("Select a broadcast",style=MaterialTheme.typography.titleMedium);if(settingsLoaded)Text("Output: ${video.outputResWidth} × ${video.outputResHeight} at ${video.frameRate} FPS",style=MaterialTheme.typography.bodySmall)else Text("Loading active profile video settings…",style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(6.dp));LazyColumn(Modifier.heightIn(max=420.dp)){items(broadcasts){b->ListItem(headlineContent={Text(b.title)},supportingContent={Text("${b.lifeCycle?:""} · ${b.ingestionType?:"protocol not assigned"}")},trailingContent={Button(enabled=settingsLoaded,onClick={runCatching{vm.toConfig(b)}.onSuccess(onSelected).onFailure{error=it.message}}){Text("Use")}})}}};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
