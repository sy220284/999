package cn.codex.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalLibraryScreen(controller: OriginalLibraryController, allowChanges: Boolean, onAbort: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState(); val status by controller.operations.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf("市场") }
    var source by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }; var sparse by remember { mutableStateOf("") }; var market by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }; var remoteId by remember { mutableStateOf("") }; var visibility by remember { mutableStateOf("PRIVATE") }; var targets by remember { mutableStateOf("") }
    var preserveAccess by remember { mutableStateOf(true) }
    var appIds by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val edit = allowChanges && !status.busy && status.connected
    LaunchedEffect(tab) { when (tab) { "市场" -> controller.refreshMarkets(); "分享" -> controller.refreshShares(); else -> controller.loadApps() } }
    OriginalOpsPage("市场、分享与连接应用", status, onBack, onAbort) {
        item { OriginalOpsTabs(listOf("市场", "分享", "应用"), tab) { if (!status.busy) tab = it } }
        if (tab == "市场") {
            item { OriginalInput(source, { source = it }, "市场来源（仓库地址或本机路径）", edit); OriginalInput(ref, { ref = it }, "分支或引用（可选）", edit); OriginalInput(sparse, { sparse = it }, "稀疏目录（每行一个，可选）", edit, 2)
                Button(onClick = { confirmation = "添加市场：$source\n引用：$ref\n目录：$sparse\n会下载或读取此来源的插件声明。" to { controller.addMarket(source, ref, sparse) } }, enabled = edit && source.isNotBlank()) { Text("添加市场") }
                OriginalInput(market, { market = it }, "要操作的市场名称；升级留空代表全部", edit)
                Row { TextButton(onClick = { confirmation = "升级${market.ifBlank { "全部市场" }}，更新本机市场内容？" to { controller.upgradeMarket(market.takeIf { it.isNotBlank() }) } }, enabled = edit) { Text("升级市场") }; TextButton(onClick = { confirmation = "移除市场 $market？相关插件来源可能不再可用。" to { controller.removeMarket(market) } }, enabled = edit && market.isNotBlank()) { Text("移除市场") } }
                TextButton(onClick = { confirmation = "根据当前远程安装状态重新对齐本机插件，会增改本机插件内容。" to controller::reconcile }, enabled = edit) { Text("对齐已安装插件") }
                TextButton(onClick = controller::refreshMarkets, enabled = !status.busy) { Text("刷新市场") }
            }
            items(state.markets) { entry -> OriginalCard(entry.string("name")) { Text("本机目录：${originalValue(entry["path"])}"); Text("插件数：${(entry["plugins"] as? JsonArray).orEmpty().size}"); TextButton(onClick = { market = entry.string("name") }, enabled = !status.busy) { Text("选择此市场") } } }
            state.marketResult?.let { result -> item { OriginalCard("市场操作结果") { listOf("marketplaceName" to "市场名称", "installedRoot" to "安装目录", "selectedMarketplaces" to "选择的市场", "upgradedRoots" to "升级目录", "errors" to "升级错误", "changedPlugins" to "变更插件", "failedRemotePluginIds" to "同步失败", "failedMaterializationRemotePluginIds" to "落盘失败").forEach { (key, label) -> result[key]?.let { Text("$label：${originalValue(it)}") } } } } }
        }
        if (tab == "分享") {
            item { Text("默认仅限私有。分享会向服务上传插件内容，可见性和对象决定其他人能否读取或编辑。")
                OriginalInput(path, { path = it }, "手机上的插件目录", edit); OriginalInput(remoteId, { remoteId = it }, "远程插件标识（新增留空）", edit)
                Row { listOf("PRIVATE" to "私有", "UNLISTED" to "不列出", "LISTED" to "可发现").forEach { (key, label) -> FilterChip(visibility == key, { visibility = key }, enabled = edit, label = { Text(label) }) } }
                Text("分享对象使用用户、群组或工作区的准确标识；可见范围由所选方式和服务权限共同决定。", style = MaterialTheme.typography.bodySmall)
                OriginalInput(targets, { targets = it }, "对象：每行 user/group/workspace 标识 reader/editor", edit, 3)
                if (remoteId.isNotBlank()) Row { Checkbox(preserveAccess, { preserveAccess = it }, enabled = edit); Text("更新内容时保留已有可见性和对象", modifier = Modifier.weight(1f)) }
                Button(onClick = { confirmation = "上传插件：$path\n远程标识：${remoteId.ifBlank { "新建" }}\n分享权限：${if (remoteId.isNotBlank() && preserveAccess) "保持原有权限" else "$visibility；对象：${targets.ifBlank { "无额外对象" }}"}" to { controller.saveShare(path, remoteId, visibility, targets, preserveAccess) } }, enabled = edit && path.isNotBlank()) { Text("保存或更新分享内容") }
                TextButton(onClick = { confirmation = "更新 $remoteId 的可见性为 $visibility，并完整替换分享对象：\n${targets.ifBlank { "清除全部额外对象" }}" to { controller.updateShare(remoteId, visibility, targets) } }, enabled = edit && remoteId.isNotBlank()) { Text("更新可见性和对象") }
                Row { TextButton(onClick = { confirmation = "将远程插件 $remoteId 检出到手机本机目录？" to { controller.checkoutShare(remoteId) } }, enabled = edit && remoteId.isNotBlank()) { Text("检出到手机") }; TextButton(onClick = { confirmation = "删除远程分享 $remoteId？已有链接和共享访问可能失效。" to { controller.deleteShare(remoteId) } }, enabled = edit && remoteId.isNotBlank()) { Text("删除分享") } }
                TextButton(onClick = controller::refreshShares, enabled = !status.busy) { Text("刷新分享列表") }
            }
            items(state.shares) { item -> val plugin = item.getValue("plugin").jsonObject; OriginalCard(plugin.string("name")) { Text("远程标识：${plugin.string("remotePluginId")}"); Text("本机目录：${originalValue(item["localPluginPath"])}"); Text("分享范围：${originalValue(plugin["shareContext"])}"); TextButton(onClick = { remoteId = plugin.string("remotePluginId"); path = item.string("localPluginPath") }, enabled = !status.busy) { Text("选择此分享") } } }
            state.shareResult?.let { result -> item { OriginalCard("分享返回结果") {
                result.string("shareUrl").takeIf { it.isNotBlank() }?.let { url -> Row { TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("插件分享链接",url)) }) { Text("复制分享链接") }; TextButton(onClick = { openOriginalLogin(context,url) }) { Text("打开分享页面") } } }
 listOf("remotePluginId" to "远程标识", "shareUrl" to "分享链接", "pluginPath" to "本机插件目录", "marketplacePath" to "本机市场目录", "remoteVersion" to "远程版本", "canPublishToWorkspace" to "允许发布到工作区", "discoverability" to "实际可见性", "principals" to "实际分享对象").forEach { (key, label) -> result[key]?.let { Text("$label：${originalValue(it)}") } } } } }
        }
        if (tab == "应用") {
            item { Row { TextButton(onClick = { controller.loadApps() }, enabled = !status.busy) { Text("刷新目录") }; TextButton(onClick = controller::loadInstalled, enabled = !status.busy) { Text("已安装及调用状态") } }; OriginalInput(appIds, { appIds = it }, "应用标识，每行一个", !status.busy, 2); TextButton(onClick = { controller.appDetail(appIds.lines().filter { it.isNotBlank() }) }, enabled = !status.busy && appIds.isNotBlank()) { Text("读取这些应用的详情") } }
            items(state.apps.entries) { app -> OriginalCard(app.string("name")) { Text(app.string("description")); Text("可访问：${originalValue(app["isAccessible"])}；已启用：${originalValue(app["isEnabled"])}"); Text("所属插件：${originalValue(app["pluginDisplayNames"])}"); Row { TextButton(onClick = { controller.appDetail(listOf(app.string("id"))) }, enabled = !status.busy) { Text("工具与详情") }; app.string("installUrl").takeIf { it.isNotBlank() }?.let { url -> TextButton(onClick = { openOriginalLogin(context, url) }) { Text("打开安装或授权页面") } } } } }
            if (state.apps.nextCursor != null) item { TextButton(onClick = { controller.loadApps(true) }, enabled = !status.busy) { Text("加载更多应用") } }
            items(state.installed) { app -> OriginalCard("已安装 · ${app.string("id")}") { Text("运行名称：${originalValue(app["runtimeName"])}"); Text("已启用：${originalValue(app["enabled"])}；可调用：${originalValue(app["callable"])}") } }
            items(state.appDetails) { app -> OriginalCard(app.string("name")) { Text(app.string("description")); (app["toolSummaries"] as? JsonArray).orEmpty().forEach { entry -> val tool = entry.jsonObject; Text("${tool.string("name")} · ${if (tool["isEnabled"]?.jsonPrimitive?.booleanOrNull == true) "已启用" else tool.string("disabledReason", "不可用")}"); Text(tool.string("description"), style = MaterialTheme.typography.bodySmall); Text(if(tool["isReadOnly"]?.jsonPrimitive?.booleanOrNull==true) "只读工具" else "工具可能产生修改或其他副作用", style=MaterialTheme.typography.labelSmall) } } }
            if (state.missingApps.isNotEmpty()) item { Text("未找到应用：${state.missingApps.joinToString()}", color = MaterialTheme.colorScheme.error) }
        }
    }
    OriginalConfirmation(confirmation) { confirmation = null }
}
