package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import java.io.File

@Composable fun FullTerminalScreen(directory: File) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as CodexApplication).fullTerminal }
    val state by controller.state.collectAsState()
    val binding = remember { arrayOfNulls<TerminalView>(1) }
    DisposableEffect(controller) { onDispose { controller.detach(binding[0]) } }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) { Text(state.title, style = MaterialTheme.typography.titleMedium); Text(state.cwd.ifBlank { directory.absolutePath }, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            if (state.running) TextButton(onClick = { controller.stop() }) { Text("关闭终端") }
            else TextButton(onClick = { controller.start(directory) }) { Text("连接终端") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.session == null) Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持颜色、光标、滚动、选择、粘贴与交互程序。离开页面后由前台通知保持会话。")
            Text("只提供 Android 系统 shell 和已随应用安装的程序；Node、Python、npm 等运行环境尚未内置。命令以本应用身份运行，没有项目级沙箱。", style = MaterialTheme.typography.bodySmall)
        } else AndroidView(factory = { ctx -> TerminalView(ctx, null).apply { setTerminalViewClient(controller.callbacks); setTextSize((14 * resources.displayMetrics.scaledDensity).toInt()); setTypeface(android.graphics.Typeface.MONOSPACE); binding[0] = this; controller.attach(this); requestFocus() } }, update = { binding[0] = it; controller.attach(it) }, modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black))
        if (state.running) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.ctrl, controller::ctrl, label = { Text("Ctrl") }); FilterChip(state.alt, controller::alt, label = { Text("Alt") })
            listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "中断" to "\u0003").forEach { (label, sequence) -> TextButton(onClick = { controller.send(sequence) }) { Text(label) } }
            TextButton(onClick = controller::paste) { Text("粘贴") }
        }
    }
    if (state.copyRequest != null || state.pasteRequest) AlertDialog(onDismissRequest = controller::dismissClipboard, title = { Text(if (state.copyRequest != null) "终端请求写入剪贴板" else "终端请求读取剪贴板") }, text = { Text(if (state.copyRequest != null) "是否将终端返回的内容复制到系统剪贴板？" else "是否把剪贴板内容发送给当前终端程序？") }, confirmButton = { TextButton(onClick = { if (state.copyRequest != null) controller.acceptCopy() else controller.paste() }) { Text("允许此次操作") } }, dismissButton = { TextButton(onClick = controller::dismissClipboard) { Text("拒绝") } })
}
