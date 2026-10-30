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
    onLogin: (String,McpLoginOptions) -> Unit, onDismissAuthorization: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    var loginScopes by remember{mutableStateOf("")};var registration by remember{mutableStateOf("auto")};var loginTimeout by remember{mutableStateOf("180")}
    var overrideScopes by remember{mutableStateOf(false)}
    var loginError by remember{mutableStateOf<String?>(null)}
    var search by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<McpConfirmation?>(null) }
    var toolCall by remember { mutableStateOf<Triple<String, String, JsonObject>?>(null) }
    var resourceServer by remember { mutableStateOf<String?>(null) }
    var resourceUri by remember { mutableStateOf("") }
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
                Text("本次OAuth登录选项",style=MaterialTheme.typography.titleSmall)
                Row{listOf("auto" to "自动发现","cimd" to "CIMD","dcr" to "动态注册").forEach{(key,label)->FilterChip(registration==key,{registration=key},label={Text(label)},enabled=!state.busy && authorizationUrl==null)}}
                Row{Checkbox(overrideScopes,{overrideScopes=it},enabled=!state.busy && authorizationUrl==null);Text("本次指定范围（空白表示明确不请求范围）")}
                OutlinedTextField(loginScopes,{loginScopes=it},label={Text("本次授权范围（每行一项，可选）")},enabled=overrideScopes && !state.busy && authorizationUrl==null,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(loginTimeout,{loginTimeout=it},label={Text("授权等待秒数（1–3600）")},enabled=!state.busy && authorizationUrl==null)
                Text("只影响下一次授权，不修改服务持久配置。注销已有服务凭证请先断开核心，在“云任务与本机维护”中预览并确认注销。",style=MaterialTheme.typography.bodySmall)
                loginError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
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
                        if (runtime != null) {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                (runtime["tools"] as? JsonObject).orEmpty().forEach { (name, metadata) ->
                                    TextButton(onClick = { toolCall = Triple(entry.name, name, metadata as? JsonObject ?: buildJsonObject {}) }, enabled = !state.busy && allowChanges) { Text("调用 $name") }
                                }
                                TextButton(onClick = { resourceServer = entry.name; resourceUri = "" }, enabled = !state.busy) { Text("读取资料地址") }
                            }
                            (runtime["resources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { resource ->
                                TextButton(onClick = { resourceServer = entry.name; resourceUri = resource.string("uri") }, enabled = !state.busy) { Text("读取 ${resource.string("name", resource.string("uri"))}") }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            val editable = state.snapshot != null && !state.busy && allowChanges && entry.editable
                            TextButton(onClick = { controller.edit(entry.name) }, enabled = editable) { Text("编辑") }
                            TextButton(onClick = { controller.edit(entry.name, advanced = true) }, enabled = editable) { Text("高级配置") }
                            TextButton(onClick = { confirmation = McpConfirmation(entry, state.snapshot!!, false) }, enabled = editable) { Text(if (entry.enabled) "停用" else "启用") }
                            TextButton(onClick = { confirmation = McpConfirmation(entry, state.snapshot!!, true) }, enabled = editable) { Text("删除") }
                            if (runtime?.string("authStatus") in setOf("notLoggedIn", "oAuth")) TextButton(onClick = { try{val options=McpLoginOptions(if(overrideScopes)loginScopes.lineSequence().filter{it.isNotBlank()}.toList()else emptyList(),registration,requireNotNull(loginTimeout.toIntOrNull()),overrideScopes);options.params(entry.name,null);loginError=null;onLogin(entry.name,options)}catch(_:Exception){loginError="请检查授权范围、注册方式和等待秒数"} }, enabled = state.connected && !state.busy && allowChanges && authorizationUrl == null) { Text("账号授权") }
                        }
                        if (!entry.editable) Text("请在对应项目配置或插件入口修改此服务。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.nextCursor != null) item { OutlinedButton(onClick = controller::more, enabled = !state.busy) { Text("加载更多服务") } }
        }
    }
    toolCall?.let { (server, name, metadata) ->
        var arguments by remember(server, name) { mutableStateOf("{}") }
        var error by remember(server, name) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { toolCall = null }, title = { Text("调用 $name") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(metadata.string("description")); Text("这会立即调用 $server 的工具，可能修改服务中的数据。请核对参数后确认。", style = MaterialTheme.typography.bodySmall)
                (metadata["inputSchema"] as? JsonObject)?.let { Text(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it), style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(arguments, { arguments = it }, label = { Text("工具参数（JSON）") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching {
                val args = Json.parseToJsonElement(arguments)
                (metadata["inputSchema"] as? JsonObject)?.let { schema -> val validator = JsonSchemaFormValidator(schema); if (validator.supported()) validator.validate(args) }
                args
            }.onSuccess { toolCall = null; controller.callTool(server, name, it) }.onFailure { error = it.message ?: "参数格式不正确" }
        }, enabled = state.connected && !state.busy && allowChanges) { Text("确认调用") } }, dismissButton = { TextButton(onClick = { toolCall = null }) { Text("取消") } })
    }
    resourceServer?.let { server -> AlertDialog(onDismissRequest = { resourceServer = null }, title = { Text("读取服务资料") }, text = {
        OutlinedTextField(resourceUri, { resourceUri = it }, label = { Text("资料 URI") }, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { resourceServer = null; controller.readResource(server, resourceUri) }, enabled = !state.busy && resourceUri.isNotBlank()) { Text("读取") } }, dismissButton = { TextButton(onClick = { resourceServer = null }) { Text("取消") } }) }
    state.result?.let { result -> AlertDialog(onDismissRequest = controller::closeResult, title = { Text(state.resultTitle) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) Text("服务报告此次工具执行失败", color = MaterialTheme.colorScheme.error)
            ((result["content"] ?: result["contents"]) as? JsonArray).orEmpty().forEach { value -> McpContentView(value as? JsonObject ?: buildJsonObject { put("text", value.toString()) }) }
            result["structuredContent"]?.let { Text(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it)) }
        }
    }, confirmButton = { TextButton(onClick = controller::closeResult) { Text("关闭") } }) }
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
    val localContext=LocalContext.current
    val originalArgs=(original["args"] as? JsonArray).orEmpty()
    var unix by remember(editor){mutableStateOf(original.string("command").substringAfterLast('/') in setOf("codex","libcodex_cli.so") && originalArgs.firstOrNull()?.jsonPrimitive?.contentOrNull=="stdio-to-uds")}
    var socket by remember(editor){mutableStateOf(if(unix)originalArgs.getOrNull(1)?.jsonPrimitive?.content.orEmpty()else "")}

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
                FilterChip(http, { http = true;unix=false }, enabled = !busy, label = { Text("远程服务") })
                FilterChip(!http && !unix, { http = false;unix=false }, enabled = !busy, label = { Text("手机上的程序") })
                FilterChip(!http && unix,{http=false;unix=true},enabled=!busy,label={Text("本机Unix服务")})
            }
            if (http) {
                OutlinedTextField(url, { url = it }, enabled = !busy, label = { Text("服务地址") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bearer, { bearer = it }, enabled = !busy, label = { Text("令牌环境变量名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(scopes, { scopes = it }, enabled = !busy, label = { Text("授权范围（每行一项，可选）") }, modifier = Modifier.fillMaxWidth())
                Text("需要账号授权时，保存后点击服务卡片的“账号授权”。", style = MaterialTheme.typography.bodySmall)
            } else if(unix){
                Text("连接已经运行、使用MCP消息的本机Unix服务。原版中继只转发字节，不会创建服务或把其他协议转换成MCP。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(socket,{socket=it},enabled=!busy,label={Text("Unix socket绝对路径")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("需要随包CLI；连接生命周期由原版MCP管理，断开核心时一并回收。",style=MaterialTheme.typography.bodySmall)
            } else {
                Text("程序需要已安装在手机可执行的位置。Node/Python/npm需要实际随包解释器与数据；填写命令不会自动安装，先在能力页核查。", style = MaterialTheme.typography.bodySmall)
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
                    val executable=if(unix)java.io.File(localContext.applicationInfo.nativeLibraryDir,"libcodex_cli.so").absolutePath else command
                    val arguments=if(unix)OriginalCliCommands.stdioToUds(socket).joinToString("\n")else args
                    val config = OriginalMcpConfig.form(original, url, executable, arguments, cwd, env, bearer, scopes, startup, timeout, active, http)
                    OriginalMcpConfig.edit(name, config)
                    formError = null; save(name, config)
                } catch (failure: IllegalArgumentException) { formError = failure.message }
            }, enabled = !busy && enabled, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存并重新连接") }
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}


@Composable private fun OriginalMcpAdvancedEditor(editor: OriginalMcpEditor, busy: Boolean, enabled: Boolean, error: String?, dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    fun lines(key: String) = (editor.original[key] as? JsonArray).orEmpty().joinToString("\n") { it.jsonPrimitive.content }
    var text by remember(editor) { mutableStateOf(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), editor.original)) }
    var allowed by remember(editor) { mutableStateOf(lines("enabled_tools")) }
    var denied by remember(editor) { mutableStateOf(lines("disabled_tools")) }
    var defaultMode by remember(editor) { mutableStateOf(editor.original.string("default_tools_approval_mode")) }
    var toolName by remember(editor) { mutableStateOf("") }
    var toolMode by remember(editor) { mutableStateOf("") }
    var budget by remember(editor) { mutableStateOf("") }
    var referenceName by remember(editor) { mutableStateOf("") }
    var referenceSource by remember(editor) { mutableStateOf("local") }
    var localError by remember(editor) { mutableStateOf<String?>(null) }
    fun modify(transform: (JsonObject) -> JsonObject) {
        runCatching { transform(Json.parseToJsonElement(text).jsonObject) }
            .onSuccess { text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it); localError = null }
            .onFailure { localError = it.message ?: "高级设置不正确" }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text((editor.name ?: "新服务") + " · 高级配置") }, text = {
        Column(Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("按固定原版字段调整策略。点击“应用到草稿”只修改本地草稿；最后确认才会版本化保存并重新连接。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(allowed, { allowed = it }, enabled = !busy, label = { Text("仅允许这些工具（每行一个，空表示不限制）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(denied, { denied = it }, enabled = !busy, label = { Text("禁用工具（每行一个）") }, modifier = Modifier.fillMaxWidth())
            Text("默认工具审批模式", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "继承", "auto" to "自动", "prompt" to "每次确认", "writes" to "写入确认", "approve" to "允许").forEach { (mode, title) ->
                    FilterChip(defaultMode == mode, { defaultMode = mode }, enabled = !busy, label = { Text(title) })
                }
            }
            OutlinedButton(onClick = { modify { OriginalMcpAdvanced.lists(it, allowed, denied, defaultMode) } }, enabled = !busy) { Text("应用服务策略到草稿") }
            HorizontalDivider()
            Text("单个工具策略", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(toolName, { toolName = it }, enabled = !busy, label = { Text("原版工具名称（精确匹配）") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "继承", "auto" to "自动", "prompt" to "每次确认", "writes" to "写入确认", "approve" to "允许").forEach { (mode, title) ->
                    FilterChip(toolMode == mode, { toolMode = mode }, enabled = !busy, label = { Text(title) })
                }
            }
            OutlinedTextField(budget, { budget = it }, enabled = !busy, label = { Text("输出预算（正整数 token；空表示继承）") }, modifier = Modifier.fillMaxWidth())
            Text("清空审批模式和预算后应用，可删除该工具的单独策略。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { modify { OriginalMcpAdvanced.tool(it, toolName, toolMode, budget) } }, enabled = !busy && toolName.isNotBlank()) { Text("应用工具策略到草稿") }
            HorizontalDivider()
            Text("stdio 环境变量引用", style = MaterialTheme.typography.titleMedium)
            Text("仅引用已有环境变量，不输入令牌明文；本机或远端来源均由实际 MCP 执行环境解析。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(referenceName, { referenceName = it }, enabled = !busy, label = { Text("环境变量名") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("local" to "本机", "remote" to "远端", "remove" to "移除引用").forEach { (source, title) ->
                    FilterChip(referenceSource == source, { referenceSource = source }, enabled = !busy, label = { Text(title) })
                }
            }
            OutlinedButton(onClick = { modify { OriginalMcpAdvanced.reference(it, referenceName, referenceSource) } }, enabled = !busy && referenceName.isNotBlank()) { Text("应用环境引用到草稿") }
            HorizontalDivider()
            Text("完整原版条目（JSON，可编辑其余已支持的环境、授权和超时字段）", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("最终服务配置") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            Text("明文 env/http_headers 会进入原版用户配置；涉及密钥优先使用 env_vars、bearer_token_env_var 或独立 OAuth。", style = MaterialTheme.typography.bodySmall)
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { OriginalMcpAdvanced.validate(Json.parseToJsonElement(text).jsonObject) }
            .onSuccess { localError = null; save(it) }
            .onFailure { localError = it.message ?: "高级配置格式不正确" }
    }, enabled = !busy && enabled) { Text(if (busy) "正在保存…" else "确认保存并重连") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
