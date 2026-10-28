package cn.codex.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

/** 同步提示就地展开，不切走对话、不抢焦点；状态同时用中文说明。 */
@Composable fun OriginalThreadSyncPanel(host: OriginalCoreHost, onAttachments: () -> Unit, onHistory: () -> Unit) {
    val state by host.state.collectAsState()
    val id = state.threadId
    val change = state.threadUpdates.threads[id]
    val duration = if (LocalMotionEnabled.current) 180 else 0
    AnimatedVisibility(visible = change != null || state.threadUpdates.projectNotice.isNotBlank(), enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会话同步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val headline = when (change?.availability) {
                    "deleted" -> "会话已删除，当前内容仅供查看"
                    "closed" -> "会话已卸载，重新打开后可继续"
                    "archived" -> "会话已归档，请先在归档列表恢复"
                    else -> change?.notice.orEmpty()
                }
                if (headline.isNotBlank()) Text(headline, style = MaterialTheme.typography.bodyMedium)
                if (change != null && change.notice.isNotBlank() && change.notice != headline && change.availability == "available") Text(change.notice, style = MaterialTheme.typography.bodySmall)
                if (state.threadUpdates.projectNotice.isNotBlank()) Text(state.threadUpdates.projectNotice, style = MaterialTheme.typography.bodySmall)
                change?.let { row ->
                    val name = (row.patch["name"] as? JsonPrimitive)?.contentOrNull
                    if (!name.isNullOrBlank() && (row.patchVersions["name"] ?: 0) > state.threadListVersion) Text(name, style = MaterialTheme.typography.titleSmall)
                    if (row.readState != null) Text(OriginalThreadUpdates.readLabel(row), style = MaterialTheme.typography.bodySmall)
                    if ((row.patchVersions["projectId"] ?: 0) > state.threadListVersion) Text("所属项目：${(row.patch["projectId"] as? JsonPrimitive)?.contentOrNull ?: "未分配"}", style = MaterialTheme.typography.bodySmall)
                    row.settings?.let { settings ->
                        Text("模型：${settings.string("model")} · ${settings.string("modelProvider")}\n工作目录：${settings.string("cwd")}", style = MaterialTheme.typography.bodySmall)
                        ExpandableCard("权限与其他设置", settings.toString(), androidx.compose.material.icons.Icons.Outlined.Info)
                    }
                    if (row.environmentId != null) Text("执行环境：${row.environmentId} · ${if (row.environmentConnected == true) "已连接" else "已断开"}", style = MaterialTheme.typography.bodySmall)
                    if (row.queueVersion > 0) Text("队列已更新，当前回合状态仍以核心回合通知为准。", style = MaterialTheme.typography.bodySmall)
                    row.prediction?.let { prediction ->
                        val result = prediction["result"] as? JsonObject
                        val text = result?.string("text").orEmpty()
                        Text(if (result?.string("type") == "failed") "下一步建议暂时不可用" else text.ifBlank { "核心没有提供下一步建议" }, style = MaterialTheme.typography.bodySmall)
                        Text("来源回合：${prediction.string("sourceTurnId")}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (row.attachmentVersion > 0) TextButton(onClick = onAttachments, enabled = !state.loading) { Text("查看并刷新资料") }
                    if (row.historyVersion > 0) TextButton(onClick = onHistory, enabled = !state.loading && !state.working && state.turnId == null) { Text("重新载入历史") }
                    if (row.availability == "closed" && id != null) TextButton(onClick = { host.openThread(id) }, enabled = !state.loading && !state.working) { Text("重新打开会话") }
                }
            }
        }
    }
}
