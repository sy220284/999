package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalSessionScreen(host: OriginalCoreHost, initialTab: String = "目标", onBack: () -> Unit) {
    val state by host.state.collectAsState()
    val extensions by host.extensions.state.collectAsState()
    val mcp by host.mcp.state.collectAsState()
    val settings by host.settings.state.collectAsState()
    val files by host.files.state.collectAsState()
    val networkGit by (androidx.compose.ui.platform.LocalContext.current.applicationContext as CodexApplication).gitOperations.state.collectAsState()
    val fullTerminal by (androidx.compose.ui.platform.LocalContext.current.applicationContext as CodexApplication).fullTerminal.state.collectAsState()
    val commands by host.commands.state.collectAsState()
    val libraryStatus by host.library.operations.state.collectAsState()
    val servicesStatus by host.services.operations.state.collectAsState()
    val queueStatus by host.queue.operations.state.collectAsState()
    val realtimeStatus by host.realtime.state.collectAsState()
    val migrationStatus by host.migration.operations.state.collectAsState()
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var goalEdit by remember { mutableStateOf(false) }
    var sectionEdit by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var attachmentEdit by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    var ownerTypeInput by remember(state.ownerType) { mutableStateOf(state.ownerType) }
    var ownerKeyInput by remember(state.ownerKey) { mutableStateOf(state.ownerKey) }
    var before by remember(state.threadId) { mutableStateOf("") }
    val editable = state.connected && !state.loading && !state.working && state.turnId == null && !extensions.mutating && !mcp.mutating && !settings.mutating && !files.mutating && !commands.running && !fullTerminal.running && !networkGit.running && !libraryStatus.mutating && !servicesStatus.mutating && !migrationStatus.mutating && !realtimeStatus.running && !queueStatus.busy && host.selectedThreadUsable()
    BackHandler(onBack = onBack)
    LaunchedEffect(state.connectionEpoch, state.threadId) { confirm = null; goalEdit = false; sectionEdit = null; attachmentEdit = false }
    LaunchedEffect(tab, state.threadId) {
        if (state.threadId != null || tab in setOf("分区", "内存会话", "资料所属", "高级操作")) when (tab) { "目标" -> host.loadGoal(); "资料" -> host.loadAttachments(); "分区" -> host.loadSections(); "历史" -> host.loadTurns(); "Git 资料" -> host.loadGitMetadata(); "内存会话" -> host.loadLoadedThreads() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("会话管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("目标", "待办", "资料", "分区", "历史", "代码审查", "Git 资料", "内存会话", "资料所属", "高级操作").forEach { title -> FilterChip(tab == title, { tab = title }, enabled = !state.loading, label = { Text(title) }) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(state.threadId?.let { "当前会话：$it" } ?: "请先返回选择会话，或管理全局分区。", style = MaterialTheme.typography.bodySmall); Text(state.status, color = MaterialTheme.colorScheme.primary) }
            if (state.threadId != null) item { OriginalThreadSyncPanel(host, onAttachments = { tab = "资料"; host.loadAttachments() }, onHistory = { host.openThread(state.threadId!!) }) }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = host::dismissError) { Text("知道了") } } }
            if(tab=="待办" && state.threadId!=null)item{OriginalQueuePanel(host,onBack)}
            if (tab == "高级操作" && state.threadId != null) item { OriginalThreadAdvanced(host, editable, onBack) }
            if (tab == "代码审查" && state.threadId != null) item {
                OriginalReviewForm(editable) { type, value, title -> host.startReview(type, value, title); if (host.state.value.working) onBack() }
            }
            if (tab == "Git 资料" && state.threadId != null) item {
                ThreadGitEditor(state.threadId!!, state.threadGitInfo, editable, host::loadGitMetadata, host::saveGitMetadata)
            }
            if (tab == "内存会话") {
                item { Text("这里列出核心当前加载的会话。关闭视图或核心后，列表会变化。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { host.loadLoadedThreads() }, enabled = !state.loading) { Text("刷新已加载会话") } }
                items(state.loadedThreads.entries, key = { "loaded:$it" }) { id ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(id); Text(OriginalSessionActions.threadStatus(state.threadStatuses[id]), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { host.openThread(id); onBack() }, enabled = editable) { Text(if (id == state.threadId) "刷新当前会话" else "打开会话") }
                    } }
                }
                if (state.loadedThreads.nextCursor != null) item { TextButton(onClick = { host.loadLoadedThreads(true) }, enabled = !state.loading) { Text("加载更多") } }
                if (!state.loading && state.loadedThreads.entries.isEmpty()) item { Text("核心当前没有返回已加载会话") }
            }
            if (tab == "资料所属") {
                item {
                    Text("按资料记录的类型和标识查找所属会话，包含已归档会话。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(ownerTypeInput, { ownerTypeInput = it }, enabled = !state.loading, label = { Text("资料类型") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ownerKeyInput, { ownerKeyInput = it }, enabled = !state.loading, label = { Text("资料唯一标识") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { host.loadAttachmentOwners(ownerTypeInput, ownerKeyInput) }, enabled = !state.loading && ownerTypeInput.isNotBlank() && ownerKeyInput.isNotBlank()) { Text("查找所属会话") }
                }
                if (state.ownerKey.isNotBlank()) item { Text("上次查询：${state.ownerType} / ${state.ownerKey}", style = MaterialTheme.typography.labelSmall) }
                items(state.attachmentOwners.entries, key = { "owner:" + it.string("threadId") }) { owner ->
                    val id = owner.string("threadId")
                    Column { val archived = owner["archived"]?.jsonPrimitive?.booleanOrNull == true; Text(id); Text(if (archived) "已归档，可在已归档列表恢复后打开" else "未归档", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { host.openThread(id); onBack() }, enabled = editable && !archived) { Text("打开会话") } }
                }
                if (state.ownerKey.isNotBlank() && state.attachmentOwners.entries.isEmpty() && !state.loading) item { Text("没有找到持有此资料记录的会话") }
                if (state.attachmentOwners.nextCursor != null) item { TextButton(onClick = { host.loadAttachmentOwners(state.ownerType, state.ownerKey, true) }, enabled = !state.loading) { Text("加载更多所属会话") } }
            }
            if (tab == "目标" && state.threadId != null) {
                item {
                    val goal = state.goal
                    Text(goal?.string("objective") ?: "尚未设置目标", style = MaterialTheme.typography.titleMedium)
                    if (goal != null) Text("${goalStatusTitle(goal.string("status"))} · 已用 ${goal["tokensUsed"]} 令牌 · ${goal["timeUsedSeconds"]} 秒\n预算：${goal["tokenBudget"]?.takeUnless { it == JsonNull } ?: "未设置"}")
                    Row { TextButton(onClick = { host.loadGoal() }, enabled = !state.loading) { Text("刷新") }; FilledTonalButton(onClick = { goalEdit = true }, enabled = editable) { Text("设置目标") }; TextButton(onClick = { confirm = "清除目标" to buildJsonObject {} }, enabled = editable && goal != null) { Text("清除") } }
                }
            }
            if (tab == "资料" && state.threadId != null) {
                item { Text("资料记录保存在当前会话。保存记录不会自动把文件内容发送给模型。", style = MaterialTheme.typography.bodySmall); Row { TextButton(onClick = { host.loadAttachments() }, enabled = !state.loading) { Text("刷新") }; FilledTonalButton(onClick = { attachmentEdit = true }, enabled = editable) { Text("添加资料记录") } } }
                items(state.attachments, key = { it.string("id") }) { attachment ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(attachment.string("identityKey"), style = MaterialTheme.typography.titleSmall)
                        Text("类型：${attachment.string("attachmentType")}")
                        Text(attachment["payload"].toString(), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { tab = "资料所属"; host.loadAttachmentOwners(attachment.string("attachmentType"), attachment.string("identityKey")) }, enabled = !state.loading) { Text("查找所属会话") }
                        TextButton(onClick = { confirm = "删除资料记录" to attachment }, enabled = editable) { Text("删除") }
                    } }
                }
                if (state.attachmentCursor != null) item { TextButton(onClick = { host.loadAttachments(true) }, enabled = !state.loading) { Text("加载更多资料") } }
            }
            if (tab == "分区") {
                item { Row { TextButton(onClick = { host.loadSections() }, enabled = !state.loading) { Text("刷新") }; FilledTonalButton(onClick = { sectionEdit = null to "" }, enabled = editable) { Text("新建分区") } }
                    if (state.threadId != null) { OutlinedTextField(before, { before = it }, label = { Text("移至此会话之前（标识，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { host.moveSection(null, before.ifBlank { null }) }, enabled = editable) { Text("将当前会话移出分区") } }
                }
                items(state.sections, key = { it.string("id") }) { section ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(section.string("name"), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { sectionEdit = section.string("id") to section.string("name") }, enabled = editable) { Text("重命名") }
                            TextButton(onClick = { host.moveSection(section.string("id"), before.ifBlank { null }) }, enabled = editable && state.threadId != null) { Text("移入当前会话") }
                            TextButton(onClick = { confirm = "删除分区" to section }, enabled = editable) { Text("删除分区") }
                        }
                    } }
                }
                if (state.sectionCursor != null) item { TextButton(onClick = { host.loadSections(true) }, enabled = !state.loading) { Text("加载更多分区") } }
            }
            if (tab == "历史" && state.threadId != null) {
                item { Text("回退只删除选定回合及之后的聊天记录，项目文件不会撤销。", color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = { host.loadTurns() }, enabled = !state.loading) { Text("刷新回合") } }
                items(state.turns, key = { it.string("id") }) { turn ->
                    Column {
                        val text = (turn["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { cn.codex.core.original.OriginalThreadView.item(it).text }.joinToString("\n")
                        ExpandableCard("${turn.string("id")} · ${when (turn.string("status")) { "completed" -> "已完成"; "inProgress" -> "进行中"; "failed" -> "失败"; "interrupted" -> "已中断"; else -> "状态待确认" }}", text, Icons.Outlined.History)
                        TextButton(onClick = { confirm = "回退聊天记录" to turn }, enabled = editable) { Text("回退至此回合之前") }
                    }
                }
                if (state.turnCursor != null) item { TextButton(onClick = { host.loadTurns(true) }, enabled = !state.loading) { Text("加载更早回合") } }
                item { HorizontalDivider(); Row {
                    TextButton(onClick = { confirm = "关闭会话视图" to buildJsonObject {} }, enabled = editable) { Text("关闭当前会话视图") }
                    TextButton(onClick = { confirm = "永久删除会话" to buildJsonObject {} }, enabled = editable) { Text("删除会话") }
                } }
            }
        }
    }
    if (goalEdit) GoalEditor(state.goal, editable, { goalEdit = false }) { objective, status, budget -> goalEdit = false; host.saveGoal(objective, status, budget) }
    sectionEdit?.let { (id, value) ->
        var name by remember(id, value) { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { sectionEdit = null }, title = { Text(if (id == null) "新建分区" else "重命名分区") }, text = { OutlinedTextField(name, { name = it }, label = { Text("分区名称") }) }, confirmButton = { TextButton(onClick = { sectionEdit = null; host.saveSection(id, name) }, enabled = editable && name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = { sectionEdit = null }) { Text("取消") } })
    }
    if (attachmentEdit) AttachmentEditor(editable, { attachmentEdit = false }) { type, key, payload -> attachmentEdit = false; host.addAttachment(type, key, payload) }
    confirm?.let { (action, value) ->
        val description = when (action) { "回退聊天记录" -> "删除此回合及之后的聊天，无法通过本页恢复；项目文件不撤销。"; "永久删除会话" -> "删除当前会话的持久记录。此操作不可通过本页恢复；项目文件保留。"; "关闭会话视图" -> "停止订阅当前会话，持久聊天记录保留。"; "删除分区" -> "删除这个分区，具体会话归属将以原版返回结果为准。"; else -> "确认执行 $action？" }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(action) }, text = { Text(description) }, confirmButton = { TextButton(onClick = {
            confirm = null
            when (action) { "清除目标" -> host.clearGoal(); "删除资料记录" -> host.removeAttachment(value.string("attachmentType"), value.string("identityKey")); "删除分区" -> host.deleteSection(value.string("id")); "回退聊天记录" -> host.revert(value.string("id")); "永久删除会话" -> host.removeThread(true); "关闭会话视图" -> host.removeThread(false) }
        }, enabled = editable) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

@Composable private fun GoalEditor(goal: JsonObject?, enabled: Boolean, dismiss: () -> Unit, save: (String, String, Long?) -> Unit) {
    var objective by remember { mutableStateOf(goal?.string("objective").orEmpty()) }
    var status by remember { mutableStateOf(goal?.string("status") ?: "active") }
    var budget by remember { mutableStateOf(goal?.get("tokenBudget")?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("会话目标") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(objective, { objective = it }, label = { Text("希望完成什么") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("active", "paused", "blocked", "usageLimited", "budgetLimited", "complete").forEach { value -> FilterChip(status == value, { status = value }, label = { Text(goalStatusTitle(value)) }) } }
        OutlinedTextField(budget, { budget = it }, label = { Text("令牌预算（留空清除）") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        val amount = budget.toLongOrNull()
        if (budget.isNotBlank() && (amount == null || amount < 0)) error = "预算需为不小于 0 的整数" else save(objective, status, amount)
    }, enabled = enabled && objective.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun AttachmentEditor(enabled: Boolean, dismiss: () -> Unit, save: (String, String, JsonElement) -> Unit) {
    var type by remember { mutableStateOf("note") }; var key by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
    var json by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("添加资料记录") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(type, { type = it }, label = { Text("类型") }); OutlinedTextField(key, { key = it }, label = { Text("唯一标识") })
        FilterChip(json, { json = !json }, label = { Text("内容使用 JSON") })
        OutlinedTextField(text, { text = it }, label = { Text(if (json) "JSON 内容" else "资料正文") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        runCatching { if (json) Json.parseToJsonElement(text) else JsonPrimitive(text) }.onSuccess { save(type, key, it) }.onFailure { error = "JSON 格式不正确" }
    }, enabled = enabled && type.isNotBlank() && key.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
private fun goalStatusTitle(value: String) = when (value) { "active" -> "进行中"; "paused" -> "暂停"; "blocked" -> "遇到阻碍"; "usageLimited" -> "额度受限"; "budgetLimited" -> "预算受限"; "complete" -> "已完成"; else -> value }
