package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 按 itemId 更新；completed 的完整内容替换 delta，避免完成时正文重复。保留未知工具的结构供界面展开。 */
data class OriginalItem(val id: String, val type: String, val text: String, val status: String = "", val data: JsonObject = buildJsonObject {})
object OriginalThreadView {
    fun item(value: JsonObject): OriginalItem {
        val type = value.string("type")
        val text = when (type) {
            "agentMessage", "plan" -> value.string("text")
            "userMessage" -> (value["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n") { it.string("text", "[附件]") }
            "reasoning" -> (value["summary"] as? JsonArray).orEmpty().joinToString("\n") { it.jsonPrimitive.content }
            "commandExecution" -> value.string("command") + "\n" + value.string("aggregatedOutput")
            "fileChange" -> (value["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n") { it.string("path") + ((it["kind"] as? JsonObject)?.string("move_path")?.takeIf { path -> path.isNotBlank() }?.let { path -> " → $path" } ?: "") + "\n" + it.string("diff") }
            "mcpToolCall" -> value.string("server") + " / " + value.string("tool")
            else -> type
        }
        return OriginalItem(value.string("id"), type, text, value.string("status"), value)
    }
    fun history(thread: JsonObject): List<OriginalItem> = (thread["turns"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.flatMap { turn -> (turn["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map(::item) }
    fun upsert(items: List<OriginalItem>, item: OriginalItem): List<OriginalItem> =
        if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it } else items + item
    fun patch(items: List<OriginalItem>, id: String, changes: JsonArray): List<OriginalItem> {
        val previous = items.firstOrNull { it.id == id }
        val raw = JsonObject(previous?.data.orEmpty() + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive("fileChange"), "changes" to changes))
        return upsert(items, item(raw))
    }
    fun delta(items: List<OriginalItem>, id: String, type: String, text: String): List<OriginalItem> {
        val existing = items.firstOrNull { it.id == id } ?: OriginalItem(id, type, "")
        return upsert(items, existing.copy(text = (existing.text + text).takeLast(1_048_576)))
    }
}
