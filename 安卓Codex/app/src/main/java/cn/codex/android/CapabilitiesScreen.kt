package cn.codex.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
    val runtime by AndroidLanguageRuntime.state.collectAsState()
    var rows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var remaining by remember { mutableStateOf(emptyList<JsonObject>()) }
    var filter by remember { mutableStateOf("全部") }
    var kind by remember { mutableStateOf("全部类型") }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { listOf("原版协议进度.json","实验协议进度.json").flatMap { file -> Json.parseToJsonElement(context.assets.open(file).bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject } } } }
    LaunchedEffect(Unit) { remaining = withContext(Dispatchers.IO) { Json.parseToJsonElement(context.assets.open("非协议能力进度.json").bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("能力，要说清楚", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("当前是开发版本，尚未完整复刻 Codex。文件、会话和工具执行在手机上完成；模型需要服务连接。此页逐项列出上传源码的客户端请求、服务端请求与通知；存在调用封装不代表语义和体验已全部完成。不能将“待移植”理解为“安卓无法实现”。", style = MaterialTheme.typography.bodyMedium) }
        item { NoteCard("手机端的实际边界", "可运行系统允许的 shell 命令，但没有桌面 Linux 沙箱；应用权限不能访问其他应用私有目录。\n\nPython/Node/npm按随包程序与运行库检测，不会自动安装完整Linux软件仓库；桌面可执行程序不能直接运行。\n\n应用后台任务受系统和厂商省电策略约束，不保证永久守护。\n\nWindows/macOS 专属沙箱与凭证接口不适用于安卓。") }
        item { NoteCard("代码进度与实际运行", "原版核心连接、完整终端、HTTPS Git 管理、插件、Hooks、MCP 与部分会话接口已有代码。当前阶段未构建、未打包、未在设备验收；其余高级能力仍在补齐。模型访问需要服务授权，手机独立运行不等于离线模型。") }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本包工具", style = MaterialTheme.typography.titleMedium)
            Text(runtime.message);runtime.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            listOf("原版核心" to "libcodex_engine.so", "代码模式" to "libcodex_code_mode_host.so", "搜索 rg" to "libcodex_rg.so", "Git 命令" to "libcodex_git.so", "Git HTTPS传输" to "libcodex_git_remote_http.so", "SSH客户端" to "libcodex_ssh.so", "Node" to "libcodex_node.so", "Python" to "libcodex_python.so", "npm/npx" to "libcodex_npm.so").forEach { (name, library) ->
                Text("$name：${if (AndroidToolPaths.installed(context, library)) "已随包安装，仍需验证执行" else "当前包未包含"}")
            }
            Text("Git 页面通过JGit连接HTTPS；Git命令网络需要随包传输助手，SSH还需你核对主机公钥。没有Perl扩展和完整桌面软件生态。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("接口之外的交付 · ${remaining.size} 个范围",style=MaterialTheme.typography.titleMedium); Text("以下范围有交叉，不与277个接口相加，也不代表只剩13个小任务。",style=MaterialTheme.typography.bodySmall) }
        items(remaining,key={"remaining:"+it.string("id")}) { item -> NoteCard(item.string("title")+" · "+item.string("status"),item.string("detail")+"\n\n完成条件："+item.string("acceptance")) }
        item { Text("源码接口对照（含Rust实验接口）· ${rows.size} 项", style = MaterialTheme.typography.titleMedium) }
        item { Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            listOf("全部类型", "客户端请求", "服务端请求", "服务端通知").forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value) }, modifier = Modifier.padding(end = 8.dp)) }
        } }
        item { Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            listOf("全部", "待完成", "代码已接入", "平台受限").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }, modifier = Modifier.padding(end = 8.dp)) }
        } }
        items(rows.filter { row -> (kind == "全部类型" || row.string("kind") == kind) && when (filter) {
            "待完成" -> listOf("待接入","未接","未完成","未实现","缺少","有限","待实现").any { row.string("status").contains(it) }
            "代码已接入" -> row.string("status").contains("已接入")
            "平台受限" -> row.string("status").contains("平台受限")
            else -> true
        } }, key = { it.string("kind") + ":" + it.string("method") }) { row ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(row.string("method"), style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(4.dp)); Text(row.string("status"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall); Text(row.string("kind") + " · " + row.string("phase") + "\n" + row.string("validation"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}
@Composable private fun NoteCard(title: String, detail: String) { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
