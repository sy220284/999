package cn.codex.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

private data class FileAction(val kind: String, val path: String, val directory: Boolean = false)
@Composable fun OriginalFilesScreen(controller: OriginalFilesController, allowChanges: Boolean, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<FileAction?>(null) }
    var target by remember { mutableStateOf("") }
    var draft by remember(state.document) { mutableStateOf(state.document?.text.orEmpty()) }
    var export by remember { mutableStateOf<OriginalFileDocument?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val document = export; export = null
        if (uri != null && document != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { val bytes = Base64.getDecoder().decode(document.dataBase64); requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) } } }
                .onSuccess { exportMessage = "文件已导出" }.onFailure { exportMessage = "导出失败：${it.message.orEmpty()}" }
        }
    }
    val editable = allowChanges && !state.busy && state.connected
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("原版项目文件", style = MaterialTheme.typography.titleLarge); TextButton(onClick = onBack) { Text("返回") } } }
        item { Text(state.directory, style = MaterialTheme.typography.bodySmall); Text("由手机上的原版核心处理文件。界面限于当前原版项目；此路径检查不是系统沙箱。", style = MaterialTheme.typography.bodySmall) }
        item { Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { controller.refresh() }, enabled = !state.busy) { Text("刷新目录") }
            TextButton(onClick = { File(state.directory).parentFile?.let { controller.refresh(it.absolutePath) } }, enabled = !state.busy && File(state.directory).canonicalFile != controller.root.canonicalFile) { Text("上一级") }
            TextButton(onClick = { target = ""; action = FileAction("create", state.directory) }, enabled = editable && state.document == null) { Text("新建文件") }
            TextButton(onClick = { target = ""; action = FileAction("mkdir", state.directory) }, enabled = editable) { Text("新建目录") }
            if (state.watchId == null) TextButton(onClick = controller::watch, enabled = !state.busy) { Text("监听此目录") }
            else TextButton(onClick = controller::unwatch, enabled = !state.busy) { Text("停止监听") }
        } }
        item {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.notice, style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            exportMessage?.let { Text(it) }
            if (state.watchId != null) Text("${if (state.watchConfirmed) "正在监听" else "监听结果待核查，可尝试停止"}：${state.watchPath}", style = MaterialTheme.typography.bodySmall)
        }
        if (state.changedPaths.isNotEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("目录有变更，请刷新核查。编辑中的正文不会自动替换。", style = MaterialTheme.typography.bodyMedium)
                state.changedPaths.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        }
        state.document?.let { document ->
            item {
                HorizontalDivider(); Text("打开的文件：${File(document.path).name}", style = MaterialTheme.typography.titleMedium)
                if (document.text == null) Text("二进制或非 UTF-8 文件不在此编辑，避免破坏字节。")
                else OutlinedTextField(draft, { draft = it }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp), label = { Text("UTF-8 正文（最多 2 MiB）") })
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (document.text != null) TextButton(onClick = { action = FileAction("save", document.path) }, enabled = editable && draft != document.text) { Text("确认保存") }
                    TextButton(onClick = { export = document; launcher.launch(File(document.path).name) }) { Text("导出已读取的原始文件") }
                    TextButton(onClick = { action = FileAction("close", document.path) }, enabled = !state.busy) { Text("关闭编辑") }
                }
                if (document.text != null && draft != document.text) Text("有未保存修改；导出的是已读取的原始内容。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.metadata != null) item {
            val data = state.metadata!!
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("属性：${state.metadataPath}")
                Text("类型：${if (data["isDirectory"]?.jsonPrimitive?.booleanOrNull == true) "目录" else if (data["isFile"]?.jsonPrimitive?.booleanOrNull == true) "文件" else "其他"}；符号链接：${if (data["isSymlink"]?.jsonPrimitive?.booleanOrNull == true) "是" else "否"}")
                Text("修改时间：${fileTime(data["modifiedAtMs"])}\n创建时间：${fileTime(data["createdAtMs"])}", style = MaterialTheme.typography.bodySmall)
            } }
        }
        items(state.entries, key = { it.string("fileName") }) { entry ->
            val name = entry.string("fileName"); val directory = entry["isDirectory"]?.jsonPrimitive?.booleanOrNull == true
            val path = File(state.directory, name).absolutePath
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text((if (directory) "目录 · " else "文件 · ") + name, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { if (state.document?.text != null && draft != state.document?.text) { exportMessage = "请先保存或关闭当前编辑，再打开其他文件" } else if (directory) controller.refresh(path) else controller.read(path) }, enabled = !state.busy) { Text(if (directory) "进入" else "打开") }
                    TextButton(onClick = { controller.metadata(path) }, enabled = !state.busy) { Text("属性") }
                    TextButton(onClick = { target = ""; action = FileAction("copy", path, directory) }, enabled = editable) { Text("复制") }
                    TextButton(onClick = { action = FileAction("remove", path, directory) }, enabled = editable) { Text("删除") }
                }
            } }
        }
        if (!state.busy && state.entries.isEmpty()) item { Text("当前目录无可展示条目；读取失败请查看上方提示。") }
    }
    action?.let { selected ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text(when (selected.kind) { "create" -> "新建 UTF-8 空文件"; "mkdir" -> "新建目录"; "copy" -> "复制到项目内"; "remove" -> "删除项目文件"; "close" -> "关闭当前编辑"; else -> "保存文件" }) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected.path)
                if (selected.kind in setOf("create", "mkdir", "copy")) OutlinedTextField(target, { target = it }, label = { Text(if (selected.kind == "copy") "目标路径（相对项目根目录）" else "名称或相对此目录的路径") }, modifier = Modifier.fillMaxWidth())
                Text(when (selected.kind) { "remove" -> if (selected.directory) "将递归删除此目录及其内容，无法从此页恢复。" else "将删除此文件，无法从此页恢复。"; "save" -> "保存前重读并比较已加载内容。原版 RPC 不提供原子版本比较，仍可能遇到外部并发修改。"; "close" -> "未保存的正文修改将丢弃。"; "copy" -> "只允许不存在的目标，不覆盖已有文件；目录会递归复制。"; else -> "在当前原版项目内创建，不覆盖已有文件。" }, style = MaterialTheme.typography.bodyMedium)
            }
        }, confirmButton = { TextButton(onClick = {
            action = null
            when (selected.kind) {
                "create" -> controller.write(null, File(selected.path, target).absolutePath, "")
                "mkdir" -> controller.mkdir(File(selected.path, target).absolutePath)
                "copy" -> controller.copy(selected.path, target, selected.directory)
                "remove" -> controller.remove(selected.path, selected.directory)
                "save" -> controller.write(state.document, selected.path, draft)
                "close" -> controller.closeDocument()
            }
        }, enabled = (selected.kind == "close" || editable) && (selected.kind !in setOf("create", "mkdir", "copy") || target.isNotBlank())) { Text("确认") } }, dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } })
    }
}
private fun fileTime(value: JsonElement?): String = (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "服务未提供"
