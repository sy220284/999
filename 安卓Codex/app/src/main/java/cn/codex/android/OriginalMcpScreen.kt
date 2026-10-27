package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

private data class McpConfirmation(val entry: OriginalMcpEntry, val snapshot: OriginalConfigSnapshot, val delete: Boolean)

@Composable fun OriginalMcpScreen(controller: OriginalMcpController, allowChanges: Boolean, imports: List<Pair<String, String>>,
    authorizationUrl: String?, authorizationName: String?, hostError: String?, onDismissHostError: () -> Unit,
    onLogin: (String) -> Unit, onDismissAuthorization: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    var search by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<McpConfirmation?>(null) }
    var showImports by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.connected) { if (!state.connected) { confirmation = null; showImports = false } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("MCP 服务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("连接你的工具与资料", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp)); Text(if (state.connected) state.notice else "原版核心已断开，请返回重新连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.connected) Text(if (state.inventoryThread == null) "当前展示全局服务状态" else "当前展示所选会话的服务状态", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = controller::refresh, enabled = state.connected && !state.busy) { Text("刷新状态") }
                    TextButton(onClick = controller::reconnect, enabled = state.connected && !state.busy && allowChanges) { Text("重新连接") }
                    FilledTonalButton(onClick = { controller.edit() }, enabled = state.snapshot != null && !state.busy && allowChanges) { Text("添加服务") }
                }
                if (imports.isNotEmpty()) TextButton(onClick = { showImports = true }, enabled = state.snapshot != null && !state.busy && allowChanges) { Text("从连接设置导入") }
                if (state.snapshot == null && state.connected) Text("没有可写的用户配置版本，当前只能查看；可刷新后重试。", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { message -> item { McpError(message, controller::dismissError) } }
            hostError?.let { message -> item { McpError(message, onDismissHostError) } }
            state.inventoryError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            authorizationUrl?.let { url -> item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("请完成 ${authorizationName.orEmpty()} 的授权")
                        Row {
                            TextButton(onClick = { openOriginalLogin(context, url) }) { Text("打开授权页面") }
                            TextButton(onClick = onDismissAuthorization) { Text("关闭提示") }
                        }
                        Text("关闭提示不会取消服务端正在等待的授权。完成后刷新服务状态。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            item { OutlinedTextField(search, { search = it }, label = { Text("搜索服务") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            val entries = state.entries.filter { search.isBlank() || it.name.contains(search, true) }
            if (entries.isEmpty()) item { Text(if (state.busy) "正在读取服务…" else "暂无匹配的服务，可以添加一个远程服务，或连接手机上已有的程序。") }
            items(entries, key = { it.name }) { entry ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = if (motion) Modifier.animateContentSize(tween(220)) else Modifier) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        val runtime = entry.runtime
                        Text(if (runtime == null) "尚未取得运行状态" else "上次读取：${mcpRuntimeTitle(runtime.string("runtimeStatus"))}", color = MaterialTheme.colorScheme.primary)
                        Text("${if (entry.enabled) "配置已启用" else "配置已停用"} · ${if (entry.editable) "用户配置" else if (runtime?.string("pluginId").isNullOrBlank()) "其他配置来源" else "插件提供"}", style = MaterialTheme.typography.labelMedium)
                        // 地址只展示服务端给出的 origin，避免把查询参数或配置中的凭证带到列表。
                        runtime?.string("httpOrigin")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (runtime != null) {
                            val auth = when (runtime.string("authStatus")) { "notLoggedIn" -> "尚未授权"; "oAuth" -> "已使用账号授权"; "bearerToken" -> "使用令牌"; "unsupported" -> "不支持账号授权"; else -> "授权状态待确认" }
                            Text(auth, style = MaterialTheme.typography.bodySmall)
                            runtime.string("toolsError").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            val tools = (runtime["tools"] as? JsonObject).orEmpty()
                            val resources = (runtime["resources"] as? JsonArray).orEmpty()
                            val templates = (runtime["resourceTemplates"] as? JsonArray).orEmpty()
                            ExpandableCard("${tools.size} 项工具 · ${resources.size} 项资料 · ${templates.size} 项资料模板",
                                (tools.map { (name, value) -> name + (value as? JsonObject)?.string("description").orEmpty().let { if (it.isBlank()) "" else "：$it" } } +
                                    resources.mapNotNull { it as? JsonObject }.map { it.string("name") + " · " + it.string("uri") } +
                                    templates.mapNotNull { it as? JsonObject }.map { it.string("name") + " · " + it.string("uriTemplate") }).joinToString("\n").ifBlank { "目录为空或尚未取得，请刷新后核查。" }, Icons.Outlined.Hub)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            val editable = state.snapshot != null && !state.busy && allowChanges && entry.editable
                            TextButton(onClick = { controller.edit(entry.name) }, enabled = editable) { Text("编辑") }
                            TextButton(onClick = { controller.edit(entry.name, advanced = true) }, enabled = editable) { Text("高级配置") }
                            TextButton(onClick = { confirmation = McpConfirmation(entry, state.snapshot!!, false) }, enabled = editable) { Text(if (entry.enabled) "停用" else "启用") }
                            TextButton(onClick = { confirmation = McpConfirmation(entry, state.snapshot!!, true) }, enabled = editable) { Text("删除") }
                            if (runtime?.string("authStatus") in setOf("notLoggedIn", "oAuth")) TextButton(onClick = { onLogin(entry.name) }, enabled = state.connected && !state.busy && allowChanges && authorizationUrl == null) { Text("账号授权") }
                        }
                        if (!entry.editable) Text("请在对应项目配置或插件入口修改此服务。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.nextCursor != null) item { OutlinedButton(onClick = controller::more, enabled = !state.busy) { Text("加载更多服务") } }
        }
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (request.delete) "删除用户服务配置" else if (request.entry.enabled) "停用服务" else "启用服务") },
            text = { Text("${request.entry.name}\n\n" + if (request.delete) "删除此用户配置并重新连接。其他来源若有同名配置，删除后可能继续生效。已保存的账号授权不会随配置删除。" else "保存后会重新连接服务，并核查实际状态；项目或管理策略可能覆盖用户设置。") },
            confirmButton = { TextButton(onClick = { confirmation = null; if (request.delete) controller.delete(request.entry, request.snapshot) else controller.toggle(request.entry, request.snapshot) }, enabled = state.connected && !state.busy && allowChanges) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
    if (showImports) AlertDialog(onDismissRequest = { showImports = false }, title = { Text("从连接设置导入") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("选择连接后核对信息，保存才会写入原版配置。")
            imports.forEach { (name, url) -> TextButton(onClick = { showImports = false; controller.importConnection(name, url) }) { Text(name) } }
        }
    }, confirmButton = { TextButton(onClick = { showImports = false }) { Text("关闭") } })
    state.editor?.let { editor -> if (editor.advanced) OriginalMcpAdvancedEditor(editor, state.busy, allowChanges && state.connected, state.error, controller::closeEditor) { config -> controller.save(editor, requireNotNull(editor.name), config) } else OriginalMcpEditorSheet(editor, state.busy, allowChanges && state.connected, state.error, controller::closeEditor) { name, config -> controller.save(editor, name, config) } }
}

