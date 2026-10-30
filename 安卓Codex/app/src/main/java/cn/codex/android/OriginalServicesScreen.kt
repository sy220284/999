package cn.codex.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalServicesScreen(controller: OriginalServicesController, cwd: String, allowChanges: Boolean, onAbort: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState(); val status by controller.operations.state.collectAsState()
    val context = LocalContext.current
    val scope=rememberCoroutineScope()
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source=exportPath;exportPath=null
        if(uri!=null && source!=null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { File(source).inputStream().use { input -> requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) } } } }
                .onSuccess { exportMessage="报告已导出" }.onFailure { exportMessage="报告导出失败：${it.message.orEmpty()}" }
        }
    }
    var tab by remember { mutableStateOf("网关授权") }; var credit by remember { mutableStateOf("") }; var mailType by remember { mutableStateOf("credits") }
    var classification by remember { mutableStateOf("bug") }; var reason by remember { mutableStateOf("") }; var logs by remember { mutableStateOf(false) }; var includeThread by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf("") }; var tags by remember { mutableStateOf("{}") }; var path by remember { mutableStateOf("$cwd/反馈报告.zip") }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val edit = allowChanges && !status.busy && status.connected
    LaunchedEffect(Unit) { controller.gatewayStatus() }
    OriginalOpsPage("账号授权、额度与反馈", status, onBack, onAbort) {
        item { OriginalOpsTabs(listOf("网关授权", "额度与邮件", "反馈"), tab) { if (!status.busy) tab = it } }
        if (tab == "网关授权") item {
            val gateway = state.gateway
            OriginalCard("模型网关授权") {
                Text("提供方：${gateway?.string("providerName").orEmpty().ifBlank { gateway?.string("providerId").orEmpty() }}")
                Text("需要授权：${originalValue(gateway?.get("required"))}")
                Text("状态：${when (gateway?.string("status")) { "notReady" -> "尚未就绪"; "started" -> "等待完成授权"; "succeeded" -> "授权成功"; "failed" -> "授权失败"; else -> "未开始" }}")
                gateway?.string("error")?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row { TextButton(onClick = controller::gatewayStatus, enabled = !status.busy) { Text("刷新状态") }; TextButton(onClick = { confirmation = "向当前模型网关申请登录授权？授权地址由该提供方返回。" to controller::loginGateway }, enabled = edit) { Text("申请授权") }; TextButton(onClick = controller::cancelGateway, enabled = edit) { Text("取消授权") } }
                state.gatewayUrl?.let { url -> Text(url, style = MaterialTheme.typography.bodySmall); Button(onClick = { openOriginalLogin(context, url) }) { Text("打开授权页面") } }
            }
        }
        if (tab == "额度与邮件") item {
            OriginalCard("使用额度重置券") {
                Text("会使用当前账户的重置资格。相同请求标识用于核查或重试，未知结果时先检查账户额度。")
                Text("请求标识：${state.creditRequestKey}", style = MaterialTheme.typography.bodySmall)
                state.creditRecoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OriginalInput(state.creditRequestId ?: credit, { credit = it }, "指定券标识（可选；留空由服务选择）", edit && state.creditRequestId == null)
                Button(onClick = { confirmation = "为当前账号使用额度重置券？\n券：${(state.creditRequestId ?: credit).ifBlank { "由服务选择" }}\n请求标识：${state.creditRequestKey}" to { controller.consumeCredit(credit) } }, enabled = edit && state.creditRecoveryError == null) { Text(if (state.creditRequestId == null) "使用重置券" else "以同一请求核查或重试") }
                state.creditOutcome?.let { outcome -> Text(when (outcome) { "reset" -> "额度已重置"; "nothingToReset" -> "当前无需重置"; "noCredit" -> "没有可用重置券"; "alreadyRedeemed" -> "此请求已兑换过"; else -> "服务返回：$outcome" }) }
                TextButton(onClick = { confirmation = "确认已核查上次账户结果，再创建全新的重置券请求？新请求会使用新的幂等标识。" to controller::newCreditRequest }, enabled = edit) { Text("新建另一次使用请求") }
            }
            OriginalCard("发送额度提醒邮件") {
                Text("由服务向当前账号关联邮箱发送提醒。")
                Row { FilterChip(mailType == "credits", { mailType = "credits" }, label = { Text("积分额度") }); FilterChip(mailType == "usage_limit", { mailType = "usage_limit" }, label = { Text("使用上限") }) }
                Button(onClick = { confirmation = "向当前账号关联邮箱发送${if (mailType == "credits") "积分额度" else "使用上限"}提醒邮件？" to { controller.sendCreditEmail(mailType) } }, enabled = edit) { Text("确认发送邮件") }
                state.mailStatus?.let { Text(when (it) { "sent" -> "邮件已发送"; "cooldown_active" -> "仍在发送冷却期，本次未发送"; else -> "服务状态：$it" }) }
            }
        }
        if (tab == "反馈") item {
            OriginalInput(classification, { classification = it }, "反馈分类", edit)
            OriginalInput(reason, { reason = it }, "问题描述", edit, 4)
            Row { Checkbox(includeThread, { includeThread = it }, enabled = edit); Text("关联当前会话") }
            Row { Checkbox(logs, { logs = it }, enabled = edit); Text("包含日志、会话记录和诊断信息（默认关闭）") }
            OriginalInput(files, { files = it }, "额外日志文件路径，每行一个", edit && logs, 2)
            OriginalInput(tags, { tags = it }, "标签（字符串值 JSON 对象）", edit, 2)
            OriginalInput(path, { path = it }, "本机保存路径（可能覆盖同名文件）", edit)
            Row {
                TextButton(onClick = { confirmation = "保存反馈到：$path\n关联会话：$includeThread\n问题描述：$reason\n标签：$tags\n包含日志、会话记录和诊断：$logs\n额外文件：${if (logs) files.ifBlank { "无" } else "无"}" to { controller.feedback(false, path, classification, reason, includeThread, logs, if (logs) files else "", tags) } }, enabled = edit && path.isNotBlank() && classification.isNotBlank()) { Text("保存到手机") }
                Button(onClick = { confirmation = "上传反馈给当前 Codex 服务？\n分类：$classification\n关联会话：$includeThread\n问题描述：$reason\n标签：$tags\n包含日志、会话记录和诊断：$logs\n额外文件：${if (logs) files.ifBlank { "无" } else "无"}\n\n反馈会关联当前服务账号。上传后内容将离开手机；包含日志时还可能附带相关会话记录和诊断信息，请先核查。" to { controller.feedback(true, path, classification, reason, includeThread, logs, if (logs) files else "", tags) } }, enabled = edit && classification.isNotBlank()) { Text("确认上传反馈") }
            }
            exportMessage?.let { Text(it) }
            state.feedback?.let { result -> OriginalCard("反馈结果") {
                result.string("savedPath").takeIf { it.isNotBlank() }?.let { saved -> TextButton(onClick = { exportPath=saved;exportLauncher.launch(File(saved).name) }) { Text("导出报告到系统文件") } }
 Text("报告会话：${result.string("threadId")}"); result.string("savedPath").takeIf { it.isNotBlank() }?.let { Text("保存路径：$it") }; Text("报告指纹：${originalValue(result["promptHash"])}") } }
        }
    }
    OriginalConfirmation(confirmation) { confirmation = null }
}
