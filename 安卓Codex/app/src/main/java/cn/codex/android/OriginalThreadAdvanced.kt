package cn.codex.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalThreadAdvanced(host: OriginalCoreHost, enabled: Boolean, onStarted: () -> Unit) {
    val state by host.state.collectAsState()
    var command by remember { mutableStateOf("") }; var timeout by remember { mutableStateOf("") }
    var history by remember { mutableStateOf("[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"补充历史\"}]}]") }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Column {
        OriginalCard("在当前会话执行 shell") {
            Text("按当前会话配置的 shell 解释管道、重定向和引号。此入口使用应用可用的完整权限，未继承会话的项目沙箱。")
            OriginalInput(command, { command = it }, "完整 shell 命令", enabled, 3); OriginalInput(timeout, { timeout = it }, "最长毫秒数；留空为一小时，0 立即超时", enabled)
            Button(onClick = { confirmation = "在当前会话执行：\n$command\n\n最长毫秒：${timeout.ifBlank { "一小时" }}\n命令可能修改文件或访问网络，按应用全部权限执行。" to { host.shellCommand(command, timeout); if (host.state.value.working) onStarted() } }, enabled = enabled && command.isNotBlank()) { Text("执行会话命令") }
        }
        OriginalCard("补入模型可见历史") {
            Text("高级功能：补入 Responses API 历史条目，影响后续模型理解。请先核对角色和正文，保存后不会自动启动模型回合。")
            OriginalInput(history, { history = it }, "历史条目 JSON 数组", enabled, 6)
            TextButton(onClick = { confirmation = "向当前会话补入以下模型历史？\n$history" to { host.injectItems(history) } }, enabled = enabled && history.isNotBlank()) { Text("确认补入历史") }
        }
        state.guardianReviews.filter { it.string("threadId") == state.threadId }.forEach { event ->
            val review = event["review"] as? JsonObject
            OriginalCard("守护审查 · ${event.string("reviewId")}") {
                Text("状态：${originalValue(review?.get("status"))}\n风险：${originalValue(review?.get("riskLevel"))}\n依据：${review?.string("rationale").orEmpty()}")
                Text("动作：${originalValue(event["action"])}")
                if (review?.string("status") == "denied") TextButton(onClick = { confirmation = "人工批准这个被拒绝的动作？\n${originalValue(event["action"])}\n\n许可意见会补入会话上下文，由后续回合处理。" to { host.approveGuardian(event) } }, enabled = enabled) { Text("人工批准此拒绝动作") }
            }
        }
    }
    OriginalConfirmation(confirmation) { confirmation = null }
}
