package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.JsonObject

@Composable fun OriginalReviewForm(enabled: Boolean, onStart: (String, String, String) -> Unit) {
    var type by remember { mutableStateOf("uncommittedChanges") }
    var value by remember(type) { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val ready = enabled && (type == "uncommittedChanges" || value.isNotBlank())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("让 Codex 审查代码", style = MaterialTheme.typography.titleLarge)
        Text("在当前会话中启动审查，进度和结果显示在对话里。可通过任务通知停止；审查会调用模型与工具，消耗当前服务额度。", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("uncommittedChanges" to "未提交修改", "baseBranch" to "与分支对比", "commit" to "指定提交", "custom" to "自定范围").forEach { (key, label) -> FilterChip(type == key, { type = key }, enabled = enabled, label = { Text(label) }) }
        }
        if (type != "uncommittedChanges") OutlinedTextField(value, { value = it }, enabled = enabled, label = { Text(when (type) { "baseBranch" -> "基准分支，如 origin/main"; "commit" -> "提交标识"; else -> "审查范围与要求" }) }, modifier = Modifier.fillMaxWidth(), minLines = if (type == "custom") 4 else 1)
        if (type == "commit") OutlinedTextField(title, { title = it }, enabled = enabled, label = { Text("提交说明（可选）") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { confirm = true }, enabled = ready) { Text("开始审查") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认启动代码审查") }, text = { Text("范围：${when (type) { "uncommittedChanges" -> "当前未提交修改"; "baseBranch" -> "与分支 $value 对比"; "commit" -> "提交 $value"; else -> value }}\n\n将使用当前会话的服务和工具权限。结果返回后可在对话中查看。") }, confirmButton = { TextButton(onClick = { confirm = false; onStart(type, value, title) }, enabled = ready) { Text("启动") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable fun ThreadGitEditor(threadId: String, metadata: JsonObject?, enabled: Boolean,
    onRefresh: () -> Unit, onSave: (Map<String, OriginalMetadataEdit>) -> Unit) {
    val fields = listOf("sha" to "提交标识", "branch" to "分支", "originUrl" to "来源地址（请勿填写令牌）")
    var edits by remember(threadId, metadata) { mutableStateOf(fields.associate { (key, _) -> key to OriginalMetadataEdit("keep", metadata?.string(key).orEmpty()) }) }
    var confirm by remember { mutableStateOf<Map<String, OriginalMetadataEdit>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("会话保存的 Git 资料", style = MaterialTheme.typography.titleLarge)
        Text("修改这里会更新会话记录。实际分支、提交和远端地址仍在 Git 仓库中管理。", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRefresh, enabled = enabled) { Text("重新读取会话资料") }
        fields.forEach { (key, label) ->
            val edit = edits.getValue(key)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text("当前：${metadata?.string(key)?.takeIf { it.isNotBlank() } ?: "未设置"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("keep" to "保留", "set" to "替换", "clear" to "清空").forEach { (mode, title) -> FilterChip(edit.mode == mode, { edits = edits + (key to edit.copy(mode = mode)) }, enabled = enabled, label = { Text(title) }) } }
                if (edit.mode == "set") OutlinedTextField(edit.value, { edits = edits + (key to edit.copy(value = it)) }, enabled = enabled, label = { Text("新的$label") }, modifier = Modifier.fillMaxWidth())
            } }
        }
        Button(onClick = { confirm = edits }, enabled = enabled && edits.values.any { it.mode != "keep" } && edits.values.none { it.mode == "set" && it.value.isBlank() }) { Text("保存会话资料") }
    }
    confirm?.let { snapshot -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("保存会话 Git 资料") }, text = {
        Text(fields.mapNotNull { (key, name) -> snapshot.getValue(key).let { if (it.mode == "keep") null else "$name：${if (it.mode == "clear") "清空" else it.value}" } }.joinToString("\n"))
    }, confirmButton = { TextButton(onClick = { confirm = null; onSave(snapshot) }, enabled = enabled) { Text("保存") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
