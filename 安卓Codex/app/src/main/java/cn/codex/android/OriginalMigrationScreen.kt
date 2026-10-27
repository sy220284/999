package cn.codex.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalMigrationScreen(controller: OriginalMigrationController, cwd: String, threadId: String?, allowChanges: Boolean, onAbort: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState(); val status by controller.operations.state.collectAsState(); val context = LocalContext.current
    var tab by remember { mutableStateOf("迁移") }; var home by remember { mutableStateOf(false) }; var paths by remember { mutableStateOf(cwd) }; var days by remember { mutableStateOf("") }; var max by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }; var provider by remember { mutableStateOf("") }; var migrationSource by remember { mutableStateOf("") }; var selected by remember(state.items) { mutableStateOf(setOf<Int>()) }
    var historyJson by remember { mutableStateOf("[{\"itemType\":\"CONFIG\",\"successes\":[],\"failures\":[]}]") }
    var key by remember { mutableStateOf("") }; var value by remember { mutableStateOf("null") }; var strategy by remember { mutableStateOf("upsert") }
    var summaryId by remember { mutableStateOf(threadId.orEmpty()) }; var summaryPath by remember { mutableStateOf("") }; var byPath by remember { mutableStateOf(false) }; var diffPath by remember { mutableStateOf(cwd) }
    var query by remember { mutableStateOf("") }; var roots by remember { mutableStateOf(cwd) }; var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val edit = allowChanges && !status.busy && status.connected
    OriginalOpsPage("配置迁移与项目工具", status, onBack, onAbort) {
        item { OriginalOpsTabs(listOf("迁移", "迁移记录", "单值配置", "会话摘要", "远端差异", "账号状态", "文件查找"), tab) { if (!status.busy) { tab = it; when (it) { "迁移记录" -> controller.readHistories(); "单值配置" -> controller.readConfig(); "账号状态" -> controller.auth(false, false) } } } }
        if (status.mutating) item { TextButton(onClick = { confirmation = "关闭核心以中断本机处理？已修改的文件保留，重新连接后请先核查迁移历史。" to onAbort }) { Text("关闭核心并核查导入") } }
        if (tab == "迁移") {
            item {
                Text("扫描手机上已有的代理配置，选择要导入的内容。账号与服务可能只支持部分来源。")
                Row { Checkbox(home, { home = it }, enabled = !status.busy); Text("扫描当前核心的用户目录") }
                OriginalInput(paths, { paths = it }, "项目目录，每行一个", !status.busy, 2)
                OriginalInput(days, { days = it }, "会话最多追溯天数（可选）", !status.busy); OriginalInput(max, { max = it }, "最多会话数（可选）", !status.busy)
                OriginalInput(source, { source = it }, "来源筛选（可选）", !status.busy); OriginalInput(migrationSource, { migrationSource = it }, "迁移来源标记（可选）", !status.busy); OriginalInput(provider, { provider = it }, "提供方标识（可选；结果登记必填）", !status.busy)
                Button(onClick = { controller.detect(home, paths, days, max, source, migrationSource) }, enabled = !status.busy) { Text("检测可迁移内容") }
                Button(onClick = { val chosen = selected.sorted().map { state.items[it] }; confirmation = "导入所选 ${chosen.size} 项：\n${chosen.joinToString("\n") { it.string("description") }}\n\n会修改当前核心的配置、技能或会话等内容，后台等待结果，不会自动重发。" to { controller.importSelected(chosen, source, provider, migrationSource) } }, enabled = edit && selected.isNotEmpty()) { Text("导入所选内容") }
            }
            items(state.items.indices.toList()) { index -> val item = state.items[index]; OriginalCard("${item.string("itemType")} · ${item.string("description")}") {
                Row { Checkbox(index in selected, { checked -> selected = if (checked) selected + index else selected - index }, enabled = edit); Text("选择此项") }
                Text("项目：${originalValue(item["cwd"])}"); item["details"]?.takeIf { it != JsonNull }?.let { Text(originalValue(it), style = MaterialTheme.typography.bodySmall) }
            } }
            items(state.connectors) { connector -> Text("检测到连接来源：${connector.string("name")} · 会话数 ${originalValue(connector["sessionCount"])} · ${connector.string("source")}") }
            state.activeImport?.let { id -> state.runs[id]?.let { run -> item { OriginalCard(if (run.completed) "导入结果 · $id" else "导入进度 · $id") { run.results.forEach { result -> Text("类型：${result.string("itemType")}"); Text("成功：${originalValue(result["successes"])}"); Text("失败：${originalValue(result["failures"])}", color = if ((result["failures"] as? JsonArray).orEmpty().isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) } } } } }
        }
        if (tab == "迁移记录") {
            item { TextButton(onClick = controller::readHistories, enabled = !status.busy) { Text("刷新迁移历史") } }
            items(state.histories) { entry -> OriginalCard("导入 ${entry.string("importId")}") { Text("提供方：${originalValue(entry["providerId"])}"); Text("成功：${originalValue(entry["successes"])}"); Text("失败：${originalValue(entry["failures"])}") } }
            items(state.importedConnectors) { connector -> Text("已迁移连接来源：${connector.string("name")} · 会话数 ${originalValue(connector["sessionCount"])} · ${connector.string("source")}") }
            item { OriginalCard("登记客户端已完成的迁移") {
                Text("仅记录你已在其他客户端实际完成的成功或失败结果，不会执行迁移。每种类型填写 itemType、successes 和 failures；各成功项含 itemType/cwd/source/target，各失败项含 itemType/errorType/subErrorType/failureStage/message/cwd/source。")
                OriginalInput(provider, { provider = it }, "提供方标识", edit); OriginalInput(historyJson, { historyJson = it }, "实际迁移结果 JSON 数组", edit, 6)
                TextButton(onClick = { confirmation = "为提供方 $provider 登记以下实际迁移结果？\n$historyJson" to { controller.recordHistory(provider, historyJson) } }, enabled = edit && provider.isNotBlank()) { Text("登记迁移结果") }
            } }
        }
        if (tab == "单值配置") item {
            TextButton(onClick = controller::readConfig, enabled = !status.busy) { Text("读取最新配置和版本") }
            Text("活动用户文件：${state.configSnapshot?.file ?: "当前无可写用户层"}")
            OriginalInput(key, { key = it }, "配置键路径（点分段，字面键使用引号）", edit)
            OriginalInput(value, { value = it }, "新的 JSON 值", edit, 4)
            Row { FilterChip(strategy == "upsert", { strategy = "upsert" }, label = { Text("合并更新") }); FilterChip(strategy == "replace", { strategy = "replace" }, label = { Text("完整替换此值") }) }
            Button(onClick = { state.configSnapshot?.let { snapshot -> confirmation = "文件：${snapshot.file}\n配置：$key\n值：$value\n策略：$strategy\n使用当前版本保存，冲突时需要重新读取。" to { controller.writeValue(snapshot, key, value, strategy) } } }, enabled = edit && state.configSnapshot != null && key.isNotBlank()) { Text("保存这个配置值") }
            state.configWrite?.let { Text("写入状态：${it.string("status")}；版本：${it.string("version")}") }
            state.config?.let { OriginalCard("重新读取的有效配置") { Text(originalValue(it), style = MaterialTheme.typography.bodySmall) } }
        }
        if (tab == "会话摘要") item {
            Row { Checkbox(byPath, { byPath = it }); Text("按历史记录路径读取") }
            if (byPath) OriginalInput(summaryPath, { summaryPath = it }, "记录路径", !status.busy) else OriginalInput(summaryId, { summaryId = it }, "会话标识", !status.busy)
            Button(onClick = { controller.summary(if (byPath) "" else summaryId, if (byPath) summaryPath else "") }, enabled = !status.busy && (if (byPath) summaryPath else summaryId).isNotBlank()) { Text("读取摘要") }
            state.summary?.let { result -> OriginalCard("会话摘要") { Text(result.string("preview")); Text("会话：${result.string("conversationId")}\n目录：${result.string("cwd")}\n记录：${result.string("path")}\n更新时间：${originalValue(result["updatedAt"])}\n模型提供方：${result.string("modelProvider")}") } }
        }
        if (tab == "远端差异") item { OriginalInput(diffPath, { diffPath = it }, "Git 项目目录", !status.busy); Button(onClick = { controller.diff(diffPath) }, enabled = !status.busy) { Text("读取与远端的差异") }; state.diff?.let { OriginalCard("对比基准 ${it.string("sha")}") { androidx.compose.foundation.text.selection.SelectionContainer { Text(it.string("diff")) } } } }
        if (tab == "账号状态") item {
            Row { TextButton(onClick = { controller.auth(false, false) }, enabled = !status.busy) { Text("读取状态") }; TextButton(onClick = { controller.auth(true, false) }, enabled = !status.busy) { Text("刷新认证状态") } }
            state.auth?.let { result -> Text("认证方式：${originalValue(result["authMethod"])}\n需要账号授权：${originalValue(result["requiresOpenaiAuth"])}") }
            TextButton(onClick = { confirmation = "读取当前账号令牌到临时内存？令牌仅在本页按需复制，关闭后可清除。" to { controller.auth(false, true) } }, enabled = !status.busy) { Text("按需读取令牌") }
            state.auth?.string("authToken")?.takeIf { it.isNotBlank() }?.let { token -> Text("令牌已读取，内容隐藏"); TextButton(onClick = { confirmation = "将账号令牌复制到系统剪贴板？其他应用可能读取剪贴板。" to { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("账号令牌", token)) } }) { Text("复制令牌") }; TextButton(onClick = controller::hideToken) { Text("清除临时令牌") } }
        }
        if (tab == "文件查找") {
            item { OriginalInput(query, { query = it }, "文件名或模糊查询", !status.busy); OriginalInput(roots, { roots = it }, "搜索目录，每行一个", !status.busy, 2); Button(onClick = { controller.search(query, roots) }, enabled = !status.busy && query.isNotBlank()) { Text("查找文件") } }
            items(state.files) { file -> OriginalCard(file.string("file_name")) { Text("根目录：${file.string("root")}\n路径：${file.string("path")}\n匹配：${file.string("match_type")} · ${originalValue(file["score"])}"); TextButton(onClick = { val raw=java.io.File(file.string("path")); val full=if(raw.isAbsolute) raw.absolutePath else java.io.File(file.string("root"),file.string("path")).absolutePath; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("文件路径",full)) }) { Text("复制完整路径") } } }
        }
    }
    DisposableEffect(tab) { val leaving = tab; onDispose { if (leaving == "账号状态") controller.hideToken() } }
    OriginalConfirmation(confirmation) { confirmation = null }
}
