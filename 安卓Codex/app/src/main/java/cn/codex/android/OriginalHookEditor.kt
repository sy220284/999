package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalHookEditor(draft: OriginalHookDraft, busy: Boolean, enabled: Boolean, error: String?, dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    var text by remember(draft) { mutableStateOf(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(draft.hooks.filterKeys { it != "state" }))) }
    var event by remember { mutableStateOf("PreToolUse") }; var command by remember { mutableStateOf("") }; var matcher by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("编辑用户自动规则") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("只编辑当前用户的规则声明。项目、插件与管理来源请在对应位置修改；启停与信任记录会保留，新规则不会自动获得信任。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OriginalConfigEdits.hookEvents.forEach { name -> FilterChip(event == name, { event = name }, enabled = !busy, label = { Text(hookEventTitle(name.replaceFirstChar { it.lowercase() })) }) } }
            OutlinedTextField(matcher, { matcher = it }, enabled = !busy, label = { Text("匹配器（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(command, { command = it }, enabled = !busy, label = { Text("手机命令") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                runCatching {
                    val current = Json.parseToJsonElement(text).jsonObject
                    val group = buildJsonObject { if (matcher.isNotBlank()) put("matcher", matcher); put("hooks", buildJsonArray { add(buildJsonObject { put("type", "command"); put("command", command) }) }) }
                    val updated = JsonObject(current + (event to JsonArray((current[event] as? JsonArray).orEmpty() + group)))
                    text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), updated); command = ""; localError = null
                }.onFailure { localError = "请先修正下方 JSON 内容" }
            }, enabled = !busy && command.isNotBlank()) { Text("追加到草稿") }
            Text("下方可完整编辑事件匹配组、命令、MCP 工具、提示词或代理处理器；删除对应条目即可移除用户规则。保存后才会生效。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("用户规则（JSON 对象）") }, modifier = Modifier.fillMaxWidth())
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { Json.parseToJsonElement(text).jsonObject.also { OriginalConfigEdits.hookRules(draft.hooks, it) } }.onSuccess { localError = null; save(it) }.onFailure { localError = it.message ?: "JSON 格式不正确" }
    }, enabled = !busy && enabled) { Text(if (busy) "正在保存…" else "确认保存规则") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