@Composable private fun McpError(message: String, dismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(message); TextButton(onClick = dismiss) { Text("知道了") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OriginalMcpEditorSheet(editor: OriginalMcpEditor, busy: Boolean, enabled: Boolean, error: String?,
    dismiss: () -> Unit, save: (String, JsonObject) -> Unit) {
    val original = editor.original
    var name by remember(editor) { mutableStateOf(editor.name ?: editor.suggestedName) }
    var http by remember(editor) { mutableStateOf(!original.containsKey("command")) }
    var url by remember(editor) { mutableStateOf(original.string("url")) }
    var command by remember(editor) { mutableStateOf(original.string("command")) }
    fun lines(key: String) = (original[key] as? JsonArray).orEmpty().joinToString("\n") { if (it is JsonObject) it.string("name") else it.jsonPrimitive.content }
    var args by remember(editor) { mutableStateOf(lines("args")) }
    var cwd by remember(editor) { mutableStateOf(original.string("cwd")) }
    var env by remember(editor) { mutableStateOf(lines("env_vars")) }
    var bearer by remember(editor) { mutableStateOf(original.string("bearer_token_env_var")) }
    var scopes by remember(editor) { mutableStateOf(lines("scopes")) }
    var startup by remember(editor) { mutableStateOf(original.string("startup_timeout_sec").ifBlank { original["startup_timeout_ms"]?.jsonPrimitive?.doubleOrNull?.div(1000)?.toString().orEmpty() }) }
    var timeout by remember(editor) { mutableStateOf(original.string("tool_timeout_sec")) }
    var active by remember(editor) { mutableStateOf(original["enabled"]?.jsonPrimitive?.booleanOrNull ?: true) }
    var formError by remember(editor) { mutableStateOf<String?>(null) }
    val currentBusy by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !currentBusy })
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (editor.name == null) "添加 MCP 服务" else "编辑 ${editor.name}", style = MaterialTheme.typography.titleLarge)
            Text("连接信息保存在手机的原版用户配置中；其他高级设置会保留。", style = MaterialTheme.typography.bodySmall)
            original.string("environment_id").takeIf { it.isNotBlank() && it != "local" }?.let { Text("原配置执行环境：$it；此处编辑会保留该归属。", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(name, { name = it }, enabled = !busy && editor.name == null, singleLine = true, label = { Text("服务标识") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(http, { http = true }, enabled = !busy, label = { Text("远程服务") })
                FilterChip(!http, { http = false }, enabled = !busy, label = { Text("手机上的程序") })
            }
            if (http) {
                OutlinedTextField(url, { url = it }, enabled = !busy, label = { Text("服务地址") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bearer, { bearer = it }, enabled = !busy, label = { Text("令牌环境变量名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(scopes, { scopes = it }, enabled = !busy, label = { Text("授权范围（每行一项，可选）") }, modifier = Modifier.fillMaxWidth())
                Text("需要账号授权时，保存后点击服务卡片的“账号授权”。", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("程序需要已安装在手机可执行的位置。当前没有内置完整 Node、Python 或 npm，填写命令不会自动安装运行环境。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(command, { command = it }, enabled = !busy, label = { Text("程序路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(args, { args = it }, enabled = !busy, label = { Text("参数（每行一个，原样传入）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cwd, { cwd = it }, enabled = !busy, label = { Text("工作目录（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(env, { env = it }, enabled = !busy, label = { Text("继承的环境变量名（每行一个，可选）") }, modifier = Modifier.fillMaxWidth())
            }
            if (http == original.containsKey("command") && original.isNotEmpty()) Text("切换连接方式会清除原连接专用的字段，请核对后保存。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(startup, { startup = it }, enabled = !busy, label = { Text("连接超时（秒，留空使用默认值）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(timeout, { timeout = it }, enabled = !busy, label = { Text("工具调用超时（秒，留空使用默认值）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("启用服务", modifier = Modifier.padding(top = 12.dp)); Switch(active, { active = it }, enabled = !busy) }
            (formError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                try {
                    val config = OriginalMcpConfig.form(original, url, command, args, cwd, env, bearer, scopes, startup, timeout, active, http)
                    OriginalMcpConfig.edit(name, config)
                    formError = null; save(name, config)
                } catch (failure: IllegalArgumentException) { formError = failure.message }
            }, enabled = !busy && enabled, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存并重新连接") }
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}


@Composable private fun OriginalMcpAdvancedEditor(editor: OriginalMcpEditor, busy: Boolean, enabled: Boolean, error: String?, dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    var text by remember(editor) { mutableStateOf(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), editor.original)) }
    var localError by remember(editor) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("${editor.name} · 高级配置") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("编辑原版完整用户条目：工具允许/禁用名单、每工具审批与输出预算、环境、请求头及授权配置都在此处保存。原版负责校验字段，保存后重新连接。", style = MaterialTheme.typography.bodySmall)
            Text("此处内容会写入用户配置文件。账号授权请优先使用服务卡片的授权入口。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("服务配置（JSON 对象）") }, modifier = Modifier.fillMaxWidth())
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { Json.parseToJsonElement(text).jsonObject }.onSuccess { localError = null; save(it) }.onFailure { localError = "请填写有效的 JSON 对象" }
    }, enabled = !busy && enabled) { Text(if (busy) "正在保存…" else "保存并重新连接") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
