package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*
import java.io.File

@Composable fun OriginalSkillsScreen(controller: OriginalExtensionsController, allowChanges: Boolean, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var roots by remember(state.extraRoots) { mutableStateOf(state.extraRoots?.joinToString("\n").orEmpty()) }
    var rootConfirmation by remember { mutableStateOf<String?>(null) }
    var skillConfirmation by remember { mutableStateOf<JsonObject?>(null) }
    val editable = state.connected && allowChanges && !state.busy
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("原版技能管理", style = MaterialTheme.typography.titleLarge); TextButton(onClick = onBack) { Text("返回") } } }
        item {
            Text(state.skillNotice)
            if (state.skillsDirty) Text("技能来源有变化，可重新扫描更新列表。", color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = controller::refreshSkills, enabled = state.connected && !state.busy) { Text("重新扫描技能") }
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称或说明") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        items(state.skillErrors) { error -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        items(state.skills.filter { it.string("name").contains(query, true) || it.string("description").contains(query, true) }, key = { it.string("path") + ":" + it.string("name") }) { skill ->
            val enabled = skill["enabled"]?.jsonPrimitive?.booleanOrNull == true
            val display = (skill["interface"] as? JsonObject)?.string("displayName").orEmpty().ifBlank { skill.string("name") }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(display, style = MaterialTheme.typography.titleMedium)
                    Text(skill.string("description"), style = MaterialTheme.typography.bodyMedium)
                    Text("${when (skill.string("scope")) { "repo" -> "项目来源"; "user" -> "用户来源"; "admin" -> "管理来源"; "system" -> "系统来源"; else -> skill.string("scope") }} · ${if (enabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.labelMedium)
                    Text(skill.string("path"), style = MaterialTheme.typography.bodySmall)
                    skill.string("pluginId").takeIf { it.isNotBlank() }?.let { Text("所属插件：$it", style = MaterialTheme.typography.bodySmall) }
                    (skill["dependencies"] as? JsonObject)?.let { dependencies ->
                        (dependencies["tools"] as? JsonArray).orEmpty().forEach { value -> val tool = value.jsonObject; Text("依赖：${tool.string("type")} / ${tool.string("value")}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row { TextButton(onClick = { controller.readSkill(skill) }, enabled = !state.busy) { Text("阅读技能说明") }; TextButton(onClick = { skillConfirmation = skill }, enabled = editable && File(skill.string("path")).isAbsolute) { Text(if (enabled) "关闭技能" else "开启技能") } }
                }
            }
        }
        item {
            HorizontalDivider(); Text("额外技能目录", style = MaterialTheme.typography.titleMedium)
            Text("使用手机上可读取的实际目录，每行一个绝对路径。设置后影响当前核心进程；重启后需重新设置。", style = MaterialTheme.typography.bodySmall)
            if (state.extraRoots == null) Text("本页尚未设置额外目录，现有列表需要自行核对。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(roots, { roots = it }, enabled = editable, label = { Text("完整的额外目录列表；留空清除") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedButton(onClick = { rootConfirmation = roots }, enabled = editable) { Text("替换额外目录并扫描") }
        }
    }
    skillConfirmation?.let { skill ->
        val enabled = skill["enabled"]?.jsonPrimitive?.booleanOrNull != true
        AlertDialog(onDismissRequest = { skillConfirmation = null }, title = { Text(if (enabled) "开启技能" else "关闭技能") }, text = { Text("${skill.string("name")}\n${skill.string("path")}\n\n保存到用户配置后重新扫描，页面显示最终加载状态。") }, confirmButton = { TextButton(onClick = { skillConfirmation = null; controller.setSkillEnabled(skill, enabled) }, enabled = editable) { Text("确认") } }, dismissButton = { TextButton(onClick = { skillConfirmation = null }) { Text("取消") } })
    }
    rootConfirmation?.let { snapshot -> AlertDialog(onDismissRequest = { rootConfirmation = null }, title = { Text("替换当前进程的额外技能目录") }, text = { Text("这会替换全部额外目录，影响此核心进程中所有会话的技能发现。\n\n${snapshot.ifBlank { "清除全部额外目录" }}") }, confirmButton = { TextButton(onClick = { rootConfirmation = null; controller.setExtraSkillRoots(snapshot) }, enabled = editable) { Text("确认替换") } }, dismissButton = { TextButton(onClick = { rootConfirmation = null }) { Text("取消") } }) }
}

@Composable fun OriginalSkillPreview(controller: OriginalExtensionsController) {
    val state by controller.state.collectAsState()
    state.skillContents?.let { contents -> AlertDialog(onDismissRequest = controller::closeSkill, title = { Text("技能说明 · ${state.skillTitle}") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正在阅读说明正文，阅读不会执行其中的命令或启用服务。", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(contents) }
        }
    }, confirmButton = { TextButton(onClick = controller::closeSkill) { Text("关闭") } }) }
}
