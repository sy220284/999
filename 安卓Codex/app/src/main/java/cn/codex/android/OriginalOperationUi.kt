package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.original.OriginalOperationStatus
import kotlinx.serialization.json.*

@Composable fun OriginalOpsPage(title: String, status: OriginalOperationStatus, onBack: () -> Unit, onAbort: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    var abortConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 12.dp), maxLines = 2); TextButton(onClick = onBack) { Text("返回") } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { if (!status.connected) Text("核心已关闭，请返回重新启动。", color = MaterialTheme.colorScheme.error); if (status.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (onAbort != null) TextButton(onClick = { abortConfirmation = true }) { Text("中断操作并关闭核心") } }; Text(status.notice); status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            content()
        }
    }
    if (abortConfirmation) AlertDialog(onDismissRequest = { abortConfirmation = false }, title = { Text("中断并关闭核心") }, text = { Text("会结束当前核心及其运行中的任务。已产生的修改保留，重新启动后请先核查实际结果。") }, confirmButton = { TextButton(onClick = { abortConfirmation = false; onAbort?.invoke() }) { Text("关闭核心") } }, dismissButton = { TextButton(onClick = { abortConfirmation = false }) { Text("继续等待") } })

}
@Composable fun OriginalOpsTabs(titles: List<String>, selected: String, onSelect: (String) -> Unit) { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { titles.forEach { title -> FilterChip(selected == title, { onSelect(title) }, label = { Text(title) }) } } }
@Composable fun OriginalInput(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true, lines: Int = 1) { OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, minLines = lines, modifier = Modifier.fillMaxWidth()) }
@Composable fun OriginalCard(title: String, content: @Composable ColumnScope.() -> Unit) { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } } }
@Composable fun OriginalConfirmation(request: Pair<String, () -> Unit>?, onDismiss: () -> Unit) { request?.let { (text, action) -> AlertDialog(onDismissRequest = onDismiss, title = { Text("确认操作") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { onDismiss(); action() }) { Text("确认执行") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) } }
fun originalValue(value: JsonElement?): String = when (value) { null, JsonNull -> "未提供"; is JsonPrimitive -> if (value.isString) value.content else when (value.booleanOrNull) { true -> "是"; false -> "否"; null -> value.content }; is JsonArray -> value.joinToString("\n") { originalValue(it) }.ifBlank { "无" }; is JsonObject -> value.entries.joinToString("\n") { "${it.key}：${originalValue(it.value)}" } }
