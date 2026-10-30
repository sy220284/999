package cn.codex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*
import java.math.BigDecimal

@Composable fun OriginalSettingsScreen(controller: OriginalSettingsController, allowChanges: Boolean, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var confirm by remember { mutableStateOf<Pair<JsonObject, Boolean>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("账号、策略与实验", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OriginalSettingsSection.entries.forEach { section -> FilterChip(state.section == section, { controller.refresh(section) }, enabled = state.connected && !state.busy, label = { Text(section.title) }) }
        } }
        item {
            Text(state.notice, style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { controller.refresh() }, enabled = state.connected && !state.busy) { Text("重新读取") }
        }
        when (state.section) {
            OriginalSettingsSection.AccountUsage, OriginalSettingsSection.ThreadUsage -> {
                item { Text(if (state.usageThread == null) "账号统计由服务返回，可能有延迟。未提供的数值不按零显示。" else "会话 ${state.usageThread} 的预估用量，实际结算以服务为准。", style = MaterialTheme.typography.bodySmall) }
                state.usage?.let { usage ->
                    val summary = usage["summary"] as? JsonObject
                    item { SettingsCard("用量概览") {
                        listOf("lifetimeTokens" to "累计 Token", "peakDailyTokens" to "单日最高 Token", "longestRunningTurnSec" to "最长回合秒数", "currentStreakDays" to "当前连续天数", "longestStreakDays" to "最长连续天数").forEach { (key, name) -> Detail(name, scalar(summary?.get(key))) }
                    } }
                    val thread = usage["threadUsage"] as? JsonObject
                    if (thread != null) {
                        item { SettingsCard("本会话预估") { Detail("会话", thread.string("threadId")); Detail("预估积分", micros(thread["estimatedUsageCreditsMicros"])); Detail("预估美元", micros(thread["estimatedUsageUsdMicros"])) } }
                        items((thread["groups"] as? JsonArray).orEmpty()) { group ->
                            val data = group.jsonObject
                            SettingsCard("模型 ${scalar(data["model"])}") {
                                Detail("思考强度", scalar(data["reasoningEffort"])); Detail("速度", scalar(data["speed"])); Detail("预估积分", micros(data["estimatedUsageCreditsMicros"]))
                                listOf("netNewInputTokens" to "新增输入", "cachedInputTokens" to "缓存输入", "inputTokens" to "输入", "outputTokens" to "输出", "totalTokens" to "总 Token").forEach { (key, title) -> Detail(title, scalar(data[key])) }
                            }
                        }
                    }
                    val days = (usage["dailyUsageBuckets"] as? JsonArray).orEmpty()
                    if (days.isNotEmpty()) item { Text("每日 Token", style = MaterialTheme.typography.titleMedium) }
                    items(days) { entry -> val day = entry.jsonObject; Detail(day.string("startDate"), scalar(day["tokens"])) }
                }
            }
            OriginalSettingsSection.Messages -> {
                val messages = (state.messages?.get("messages") as? JsonArray).orEmpty()
                if (state.messages != null && messages.isEmpty()) item { Text(if (state.messages?.get("featureEnabled")?.jsonPrimitive?.booleanOrNull == true) "当前没有工作区消息" else "此账号的工作区消息服务尚未开放") }
                items(messages) { entry -> val message = entry.jsonObject; SettingsCard(when (message.string("messageType")) { "headline" -> "工作区通知"; "announcement" -> "工作区公告"; else -> "工作区消息" }) { Text(message.string("messageBody")); Text("消息编号：${message.string("messageId")}", style = MaterialTheme.typography.labelSmall) } }
            }
            OriginalSettingsSection.Provider -> state.provider?.let { value -> item { SettingsCard("模型提供方声明") {
                Detail("图片生成", booleanText(value["imageGeneration"])); Detail("网页搜索", booleanText(value["webSearch"]))
                Text("服务声明支持不代表当前账号已授权，实际调用仍可能受模型与组织策略限制。", style = MaterialTheme.typography.bodySmall)
            } } }
            OriginalSettingsSection.Profiles -> {
                item { Text("这里只展示核心返回的权限方案与管理允许状态。允许选择不代表安卓已提供桌面沙箱；本页不会扩大权限。", style = MaterialTheme.typography.bodySmall) }
                items(state.profiles, key = { it.string("id") }) { profile -> SettingsCard(profile.string("id")) { Text(profile.string("description", "未提供说明")); Detail("管理策略", if (profile["allowed"]?.jsonPrimitive?.booleanOrNull == true) "允许此方案" else "不允许此方案") } }
                if (!state.busy && state.profiles.isEmpty() && state.error == null) item { Text("没有可展示的权限方案") }
            }
            OriginalSettingsSection.Requirements -> state.requirements?.let { response ->
                val requirements = response["requirements"] as? JsonObject
                item { Text(if (requirements == null) "未配置管理要求" else "以下内容由核心返回，作为只读管理要求展示。", style = MaterialTheme.typography.bodyMedium) }
                if (requirements != null) items(requirements.entries.filter { it.value != JsonNull }, key = { it.key }) { (key, value) ->
                    SettingsCard(requirementNames[key] ?: "其他管理项 · $key") { Text(policyValue(value), style = MaterialTheme.typography.bodyMedium) }
                }
                item { Detail("快速与超快速独立管控", booleanText(response["supportsIndependentSpeedModes"])) }
            }
            OriginalSettingsSection.Features -> {
                item { Text("下列状态按读取时的会话计算。可修改的开关只影响当前核心进程，重启后不保证保留；其他项需通过受支持的配置管理。开启功能不表示安卓客户端已实现其界面。", style = MaterialTheme.typography.bodySmall) }
                item { Text("读取范围：${state.featureThread ?: "核心全局配置"}", style = MaterialTheme.typography.labelSmall) }
                items(state.features, key = { it.string("name") }) { feature ->
                    val enabled = feature["enabled"]?.jsonPrimitive?.booleanOrNull == true
                    SettingsCard(feature.string("displayName").ifBlank { feature.string("name") }) {
                        Text(feature.string("name"), style = MaterialTheme.typography.labelSmall)
                        feature.string("description").takeIf { it.isNotBlank() }?.let { Text(it) }
                        feature.string("announcement").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Detail("阶段", when (feature.string("stage")) { "beta" -> "测试版"; "stable" -> "稳定"; "underDevelopment" -> "开发中"; "deprecated" -> "已弃用"; "removed" -> "已移除"; else -> feature.string("stage") })
                        Detail("当前有效状态", if (enabled) "已开启" else "已关闭"); Detail("默认状态", booleanText(feature["defaultEnabled"]))
                        if (OriginalSettingsController.canToggle(feature)) OutlinedButton(onClick = { confirm = feature to !enabled }, enabled = allowChanges && !state.busy) { Text(if (enabled) "关闭此进程的功能" else "开启此进程的功能") }
                        else Text(if (feature.string("name") == "windows_sandbox_service") "Windows 专用，安卓不提供此服务" else "此接口不支持运行时修改该项", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.busy && state.features.isEmpty() && state.error == null) item { Text("没有可展示的实验功能") }
            }
        }
        if (state.nextCursor != null) item { OutlinedButton(onClick = controller::more, enabled = !state.busy) { Text("加载更多") } }
    }
    confirm?.let { (feature, enabled) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (enabled) "开启运行时功能" else "关闭运行时功能") }, text = {
        Text("功能：${feature.string("name")}\n\n会影响当前核心进程及其加载的配置，部分功能可能启动后台处理。此操作不保存为永久用户设置，仍受管理策略约束。")
    }, confirmButton = { TextButton(onClick = { confirm = null; controller.toggle(feature, enabled) }, enabled = allowChanges && !state.busy) { Text("确认修改") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable private fun Detail(title: String, value: String) { Column { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) } }
private fun scalar(value: JsonElement?) = (value as? JsonPrimitive)?.contentOrNull ?: "服务未提供"
private fun booleanText(value: JsonElement?) = when ((value as? JsonPrimitive)?.booleanOrNull) { true -> "是"; false -> "否"; null -> "服务未提供" }
private fun micros(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull?.let { runCatching { BigDecimal(it).movePointLeft(6).stripTrailingZeros().toPlainString() }.getOrNull() } ?: "服务未提供"
private fun policyValue(value: JsonElement): String = when (value) { is JsonPrimitive -> if (value.booleanOrNull != null) booleanText(value) else scalar(value); is JsonArray -> value.joinToString("\n") { policyValue(it) }.ifBlank { "无允许项" }; is JsonObject -> value.entries.joinToString("\n") { "${it.key}：${policyValue(it.value)}" }; else -> "未指定" }
private val requirementNames = mapOf(
    "modelProvider" to "指定模型提供方", "modelProviders" to "模型服务定义", "allowedLoginMethods" to "允许的登录方式",
    "cliAuthCredentialsStore" to "凭证保存要求", "chatgptBaseUrl" to "ChatGPT 服务地址", "additionalDeveloperInstructions" to "附加开发指令",
    "allowedApprovalPolicies" to "允许的审批策略", "allowedSandboxModes" to "允许的沙箱类型", "allowedWindowsSandboxImplementations" to "Windows 沙箱要求",
    "allowedPermissionProfiles" to "允许的权限方案", "defaultPermissions" to "默认权限方案", "allowedWebSearchModes" to "网页搜索方式",
    "allowManagedHooksOnly" to "仅使用管理的自动规则", "allowBrowserAndComputerUse" to "允许浏览器与电脑控制", "allowAppshots" to "允许应用快照",
    "allowRemoteControl" to "允许远程控制", "computerUse" to "电脑控制要求", "browserUse" to "浏览器控制要求", "inAppBrowser" to "应用内浏览器要求",
    "featureRequirements" to "实验功能要求", "enforceResidency" to "数据驻留要求", "autoReview" to "自动审查要求", "models" to "模型限制",
    "sqliteHome" to "数据库位置", "logDir" to "日志位置", "modelCatalogJson" to "模型目录位置", "checkForUpdateOnStartup" to "启动更新检查",
    "allowLoginShell" to "允许登录式 shell", "feedback" to "反馈要求")
