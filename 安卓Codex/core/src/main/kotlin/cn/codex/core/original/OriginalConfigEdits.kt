package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

data class OriginalConfigSnapshot(val file: String, val version: String)

/** config/read 将内部层反转成高到低顺序；第一个 user 才是活动用户层。 */
object OriginalConfigEdits {
    fun userLayer(response: JsonObject): JsonObject? =
        (response["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["name"] as? JsonObject)?.string("type") == "user" }
    fun snapshot(response: JsonObject): OriginalConfigSnapshot? {
        val layer = userLayer(response) ?: return null
        if (layer["disabledReason"] != null && layer["disabledReason"] != JsonNull) return null
        val file = (layer["name"] as? JsonObject)?.string("file").orEmpty()
        val version = layer.string("version")
        return if (file.isBlank() || version.isBlank()) null else OriginalConfigSnapshot(file, version)
    }
    fun request(snapshot: OriginalConfigSnapshot, edit: JsonObject) = request(snapshot, listOf(edit))
    fun request(snapshot: OriginalConfigSnapshot, edits: List<JsonObject>) = buildJsonObject {
        require(snapshot.file.isNotBlank() && snapshot.version.isNotBlank())
        put("filePath", snapshot.file); put("expectedVersion", snapshot.version)
        put("reloadUserConfig", true); put("edits", buildJsonArray { edits.forEach { add(it) } })
    }
    private fun edit(table: String, key: String, fields: JsonObject) = buildJsonObject {
        require(key.isNotBlank()) { "配置标识缺失，请刷新" }
        put("keyPath", table); put("mergeStrategy", "upsert")
        // ID 和 Hook key 是字面数据，不能拼进按点解析的 keyPath。
        put("value", buildJsonObject { put(key, fields) })
    }
    fun plugin(id: String, enabled: Boolean) = edit("plugins", id, buildJsonObject { put("enabled", enabled) })
    private fun checkHook(approved: JsonObject, current: JsonObject) {
        require(approved.string("key").isNotBlank() && approved.string("key") == current.string("key")) { "规则身份已变化，请刷新" }
        require(current["isManaged"]?.jsonPrimitive?.booleanOrNull == false && current.string("trustStatus") != "managed") { "该规则由策略管理或状态不明，不能修改" }
        require(current.string("trustStatus") in setOf("trusted", "untrusted", "modified")) { "未知信任状态，不能修改" }
        require(approved.string("currentHash").isNotBlank() && approved.string("currentHash") == current.string("currentHash")) { "规则内容已变化，请重新查看并确认" }
        require(approved.string("sourcePath") == current.string("sourcePath") && approved.string("pluginId") == current.string("pluginId") && approved.string("environmentId") == current.string("environmentId")) { "规则来源已变化，请刷新" }
    }
    fun hook(approved: JsonObject, current: JsonObject, trust: Boolean): JsonObject {
        checkHook(approved, current)
        return edit("hooks.state", current.string("key"), buildJsonObject {
            if (trust) {
                put("trusted_hash", current.string("currentHash"))
            } else {
                val before = approved["enabled"]?.jsonPrimitive?.booleanOrNull
                require(before != null && before == current["enabled"]?.jsonPrimitive?.booleanOrNull) { "规则启用状态已变化，请刷新" }
                put("enabled", !before)
            }
        })
    }
    fun childPath(parent: String, key: String): String {
        require(key.isNotBlank() && key.none { it.isISOControl() }) { "请填写有效的配置标识" }
        return "$parent.\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
    fun revokeHook(approved: JsonObject, current: JsonObject): List<JsonObject> {
        checkHook(approved, current)
        val path = childPath("hooks.state", current.string("key"))
        return listOf(
            buildJsonObject { put("keyPath", "$path.trusted_hash"); put("mergeStrategy", "replace"); put("value", JsonNull) },
            buildJsonObject { put("keyPath", "$path.trusted_content_hash"); put("mergeStrategy", "replace"); put("value", JsonNull) },
            edit("hooks.state", current.string("key"), buildJsonObject { put("enabled", false) })
        )
    }

    val hookEvents = setOf("PreModelRequest", "PostModelRequest", "PreToolUse", "PermissionRequest", "PostToolUse", "PreCompact", "PostCompact", "SessionStart", "SessionEnd", "UserPromptSubmit", "SubagentStart", "SubagentStop", "Stop", "Interrupt")
    fun hookRules(previous: JsonObject, rules: JsonObject): List<JsonObject> {
        require(rules.keys.all { it in hookEvents }) { "规则事件名不正确；信任状态请在规则卡片中修改" }
        require(rules.values.all { it is JsonArray }) { "每个事件需要填写匹配组数组" }
        return ((previous.keys - "state") + rules.keys).map { event -> buildJsonObject {
            put("keyPath", childPath("hooks", event)); put("mergeStrategy", "replace"); put("value", rules[event] ?: JsonNull)
        } }
    }

}
