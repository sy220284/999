package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

data class OriginalThreadUpdate(
    val version: Long = 0, val patch: JsonObject = buildJsonObject {},
    val patchVersions: Map<String, Long> = emptyMap(), val archivedVersion: Long = 0, val availabilityVersion: Long = 0,
    val availability: String? = null, val archived: Boolean? = null,
    val readState: JsonObject? = null, val settings: JsonObject? = null,
    val environmentId: String? = null, val environmentConnected: Boolean? = null,
    val attachmentVersion: Long = 0, val historyVersion: Long = 0, val queueVersion: Long = 0,
    val prediction: JsonObject? = null, val notice: String = ""
)
data class OriginalThreadUpdatesState(
    val version: Long = 0, val threads: Map<String, OriginalThreadUpdate> = emptyMap(),
    val started: JsonObject? = null, val projectNotice: String = ""
)
/** 通知只更新所属会话；无正文的变化保留刷新标记，绝不伪造附件、历史或队列内容。 */
object OriginalThreadUpdates {
    fun reduce(old: OriginalThreadUpdatesState, method: String, params: JsonObject): OriginalThreadUpdatesState {
        val version = old.version + 1
        if (method == "project/changed") {
            val change = when (val raw = params.string("changeType")) { "created" -> "已创建"; "updated" -> "已更新"; "deleted" -> "已删除"; else -> raw }
            return old.copy(version = version, projectNotice = "项目 ${params.string("projectId")} $change，请核对会话归属")
        }
        val thread = if (method == "thread/started") params["thread"] as? JsonObject else null
        val id = thread?.string("id") ?: params.string("threadId")
        if (id.isBlank()) return old
        val previous = old.threads[id] ?: OriginalThreadUpdate()
        val next = when (method) {
            "thread/started" -> previous.copy(availability = "available", archived = false, notice = "会话已加载")
            "thread/name/updated" -> previous.copy(patch = JsonObject(previous.patch + ("name" to (params["threadName"] ?: JsonNull))))
            "thread/readState/changed" -> previous.copy(readState = params["readState"] as? JsonObject)
            "thread/archived" -> previous.copy(archived = true, availability = "archived", notice = "会话已归档，可在归档列表恢复")
            "thread/unarchived" -> previous.copy(archived = false, availability = "available", notice = "会话已恢复，请刷新列表")
            "thread/deleted" -> previous.copy(availability = "deleted", notice = "会话已删除，当前内容仅供查看")
            "thread/closed" -> previous.copy(availability = "closed", notice = "会话已从核心卸载，重新打开后可继续")
            "thread/reverted" -> previous.copy(historyVersion = version, notice = "聊天历史已回退，请重新载入；项目文件没有撤销")
            "thread/attachment/updated" -> previous.copy(attachmentVersion = version, notice = if (params.string("operation") == "deleted") "资料记录已删除，请刷新资料" else "资料记录已变化，请刷新资料")
            "thread/prediction/updated" -> {
                val result = params["result"] as? JsonObject ?: return old
                val text = result.string("text")
                val displayResult = if (text.length > 8192) JsonObject(result + ("text" to JsonPrimitive(text.take(8192) + "…（建议过长，已截取）"))) else result
                previous.copy(prediction = buildJsonObject { put("sourceTurnId", params.string("sourceTurnId")); put("result", displayResult) }, notice = "下一步建议已更新")
            }
            "thread/queue/changed" -> previous.copy(queueVersion = version, notice = "待处理队列已变化，核心会按实际队列调度")
            "thread/project/updated" -> previous.copy(patch = JsonObject(previous.patch + ("projectId" to (params["projectId"] ?: JsonNull))), notice = "会话的项目归属已更新")
            "thread/environment/connected" -> previous.copy(environmentId = params.string("environmentId"), environmentConnected = true, notice = "执行环境已连接")
            "thread/environment/disconnected" -> if (previous.environmentId != null && previous.environmentId != params.string("environmentId")) return old else previous.copy(environmentId = params.string("environmentId"), environmentConnected = false, notice = "执行环境已断开")
            "thread/settings/updated" -> {
                val settings = params["threadSettings"] as? JsonObject ?: return old
                previous.copy(settings = settings, patch = JsonObject(previous.patch + mapOf("model" to (settings["model"] ?: JsonNull), "modelProvider" to (settings["modelProvider"] ?: JsonNull)) + (settings["cwd"]?.let{mapOf("cwd" to it)} ?: emptyMap())), notice = "会话设置已更新，以下显示核心当前配置")
            }
            else -> return old
        }
        val changedFields = when (method) {
            "thread/name/updated" -> listOf("name")
            "thread/project/updated" -> listOf("projectId")
            "thread/settings/updated" -> listOf("model", "modelProvider", "cwd")
            else -> emptyList()
        }
        val stamped = next.copy(version = version,
            patchVersions = previous.patchVersions + changedFields.associateWith { version },
            archivedVersion = if (method in setOf("thread/started", "thread/archived", "thread/unarchived")) version else previous.archivedVersion,
            availabilityVersion = if (method in setOf("thread/started", "thread/archived", "thread/unarchived", "thread/closed", "thread/deleted")) version else previous.availabilityVersion)
        val threads = LinkedHashMap(old.threads).apply { remove(id); put(id, stamped) }.entries.toList().takeLast(256).associate { it.toPair() }
        return old.copy(version = version, threads = threads, started = if (thread != null) thread else old.started)
    }
    fun overlay(rows: List<JsonObject>, updates: OriginalThreadUpdatesState, archived: Boolean, since: Long): List<JsonObject> = rows.mapNotNull { row ->
        val change = updates.threads[row.string("id")] ?: return@mapNotNull row
        if (change.availability == "deleted" || change.archivedVersion > since && change.archived != null && change.archived != archived) null
        else JsonObject(row + change.patch.filterKeys { (change.patchVersions[it] ?: 0) > since })
    }
    fun canUse(change: OriginalThreadUpdate?) = change?.availability !in setOf("closed", "deleted", "archived") && (change?.historyVersion ?: 0) == 0L
    fun readLabel(change: OriginalThreadUpdate?): String = when {
        change?.readState == null -> "状态未同步"
        change.readState["firstUnread"] == JsonNull -> "已读"
        change.readState.containsKey("firstUnread") -> "有未读内容"
        else -> "状态未同步"
    }
    fun acknowledgeHistory(old: OriginalThreadUpdatesState, id: String, through: Long): OriginalThreadUpdatesState {
        val change = old.threads[id] ?: return old
        val reloaded = change.historyVersion > 0 && change.historyVersion <= through || change.availabilityVersion <= through && change.availability == "closed"
        return old.copy(threads = old.threads + (id to change.copy(
            notice = if (reloaded) "会话内容已重新载入" else change.notice,
            historyVersion = if (change.historyVersion <= through) 0 else change.historyVersion,
            availability = if (change.availabilityVersion <= through && change.availability == "closed") "available" else change.availability
        )))
    }
    fun acknowledgeAttachments(old: OriginalThreadUpdatesState, id: String, through: Long): OriginalThreadUpdatesState {
        val change = old.threads[id] ?: return old
        return old.copy(threads = old.threads + (id to change.copy(attachmentVersion = if (change.attachmentVersion <= through) 0 else change.attachmentVersion)))
    }
}
