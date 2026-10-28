package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.util.Base64

data class OriginalEventNotice(val key: String, val title: String, val body: String, val threadId: String = "", val turnId: String = "")
data class OriginalProcessEvent(val stdout: String = "", val stderr: String = "", val exitCode: Int? = null, val capped: Boolean = false)
data class OriginalSearchEvent(val data: JsonObject = buildJsonObject {}, val completed: Boolean = false)
data class OriginalRealtimeEvent(val active: Boolean = false, val sessionId: String = "", val status: String = "尚未开始", val items: Map<String,String> = emptyMap(), val partials: Map<String,String> = emptyMap(), val transcripts: List<Pair<String,String>> = emptyList(), val audioBytes: Long = 0, val audioFormat: String = "", val sdpReceived: Boolean = false, val canonicalText: Boolean = false)
data class OriginalEventState(val notices: List<OriginalEventNotice> = emptyList(), val processes: Map<String,OriginalProcessEvent> = emptyMap(), val searches: Map<String,OriginalSearchEvent> = emptyMap(), val realtime: Map<String,OriginalRealtimeEvent> = emptyMap(), val rateLimits: JsonObject = buildJsonObject {}, val apps: List<JsonObject> = emptyList(), val remote: JsonObject? = null)

/** 连接所有；无RPC、无音频播放和磁盘日志，接收循环不等待外部工作。 */
class OriginalRemainingEvents {
    var state = OriginalEventState()
        private set
    private var sequence = 0L
    private val decoders = linkedMapOf<String,CommandUtf8Stream>()
    fun reset() { state = OriginalEventState();decoders.clear();sequence = 0 }
    fun notice(key: String, title: String, body: String, thread: String = "", turn: String = "") {
        val text = if (body.length > 16_384) body.take(16_384) + "\n（内容过长，仅显示前段）" else body
        state = state.copy(notices = (state.notices.filterNot { it.key == key } + OriginalEventNotice(key,title,text,thread,turn)).takeLast(64))
    }
    fun dismiss(key: String) { state = state.copy(notices = state.notices.filterNot { it.key == key }) }
    private fun <T> bounded(map: Map<String,T>, key: String, value: T, limit: Int = 16) = LinkedHashMap(map).apply { remove(key);put(key,value) }.entries.toList().takeLast(limit).associate { it.toPair() }
    fun accept(method: String, p: JsonObject) {
        if (method.startsWith("thread/realtime/")) { realtime(method,p);return }
        when(method) {
            "process/outputDelta", "process/exited" -> {
                val id = p.string("processHandle");if(id.isBlank())return
                var value = state.processes[id] ?: OriginalProcessEvent()
                fun decode(stream: String, bytes: ByteArray, end: Boolean = false): String {
                    val key = "$id/$stream"
                    val result = decoders.getOrPut(key) { CommandUtf8Stream() }.append(bytes,end)
                    if(end) decoders.remove(key)
                    while(decoders.size > 32) decoders.remove(decoders.keys.first())
                    return result
                }
                if(method == "process/outputDelta") {
                    if(value.exitCode != null)return
                    val stream = p.string("stream");if(stream !in setOf("stdout","stderr"))return
                    val text = decode(stream,Base64.getDecoder().decode(p.string("deltaBase64")))
                    value = if(stream == "stdout")value.copy(stdout = clip(value.stdout + text))else value.copy(stderr = clip(value.stderr + text))
                    value = value.copy(capped = value.capped || (p["capReached"] as? JsonPrimitive)?.booleanOrNull == true)
                } else {
                    if(value.exitCode != null)return
                    value = value.copy(stdout = clip(value.stdout + decode("stdout",byteArrayOf(),true) + p.string("stdout")), stderr = clip(value.stderr + decode("stderr",byteArrayOf(),true) + p.string("stderr")), exitCode = (p["exitCode"] as? JsonPrimitive)?.intOrNull,
                        capped = value.capped || (p["stdoutCapReached"] as? JsonPrimitive)?.booleanOrNull == true || (p["stderrCapReached"] as? JsonPrimitive)?.booleanOrNull == true)
                }
                state = state.copy(processes = bounded(state.processes,id,value))
            }
            "account/rateLimits/updated" -> (p["rateLimits"] as? JsonObject)?.let { state = state.copy(rateLimits = mergeSparse(state.rateLimits,it)) }
            "app/list/updated" -> state = state.copy(apps = (p["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(200))
            "remoteControl/status/changed" -> state = state.copy(remote = p)
            "fuzzyFileSearch/sessionUpdated", "fuzzyFileSearch/sessionCompleted" -> {
                val id = p.string("sessionId");if(id.isBlank())return
                val old = state.searches[id] ?: OriginalSearchEvent()
                // completed是终态；乱序更新不能重新进入搜索中。
                val data = if(method.endsWith("Updated")) JsonObject(p + ("files" to JsonArray((p["files"] as? JsonArray).orEmpty().take(200)))) else old.data
                state = state.copy(searches = bounded(state.searches,id,OriginalSearchEvent(data,old.completed || method.endsWith("Completed"))))
            }
            else -> {
                val title = titles[method] ?: return
                val thread = p.string("threadId");val turn = p.string("turnId")
                val identity = p.string("reviewId").ifBlank { p.string("responseId").ifBlank { p.string("itemId").ifBlank { (p["item"] as? JsonObject)?.string("id").orEmpty().ifBlank { turn } } } }
                val body = when(method) {
                    "guardianWarning" -> p.string("message")
                    "configWarning", "deprecationNotice" -> listOf(p.string("summary"),p.string("details"),p.string("path")).filter { it.isNotBlank() }.joinToString("\n")
                    "model/rerouted" -> "${p.string("fromModel")} → ${p.string("toModel")}\n原因：${p.string("reason")}"
                    "modelProvider/authRecoveryStarted", "modelProvider/authRecoveryCompleted" -> "提供方：${p.string("provider")}\n${p.string("message")}"
                    "model/safetyBuffering/updated" -> "模型：${p.string("model")}\n${if((p["showBufferingUi"] as? JsonPrimitive)?.booleanOrNull == true) "正在等待安全缓冲" else "安全缓冲已解除"}\n用途：${p["useCases"]}\n原因：${p["reasons"]}\n可选更快模型：${(p["fasterModel"] as? JsonPrimitive)?.contentOrNull ?: "未提供"}"
                    "item/reasoning/summaryPartAdded" -> "条目 ${p.string("itemId")} 开始新的思考摘要段落 ${p["summaryIndex"]}"
                    else -> p.toString()
                }
                val key = if(method == "mcpServer/event/stream/notification") "$method/${p.string("subscriptionId")}/${++sequence}" else "$method/$thread/$identity"
                notice(key,title,body,thread,turn)
            }
        }
    }
    private fun realtime(method: String, p: JsonObject) {
        val id = p.string("threadId");if(id.isBlank())return
        var rt = state.realtime[id] ?: OriginalRealtimeEvent()
        when(method) {
            "thread/realtime/started" -> rt = OriginalRealtimeEvent(active = true,sessionId = p.string("realtimeSessionId"),status = "实时会话已连接")
            "thread/realtime/error" -> rt = rt.copy(status = "实时会话错误：${p.string("message")}")
            "thread/realtime/closed" -> rt = rt.copy(active = false,partials = emptyMap(), status = "实时会话已关闭：${p.string("reason")}")
            "thread/realtime/sdp" -> rt = rt.copy(sdpReceived = true,status = "已收到WebRTC协商；当前包尚无WebRTC媒体适配，请使用文字和本机音频输出")
            "thread/realtime/outputAudio/delta" -> {
                if(!rt.active)return
                val audio = p["audio"] as? JsonObject ?: return
                val bytes = Base64.getDecoder().decode(audio.string("data"))
                rt = rt.copy(audioBytes = rt.audioBytes + bytes.size,audioFormat = "${audio["sampleRate"]} Hz · ${audio["numChannels"]} 声道")
            }
            "thread/realtime/transcript/delta" -> {
                if(!rt.active)return
                val role = p.string("role");rt = rt.copy(partials = bounded(rt.partials,role,clip(rt.partials[role].orEmpty()+p.string("delta")),4))
            }
            "thread/realtime/transcript/done" -> {
                val role = p.string("role");rt = rt.copy(partials = rt.partials-role,transcripts = (rt.transcripts + (role to clip(p.string("text")))).takeLast(64))
            }
            "thread/realtime/item/transcript/delta" -> {
                if(!rt.active)return
                val item = p.string("itemId");if(item.isBlank())return
                rt = rt.copy(canonicalText = true,items = bounded(rt.items,item,clip(rt.items[item].orEmpty()+p.string("delta")),64))
            }
            "thread/realtime/itemAdded", "thread/realtime/item/started", "thread/realtime/item/completed" -> {
                val item = p["item"] as? JsonObject ?: return
                val session = item.string("realtimeSessionId");if(session.isNotBlank() && rt.sessionId.isNotBlank() && session != rt.sessionId)return
                val itemId = item.string("id");if(itemId.isBlank())return
                if(item.string("type") == "transcriptSegment")rt = rt.copy(canonicalText = true)
                val text = when(item.string("type")) { "transcriptSegment" -> item.string("role")+"："+item.string("text");"realtimeSessionStarted" -> "实时会话开始";"realtimeSessionClosed" -> "实时会话结束 · ${item["outcome"]}";"bemItemPromoted" -> "已并入主对话：回合 ${item.string("turnId")} / 条目 ${item.string("itemId")}";else -> item.toString() }
                // started不覆盖已积累的流式正文；completed的完整正文替换分片。
                if(!method.endsWith("/started") || itemId !in rt.items) rt = rt.copy(items = bounded(rt.items,itemId,clip(text),64))
            }
            else -> return
        }
        state = state.copy(realtime = bounded(state.realtime,id,rt))
    }
    companion object {
        private fun clip(s: String) = if(s.length > 32_768) "（仅保留最近内容）\n"+s.takeLast(32_700) else s
        fun mergeSparse(old: JsonObject, update: JsonObject): JsonObject = JsonObject(old + update.filterValues { it != JsonNull }.mapValues { (key,value) -> if(value is JsonObject && old[key] is JsonObject)mergeSparse(old.getValue(key).jsonObject,value)else value })
        val titles = mapOf(
            "item/autoApprovalReview/started" to "守护审查开始", "autoApprovalReview/strictReviewRequired" to "需要严格审查",
            "rawResponseItem/completed" to "原始响应条目", "rawResponse/completed" to "单次响应与实际用量",
            "mcpServer/event/stream/notification" to "MCP订阅事件", "item/reasoning/summaryPartAdded" to "思考摘要新段落",
            "model/rerouted" to "模型已切换", "model/verification" to "模型验证信息",
            "modelProvider/authRecoveryStarted" to "正在恢复认证", "modelProvider/authRecoveryCompleted" to "认证恢复结果",
            "turn/moderationMetadata" to "回合审核信息", "model/safetyBuffering/updated" to "安全缓冲状态",
            "guardianWarning" to "守护提醒", "deprecationNotice" to "兼容性提醒", "configWarning" to "配置提醒"
        )
        val methods = titles.keys + setOf("process/outputDelta","process/exited","account/rateLimits/updated","app/list/updated","remoteControl/status/changed","fuzzyFileSearch/sessionUpdated","fuzzyFileSearch/sessionCompleted",
            "thread/realtime/started","thread/realtime/itemAdded","thread/realtime/item/started","thread/realtime/item/transcript/delta","thread/realtime/item/completed","thread/realtime/transcript/delta","thread/realtime/transcript/done","thread/realtime/outputAudio/delta","thread/realtime/sdp","thread/realtime/error","thread/realtime/closed")
    }
}
