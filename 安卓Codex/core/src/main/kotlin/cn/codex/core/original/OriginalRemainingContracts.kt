package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

object OriginalRemainingContracts {
    fun targets(text: String): JsonArray = buildJsonArray {
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val fields = line.split('\t').let { if (it.size == 3) it else line.trim().split(Regex("\\s+")) }
            require(fields.size == 3 && fields[0] in setOf("user", "group", "workspace") && fields[1].isNotBlank() && fields[2] in setOf("reader", "editor")) { "分享对象每行：user/group/workspace 对象标识 reader/editor，用制表或空格分隔" }
            add(buildJsonObject { put("principalType", fields[0]); put("principalId", fields[1]); put("role", fields[2]) })
        }
    }
    fun history(text: String): JsonArray {
        val items = Json.parseToJsonElement(text) as? JsonArray ?: error("历史内容需要 JSON 数组")
        require(items.isNotEmpty() && items.all { it is JsonObject }) { "请至少提供一项 Responses 历史对象" }
        return items
    }
    fun tags(text: String): JsonObject {
        val tags = Json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject ?: error("标签需要 JSON 对象")
        require(tags.values.all { it is JsonPrimitive && it.isString }) { "标签值必须为字符串" }; return tags
    }
    fun historyResults(text: String): JsonArray {
        val results = Json.parseToJsonElement(text) as? JsonArray ?: error("迁移结果需要数组")
        require(results.isNotEmpty()) { "请填写实际迁移结果" }
        results.forEach { value -> val result = value.jsonObject; require(result.string("itemType") in migrationTypes) { "未知迁移类型" }; require(result["successes"] is JsonArray && result["failures"] is JsonArray) { "每种类型需要 successes 和 failures 数组" } }
        return results
    }
    val migrationTypes = setOf("AGENTS_MD", "CONFIG", "SKILLS", "PLUGINS", "MCP_SERVER_CONFIG", "SUBAGENTS", "HOOKS", "COMMANDS", "MEMORY", "SESSIONS")
    fun guardianEvent(notification: JsonObject): JsonObject {
        val review = notification.getValue("review").jsonObject
        require(review.string("status") == "denied") { "只能人工批准被拒绝的动作" }
        val action = notification.getValue("action").jsonObject
        fun snake(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        val original = buildJsonObject { action.forEach { (key, value) ->
            put(snake(key), when {
                key == "type" || key == "source" || key == "protocol" -> JsonPrimitive(snake(value.jsonPrimitive.content))
                key == "cwd" && action.string("type") == "writeStdin" && value.jsonPrimitive.content.startsWith("/") -> JsonPrimitive(java.io.File(value.jsonPrimitive.content).toURI().toASCIIString())
                key == "permissions" && value is JsonObject -> buildJsonObject {
                    put("network", value["network"] ?: JsonNull)
                    val fs = value["fileSystem"] as? JsonObject
                    put("file_system", if (fs == null) JsonNull else buildJsonObject {
                        val entries = (fs["entries"] as? JsonArray) ?: buildJsonArray {
                            listOf("read", "write").forEach { mode -> (fs[mode] as? JsonArray).orEmpty().forEach { path -> add(buildJsonObject { put("path", buildJsonObject { put("type", "path"); put("path", path) }); put("access", mode) }) } }
                        }
                        put("entries", entries); fs["globScanMaxDepth"]?.let { put("glob_scan_max_depth", it) }
                    })
                }
                else -> value
            })
        } }
        return buildJsonObject {
            put("id", notification.string("reviewId")); put("turn_id", notification.string("turnId")); put("status", "denied"); put("action", original)
            notification["targetItemId"]?.let { put("target_item_id", it) }
            notification["startedAtMs"]?.let { put("started_at_ms", it) }; notification["completedAtMs"]?.let { put("completed_at_ms", it) }
            listOf("riskLevel", "userAuthorization", "rationale").forEach { key -> review[key]?.let { put(snake(key), it) } }
            notification["decisionSource"]?.let { put("decision_source", it) }
        }
    }
}
