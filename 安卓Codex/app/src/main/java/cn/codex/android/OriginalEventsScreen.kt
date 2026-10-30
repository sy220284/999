package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalEventsScreen(host: OriginalCoreHost, onRealtime: () -> Unit, onBack: () -> Unit) {
    val state by host.state.collectAsState()
    val sound by host.realtimeAudio.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    BackHandler(onBack = onBack)
    DisposableEffect(owner,host,state.threadId) {
        val observer = LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_STOP)host.realtimeAudio.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);host.realtimeAudio.stop() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("返回工作区") };Text("运行动态",style = MaterialTheme.typography.headlineSmall)
            Text("通知跟随本次核心连接。这里显示当前会话及全局动态，断开后清空。",style = MaterialTheme.typography.bodySmall) }
        item { ExpandableCard("当前能力与待补部分","手机动态工具：逐次批准项目目录/文本读取。\n外部账号令牌刷新：未接外部提供者，请用原版核心登录。\n设备证明：未接受认可的证明提供者，连接不声明此能力。\n实时音频：支持接收PCM16后由你开启播放，离页或后台自动静音。\nWebRTC：已有媒体协商与静音控制代码，需随包SDK和实际设备验证；在实时页手动选择。\n实验发起入口已接：独立命令可选择process/spawn，持续搜索和实时文字/录音有专用页；WebSocket会话由前台托管至关闭，实验接口另列清单。",Icons.Outlined.Info) }
        item { TextButton(onClick=onRealtime){Text("打开实时文字与语音") } }
        val events = state.events
        val rt = events.realtime[state.threadId]
        if(rt != null) item {
            Text("实时会话",style = MaterialTheme.typography.titleMedium);Text(rt.status)
            Text("已接收音频 ${rt.audioBytes} 字节 · ${rt.audioFormat}",style = MaterialTheme.typography.bodySmall)
            Row { Switch(sound.enabled,{ if(it)host.realtimeAudio.enable()else host.realtimeAudio.stop() },enabled = rt.active && !rt.sdpReceived);Text("播放当前会话音频",Modifier.padding(12.dp)) }
            Text(sound.message,style = MaterialTheme.typography.bodySmall)
            rt.items.forEach { (id,text) -> ExpandableCard("实时条目 · ${id.takeLast(8)}",text,Icons.Outlined.Info) }
            if(!rt.canonicalText) rt.transcripts.forEach { (role,text) -> SelectionContainer { Text("${roleLabel(role)}：$text") } }
            if(!rt.canonicalText) rt.partials.forEach { (role,text) -> Text("${roleLabel(role)}（正在转写）：$text") }
        }
        if(events.rateLimits.isNotEmpty()) item { ExpandableCard("最新额度变化",events.rateLimits.toString(),Icons.Outlined.Info);TextButton(onClick = { host.inspect("配额") }) { Text("读取完整额度") } }
        if(events.apps.isNotEmpty()) item { Text("应用目录已更新：${events.apps.size} 项",style = MaterialTheme.typography.titleMedium)
            Text("最多展示200项；完整列表可到市场、分享与应用读取。",style = MaterialTheme.typography.bodySmall)
            events.apps.forEach { app -> Text(app.string("name").ifBlank { app.string("id") },style = MaterialTheme.typography.bodyMedium) }
        }
        events.remote?.let { remote -> item { ExpandableCard("远程连接状态", "状态：${remote["status"]}\n服务：${remote.string("serverName")}\n安装标识：${remote.string("installationId")}\n环境：${remote["environmentId"]}",Icons.Outlined.Info) } }
        events.processes.forEach { (id,p) -> item(key = "process:$id") { ExpandableCard("进程 ${id.takeLast(8)} · ${p.exitCode?.let { "退出码 $it" } ?: "运行中"}","标准输出：\n${p.stdout}\n标准错误：\n${p.stderr}\n${if(p.capped) "输出已截断" else ""}",Icons.Outlined.Info) } }
        events.searches.forEach { (id,search) -> item(key = "search:$id") { ExpandableCard("搜索 ${id.takeLast(8)} · ${if(search.completed) "已结束" else "更新中"}",search.data.toString(),Icons.Outlined.Info) } }
        val notices = events.notices.filter { it.threadId.isBlank() || it.threadId == state.threadId }.asReversed()
        items(notices,key = { it.key }) { notice -> ExpandableCard(notice.title,notice.body,Icons.Outlined.Info) }
        if(notices.isEmpty() && rt == null && events.processes.isEmpty()) item { Text("暂无新的运行动态",style = MaterialTheme.typography.bodyMedium) }
    }
}
private fun roleLabel(role: String) = when(role) { "assistant" -> "Codex";"user" -> "你";else -> role }

@Composable fun OriginalDynamicToolDialog(host: OriginalCoreHost, request: ServerMessage.Request) {
    val state by host.state.collectAsState()
    val p = request.params as? JsonObject ?: buildJsonObject {}
    val supported = host.androidToolRoot(request)!=null && p.toString().length <= 8192 && p.string("tool") in setOf("android_project_read","android_project_list") && (p["namespace"] == null || p["namespace"] == JsonNull)
    fun decline() { host.answer(request, OriginalAndroidTools.response(false,"用户拒绝此次工具调用")) }
    AlertDialog(onDismissRequest = { if(!state.toolBusy)decline() },title = { Text("允许手机读取项目？") },text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("会话：${p.string("threadId")}\n工具：${p.string("tool")}")
            Text("参数：${p["arguments"].toString().take(8192)}")
            if(p.toString().length > 8192)Text("请求过长，无法完整核对，请拒绝后缩小范围。",color = MaterialTheme.colorScheme.error)
            Text("读取根目录：${host.androidToolRoot(request).orEmpty()}\n读取结果会返回模型。每次调用单独确认。",style = MaterialTheme.typography.bodySmall)
            if(!supported)Text("此工具没有注册本机执行器，可拒绝后继续。",color = MaterialTheme.colorScheme.error)
            if(state.toolBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton = { TextButton(onClick = { host.executeAndroidTool(request) },enabled = supported && !state.toolBusy) { Text("读取并返回结果") } },dismissButton = { TextButton(onClick = ::decline,enabled = !state.toolBusy) { Text("拒绝") } })
}
