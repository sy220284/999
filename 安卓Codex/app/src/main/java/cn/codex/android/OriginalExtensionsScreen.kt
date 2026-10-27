package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

private data class HookConfirmation(val hook: JsonObject, val trust: Boolean, val revoke: Boolean = false)

@Composable fun OriginalExtensionsScreen(controller: OriginalExtensionsController, allowChanges: Boolean, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var tab by remember { mutableStateOf("插件") }
    var installedOnly by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<OriginalPlugin, Boolean>?>(null) }
    var togglePlugin by remember { mutableStateOf<OriginalPlugin?>(null) }
    var hookConfirm by remember { mutableStateOf<HookConfirmation?>(null) }
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    BackHandler(onBack = onBack)
    // 连接代次变化或操作结束后，重新检查确认框中的插件，避免旧目录对象继续操作。
    LaunchedEffect(state.connected) { if (!state.connected) { confirm = null; togglePlugin = null; hookConfirm = null } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回原版会话") }
            listOf("插件", "自动规则").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (tab == "插件") "扩展你的工作方式" else "让规则跟随工作流", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                val status = if (state.connected) state.status else "原版核心已断开，请返回重新连接"
                if (motion) AnimatedContent(targetState = status, label = "扩展状态切换") { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { controller.refresh() }, enabled = state.connected && !state.busy) { Text("刷新本机状态") }
                    TextButton(onClick = { controller.refresh(fresh = true) }, enabled = state.connected && !state.busy) { Text("更新插件目录") }
                }
            }
            if (state.error != null) item { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) { Column(Modifier.padding(14.dp)) { Text(state.error.orEmpty()); TextButton(onClick = controller::dismissError) { Text("知道了") } } } }
            item { Text(state.configNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.marketplaceErrors.forEachIndexed { index, error -> item(key = "market-error:$index") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (tab == "插件") {
                item {
                    OutlinedTextField(search, { search = it }, label = { Text("搜索插件或市场") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FilterChip(installedOnly, { installedOnly = !installedOnly }, label = { Text("只看已安装") })
                }
                val plugins = state.plugins.filter { (!installedOnly || it.installed) && (search.isBlank() || (it.displayName + it.marketplace + it.description).contains(search, ignoreCase = true)) }
                if (plugins.isEmpty()) item { Text(if (state.busy) "正在读取插件" else "没有匹配的插件。可刷新目录，或检查市场加载提示。") }
                items(plugins, key = { it.key }) { plugin ->
                    Surface(modifier = if (motion) Modifier.animateContentSize(tween(220)) else Modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(plugin.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                            Text("${plugin.marketplace} · ${if (plugin.installed) if (plugin.enabled) "已安装 · 已启用" else "已安装 · 未启用" else "未安装"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("来源：${plugin.source.string("type")} ${plugin.source.string("url", plugin.source.string("package", plugin.source.string("path")))}", style = MaterialTheme.typography.bodySmall)
                            plugin.installBlock?.takeIf { !plugin.installed }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row {
                                TextButton(onClick = { controller.detail(plugin) }, enabled = !state.busy && state.connected) { Text("详情与能力") }
                                if (plugin.installed) TextButton(onClick = { togglePlugin = plugin }, enabled = !state.busy && state.connected && allowChanges && state.configSnapshot != null && plugin.summary.string("availability") == "AVAILABLE") { Text(if (plugin.enabled) "停用" else "启用") }
                                if (plugin.installed) TextButton(onClick = { confirm = plugin to false }, enabled = !state.busy && state.connected && allowChanges) { Text("卸载") }
                                else FilledTonalButton(onClick = { confirm = plugin to true; controller.detail(plugin) }, enabled = !state.busy && state.connected && allowChanges && plugin.installBlock == null) { Text("安装") }
                            }
                        }
                    }
                }
            } else {
                item { FilledTonalButton(onClick = controller::editRules, enabled = state.connected && !state.busy && allowChanges && state.configSnapshot != null) { Text("创建与编辑用户规则") } }
                if (state.hooks.isEmpty()) item { Text("当前没有已加载的 Hook 配置。此页展示原版核心实际加载的规则。") }
                items(state.hooks, key = { "hooks:${it.string("cwd")}" }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(entry.string("cwd"), style = MaterialTheme.typography.labelMedium)
                        (entry["warnings"] as? JsonArray).orEmpty().forEach { Text(it.jsonPrimitive.content, color = MaterialTheme.colorScheme.error) }
                        (entry["errors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { Text(it.string("path") + "：" + it.string("message"), color = MaterialTheme.colorScheme.error) }
                        (entry["hooks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { hook ->
                            val enabled = hook["enabled"]?.jsonPrimitive?.booleanOrNull == true
                            val trust = when (hook.string("trustStatus")) { "trusted" -> "已信任"; "untrusted" -> "未信任"; "modified" -> "内容已变化，需重新信任"; "managed" -> "由策略管理"; else -> "未知信任状态" }
                            ExpandableCard("${hookEventTitle(hook.string("eventName"))} · ${if (enabled) "启用" else "停用"} · $trust", listOf(hook.string("sourcePath"), "处理器：${when (hook.string("handlerType")) { "command" -> "本机命令"; "mcpTool" -> "MCP 工具"; "prompt" -> "提示词"; "agent" -> "代理任务"; else -> hook.string("handlerType") }}", hook.string("command"), "匹配：${hook.string("matcher", "全部")}", "超时：${hook["timeoutSec"]} 秒", hook.string("statusMessage")).filter { it.isNotBlank() }.joinToString("\n"), Icons.Outlined.Bolt)
                            val managed = hook["isManaged"]?.jsonPrimitive?.booleanOrNull != false || hook.string("trustStatus") == "managed"
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                val canEdit = !state.busy && state.connected && allowChanges && state.configSnapshot != null && !managed && hook.string("currentHash").isNotBlank() && hook.string("trustStatus") in setOf("trusted", "untrusted", "modified")
                                TextButton(onClick = { hookConfirm = HookConfirmation(hook, false) }, enabled = canEdit) { Text(if (enabled) "停用规则" else "启用规则") }
                                if (hook.string("trustStatus") in setOf("untrusted", "modified")) TextButton(onClick = { hookConfirm = HookConfirmation(hook, true) }, enabled = canEdit) { Text("查看并信任") }
                                if (hook.string("trustStatus") in setOf("trusted", "modified")) TextButton(onClick = { hookConfirm = HookConfirmation(hook, false, true) }, enabled = canEdit) { Text("移除信任并停用") }
                            }
                            if (managed) Text("由策略管理或状态未知，不能在此修改", style = MaterialTheme.typography.bodySmall)

                        }
                    }
                }
                item { HorizontalDivider(); Text("最近 Hook 运行", style = MaterialTheme.typography.titleMedium) }
                if (state.hookRuns.isEmpty()) item { Text("尚未收到 Hook 运行通知。") }
                items(state.hookRuns.reversed(), key = { "run:${it.id}" }) { run ->
                    val status = when (run.status) { "running" -> "运行中"; "completed" -> "已完成"; "failed" -> "失败"; "blocked" -> "已阻止"; "stopped" -> "已停止"; else -> run.status }
                    ExpandableCard("${hookEventTitle(run.data.string("eventName"))} · $status", "会话：${run.data.string("threadId")}\n${run.text}", Icons.Outlined.Bolt)
                }
            }
        }
    }
    state.rulesEditor?.let { draft -> OriginalHookEditor(draft, state.busy, allowChanges && state.connected, state.error, controller::closeRules) { controller.saveRules(draft, it) } }
    togglePlugin?.let { plugin ->
        AlertDialog(onDismissRequest = { togglePlugin = null }, title = { Text(if (plugin.enabled) "停用插件" else "启用插件") }, text = {
            Text("${plugin.displayName}\n市场：${plugin.marketplace}\n标识：${plugin.id}\n\n修改用户配置并重新读取有效状态。更高优先级策略可能覆盖此设置。")
        }, confirmButton = { TextButton(onClick = { togglePlugin = null; controller.togglePlugin(plugin) }, enabled = state.connected && !state.busy && allowChanges && state.configSnapshot != null) { Text("确认修改") } }, dismissButton = { TextButton(onClick = { togglePlugin = null }) { Text("取消") } })
    }
    hookConfirm?.let { (hook, trust, revoke) ->
        AlertDialog(onDismissRequest = { hookConfirm = null }, title = { Text(if (revoke) "移除用户信任并停用" else if (trust) "信任此版本的规则" else "修改规则启用状态") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hookEventTitle(hook.string("eventName")))
                Text("来源：${hook.string("sourcePath")}\n标识：${hook.string("key")}\n处理器：${hook.string("handlerType")}")
                Text(hook.string("command").ifBlank { "此处理器的完整内容需在来源文件中核查，请确认了解其行为后再信任。" })
                if (trust || revoke) Text(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), hook), style = MaterialTheme.typography.bodySmall)
                Text("内容哈希：${hook.string("currentHash")}", style = MaterialTheme.typography.bodySmall)
                Text(if (revoke) "移除当前用户配置记录的两类信任哈希，并同时停用规则。其他配置来源可能仍保留信任或覆盖此设置；提交后请核查刷新后的实际状态。" else if (trust) "信任后，这一版本的规则可参与自动执行，可能运行命令或调用服务。请核查来源与行为。此操作只记录信任，不自动启用规则；内容变化后需要再次确认。" else "修改启用状态不会授予信任。运行仍受原版信任检查与配置策略限制。")
            }
        }, confirmButton = { TextButton(onClick = { hookConfirm = null; controller.changeHook(hook, trust, revoke) }, enabled = state.connected && !state.busy && allowChanges && state.configSnapshot != null) { Text(if (revoke) "移除并停用" else if (trust) "信任此哈希" else "确认修改") } }, dismissButton = { TextButton(onClick = { hookConfirm = null }) { Text("取消") } })
    }
    confirm?.let { (plugin, install) ->
        val detail = state.detail?.takeIf { state.detailKey == plugin.key }
        val summary = detail?.get("summary") as? JsonObject ?: plugin.summary
        val ui = summary["interface"] as? JsonObject
        AlertDialog(onDismissRequest = { confirm = null; controller.closeDetail() }, title = { Text(if (install) "安装 ${plugin.displayName}" else "卸载 ${plugin.displayName}") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("市场：${plugin.marketplace}\n标识：${plugin.id}")
                Text(if (install) "插件可能包含命令、技能、MCP 服务或 Hooks。安装成功后，部分服务还需单独授权；手机缺少的运行依赖会影响使用。" else "卸载会修改原版插件安装状态，已有任务记录和项目文件保留。管理策略可能拒绝此操作。")
                if (plugin.source.string("type") == "npm") Text("此来源依赖 npm；当前应用没有内置完整 Node/npm 环境。")
                Text(detail?.string("description").orEmpty().ifBlank { plugin.description })
                if (install && detail == null) Text(if (state.busy) "正在读取插件详情，请稍候…" else "插件详情未加载，请取消后重试。", color = MaterialTheme.colorScheme.primary)
                detail?.let { metadata ->
                    listOf("skills" to "技能", "hooks" to "Hooks", "mcpServers" to "MCP 服务", "apps" to "连接服务", "scheduledTasks" to "计划任务声明").forEach { (key, label) ->
                        val entries = (metadata[key] as? JsonArray).orEmpty()
                        if (entries.isNotEmpty()) Text("$label：${entries.size} 项")
                    }
                }
                val capabilities = (ui?.get("capabilities") as? JsonArray).orEmpty().joinToString("、") { it.jsonPrimitive.content }
                if (capabilities.isNotBlank()) Text("声明能力：$capabilities")
                listOf("privacyPolicyUrl" to "隐私政策", "termsOfServiceUrl" to "服务条款").forEach { (key, label) -> ui?.string(key)?.takeIf { it.isNotBlank() }?.let { url -> TextButton(onClick = { openOriginalLogin(context, url) }) { Text(label) } } }
            }
        }, confirmButton = { TextButton(onClick = { confirm = null; controller.closeDetail(); if (install) controller.install(plugin) else controller.uninstall(plugin) }, enabled = !state.busy && state.connected && allowChanges && (!install || detail != null)) { Text(if (install) "确认安装" else "确认卸载") } }, dismissButton = { TextButton(onClick = { confirm = null; controller.closeDetail() }) { Text("取消") } })
    }
    if (state.detail != null && confirm == null && state.skillContents == null) AlertDialog(onDismissRequest = controller::closeDetail, title = { Text("插件详情") }, text = {
        val detail = state.detail!!
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(detail.string("description", "暂无说明"))
            listOf("skills" to "技能", "hooks" to "Hooks", "mcpServers" to "MCP 服务", "apps" to "连接服务", "scheduledTasks" to "插件声明的计划任务").forEach { (key, title) ->
                val entries = (detail[key] as? JsonArray).orEmpty()
                if (entries.isNotEmpty()) { Text(title, style = MaterialTheme.typography.titleSmall); entries.forEach { value ->
                    val label = if (value is JsonObject) value.string("name", value.string("id", value.string("eventName", "详情见插件说明"))) else value.jsonPrimitive.content
                    Text(label)
                    if (key == "skills") {
                        val plugin = state.plugins.singleOrNull { it.key == state.detailKey }
                        if (plugin != null && plugin.marketplacePath == null && plugin.summary.string("remotePluginId").isNotBlank()) TextButton(onClick = { controller.readPluginSkill(label) }, enabled = !state.busy) { Text("阅读远程技能说明") }
                    }
                } }
            }
            Text("此页展示插件声明。入门指令、服务授权与计划任务的实际启用状态还需分别核查。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = controller::closeDetail) { Text("关闭") } })
    if (state.appsNeedingAuth.isNotEmpty()) AlertDialog(onDismissRequest = controller::dismissAuth, title = { Text("这些服务还需要授权") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("插件安装请求已完成。请分别完成服务授权，授权失败时插件的相关能力可能无法使用。")
            state.appsNeedingAuth.forEach { app -> Text(app.string("name")); app.string("installUrl").takeIf { it.isNotBlank() }?.let { url -> TextButton(onClick = { openOriginalLogin(context, url) }) { Text("打开服务授权") } } }
        }
    }, confirmButton = { TextButton(onClick = controller::dismissAuth) { Text("稍后处理") } })
}

fun hookEventTitle(event: String) = when (event) {
    "preModelRequest" -> "模型请求前"; "postModelRequest" -> "模型请求后"
    "preToolUse" -> "工具调用前"; "postToolUse" -> "工具调用后"; "permissionRequest" -> "申请权限时"
    "preCompact" -> "压缩上下文前"; "postCompact" -> "压缩上下文后"
    "sessionStart" -> "会话开始"; "sessionEnd" -> "会话结束"; "userPromptSubmit" -> "提交任务时"
    "subagentStart" -> "子代理开始"; "subagentStop" -> "子代理停止"; "stop" -> "任务停止"; "interrupt" -> "任务中断"
    else -> "未知事件：$event"
}
