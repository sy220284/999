package cn.codex.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.codex.core.*
import cn.codex.core.original.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*

internal class OriginalRequestKey(private val request:ServerMessage.Request) {
    override fun equals(other:Any?)=other is OriginalRequestKey && request===other.request
    override fun hashCode()=System.identityHashCode(request)
}

@Composable fun OriginalCoreScreen(host: OriginalCoreHost) {
    val state by host.state.collectAsState()
    val extensionState by host.extensions.state.collectAsState()
    val mcpState by host.mcp.state.collectAsState()
    val settingsState by host.settings.state.collectAsState()
    val filesState by host.files.state.collectAsState()
    val libraryStatus by host.library.operations.state.collectAsState()
    val servicesStatus by host.services.operations.state.collectAsState()
    val migrationStatus by host.migration.operations.state.collectAsState()
    val queueStatus by host.queue.operations.state.collectAsState()
    val cliStatus by host.cli.state.collectAsState()
    val sshStatus by host.sshTrust.state.collectAsState()
    val projectsStatus by host.projects.operations.state.collectAsState()
    val historyStatus by host.history.operations.state.collectAsState()
    val advancedStatus by host.advanced.operations.state.collectAsState()
    val connectionsStatus by host.connections.operations.state.collectAsState()
    val searchStatus by host.search.state.collectAsState()
    val realtimeStatus by host.realtime.state.collectAsState()
    val agentActionStatus by host.agentActions.operations.state.collectAsState()
    val worktreeStatus by host.worktrees.operations.state.collectAsState()
    val managedBusy = worktreeStatus.busy ||  agentActionStatus.busy ||  cliStatus.busy || sshStatus.working || projectsStatus.mutating || historyStatus.mutating || advancedStatus.mutating || connectionsStatus.mutating || queueStatus.busy || realtimeStatus.running || libraryStatus.mutating || servicesStatus.mutating || migrationStatus.mutating
    val configurationRecords by host.configurationJournal.records.collectAsState()
    val configurationReadError by host.configurationJournal.readError.collectAsState()
    val activeConfiguration by host.configurationJournal.active.collectAsState()
    var mcpManager by remember { mutableStateOf(false) }
    val imports = remember(host) { host.mcpImports() }
    val networkGit by (LocalContext.current.applicationContext as CodexApplication).gitOperations.state.collectAsState()
    val fullTerminalState by (LocalContext.current.applicationContext as CodexApplication).fullTerminal.state.collectAsState()
    val commandState by host.commands.state.collectAsState()
    var commandManager by remember { mutableStateOf(false) }
    var settingsManager by remember { mutableStateOf(false) }
    var filesManager by remember { mutableStateOf(false) }
    var skillsManager by remember { mutableStateOf(false) }
    var advancedManager by remember { mutableStateOf<String?>(null) }
    var realtimeManager by remember { mutableStateOf(false) }
    var searchManager by remember { mutableStateOf(false) }
    var eventsManager by remember { mutableStateOf(false) }
    var sessionManager by remember { mutableStateOf(false) }
    var sessionInitialTab by remember { mutableStateOf("目标") }
    var extensions by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var renamed by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    state.requests.firstOrNull()?.let { request -> key(state.connectionEpoch, OriginalRequestKey(request)) { if(request.method == "item/tool/call") OriginalDynamicToolDialog(host,request) else OriginalRequestDialog(request, state.items, { host.answer(request, it) },onVerify={host.verifyElicitation(request)},verifying=state.verifyingElicitation===request,verificationError=state.verificationError) } }
    OriginalSkillPreview(host.extensions)
    if (state.agent != null) {
        val agent = state.agent!!
        AlertDialog(onDismissRequest = host::closeAgent, title = { Text("代理会话 ${agent.string("id").takeLast(8)}") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("关闭此视图不会停止代理。任务仍由原版核心管理。", style = MaterialTheme.typography.bodySmall)
                if (state.agentItems.isEmpty()) Text("尚未取得消息，可读取代理回合。")
                state.agentItems.forEach { item -> Text(item.text) }
                TextButton(onClick = { host.inspectAgent(agent.string("id")) }, enabled = !state.loading) { Text("刷新进展") }
                TextButton(onClick = { host.loadAgentTurns(state.agentCursor != null) }, enabled = !state.loading) { Text(if (state.agentCursor == null) "读取代理回合" else "加载更早消息") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { host.closeAgent(); host.openThread(agent.string("id")) }, enabled = !state.working && state.turnId == null && !state.loading) { Text("切换到此会话") } }, dismissButton = { TextButton(onClick = host::closeAgent) { Text("关闭查看") } })
    }
    advancedManager?.let { page ->
        val allow = !state.working && state.turnId == null && !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !commandState.running && !managedBusy
        when (page) {
            "代理总览" -> OriginalAgentsScreen(host) { advancedManager = null }
            "应用更新" -> AndroidUpdateScreen { advancedManager = null }
            "本机连接服务" -> NativeEndpointsScreen(host) { advancedManager = null }
            "云任务与本机维护" -> OriginalCliScreen(host) { advancedManager = null }
            "SSH主机校验" -> OriginalSshScreen(host.sshTrust,java.io.File(AndroidSshTrust.directory(LocalContext.current),"id_ed25519").absolutePath,allow) { advancedManager = null }
            "项目管理" -> OriginalProjectsScreen(host) { advancedManager = null }
            "搜索与会话记录" -> OriginalHistoryScreen(host,onOpen={id->host.openThread(id);advancedManager=null}) { advancedManager = null }
            "会话设置与记忆" -> OriginalAdvancedScreen(host) { advancedManager = null }
            "连接、订阅与认证" -> OriginalConnectionsScreen(host) { advancedManager = null }
            "市场、分享与应用" -> OriginalLibraryScreen(host.library, allow, host::close) { advancedManager = null }
            "授权、额度与反馈" -> OriginalServicesScreen(host.services, host.commandWorkspace(), allow, host::close) { advancedManager = null }
            "迁移、配置与工具" -> OriginalMigrationScreen(host.migration, host.commandWorkspace(), state.threadId, allow, host::close) { advancedManager = null }
        }
        return
    }
    if (skillsManager) { OriginalSkillsScreen(host.extensions, allowChanges = !state.working && state.turnId == null && !state.loading && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !managedBusy && !commandState.running) { skillsManager = false }; return }
    if (filesManager) { OriginalFilesScreen(host.files, allowChanges = !state.working && state.turnId == null && !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !managedBusy && !commandState.running) { filesManager = false }; return }
    if (settingsManager) { OriginalSettingsScreen(host.settings, allowChanges = !state.working && state.turnId == null && !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !managedBusy && !commandState.running) { settingsManager = false }; return }
    if (commandManager) { OriginalCommandScreen(host.commands, host.commandWorkspace()) { commandManager = false }; return }
    if (realtimeManager) { OriginalRealtimeScreen(host) { realtimeManager = false }; return }
    if (searchManager) { OriginalSearchScreen(host,onOpen={path->host.files.read(path);searchManager=false;filesManager=true}) { searchManager = false }; return }
    if (eventsManager) { OriginalEventsScreen(host,onRealtime={eventsManager=false;realtimeManager=true}) { eventsManager = false }; return }
    if (sessionManager) { OriginalSessionScreen(host, initialTab = sessionInitialTab) { sessionManager = false }; return }
    if (mcpManager) {
        OriginalMcpScreen(host.mcp, allowChanges = !state.working && state.turnId == null && !state.loading && !extensionState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !managedBusy,
            imports = imports, authorizationUrl = state.mcpAuthorizationUrl, authorizationName = state.mcpAuthorizationName,
            hostError = state.error, onDismissHostError = host::dismissError, onLogin = { host.startMcpLogin(it) },
            onDismissAuthorization = host::dismissMcpLogin, onBack = { mcpManager = false })
        return
    }
    if (extensions) { OriginalExtensionsScreen(host.extensions, allowChanges = !state.working && state.turnId == null && !state.loading && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !fullTerminalState.running && !networkGit.running && !managedBusy, onBack = { extensions = false }); return }
    val active = state.working || state.turnId != null
    val editable = state.connected && !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !commandState.running && !fullTerminalState.running && !networkGit.running && !managedBusy && !active
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("原版 Codex，在手机上继续", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (fullTerminalState.running) Text("交互终端正在运行，请先在终端页关闭后再启动任务或修改配置。", color = MaterialTheme.colorScheme.primary)
                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text("原版会话单独保存。文件页选择“原版项目”查看任务文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (configurationReadError != null || configurationRecords.any { it.id != activeConfiguration }) item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        configurationReadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("上次原版操作的结果需要核查。请检查配置或命令产生的文件与外部变化；不会自动重复提交。")
                        configurationRecords.filter { it.id != activeConfiguration }.forEach { Text(it.kind, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { host.extensions.refresh(); host.mcp.refresh() }, enabled = state.connected && !state.loading && !extensionState.busy && !mcpState.busy) { Text("刷新核查") }
                            TextButton(onClick = { host.configurationJournal.confirmReviewed() }, enabled = activeConfiguration == null) { Text("我已核查实际状态") }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick={advancedManager="代理总览"},enabled=state.connected){Text("共享核心代理总览")} }
            item { TextButton(onClick={advancedManager="应用更新"}){Text("应用更新与签名包校验")} }
            item { OutlinedButton(onClick={advancedManager="本机连接服务"}){Text("本机代理、隧道与共享核心")} }
            item { OutlinedButton(onClick={advancedManager="云任务与本机维护"},enabled=!state.loading && !state.closing){Text("云任务与本机维护")} }
            if (!state.connected) item {
                Text("此入口使用随应用安装的原版核心，连接原版会话与账号。缺少运行核心时会提示具体原因。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { consent = true }, enabled = !state.loading && !state.closing && !cliStatus.busy) { Text(if (state.loading) "正在启动" else "启动原版核心") }
            }
            if(queueStatus.error!=null) item {
                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer){Column(Modifier.fillMaxWidth().padding(14.dp)){
                    Text(queueStatus.error.orEmpty());TextButton(onClick={sessionInitialTab="待办";sessionManager=true}){Text("查看待办并核查结果")}
                }}
            }
            if(searchStatus.error!=null) item {
                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer){Column(Modifier.fillMaxWidth().padding(14.dp)){
                    Text(searchStatus.error.orEmpty());TextButton(onClick={searchManager=true}){Text("查看搜索并核查停止状态")}
                }}
            }
            if (state.error != null) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = host::dismissError) { Text("知道了") } }
                }
            }
            state.events.notices.filter { it.threadId.isBlank() || it.threadId == state.threadId }.let { notices ->
                notices.lastOrNull { it.key.startsWith("guardianWarning") || it.key.startsWith("autoApprovalReview/strictReviewRequired") || it.key.startsWith("configWarning") } ?: notices.lastOrNull()
            }?.let { notice -> item {
                Surface(shape = RoundedCornerShape(14.dp),color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(notice.title,style = MaterialTheme.typography.titleSmall)
                        Text(notice.body.take(240),style = MaterialTheme.typography.bodySmall,maxLines = 4)
                        Row { TextButton(onClick = { eventsManager = true }) { Text("查看运行动态") };TextButton(onClick = { host.dismissRuntimeNotice(notice.key) }) { Text("知道了") } }
                    }
                }
            } }
            if (state.connected) {
                item {
                    OutlinedButton(onClick = { extensions = true; host.extensions.refresh() }, enabled = !state.loading) { Icon(Icons.Outlined.Extension, null); Spacer(Modifier.width(8.dp)); Text("插件与自动规则") }
                    OutlinedButton(onClick = { mcpManager = true; host.mcp.refresh() }, enabled = !state.loading) { Icon(Icons.Outlined.Hub, null); Spacer(Modifier.width(8.dp)); Text("MCP 服务管理") }
                    OutlinedButton(onClick = { commandManager = true }, enabled = state.connected && !state.loading) { Text("独立命令与交互输入") }
                    OutlinedButton(onClick = { settingsManager = true; host.settings.refresh() }, enabled = !state.loading) { Text("账号用量、策略与实验功能") }
                    OutlinedButton(onClick = { filesManager = true; host.files.refresh() }, enabled = !state.loading) { Text("原版项目文件") }
                    OutlinedButton(onClick = { skillsManager = true; host.extensions.refreshSkills() }, enabled = !state.loading) { Text("原版技能管理") }
                    TextButton(onClick = { eventsManager = true }) { Text("运行动态与能力") }
                    Row { TextButton(onClick={realtimeManager=true}){Text("实时文字与语音")};TextButton(onClick={searchManager=true}){Text("持续搜索项目")} }
                    listOf("项目管理", "SSH主机校验", "搜索与会话记录", "会话设置与记忆", "连接、订阅与认证", "市场、分享与应用", "授权、额度与反馈", "迁移、配置与工具").forEach { title -> OutlinedButton(onClick = { advancedManager = title }, enabled = !state.loading) { Text(title) } }
                    Text(state.account, style = MaterialTheme.typography.titleSmall)
                    Text("账号与 MCP 凭证由 Android Keystore 加密保存在手机，退出核心后仍可恢复。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { host.login(true) }, enabled = !state.loading && !active) { Text("设备码登录") }
                        OutlinedButton(onClick = { host.login(false) }, enabled = !state.loading && !active) { Text("浏览器登录") }
                        TextButton(onClick = { host.loginSavedApiKey() }, enabled = editable) { Text("使用设置中的密钥") }
                        TextButton(onClick = { host.logout() }, enabled = editable) { Text("退出账号") }
                        TextButton(onClick = host::close, enabled = !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !commandState.running && !fullTerminalState.running && !networkGit.running && !managedBusy && !active) { Text("关闭核心") }
                    }
                }
                state.mcpAuthorizationUrl?.let { url -> item {
                    Text("请在服务页面完成 ${state.mcpAuthorizationName.orEmpty()} 授权。")
                    Row {
                        OutlinedButton(onClick = { openOriginalLogin(context, url) }) { Text("打开服务授权") }
                        TextButton(onClick = host::dismissMcpLogin) { Text("关闭提示") }
                    }
                } }
                if (state.loginUrl != null) item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.loginCode?.let { Text("授权码：$it", fontWeight = FontWeight.SemiBold) }
                            Text("请在浏览器中完成账号授权。", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                Button(onClick = { openOriginalLogin(context, state.loginUrl.orEmpty()) }) { Text("打开登录页面") }
                                TextButton(onClick = { host.cancelLogin() }, enabled = !state.loading) { Text("取消登录") }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(state.model, host::selectModel, label = { Text("本次会话模型") }, enabled = editable && state.threadId == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { host.loadModels() }, enabled = editable) { Text("获取原版模型列表") }
                    if (state.models.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.models) { model -> FilterChip(selected = state.model == model, onClick = { host.selectModel(model) }, enabled = editable && state.threadId == null, label = { Text(model) }) } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { host.newThread() }, enabled = editable) { Text("新建会话") }
                        OutlinedButton(onClick = { host.refresh(!state.archived) }, enabled = editable) { Text(if (state.archived) "最近会话" else "已归档") }
                        TextButton(onClick = { host.refresh() }, enabled = !state.loading) { Text("刷新") }
                    }
                }
                if (state.threadUpdates.version > state.threadListVersion) item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("会话列表有变化", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { host.refresh() }, enabled = !state.loading) { Text("刷新列表") }
                        }
                    }
                }
                items(state.threads, key = { "thread:${it.string("id")}" }) { thread ->
                    val id = thread.string("id")
                    Surface(color = if (state.threadId == id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick = { host.openThread(id) }, enabled = editable) { Text(thread.string("name").ifBlank { thread.string("preview").ifBlank { "新对话" } }.take(100), Modifier.fillMaxWidth(), maxLines = 2) }
                            Text(OriginalSessionActions.threadStatus(state.threadStatuses[id]) + " · " + OriginalThreadUpdates.readLabel(state.threadUpdates.threads[id]), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { host.archive(id, !state.archived) }, enabled = editable) { Text(if (state.archived) "恢复会话" else "归档") }
                        }
                    }
                }
                if (state.nextCursor != null) item { TextButton(onClick = { host.refresh(more = true) }, enabled = !state.loading) { Text("加载更多会话") } }
                if (state.threadId != null) item { OriginalThreadSyncPanel(host, onAttachments = { sessionInitialTab = "资料"; sessionManager = true }, onHistory = { host.openThread(state.threadId!!) }) }
                if (state.threadId != null) item {
                    HorizontalDivider()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick={sessionInitialTab="待办";sessionManager=true},enabled=!state.loading){Text("待办队列")}
                        TextButton(onClick = { sessionInitialTab = "目标"; sessionManager = true }, enabled = !state.loading) { Text("目标与会话管理") }
                        TextButton(onClick = { host.fork() }, enabled = editable) { Text("分叉") }
                        TextButton(onClick = { renamed = "" }, enabled = editable) { Text("重命名") }
                        TextButton(onClick = { host.compact() }, enabled = editable) { Text("压缩上下文") }
                        listOf("配额", "技能", "MCP", "钩子", "插件").forEach { title -> TextButton(onClick = { if (title == "技能") { skillsManager = true; host.extensions.refreshSkills() } else host.inspect(title) }, enabled = !state.loading) { Text(title) } }
                    }
                }
                if (state.historyCursor != null) item { TextButton(onClick = { host.loadHistory() }, enabled = !state.loading) { Text("加载更早消息") } }
                items(state.items, key = { "item:${it.id}" }) { item ->
                    if (item.type in setOf("userMessage", "agentMessage")) {
                        Surface(shape = RoundedCornerShape(16.dp), color = if (item.type == "userMessage") MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(if (item.type == "userMessage") "你" else "Codex", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)); Text(item.text) }
                        }
                    } else if (item.type in setOf("enteredReviewMode", "exitedReviewMode")) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (item.type == "enteredReviewMode") "开始代码审查" else "代码审查结果", style = MaterialTheme.typography.titleMedium)
                                androidx.compose.foundation.text.selection.SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    } else Column {
                        ExpandableCard(originalItemTitle(item.type), item.text, Icons.Outlined.Code)
                        if (item.type == "collabAgentToolCall") (item.data["receiverThreadIds"] as? JsonArray).orEmpty().forEach { thread ->
                            TextButton(onClick = { host.inspectAgent(thread.jsonPrimitive.content) }, enabled = state.connected && !state.loading) { Text("查看代理进展 ${thread.jsonPrimitive.content.takeLast(8)}") }
                        }
                    }
                }
                state.turnProgress.entries.toList().takeLast(3).forEach { (turnId, progress) ->
                    item(key = "progress:$turnId") {
                        val details = remember(progress) { OriginalLiveUpdates.describe(progress) }
                        ExpandableCard("运行进展 · ${turnId.takeLast(8)}", details, Icons.Outlined.Info)
                    }
                }
                if (state.report.isNotBlank()) item { ExpandableCard("核心返回详情", state.report, Icons.Outlined.Info) }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.connected) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(draft, { draft = it }, label = { Text(if (active) "追加本次任务的指令" else "交给 Codex 的任务") }, modifier = Modifier.fillMaxWidth(), maxLines = 6, shape = RoundedCornerShape(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (active) TextButton(onClick = { host.stop() }) { Text("停止任务") }
                    Button(onClick = { if (active) host.steer(draft) else host.send(draft); draft = "" }, enabled = draft.isNotBlank() && !state.loading && !extensionState.mutating && !mcpState.mutating && !settingsState.mutating && !filesState.mutating && !commandState.running && !fullTerminalState.running && !networkGit.running && !managedBusy && host.selectedThreadUsable() && (!active || state.turnId != null)) { Text(if (active) "追加指令" else "发送") }
                }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("手机执行权限") }, text = { Text("原版核心将以本应用身份运行命令。Android 应用隔离无法把命令限制在单个项目目录内，命令也可能访问本应用的其他数据。该模式保留原版审批机制；请逐条检查命令和文件修改。是否在本次启动中继续？") }, confirmButton = { TextButton(onClick = { consent = false; host.connect() }) { Text("同意并启动") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } })
    renamed?.let { title -> AlertDialog(onDismissRequest = { renamed = null }, title = { Text("重命名原版会话") }, text = { OutlinedTextField(title, { renamed = it }, label = { Text("会话名称") }) }, confirmButton = { TextButton(onClick = { host.rename(title); renamed = null }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = { renamed = null }) { Text("取消") } }) }
}
private fun originalItemTitle(type: String) = when (type) { "reasoning" -> "思考过程"; "plan" -> "工作计划"; "commandExecution" -> "命令执行"; "fileChange" -> "文件修改"; "mcpToolCall" -> "MCP 工具"; "collabAgentToolCall" -> "代理协作"; "contextCompaction" -> "上下文压缩"; else -> "工具记录 · $type" }
internal fun openOriginalLogin(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1"))) {
        android.widget.Toast.makeText(context, "仅支持 HTTPS 或本机授权页面", android.widget.Toast.LENGTH_LONG).show(); return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { android.widget.Toast.makeText(context, "无法打开浏览器，请检查浏览器是否可用", android.widget.Toast.LENGTH_LONG).show() }
}

