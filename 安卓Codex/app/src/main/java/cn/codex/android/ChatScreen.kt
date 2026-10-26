@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.codex.android

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun ChatScreen(state: ScreenState, draft: String, onDraft: (String) -> Unit, settings: () -> Unit, controller: RuntimeController, send: (String) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var attachLoading by remember { mutableStateOf(false) }
    val nearBottom by remember { derivedStateOf { val info = list.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ?: true } }
    val attachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            attachLoading = true
            runSuspendCatching { withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(10 * 1024 * 1024 + 1) }
                require(bytes.size <= 10 * 1024 * 1024) { "附件不能超过 10MB" }
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "附件" } ?: "附件"
                val input = if (mime.startsWith("image/")) obj("type" to "input_image", "image_url" to "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}") else {
                    require(mime != "application/pdf" && mime != "application/zip") { "当前支持图片和 UTF-8 文本附件；PDF/压缩包请先转换或导入项目" }
                    require(bytes.size <= 2 * 1024 * 1024) { "文本附件不能超过 2MB" }
                    obj("type" to "input_text", "text" to "附件：$name\n${bytes.toString(Charsets.UTF_8)}")
                }
                name to input
            } }.onSuccess { controller.addAttachment(it.first, it.second) }.onFailure { controller.fail("附件读取失败：${it.message}") }
            attachLoading = false
        }
    }
    LaunchedEffect(state.session?.id) { if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1) }
    LaunchedEffect(state.session?.messages?.size, state.stream.length / 80, state.tool) {
        if (nearBottom && list.layoutInfo.totalItemsCount > 0) { if (state.config.motion) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) else list.scrollToItem(list.layoutInfo.totalItemsCount - 1) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (state.session?.messages.isNullOrEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 36.dp), verticalArrangement = Arrangement.Center) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Code, null, Modifier.size(33.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) } }
                Spacer(Modifier.height(28.dp))
                Text("把想法，\n写进现实。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text("你的项目，就在手边。\n读代码、改文件、验证结果，在手机上继续。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                Suggestion("理解这个项目", "梳理结构，找到关键入口", Icons.Outlined.AccountTree) { onDraft("阅读当前项目，梳理目录结构、关键入口和主要功能，先不要修改文件。") }
                Suggestion("一起修复问题", "定位原因，再验证修改", Icons.Outlined.Build) { onDraft("检查当前项目的问题，先说明定位依据，再提出修改并验证。") }
                Suggestion("审查代码修改", "关注错误、回归与遗漏", Icons.Outlined.FactCheck) { onDraft("审查当前项目的 Git 差异，检查错误、回归与遗漏，给出具体文件位置。") }
                if (state.config.model.isBlank()) TextButton(onClick = settings, contentPadding = PaddingValues(top = 16.dp)) { Icon(Icons.Outlined.Key, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("连接你的模型") }
            }
        } else Box(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                state.session?.goal?.takeIf { it.isNotBlank() }?.let { goal -> item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(12.dp)) { Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(goal, style = MaterialTheme.typography.bodyMedium) } } } }
                items(state.session?.messages.orEmpty(), key = { it.id }) { message -> MessageView(message) }
                if (state.thinking.isNotBlank()) item { ExpandableCard("思考摘要", state.thinking, Icons.Outlined.Psychology) }
                if (state.stream.isNotBlank()) item { Column { AssistantLabel("正在回复"); Spacer(Modifier.height(10.dp)); MarkdownText(state.stream) } }
                if (state.busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(10.dp)); Text(state.tool.ifBlank { if (state.approval != null) "等待你的确认" else "正在处理" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } }
            }
            if (!nearBottom) SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) { Icon(Icons.Outlined.KeyboardArrowDown, "回到最新消息") }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            if (state.attachments.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Text("已附加 ${state.attachments.size} 个文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.weight(1f)); TextButton(controller::clearAttachments, enabled = !state.busy) { Text("清除") } }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(6.dp)) {
                    TextField(draft, onDraft, placeholder = { Text("描述任务，或提出问题…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 170.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), maxLines = 6)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { attachment.launch(arrayOf("text/*", "image/*", "application/json", "application/octet-stream")) }, enabled = !state.busy && !attachLoading) { if (attachLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Add, "添加图片或文本附件") }
                        TextButton(onClick = settings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(state.config.model.ifBlank { "连接模型" }.take(22), style = MaterialTheme.typography.labelMedium, maxLines = 1); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp)) }
                        Spacer(Modifier.weight(1f))
                        if (state.canSteer && draft.isNotBlank()) {
                            TextButton(onClick = { controller.steer(draft); onDraft("") }) { Text("追加指令") }
                        }
                        FilledIconButton(onClick = { if (state.busy) controller.stop() else if (draft.isNotBlank()) send(draft) }, enabled = state.busy || (draft.isNotBlank() && !attachLoading), modifier = Modifier.size(48.dp)) { AnimatedContent(state.busy, transitionSpec = { fadeIn(tween(if (state.config.motion) 180 else 0)) togetherWith fadeOut(tween(if (state.config.motion) 120 else 0)) }, label = "发送与停止") { busy -> Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (busy) "停止任务" else "发送任务") } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("本机执行 · 修改前确认", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp); Text(state.usage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}
@Composable private fun Suggestion(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = click).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun AssistantLabel(label: String = "Codex") { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Code, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun MessageView(message: Message) {
    when (message.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), modifier = Modifier.widthIn(max = 560.dp)) { SelectionContainer { Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge) } } }
        "tool" -> ExpandableCard(toolTitle(message.text), message.detail, Icons.Outlined.CheckCircle)
        else -> Column { AssistantLabel(); Spacer(Modifier.height(10.dp)); MarkdownText(message.text) }
    }
}
fun toolTitle(name: String) = mapOf("list_files" to "查看项目文件", "read_file" to "读取文件", "search_files" to "搜索项目", "write_file" to "写入文件", "apply_patch" to "应用文件修改", "delete_file" to "删除文件", "shell" to "执行本机命令", "git" to "Git 操作", "update_plan" to "工作计划", "mcp" to "扩展工具", "list_skills" to "查找技能", "read_skill" to "读取技能")[name] ?: name
@Composable fun ExpandableCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = if (LocalMotionEnabled.current) spring() else snap())) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "收起" else "展开", Modifier.size(18.dp)) }
            if (open) SelectionContainer { Text(detail, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}
@Composable fun MarkdownText(text: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        text.split("```").forEachIndexed { index, block ->
            if (index % 2 == 1) {
                val language = block.substringBefore('\n').trim()
                val code = block.substringAfter('\n', "")
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) { Column(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) { Text(language.ifBlank { "代码" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)); IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(17.dp)) } }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); SelectionContainer { Text(code.trimEnd(), Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, softWrap = false) } } }
            } else if (block.isNotBlank()) SelectionContainer {
                Text(buildAnnotatedString {
                    val regex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
                    var p = 0
                    for (m in regex.findAll(block)) { append(block.substring(p, m.range.first)); withStyle(SpanStyle(fontWeight = if (m.value.startsWith("**")) FontWeight.SemiBold else null, fontFamily = if (m.value.startsWith("`")) FontFamily.Monospace else null)) { append(m.groupValues[1].ifBlank { m.groupValues[2] }) }; p = m.range.last + 1 }
                    append(block.substring(p).trimEnd())
                }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
@Composable fun ApprovalSheet(value: Approval, decide: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = { decide(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Icon(if (value.shell) Icons.Outlined.Terminal else Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp)); Text(value.title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp))
            if (value.shell) Text("命令以此应用的权限在手机上执行，可访问应用私有数据；它不具备桌面版的独立 shell 沙箱。请确认命令内容。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp)); SelectionContainer { Text(value.detail, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
            value.changes.forEach { c -> Spacer(Modifier.height(12.dp)); DiffView(c) }
            Spacer(Modifier.height(20.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = { decide(false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("拒绝") }; Button(onClick = { decide(true) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("允许这一次") } }
        }
    }
}
@Composable fun DiffView(change: FileChange) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(change.path, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            val before = change.before.orEmpty().lines(); val after = change.after.orEmpty().lines()
            var prefix = 0; while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
            var suffix = 0; while (suffix < minOf(before.size, after.size) - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
            SelectionContainer { Column(Modifier.horizontalScroll(rememberScrollState())) {
                before.drop(prefix).dropLast(suffix).take(120).forEach { Text("− $it", color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false) }
                after.drop(prefix).dropLast(suffix).take(120).forEach { Text("+ $it", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false) }
                if (maxOf(before.size, after.size) - prefix - suffix > 120) Text("预览仅展示前 120 行修改，请在文件页核对完整内容", style = MaterialTheme.typography.labelSmall)
            } }
        }
    }
}
