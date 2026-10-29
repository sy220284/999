package cn.codex.android
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.OriginalAgentActions
import kotlinx.serialization.json.*

@Composable fun OriginalAgentsScreen(host:OriginalCoreHost,onBack:()->Unit){
    val state by host.state.collectAsState();var source by rememberSaveable{mutableStateOf(state.agentsSource)};var children by rememberSaveable{mutableStateOf(true)}
    val actions=host.agentActions;val actionState by actions.state.collectAsState();val operation by actions.operations.state.collectAsState()
    var confirmation by remember{mutableStateOf<Pair<String,()->Unit>?>(null)};var renaming by remember{mutableStateOf<JsonObject?>(null)};var name by remember{mutableStateOf("")}
    val worktreeState by host.worktrees.state.collectAsState();val worktreeOperation by host.worktrees.operations.state.collectAsState()
    val editable=!worktreeOperation.busy && state.connected && !state.loading && !state.working && state.turnId==null && !operation.busy
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("代理总览",style=MaterialTheme.typography.headlineSmall)
            Text("已加载列表来自当前连接的核心；使用共享核心时包含其他客户端的任务。切换来源后请重新读取，列表不是全部账号的实时全局状态。",style=MaterialTheme.typography.bodySmall)
            listOf("loaded" to "当前核心已加载","interactive" to "交互历史","exec" to "执行与服务历史").forEach{(key,label)->FilterChip(source==key,{source=key},label={Text(label)},enabled=!state.loading)}
            Row{Checkbox(children,{children=it});Text("同时显示子代理",Modifier.padding(top=12.dp))}
            TextButton(onClick={host.readAgents(source)},enabled=state.connected && !state.loading){Text("读取此来源")}
            Text(operation.notice,color=MaterialTheme.colorScheme.primary);operation.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(actionState.hidden.isNotEmpty())TextButton(onClick=actions::restoreHidden){Text("重新显示本次隐藏的${actionState.hidden.size}项")}
            Row{TextButton(onClick={host.newThread();onBack()},enabled=editable){Text("新建会话")};TextButton(onClick={confirmation="从当前仓库默认分支创建独立工作树和新会话？会使用本机Git检出文件，可能执行可信仓库的过滤器；失败或取消后请核查保留目录。" to {host.worktrees.create(null,state.model)}},enabled=editable){Text("新工作树会话")}}
            Text(worktreeOperation.notice);worktreeOperation.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            worktreeState.retained?.let{SelectionContainer{Text("结果待核查，保留目录：$it")}}
            TextButton(onClick={host.worktrees.refresh()},enabled=!worktreeOperation.busy){Text("读取托管工作树")}
            worktreeState.thread?.let{thread->TextButton(onClick={host.openThread(thread.string("id"));onBack()},enabled=editable){Text("打开已创建的工作树会话")}}
            if(state.loading || operation.busy || worktreeOperation.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(state.agentsReadFailures>0)Text("${state.agentsReadFailures}个已加载会话在读取时不可用，请刷新实际状态。")
        }
        if(source==state.agentsSource)items((state.agentsOverview.map{actionState.updated[it.string("id")] ?: it}+actionState.updated.values.filter{updated->state.agentsOverview.none{it.string("id")==updated.string("id")}}).filter{it.string("id") !in actionState.hidden && it.string("parentThreadId") !in actionState.hidden}.filter{children || it["parentThreadId"]==null || it["parentThreadId"]==JsonNull}.sortedByDescending{(it["section"] as? JsonObject)?.string("id")==OriginalAgentActions.PINNED},key={it.string("id")}){thread->
            val id=thread.string("id");val status=state.threadStatuses[id] ?: thread["status"] as? JsonObject
            val label=when(status?.string("type")){"active"->"运行中";"idle"->"空闲";"notLoaded"->"未加载";"systemError"->"核心错误";else->"状态未同步"}
            OutlinedCard{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(thread.string("name").ifBlank{thread.string("agentNickname").ifBlank{thread.string("preview").take(160).ifBlank{id}}},style=MaterialTheme.typography.titleMedium)
                Text("$label · $id",style=MaterialTheme.typography.bodySmall);Text(thread.string("cwd"),style=MaterialTheme.typography.bodySmall)
                thread.string("parentThreadId").takeIf{it.isNotBlank()}?.let{Text("父会话：$it",style=MaterialTheme.typography.bodySmall)}
                Row{TextButton(onClick={host.inspectAgent(id)},enabled=!state.loading){Text("查看进展")};TextButton(onClick={host.openThread(id);onBack()},enabled=!state.loading && !state.working && state.turnId==null){Text("切换到会话")}}
                Row{TextButton(onClick={renaming=thread;name=thread.string("name")},enabled=editable){Text("改名")};TextButton(onClick={val pinned=(thread["section"] as? JsonObject)?.string("id")==OriginalAgentActions.PINNED;confirmation=(if(pinned)"取消置顶此会话？"else"置顶此会话？") to {actions.pin(thread,!pinned)}},enabled=editable){Text(if((thread["section"] as? JsonObject)?.string("id")==OriginalAgentActions.PINNED)"取消置顶"else"置顶")};TextButton(onClick={actions.usage(id)},enabled=!operation.busy){Text("用量")}}
                TextButton(onClick={confirmation="从此会话分叉到新工作树？会创建独立目录并保留历史，不自动发送任务；失败后请核查保留目录。" to {host.worktrees.create(id,state.model)}},enabled=editable){Text("分叉到新工作树")}
                Row{TextButton(onClick={confirmation="从此会话分叉？不会自动发送任务。" to {actions.fork(thread)}},enabled=editable){Text("分叉")};TextButton(onClick={actions.inspectStop(id)},enabled=editable){Text("核查停止回合")};TextButton(onClick={actions.hide(id)},enabled=!operation.busy){Text("本次隐藏")}}
                Row{TextButton(onClick={confirmation="归档此会话？可在归档页恢复。" to {actions.remove(thread,false)}},enabled=editable && id!=state.threadId){Text("归档")};TextButton(onClick={confirmation="删除此会话？操作不可撤销，已修改的项目文件不会因此还原。" to {actions.remove(thread,true)}},enabled=editable && id!=state.threadId){Text("删除")}}

            }}
        }
        items(worktreeState.rows,key={"worktree:"+it.string("root")}){checkout->OutlinedCard{Column(Modifier.padding(12.dp)){Text("托管工作树");SelectionContainer{Text(checkout.string("cwd"))};Text("版本：${checkout.string("headSha").take(12)}",style=MaterialTheme.typography.bodySmall)
            checkout.string("ownerThreadId").takeIf{it.isNotBlank()}?.let{owner->TextButton(onClick={host.openThread(owner);onBack()},enabled=editable){Text("打开关联会话")}}
            TextButton(onClick={confirmation="清理这个工作树？\n${checkout.string("root")}\n请先确认其他客户端不再使用。会核查并卸载关联会话；当前回合活跃或目录有本地/忽略文件时拒绝删除。" to {host.worktrees.remove(checkout)}},enabled=editable && checkout.string("ownerThreadId")!=state.threadId){Text("核查并清理工作树")}
        }}}
        actionState.usage?.let{usage->item{Text("读取到的服务用量");SelectionContainer{Text(usage.toString(),style=MaterialTheme.typography.bodySmall)}}}
        item{TextButton(onClick={host.readAgents(source,true)},enabled=state.connected && !state.loading && source==state.agentsSource && state.agentsCursor!=null){Text("加载更多")}}
    }
    confirmation?.let{frozen->AlertDialog(onDismissRequest={confirmation=null},title={Text("确认代理操作")},text={Text(frozen.first)},confirmButton={TextButton(onClick={confirmation=null;frozen.second()},enabled=!operation.busy){Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}){Text("返回")}})}
    renaming?.let{thread->AlertDialog(onDismissRequest={renaming=null},title={Text("修改会话名称")},text={OutlinedTextField(name,{name=it},label={Text("新名称")})},confirmButton={TextButton(onClick={renaming=null;actions.rename(thread,name)},enabled=editable && name.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={renaming=null}){Text("返回")}})}
    actionState.stop?.let{preview->AlertDialog(onDismissRequest=actions::dismissStop,title={Text("停止指定回合？")},text={Text("会话：${preview.thread}\n回合：${preview.turn}\n会在提交前再次读取核对；停止不撤销已经发生的变更。")},confirmButton={TextButton(onClick={actions.stop(preview)},enabled=editable){Text("停止此回合")}},dismissButton={TextButton(onClick=actions::dismissStop){Text("返回")}})}
}
