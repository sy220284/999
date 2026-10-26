@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.codex.android

import android.provider.OpenableColumns
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import cn.codex.core.*
import kotlinx.coroutines.*

@Composable fun FilesScreen(controller: RuntimeController, state: ScreenState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var path by rememberSaveable(state.project) { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<FileEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(state.project) { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var original by rememberSaveable { mutableStateOf("") }
    var newDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(false) }
    var dirtyClose by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    fun closeEditor() { if (text != original) dirtyClose = true else selected = null }
    BackHandler(selected != null || path.isNotBlank()) { if (selected != null) closeEditor() else path = path.substringBeforeLast('/', "") }
    LaunchedEffect(state.project, path, revision, state.busy) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { controller.workspace().list(path) } }.onSuccess { entries = it }.onFailure { controller.fail("目录读取失败：${it.message}") }
        loading = false
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) scope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } } }.onFailure { controller.fail("导出失败：${it.message}") } } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        working = true
        runCatching { withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "导入文件" } ?: "导入文件"
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", "..")) { "文件名称无效" }
            val target = controller.workspace().resolve(if (path.isBlank()) name else "$path/$name")
            require(!target.exists()) { "同名文件已存在，请先重命名或选择其他目录" }
            val temp = java.io.File.createTempFile("导入-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output -> val b = ByteArray(32768); var total = 0L; while (true) { val n = input.read(b); if (n < 0) break; total += n; require(total <= 64 * 1024 * 1024) { "单个导入文件上限 64MB" }; output.write(b, 0, n) } } }
                target.parentFile!!.mkdirs(); java.nio.file.Files.move(temp.toPath(), target.toPath())
            } finally { temp.delete() }
        } }.onFailure { controller.fail("导入失败：${it.message}") }
        working = false; revision++
    } }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::closeEditor) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭编辑器") }
                Text(selected!!.substringAfterLast('/') + if (text != original) " · 未保存" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { export.launch(selected!!.substringAfterLast('/')) }) { Icon(Icons.Outlined.IosShare, "导出文件") }
                TextButton(onClick = { scope.launch { working = true; runCatching { controller.writeFile(selected!!, text) }.onSuccess { original = text; revision++ }.onFailure { controller.fail("保存失败：${it.message}") }; working = false } }, enabled = !state.busy && !working && text != original) { Text("保存") }
            }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxSize().padding(12.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 23.sp), enabled = !state.busy && !working, shape = RoundedCornerShape(14.dp), label = { Text("UTF-8 文本") })
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (path.isNotBlank()) IconButton(onClick = { path = path.substringBeforeLast('/', "") }) { Icon(Icons.Outlined.DriveFolderUpload, "上一级目录") }
                Column(Modifier.weight(1f)) { Text(state.project, style = MaterialTheme.typography.titleMedium); Text(path.ifBlank { "项目根目录" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !state.busy && !working) { Icon(Icons.Outlined.FileDownload, "导入文件") }
                IconButton(onClick = { newName = ""; newDialog = true }, enabled = !state.busy && !working) { Icon(Icons.Outlined.CreateNewFolder, "新建文件或目录") }
                IconButton(onClick = { revision++ }) { Icon(Icons.Outlined.Refresh, "刷新目录") }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("筛选当前目录") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp))
            if (loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            val filtered = entries.filter { it.name.contains(query, true) }
            if (!loading && filtered.isEmpty()) EmptyPanel("这里还很安静", "导入项目文件，或创建第一个文件。", Icons.Outlined.FolderOpen)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(filtered, key = { it.path }) { entry ->
                    ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(if (entry.directory) "文件夹" else formatSize(entry.size)) }, leadingContent = { Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) { Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, Modifier.padding(12.dp).size(22.dp), tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }, trailingContent = { if (entry.directory) Icon(Icons.Outlined.ChevronRight, null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background), modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !working) {
                        if (entry.directory) path = entry.path else scope.launch {
                            working = true
                            runCatching { withContext(Dispatchers.IO) { controller.workspace().read(entry.path).also { require('\u0000' !in it) { "二进制文件不支持文本编辑" } } } }.onSuccess { text = it; original = it; selected = entry.path }.onFailure { controller.fail("无法打开：${it.message}") }
                            working = false
                        }
                    })
                }
            }
            Text("${entries.size} 项 · 文件保存在本应用的工作区，可通过系统文件选择器导入导出", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (newDialog) AlertDialog(onDismissRequest = { newDialog = false }, title = { Text("新建") }, text = { Column { OutlinedTextField(newName, { newName = it }, label = { Text("名称，例如：说明.md") }, singleLine = true); Row(verticalAlignment = Alignment.CenterVertically) { Switch(directory, { directory = it }); Spacer(Modifier.width(12.dp)); Text("创建文件夹") } } }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { require(newName.isNotBlank() && '/' !in newName && '\\' !in newName && newName !in setOf(".", "..")); val relative = if (path.isBlank()) newName else "$path/$newName"; val file = controller.workspace().resolve(relative); require(!file.exists()) { "同名项目已存在" }; if (directory) check(file.mkdirs()) else controller.writeFile(relative, "") } }.onSuccess { newDialog = false; revision++ }.onFailure { controller.fail("创建失败：${it.message}") } } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { newDialog = false }) { Text("取消") } })
    if (dirtyClose) AlertDialog(onDismissRequest = { dirtyClose = false }, title = { Text("保留未保存的修改？") }, text = { Text("返回后将放弃本次编辑，已保存的文件不会变化。") }, confirmButton = { TextButton(onClick = { dirtyClose = false; selected = null }) { Text("放弃编辑") } }, dismissButton = { TextButton(onClick = { dirtyClose = false }) { Text("继续编辑") } })
}
fun formatSize(size: Long) = when { size < 1024 -> "$size B"; size < 1024 * 1024 -> "${size / 1024} KB"; else -> "${"%.1f".format(size / 1048576.0)} MB" }
@Composable fun EmptyPanel(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun ChangesScreen(controller: RuntimeController, state: ScreenState) {
    val scope = rememberCoroutineScope()
    var undo by remember { mutableStateOf<FileChange?>(null) }
    if (state.changes.isEmpty()) { EmptyPanel("还没有文件修改", "通过对话或编辑器保存文件后，在这里查看和撤销。", Icons.Outlined.Difference); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(state.changes.asReversed()) { _, change -> Column { DiffView(change); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { undo = change }, enabled = !state.busy) { Icon(Icons.Outlined.Undo, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("撤销这次修改") } } } }
    }
    undo?.let { change -> AlertDialog(onDismissRequest = { undo = null }, title = { Text("撤销修改？") }, text = { Text("${change.path}\n文件若已再次变化，撤销会停止，避免覆盖后续编辑。") }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { controller.undo(change) }.onFailure { controller.fail("撤销失败：${it.message}") }; undo = null } }) { Text("撤销") } }, dismissButton = { TextButton(onClick = { undo = null }) { Text("取消") } }) }
}
@Composable fun TerminalScreen(state: ScreenState, send: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("手机自带 shell", style = MaterialTheme.typography.titleLarge)
        Text("在当前项目目录执行命令。可用命令取决于手机系统；不自带 Python、Node 或完整 Linux 环境。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) { SelectionContainer { Text(state.terminal.ifBlank { "命令输出会显示在这里。" }, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("pwd", "ls -la", "uname -a").forEach { text -> SuggestionChip(onClick = { command = text }, label = { Text(text, fontFamily = FontFamily.Monospace) }, enabled = !state.busy) } }
        OutlinedTextField(command, { command = it }, modifier = Modifier.fillMaxWidth(), label = { Text("输入命令") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(14.dp))
        Button(onClick = { send(command) }, enabled = command.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("确认并执行") }
    }
}
