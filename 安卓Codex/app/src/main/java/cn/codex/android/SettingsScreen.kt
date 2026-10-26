package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun SettingsScreen(controller: RuntimeController, state: ScreenState) {
    var endpoint by rememberSaveable { mutableStateOf(state.config.endpoint) }
    var key by remember { mutableStateOf(state.config.apiKey) }
    var model by rememberSaveable { mutableStateOf(state.config.model) }
    var reasoning by rememberSaveable { mutableStateOf(state.config.reasoning) }
    var loopback by remember { mutableStateOf(state.config.allowLoopbackHttp) }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var message by remember { mutableStateOf("") }
    var modelPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("你的模型，你的工作区", style = MaterialTheme.typography.titleLarge)
        Text("任务和文件操作在手机上执行；模型通常需要联网。ChatGPT 订阅登录尚未移植，请使用 API 密钥或本机兼容服务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Responses 服务地址") }, supportingText = { Text("填写包含 /v1 的 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(key, { key = it }, label = { Text("API 密钥") }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密钥" else "显示密钥") } }, supportingText = { Text("加密保存在手机中，不写入项目或日志") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, placeholder = { Text("从服务获取，或填写准确模型 ID") }, trailingIcon = { IconButton(onClick = { modelPicker = true }, enabled = models.isNotEmpty()) { Icon(Icons.Outlined.ExpandMore, "选择模型") } }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedButton(onClick = { scope.launch {
            loading = true; message = ""
            runSuspendCatching { ResponsesClient(ApiConfig(endpoint.trim(), key.trim(), model.trim(), reasoning, loopback)).models() }.onSuccess { models = it; message = "连接成功，获取到 ${it.size} 个模型"; modelPicker = true }.onFailure { message = "连接失败：${it.message}" }
            loading = false
        } }, enabled = !loading && !state.busy, modifier = Modifier.fillMaxWidth()) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (loading) "正在连接" else "测试连接并获取模型") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text("思考强度", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("" to "不发送", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "最高").forEach { (value, title) -> FilterChip(reasoning == value, { reasoning = value }, label = { Text(title) }) } }
        Text("兼容服务不支持此参数时，选择“不发送”。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许本机 HTTP 模型"); Text("只允许 localhost / 127.0.0.1 / ::1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(loopback, { loopback = it }) }
        Button(onClick = { controller.saveConfig(state.config.copy(endpoint = endpoint.trim().trimEnd('/'), apiKey = key.trim(), model = model.trim(), reasoning = reasoning, allowLoopbackHttp = loopback)); message = "" }, enabled = !state.busy && endpoint.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("保存连接设置") }
        if (state.config.endpoint == endpoint.trim().trimEnd('/') && state.config.apiKey == key.trim() && state.config.model == model.trim() && state.config.reasoning == reasoning && state.config.allowLoopbackHttp == loopback) Text("当前设置已保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("外观与体验", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (theme, name) -> FilterChip(state.config.theme == theme, { controller.saveConfig(state.config.copy(theme = theme)) }, label = { Text(name) }) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("界面动效"); Text("同时遵循系统动画设置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(state.config.motion, { controller.saveConfig(state.config.copy(motion = it)) }) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Codex · 随行  0.1.0", style = MaterialTheme.typography.bodyMedium)
        Text("基于上传的 OpenAI Codex 源码分析进行安卓适配，非 OpenAI 官方应用。当前为开发版本，完整功能覆盖情况见“功能与限制”。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text("选择模型") }, text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(models) { id -> TextButton(onClick = { model = id; modelPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(id, Modifier.fillMaxWidth()) } } } }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text("关闭") } })
}
@Composable fun SkillsScreen(controller: RuntimeController, state: ScreenState) {
    var skills by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var additionalRoots by rememberSaveable(state.project) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(state.project, state.busy, reload) {
        runSuspendCatching { withContext(Dispatchers.IO) {
            val fs = controller.workspace(state.project)
            val catalog = SkillCatalog(fs)
            val roots = catalog.extraRoots()
            val files = catalog.list().map { path -> path to fs.read(path) }
            roots to files
        } }.onSuccess { (roots, files) -> additionalRoots = roots.joinToString(", "); skills = files }.onFailure { controller.fail("技能读取失败：${it.message}") }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("让项目带着自己的经验", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("支持项目内的“技能”、“.agents/skills”及自定义技能目录。只发现 SKILL.md，不自动安装执行程序。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedTextField(additionalRoots, { additionalRoots = it }, label = { Text("额外技能目录（逗号分隔的项目相对路径）") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { controller.setSkillRoots(additionalRoots.split(",").map { it.trim() }); reload++ }, enabled = !state.busy) { Text("保存技能目录") }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && skills.isEmpty()) item { EmptyPanel("还没有项目技能", "在文件页创建技能目录并导入 SKILL.md。", Icons.Outlined.AutoAwesome) }
        items(skills) { (path, text) -> ExpandableCard(path, text, Icons.Outlined.AutoAwesome) }
    }
}
@Composable fun McpScreen(controller: RuntimeController) {
    val context = LocalContext.current
    val tools = remember { McpTools(context) }
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(emptyList<McpConnection>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runSuspendCatching { withContext(Dispatchers.IO) { tools.connections() } }.onSuccess { connections = it }.onFailure { controller.fail("连接配置读取失败：${it.message}") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("扩展你的工具箱", style = MaterialTheme.typography.titleLarge)
        Text("支持无需登录的 HTTPS Streamable HTTP MCP 服务。OAuth、stdio 本地进程和交互式网页工具尚未移植。连接外部服务需要网络。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        connections.forEach { c -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(c.name, fontWeight = FontWeight.Medium); Text(c.url, style = MaterialTheme.typography.labelSmall); Row { TextButton(onClick = { scope.launch { loading = true; runSuspendCatching { tools.execute(ToolCall("manual", "mcp", obj("server" to c.name, "action" to "list_tools"))) }.onSuccess { result = it }.onFailure { result = "连接失败：${it.message}" }; loading = false } }, enabled = !loading) { Text("检查工具列表") }; TextButton(onClick = { scope.launch { runSuspendCatching { withContext(Dispatchers.IO) { tools.save(connections.filterNot { it.name == c.name }) } }.onSuccess { connections = connections.filterNot { it.name == c.name } }.onFailure { controller.fail(it.message.orEmpty()) } } }) { Text("移除连接") } } } } }
        OutlinedTextField(name, { name = it }, label = { Text("连接标识，例如 my_tools") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS MCP 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Button(onClick = { scope.launch { val updated = connections.filterNot { it.name == name.trim() } + McpConnection(name.trim(), url.trim()); runSuspendCatching { withContext(Dispatchers.IO) { tools.save(updated) } }.onSuccess { connections = updated; name = ""; url = "" }.onFailure { controller.fail(it.message.orEmpty()) } } }, enabled = name.isNotBlank() && url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("保存连接") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result.isNotBlank()) ExpandableCard("服务返回结果", result, Icons.Outlined.Extension)
    }
}
