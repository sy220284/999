package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 只能回传用户逐项选中的原始请求，不能扩大路径或把读取升级为写入。 */
class PermissionSelection(val requested: JsonObject) {
    data class Choice(val id: String, val label: String, val value: JsonElement, val group: String)
    val network = (requested["network"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull
    private val files = requested["fileSystem"] as? JsonObject
    val choices = buildList {
        listOf("read" to "读取", "write" to "写入").forEach { (key, label) ->
            (files?.get(key) as? JsonArray).orEmpty().forEachIndexed { index, path -> add(Choice("$key:$index", "$label：${path.jsonPrimitive.content}", path, key)) }
        }
        (files?.get("entries") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEachIndexed { index, entry ->
            val path = entry["path"] as? JsonObject ?: return@forEachIndexed
            val label = when (entry.string("access")) { "read" -> "读取"; "write" -> "写入"; "deny" -> "禁止访问"; else -> "未识别权限" }
            val location = when (path.string("type")) { "path" -> path.string("path"); "glob_pattern" -> "匹配 ${path.string("pattern")}"; else -> "特殊路径 ${path["value"]}" }
            add(Choice("entries:$index", "$label：$location", entry, "entries"))
        }
    }
    fun response(selected: Set<String>, selectNetwork: Boolean, session: Boolean): JsonObject {
        require(selected.all { id -> choices.any { it.id == id } }) { "权限选择已变化" }
        require(!selectNetwork || network != null) { "未请求联网权限" }
        val chosen = choices.filter { it.id in selected }
        return buildJsonObject {
            put("permissions", buildJsonObject {
                if (selectNetwork) put("network", buildJsonObject { put("enabled", network!!) })
                if (chosen.isNotEmpty()) put("fileSystem", buildJsonObject {
                    for (key in listOf("read", "write")) {
                        val paths = chosen.filter { it.group == key }.map { it.value }
                        put(key, if (paths.isEmpty()) JsonNull else JsonArray(paths))
                    }
                    chosen.filter { it.group == "entries" }.takeIf { it.isNotEmpty() }?.let { put("entries", JsonArray(it.map { choice -> choice.value })) }
                    files?.get("globScanMaxDepth")?.let { put("globScanMaxDepth", it) }
                })
            })
            put("scope", if (session) "session" else "turn")
            put("strictAutoReview", true)
        }
    }
}
