package com.stream4k60.app.ui.search

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.util.showImeOnFocus

data class FeatureEntry(
    val title:String,
    val category:String,
    val detail:String,
    val action:(()->Unit)?,
    val keywords:String = "",
    val status:String = ""
)

@Composable
fun FeatureSearchSheet(entries:List<FeatureEntry>,onDismiss:()->Unit){
    var q by remember{mutableStateOf("")}
    val query=q.trim().lowercase()
    val filtered=remember(entries,q){
        if(query.isBlank()) entries else entries.mapNotNull { e ->
            val hay="${e.title} ${e.category} ${e.detail} ${e.keywords}".lowercase()
            val score=when{
                e.title.lowercase().startsWith(query)->100
                e.title.lowercase().contains(query)->70
                e.category.lowercase().contains(query)->55
                e.keywords.lowercase().split(' ').any{it.startsWith(query)}->45
                hay.contains(query)->25
                else->0
            }
            if(score==0)null else score to e
        }.sortedByDescending{it.first}.map{it.second}
    }
    AlertDialog(onDismissRequest=onDismiss,title={ClosableTitle("Search everything",onDismiss)},text={Column{
        OutlinedTextField(q,{q=it},modifier=Modifier.fillMaxWidth().showImeOnFocus(),singleLine=true,placeholder={Text("Try: 4K120, HEVC, YouTube, audio, USB, HDR…")})
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max=520.dp)){
            items(filtered){e->
                ListItem(
                    headlineContent={Text(e.title)},
                    supportingContent={Text("${e.category} · ${e.status}: ${e.detail}")},
                    modifier=Modifier.fillMaxWidth(),
                    trailingContent={
                        if(e.action!=null) TextButton(onClick={e.action.invoke();onDismiss()}){Text("Open")}
                        else TextButton(onClick={},enabled=false){Text("Not ready")}
                    }
                )
            }
        }
    }},confirmButton={})
}
