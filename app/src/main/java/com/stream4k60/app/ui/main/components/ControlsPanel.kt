package com.stream4k60.app.ui.main.components
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable fun ControlsPanel(isStreaming:Boolean,isRecording:Boolean,isStudioMode:Boolean,onStartStreaming:()->Unit,onStopStreaming:()->Unit,onStartRecording:()->Unit,onStopRecording:()->Unit,onStartReplay:()->Unit,onToggleStudio:()->Unit,onSettings:()->Unit,modifier:Modifier=Modifier){Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Controls",fontSize=12.sp);Button(onClick=if(isStreaming)onStopStreaming else onStartStreaming,Modifier.fillMaxWidth().height(36.dp)){Text(if(isStreaming)"Stop streaming" else "Go live")};Button(onClick=if(isRecording)onStopRecording else onStartRecording,Modifier.fillMaxWidth().height(36.dp)){Text(if(isRecording)"Stop recording" else "Record")};Button(onClick=onStartReplay,Modifier.fillMaxWidth().height(32.dp)){Text("Replay buffer")};Button(onClick=onToggleStudio,Modifier.fillMaxWidth().height(32.dp)){Text(if(isStudioMode)"Exit Studio Mode" else "Studio Mode")} ;OutlinedButton(onClick=onSettings,Modifier.fillMaxWidth().height(32.dp)){Text("Settings")}}}
