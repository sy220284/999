package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun GitScreen(controller: RuntimeController, screen: ScreenState) {
    val context = LocalContext.current
    val app = context.applicationContext as CodexApplication
    val worker = app.gitOperations
    val state by worker.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember(screen.project) { mutableStateOf("") }; var remote by remember(screen.project) { mutableStateOf("origin") }
    var branch by remember(screen.project) { mutableStateOf("main") }; var username by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val directory = remember(screen.project) { controller.workspace(screen.project).root }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Git 仓库", style = MaterialTheme.typography.titleLarge); Text("当前项目：${screen.project}")
        Text("手机直接操作仓库，不依赖桌面电脑。远程连接当前支持 HTTPS；SSH 与多个推送目标尚未接入。", style = MaterialTheme.typography.bodySmall)
        if (state.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = worker::cancel) { Text("请求停止传输") } }
        OutlinedTextField(url, { url = it }, enabled = !state.running, label = { Text("已核对的仓库 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(remote, { remote = it }, enabled = !state.running, label = { Text("远端名称") }, modifier = Modifier.weight(1f))
            OutlinedTextField(branch, { branch = it }, enabled = !state.running, label = { Text("分支") }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("remotes" to "查看远端", "status" to "状态", "diff" to "修改差异", "log" to "提交记录", "branch" to "本地分支").forEach { (action, title) -> TextButton(onClick = { worker.execute(directory, action, "", remote, branch, url) }, enabled = !state.running) { Text(title) } }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("clone" to "克隆到空项目", "set_remote" to "保存获取与推送地址", "fetch" to "获取", "pull" to "拉取并合并", "push" to "推送当前填写分支").forEach { (action, title) -> TextButton(onClick = { confirm = action }, enabled = !state.running && url.isNotBlank()) { Text(title) } }
        }
        HorizontalDivider(); Text("仓库凭证", style = MaterialTheme.typography.titleMedium)
        Text("凭证按 HTTPS 仓库地址加密保存在手机，地址与日志中不保存访问令牌。公开仓库可不配置。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("访问令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { GitCredentialStore(context).save(url, username, token) } }.onSuccess { token = ""; message = "凭证已加密保存" }.onFailure { message = it.message } } }, enabled = !state.running && username.isNotBlank() && token.isNotBlank()) { Text("保存凭证") }
            TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { GitCredentialStore(context).remove(url) } }.onSuccess { message = "已删除该地址的凭证" }.onFailure { message = it.message } } }, enabled = !state.running && url.isNotBlank()) { Text("删除凭证") }
        }
        HorizontalDivider(); Text("本地提交", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(commit, { commit = it }, label = { Text("中文提交说明") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("init" to "初始化", "add" to "暂存全部", "commit" to "提交", "checkout" to "切换填写的分支").forEach { (action, title) -> TextButton(onClick = { confirm = action }, enabled = !state.running && (action != "commit" || commit.isNotBlank())) { Text(title) } }
        }
        message?.let { Text(it) }; state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.output.isNotBlank()) Text(state.output)
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认 Git 操作") }, text = {
            Text("项目：${screen.project}\n远端：$remote\n地址：$url\n分支：$branch\n\n" + when (action) { "push" -> "将此本地分支上传至已核对的目标地址，不强制推送。"; "pull" -> "拉取后会合并并修改项目文件，冲突需要手动处理。"; "clone" -> "当前项目目录必须为空，失败后不会自动清理已有文件。"; "set_remote" -> "将获取与推送地址一同设置为上方地址。"; "checkout" -> "切换本地分支，未提交的修改可能阻止切换。"; "commit" -> "提交当前暂存区，说明：$commit"; else -> "执行此操作并读取实际结果。" })
        }, confirmButton = { TextButton(onClick = { confirm = null; val value = when (action) { "clone", "set_remote" -> url; "commit" -> commit; "checkout" -> branch; "add" -> "."; else -> "" }; worker.execute(directory, action, value, remote, branch, url) }, enabled = !state.running) { Text("确认执行") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}
