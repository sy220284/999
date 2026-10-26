package cn.codex.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun CapabilitiesScreen() {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var filter by remember { mutableStateOf("全部") }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { Json.parseToJsonElement(context.assets.open("功能对照.json").bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("能力，要说清楚", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("当前是开发版本，尚未完整复刻 Codex。文件、会话和工具执行在手机上完成；模型需要服务连接。此页逐项列出上传源码的客户端接口覆盖，不能将“待移植”理解为“安卓无法实现”。", style = MaterialTheme.typography.bodyMedium) }
        item { NoteCard("手机端的实际边界", "可运行系统允许的 shell 命令，但没有桌面 Linux 沙箱；应用权限不能访问其他应用私有目录。\n\n没有内置 Python、Node、完整 Linux 软件仓库；桌面可执行程序不能直接运行。\n\n应用后台任务受系统和厂商省电策略约束，不保证永久守护。\n\nWindows/macOS 专属沙箱与凭证接口不适用于安卓。") }
        item { NoteCard("尚待移植，不是安卓做不到", "原版 Rust 引擎、ChatGPT 订阅登录、上游模型路由、上下文压缩、完整 PTY 交互终端、Git 网络认证/推送、MCP OAuth/stdio、插件市场、Hooks、完整多代理、云任务、浏览器控制、实时语音和上游实验功能。") }
        item { Text("源码接口对照 · ${rows.size} 项", style = MaterialTheme.typography.titleMedium) }
        item { Row { listOf("全部", "安卓等价实现", "部分适配", "待移植").forEach { status -> if (status == "全部" || status == "待移植") FilterChip(selected = filter == status, onClick = { filter = status }, label = { Text(status) }, modifier = Modifier.padding(end = 8.dp)) } } }
        items(rows.filter { filter == "全部" || it.string("status") == filter }) { row ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(row.string("method"), style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(4.dp)); Text(row.string("status"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall); Text(row.string("detail"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}
@Composable private fun NoteCard(title: String, detail: String) { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