@Composable private fun OriginalRequestDialog(request: ServerMessage.Request, items: List<OriginalItem>, respond: (JsonElement) -> Unit,onVerify:()->Unit={},verifying:Boolean=false,verificationError:String?=null) {
    val params = request.params as? JsonObject ?: buildJsonObject {}
    if (request.method in setOf("execCommandApproval", "applyPatchApproval")) { OriginalLegacyApprovalDialog(request, respond); return }
    if (request.method == "item/permissions/requestApproval") { OriginalPermissionDialog(params, respond); return }
    var answers by remember(request) { mutableStateOf(emptyMap<String, String>()) }
    val question = request.method == "item/tool/requestUserInput"
    val elicitation = request.method == "mcpServer/elicitation/request"
    val context = LocalContext.current
    val formScope=rememberCoroutineScope()
    var preparedForm by remember(request){mutableStateOf<ElicitationForm?>(null)}
    var formLoading by remember(request){mutableStateOf(params["requestedSchema"] is JsonObject)}
    var formValidating by remember(request){mutableStateOf(false)}
    var declined by remember(request){mutableStateOf(false)}
    var decided by remember(request){mutableStateOf(false)}
    var validationJob by remember(request){mutableStateOf<Job?>(null)}
    var suppliedResources by remember(request){mutableStateOf<List<JsonObject>>(emptyList())}
    var resourceImportOwner by remember(request){mutableStateOf<ServerMessage.Request?>(null)}
    var resourceError by remember(request){mutableStateOf<String?>(null)}
    val resourcePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && resourceImportOwner===request && !decided && !declined){
            resourceImportOwner=null;validationJob?.cancel();formLoading=true;formScope.launch{try{
                val documents=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);val bytes=input.readNBytes(256*1024+1);require(bytes.size<=256*1024);SchemaResourceBundle.parse(bytes.toString(Charsets.UTF_8))}}
                if(!decided && !declined){suppliedResources=documents;resourceError=null}
            }catch(failure:Exception){if(failure is CancellationException)throw failure;resourceError="离线规则无法读取或资源标识无效，未改变当前规则";formLoading=false}}
        }
    }
    val form=preparedForm
    LaunchedEffect(request,suppliedResources){
        validationJob?.cancel();formLoading=true
        val prepared=withContext(Dispatchers.Default){runCatching{(params["requestedSchema"] as? JsonObject)?.let{ElicitationForm(it,suppliedResources)}}.getOrNull()}
        preparedForm=prepared;formLoading=false
        if(elicitation && prepared!=null && answers.isEmpty())answers=prepared.initialValues
    }
    var formError by remember(request) { mutableStateOf("") }
    val reviewedItem = items.firstOrNull { it.id == params.string("itemId") }
    val canReview = if (request.method.contains("fileChange")) reviewedItem?.type == "fileChange" && (reviewedItem.data["changes"] as? JsonArray)?.isNotEmpty() == true && reviewedItem.text.length <= 100_000 else params.string("command").isNotBlank() || reviewedItem?.type == "commandExecution"
    val questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun decision(allow: Boolean) = buildJsonObject { put("decision", if (allow) "accept" else "decline") }
    fun decline() { if(decided)return;decided=true;declined=true;validationJob?.cancel();respond(when { question -> buildJsonObject { put("answers", buildJsonObject {}) }; elicitation -> buildJsonObject { put("action", "decline"); put("content", JsonNull); put("_meta", JsonNull) }; else -> decision(false) }) }
    AlertDialog(onDismissRequest = ::decline, title = { Text(when { question -> "Codex 需要你的选择"; elicitation -> "工具服务请求信息"; request.method.contains("fileChange") -> "确认文件修改"; else -> "确认执行命令" }) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (question) questions.forEach { q ->
                val id = q.string("id")
                Text(q.string("question"))
                (q["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { option ->
                    FilterChip(selected = answers[id] == option.string("label"), onClick = { answers = answers + (id to option.string("label")) }, label = { Text(option.string("label")) })
                    Text(option.string("description"), style = MaterialTheme.typography.bodySmall)
                }
                if ((q["options"] as? JsonArray).isNullOrEmpty() || q["isOther"]?.jsonPrimitive?.booleanOrNull == true) OutlinedTextField(answers[id].orEmpty(), { answers = answers + (id to it) }, label = { Text(if (q["isSecret"]?.jsonPrimitive?.booleanOrNull == true) "保密回答" else "你的回答") }, visualTransformation = if (q["isSecret"]?.jsonPrimitive?.booleanOrNull == true) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth())
            } else if (elicitation) {
                Text(params.string("message", "服务请求补充信息"))
                if(params.string("mode")=="openai/userVerification"){
                    Text(params.string("title"),style=MaterialTheme.typography.titleMedium);Text(params.string("description"))
                    Text("确认后由系统强生物认证签署此挑战，私钥保留在手机。",style=MaterialTheme.typography.bodySmall)
                    if(verifying)LinearProgressIndicator(Modifier.fillMaxWidth())
                    verificationError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                } else if (params.string("mode") == "url") {
                    Text(params.string("url"), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { openOriginalLogin(context, params.string("url")) }) { Text("打开授权页面") }
                    Text("完成服务页面的授权后，再确认返回工具。", style = MaterialTheme.typography.bodySmall)
                } else if(formLoading){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在准备表单规则…")} else if (form?.supported == true) {
                    if (form.rawInput) OutlinedTextField(answers["__form_json"].orEmpty(), { answers = answers + ("__form_json" to it) }, label = { Text("表单内容（JSON）") }, modifier = Modifier.fillMaxWidth())
                    else form.fields.forEach { field ->
                        Text(field.title + if (field.required) " *" else "")
                        if (field.description.isNotBlank()) Text(field.description, style = MaterialTheme.typography.bodySmall)
                        if(field.multiOptions!=null){
                            val selected=runCatching{Json.parseToJsonElement(answers[field.name] ?: "[]").jsonArray}.getOrDefault(JsonArray(emptyList()))
                            field.multiOptions!!.forEachIndexed{index,option->FilterChip(selected=option in selected,onClick={val next=if(option in selected)selected.filterNot{it==option}else selected+option;answers=answers+(field.name to JsonArray(next).toString())},label={Text(field.multiLabel(index))})}
                        } else if (field.options != null) field.options!!.forEachIndexed { index, option ->
                            field.imageOptions.getOrNull(index)?.let{OriginalImageChoicePreview(it)}
                            val value = option.jsonPrimitive.content
                            FilterChip(answers[field.name] == value, { answers = answers + (field.name to value) }, label = { Text(field.label(index)) })
                        } else if (field.type == "boolean") Row {
                            listOf("true" to "是", "false" to "否").forEach { (value, label) -> FilterChip(answers[field.name] == value, { answers = answers + (field.name to value) }, label = { Text(label) }) }
                        } else OutlinedTextField(answers[field.name].orEmpty(), { answers = answers + (field.name to it) }, label = { Text(if (field.type in setOf("json", "object", "array")) "${field.title}（JSON）" else field.title) }, modifier = Modifier.fillMaxWidth())
                    }
                } else Text("此表单包含无法离线解析的引用或验证规则，可拒绝后继续其他任务。", style = MaterialTheme.typography.bodySmall)
                if(params["requestedSchema"] is JsonObject){
                    TextButton(onClick={resourceImportOwner=request;resourcePicker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!decided && !formLoading && !formValidating){Text("选择本次表单缺失的离线规则")}
                    if(suppliedResources.isNotEmpty()){
                        Text("已加入${suppliedResources.size}份规则，仅用于本次验证：",style=MaterialTheme.typography.bodySmall)
                        suppliedResources.forEach{Text(it.string("\$id"),style=MaterialTheme.typography.bodySmall)}
                        TextButton(onClick={validationJob?.cancel();formLoading=true;suppliedResources=emptyList()},enabled=!decided && !formValidating){Text("移除附加规则")}
                    }
                    Text("规则文件应来自你信任的服务方。选择文件不会自动批准或发送填写内容，也不会从引用地址联网下载。",style=MaterialTheme.typography.bodySmall)
                    resourceError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                }
                if(formValidating){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在核对填写内容…")}
                if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error)
            } else {
                val item = items.firstOrNull { it.id == params.string("itemId") }
                Text(params.string("reason", "请检查此操作的范围和内容"))
                if (params.string("command").isNotBlank()) Text(params.string("command"))
                if (params.string("cwd").isNotBlank()) Text("目录：${params.string("cwd")}")
                item?.let { Text(it.text.take(100_000)) }
                if (!canReview) Text("缺少可完整查看的操作内容，请拒绝后重新请求。", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        if(elicitation && params.string("mode")=="openai/userVerification")TextButton(onClick=onVerify,enabled=!verifying && !decided){Text(if(verifying)"正在系统验证" else "验证并同意")}
        else if (elicitation && (params.string("mode") == "url" || form?.supported == true)) TextButton(onClick = {
            if(!formValidating && !formLoading && !decided){
                val approvedForm=form;val approvedResources=suppliedResources
                val snapshot=answers;formValidating=true;formError=""
                validationJob=formScope.launch{
                    try{
                        val content=withContext(Dispatchers.Default){if(params.string("mode")=="url")JsonNull else requireNotNull(approvedForm).content(snapshot)}
                        if(!declined){
                            check(answers==snapshot && preparedForm===approvedForm && suppliedResources===approvedResources){"填写内容或规则已变化，请核对后重新提交"}
                            decided=true
                            respond(buildJsonObject{put("action","accept");put("content",content);put("_meta",JsonNull)})
                        }
                    }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){if(!declined)formError=failure.message ?: "请检查填写内容"}
                    finally{formValidating=false}
                }
            }
        },enabled=!formLoading && !formValidating && !decided) { Text(if (params.string("mode") == "url") "已完成授权" else "提交表单") }
        else if (!elicitation) TextButton(onClick = {
            if(!decided){
            val result = if (question) buildJsonObject {
                put("answers", buildJsonObject {
                    questions.forEach { q ->
                        put(q.string("id"), buildJsonObject {
                            put("answers", buildJsonArray { add(answers[q.string("id")].orEmpty()) })
                        })
                    }
                })
            } else decision(true)
            decided=true;respond(result)
            }
        }, enabled = !decided && (if (question) questions.all { !answers[it.string("id")].isNullOrBlank() } else canReview)) { Text(if (question) "提交回答" else "允许此次操作") }
    }, dismissButton = { TextButton(onClick = ::decline) { Text(if (question) "暂不回答" else "拒绝") } })
}

@Composable private fun OriginalLegacyApprovalDialog(request: ServerMessage.Request, respond: (JsonElement) -> Unit) {
    val preview = remember(request) { OriginalLegacyApproval.preview(request.method, request.params as? JsonObject ?: buildJsonObject {}) }
    fun decline() = respond(OriginalLegacyApproval.decision(false))
    AlertDialog(onDismissRequest = ::decline, title = { Text(preview.title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer { Text(preview.body, style = MaterialTheme.typography.bodySmall) }
            if (!preview.reviewable) Text("无法完整核对内容，只能拒绝此次请求。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { respond(OriginalLegacyApproval.decision(true)) }, enabled = preview.reviewable) { Text("允许此次请求") } },
        dismissButton = { TextButton(onClick = ::decline) { Text("拒绝") } })
}
