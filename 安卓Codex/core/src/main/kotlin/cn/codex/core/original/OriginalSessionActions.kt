package cn.codex.core.original

import kotlinx.serialization.json.*

data class OriginalMetadataEdit(val mode: String, val value: String = "")
sealed interface OriginalTurnTask {
    data class Prompt(val text: String) : OriginalTurnTask
    data class Review(val threadId: String, val target: JsonObject) : OriginalTurnTask
}
data class OriginalSessionPage<T>(val entries: List<T> = emptyList(), val nextCursor: String? = null,
    val seenCursors: Set<String> = emptySet(), val repeatedCursor: Boolean = false) {
    fun append(incoming: List<T>, next: String?, requested: String?, identity: (T) -> String): OriginalSessionPage<T> {
        val repeated = next != null && (next == requested || next in seenCursors)
        return copy(entries = (entries + incoming).associateBy(identity).values.toList(),
            nextCursor = next.takeUnless { repeated }, seenCursors = if (next == null) seenCursors else seenCursors + next, repeatedCursor = repeated)
    }
}
object OriginalSessionActions {
    fun gitPatch(fields: Map<String, OriginalMetadataEdit>): JsonObject = buildJsonObject {
        require(fields.keys.all { it in setOf("sha", "branch", "originUrl") }) { "未知 Git 资料字段" }
        fields.forEach { (key, edit) -> when (edit.mode) {
            "keep" -> Unit
            "clear" -> put(key, JsonNull)
            "set" -> { require(edit.value.isNotBlank()) { "替换值不能为空" }; put(key, edit.value) }
            else -> error("未知字段编辑方式")
        } }
    }.also { require(it.isNotEmpty()) { "请至少修改一个字段" } }
    fun reviewTarget(type: String, value: String = "", title: String = ""): JsonObject = buildJsonObject {
        put("type", type)
        when (type) {
            "uncommittedChanges" -> Unit
            "baseBranch" -> { require(value.isNotBlank()) { "请填写基准分支" }; put("branch", value) }
            "commit" -> { require(value.isNotBlank()) { "请填写提交标识" }; put("sha", value); put("title", title.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull) }
            "custom" -> { require(value.isNotBlank()) { "请填写审查范围与要求" }; put("instructions", value) }
            else -> error("未知审查范围")
        }
    }
    fun threadStatus(status: JsonObject?): String = when ((status?.get("type") as? JsonPrimitive)?.contentOrNull) {
        "notLoaded" -> "未加载"; "idle" -> "空闲"; "systemError" -> "核心异常"
        "active" -> "处理中" + (status?.get("activeFlags") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { when (it) { "waitingOnApproval" -> "等待批准"; "waitingOnUserInput" -> "等待补充信息"; else -> it } }.joinToString("、", prefix = " · ").takeUnless { it == " · " }.orEmpty()
        else -> "尚未取得状态"
    }
}
