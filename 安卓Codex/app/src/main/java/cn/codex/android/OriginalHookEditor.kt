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
    var event by remember(draft) { mutableStateOf("PreToolUse") }
    var command by remember(draft) { mutableStateOf("") }
    var matcher by remember(draft) { mutableStateOf("") }
    var editing by remember(draft) { mutableStateOf<Pair<String, Int>?>(null) }
    var localError by remember(draft) { mutableStateOf<String?>(null) }
    fun mutate(transform: (JsonObject) -> JsonObject) {
        runCatching { transform(Json.parseToJsonElement(text).jsonObject) }
            .onSuccess { text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it); localError = null }
            .onFailure { localError = it.message ?: "规则草稿无效" }
    }
    val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("编辑用户自动规则") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("用户规则按配置版本写入。项目、插件和管理来源只读；规则信任与启停状态独立保留，新规则不会自动获得信任。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OriginalConfigEdits.hookEvents.forEach { name ->
                    FilterChip(event == name, { event = name; editing = null }, enabled = !busy, label = { Text(hookEventTitle(name.replaceFirstChar { it.lowercase() })) })
                }
            }
            OutlinedTextField(matcher, { matcher = it }, enabled = !busy, label = { Text("匹配器（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(command, { command = it }, enabled = !busy, label = { Text("手机命令（必须已安装）") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                mutate { current ->
                    require(command.isNotBlank()) { "命令不能为空" }
                    val selected = editing
                    val groups = (current[event] as? JsonArray).orEmpty().toMutableList()
                    val previous = if (selected == null) null else {
                        require(selected.first == event && selected.second in groups.indices) { "要编辑的匹配组已变化，请重新选择" }
                        groups[selected.second] as? JsonObject ?: error("匹配组格式已变化")
                    }
                    // 修改已存在的命令时保留高级组字段和处理器字段（如超时），只改变明确编辑的值。
                    val handler = (previous?.get("hooks") as? JsonArray)?.singleOrNull() as? JsonObject
                    if (previous != null) require(handler?.get("type")?.jsonPrimitive?.content == "command") { "此匹配组含高级处理器，请在下方完整 JSON 中编辑" }
                    val fields = previous?.toMutableMap() ?: mutableMapOf()
                    if (matcher.isNotBlank()) fields["matcher"] = JsonPrimitive(matcher) else fields.remove("matcher")
                    fields["hooks"] = buildJsonArray {
                        add(JsonObject((handler?.toMutableMap() ?: mutableMapOf()) + mapOf(
                            "type" to JsonPrimitive("command"), "command" to JsonPrimitive(command))))
                    }
                    val group = JsonObject(fields)
                    if (selected != null) groups[selected.second] = group else groups.add(group)
                    JsonObject(current + (event to JsonArray(groups)))
                }
                if (localError == null) { command = ""; matcher = ""; editing = null }
            }, enabled = !busy && command.isNotBlank()) { Text(if (editing == null) "追加命令规则到草稿" else "保存当前匹配组到草稿") }
            Text("匹配组（选择普通命令组可修改；多处理器及其他类型可在 JSON 中编辑）", style = MaterialTheme.typography.labelLarge)
            parsed?.forEach { (name, value) ->
                if (name in OriginalConfigEdits.hookEvents) (value as? JsonArray).orEmpty().forEachIndexed { index, raw ->
                    val group = raw as? JsonObject ?: return@forEachIndexed
                    val handlers = (group["hooks"] as? JsonArray).orEmpty()
                    val oneCommand = handlers.size == 1 && (handlers.firstOrNull() as? JsonObject)?.get("type")?.jsonPrimitive?.content == "command"
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name + " · 第 " + (index + 1) + " 组 · " + (group["matcher"]?.jsonPrimitive?.content ?: "任意匹配"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (oneCommand) TextButton(onClick = {
                                event = name; editing = name to index; matcher = group["matcher"]?.jsonPrimitive?.content.orEmpty()
                                command = (handlers[0] as JsonObject)["command"]?.jsonPrimitive?.content.orEmpty()
                            }, enabled = !busy) { Text("编辑") }
                            TextButton(onClick = {
                                mutate { current ->
                                    val list = (current[name] as? JsonArray).orEmpty().toMutableList()
                                    require(index in list.indices && list[index] == raw) { "规则已变化，请重新选择" }
                                    list.removeAt(index)
                                    val out = current.toMutableMap()
                                    if (list.isEmpty()) out.remove(name) else out[name] = JsonArray(list)
                                    JsonObject(out)
                                }
                                editing = null
                            }, enabled = !busy) { Text("移除本组") }
                        }
                    }
                }
            }
            Text("完整规则 JSON：支持原版处理器字段与多个匹配组。移除事件内最后一组后，保存会删除此用户事件声明。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it; editing = null }, enabled = !busy, label = { Text("用户规则（JSON 对象）") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { Json.parseToJsonElement(text).jsonObject.also { OriginalConfigEdits.hookRules(draft.hooks, it) } }
            .onSuccess { localError = null; save(it) }
            .onFailure { localError = it.message ?: "JSON 规则结构不正确" }
    }, enabled = !busy && enabled) { Text(if (busy) "正在保存…" else "确认保存规则") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
