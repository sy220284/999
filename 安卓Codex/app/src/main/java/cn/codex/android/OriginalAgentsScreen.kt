package cn.codex.android
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import kotlinx.serialization.json.*

@Composable fun OriginalAgentsScreen(host:OriginalCoreHost,onBack:()->Unit){
    val state by host.state.collectAsState();var source by rememberSaveable{mutableStateOf(state.agentsSource)};var children by rememberSaveable{mutableStateOf(true)}
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("代理总览",style=MaterialTheme.typography.headlineSmall)
            Text("已加载列表来自当前连接的核心；使用共享核心时包含其他客户端的任务。切换来源后请重新读取，列表不是全部账号的实时全局状态。",style=MaterialTheme.typography.bodySmall)
            listOf("loaded" to "当前核心已加载","interactive" to "交互历史","exec" to "执行与服务历史").forEach{(key,label)->FilterChip(source==key,{source=key},label={Text(label)},enabled=!state.loading)}
            Row{Checkbox(children,{children=it});Text("同时显示子代理",Modifier.padding(top=12.dp))}
            TextButton(onClick={host.readAgents(source)},enabled=state.connected && !state.loading){Text("读取此来源")}
            if(state.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(state.agentsReadFailures>0)Text("${state.agentsReadFailures}个已加载会话在读取时不可用，请刷新实际状态。")
        }
        if(source==state.agentsSource)items(state.agentsOverview.filter{children || it["parentThreadId"]==null || it["parentThreadId"]==JsonNull},key={it.string("id")}){thread->
            val id=thread.string("id");val status=state.threadStatuses[id] ?: thread["status"] as? JsonObject
            val label=when(status?.string("type")){"active"->"运行中";"idle"->"空闲";"notLoaded"->"未加载";"systemError"->"核心错误";else->"状态未同步"}
            OutlinedCard{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(thread.string("name").ifBlank{thread.string("agentNickname").ifBlank{thread.string("preview").take(160).ifBlank{id}}},style=MaterialTheme.typography.titleMedium)
                Text("$label · $id",style=MaterialTheme.typography.bodySmall);Text(thread.string("cwd"),style=MaterialTheme.typography.bodySmall)
                thread.string("parentThreadId").takeIf{it.isNotBlank()}?.let{Text("父会话：$it",style=MaterialTheme.typography.bodySmall)}
                Row{TextButton(onClick={host.inspectAgent(id)},enabled=!state.loading){Text("查看进展")};TextButton(onClick={host.openThread(id);onBack()},enabled=!state.loading && !state.working && state.turnId==null){Text("切换到会话")}}
            }}
        }
        item{TextButton(onClick={host.readAgents(source,true)},enabled=state.connected && !state.loading && source==state.agentsSource && state.agentsCursor!=null){Text("加载更多")}}
    }
}
