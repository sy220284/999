package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.codex.core.original.OriginalCommandController

@Composable fun OriginalCommandScreen(controller: OriginalCommandController, defaultCwd: String, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var command by remember { mutableStateOf("") }; var cwd by remember { mutableStateOf(defaultCwd) }
    var tty by remember { mutableStateOf(false) }; var consent by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }; var timeout by remember { mutableStateOf("60000") }
    var rows by remember { mutableStateOf("24") }; var cols by remember { mutableStateOf("80") }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回原版工作区") }
        Text("原版独立命令", style = MaterialTheme.typography.titleLarge); Text(state.status, color = MaterialTheme.colorScheme.primary)
        Text("以本应用身份执行，可访问本应用能够访问的文件与网络；没有项目级沙箱。返回页面不会停止命令。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(command, { command = it }, label = { Text("手机命令") }, enabled = !state.running, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cwd, { cwd = it }, label = { Text("工作目录") }, enabled = !state.running, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(timeout, { timeout = it }, label = { Text("超时（毫秒）") }, enabled = !state.running, singleLine = true)
        Row { Checkbox(tty, { tty = it }, enabled = !state.running); Text("使用伪终端", modifier = Modifier.padding(top = 12.dp)) }
        Row { Checkbox(consent, { consent = it }, enabled = !state.running); Text("我确认这条命令及其访问范围", modifier = Modifier.padding(top = 12.dp)) }
        Button(onClick = { controller.run(command, cwd, tty, requireNotNull(timeout.toLongOrNull())) }, enabled = state.connected && !state.running && consent && command.isNotBlank() && timeout.toLongOrNull()?.let { it > 0 && it <= Long.MAX_VALUE - 10_000 } == true) { Text("执行命令") }
        if (state.running) {
            OutlinedTextField(input, { input = it }, label = { Text("标准输入") }, enabled = !state.stdinClosed, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { controller.send(input + "\n"); input = "" }, enabled = !state.controlBusy && !state.stdinClosed) { Text("发送输入") }
                TextButton(onClick = { controller.send("", true) }, enabled = !state.controlBusy && !state.stdinClosed) { Text("关闭输入") }
                TextButton(onClick = controller::stop, enabled = !state.controlBusy) { Text("终止命令") }
            }
            if (state.tty) {
                Row { OutlinedTextField(rows, { rows = it }, label = { Text("行") }, modifier = Modifier.weight(1f)); OutlinedTextField(cols, { cols = it }, label = { Text("列") }, modifier = Modifier.weight(1f)) }
                TextButton(onClick = { controller.resize(rows.toInt(), cols.toInt()) }, enabled = !state.controlBusy && rows.toIntOrNull()?.let { it > 0 } == true && cols.toIntOrNull()?.let { it > 0 } == true) { Text("调整终端尺寸") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(); Text(state.output.ifBlank { "等待命令输出…" }, fontFamily = FontFamily.Monospace)
    }
}
