package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.PermissionSelection
import kotlinx.serialization.json.*

@Composable fun OriginalPermissionDialog(params: JsonObject, respond: (JsonElement) -> Unit) {
    val request = remember(params) { PermissionSelection(params["permissions"] as? JsonObject ?: buildJsonObject {}) }
    var chosen by remember(params) { mutableStateOf(emptySet<String>()) }
    var network by remember(params) { mutableStateOf(false) }
    var session by remember(params) { mutableStateOf(false) }
    fun decline() = respond(request.response(emptySet(), false, false))
    AlertDialog(onDismissRequest = ::decline, title = { Text("确认额外权限范围") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(params.string("reason", "任务请求增加权限")); Text("工作目录：${params.string("cwd")}", style = MaterialTheme.typography.bodySmall)
            Text("此处记录你允许的请求范围。当前 Android 核心没有桌面项目沙箱，手机应用隔离不能限制命令只访问这些路径。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            request.network?.let { enabled -> Row { Checkbox(network, { network = it }); Text(if (enabled) "允许联网" else "禁止联网", modifier = Modifier.padding(top = 12.dp)) } }
            request.choices.forEach { choice -> Row { Checkbox(choice.id in chosen, { selected -> chosen = if (selected) chosen + choice.id else chosen - choice.id }); Text(choice.label, modifier = Modifier.padding(top = 10.dp)) } }
            if (request.choices.isEmpty() && request.network == null) Text("没有可识别的额外权限，本次将返回空授权。")
            Row { Checkbox(session, { session = it }); Text("将所选授权用于当前整个会话", modifier = Modifier.padding(top = 10.dp)) }
            Text("默认仅本回合；之后的命令仍要求严格复核。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { respond(request.response(chosen, network, session)) }, enabled = chosen.isNotEmpty() || network) { Text("确认所选权限") } }, dismissButton = { TextButton(onClick = ::decline) { Text("不授予额外权限") } })
}
