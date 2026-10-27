package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

data class OriginalConfigSnapshot(val file: String, val version: String)

/** 与原版 get_active_user_layer 的低到高顺序一致；无版本时拒绝盲写。 */
object OriginalConfigEdits {
    fun snapshot(response: JsonObject): OriginalConfigSnapshot? {
        val layer = (response["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .lastOrNull { (it["name"] as? JsonObject)?.string("type") == "user" } ?: return null
        if (layer["disabledReason"] != null && layer["disabledReason"] != JsonNull) return null
        val file = (layer["name"] as? JsonObject)?.string("file").orEmpty()
        val version = layer.string("version")
        return if (file.isBlank() || version.isBlank()) null else OriginalConfigSnapshot(file, version)
    }
    fun request(snapshot: OriginalConfigSnapshot, edit: JsonObject) = buildJsonObject {
        require(snapshot.file.isNotBlank() && snapshot.version.isNotBlank())
        put("filePath", snapshot.file); put("expectedVersion", snapshot.version)
        put("reloadUserConfig", true); put("edits", buildJsonArray { add(edit) })
    }
    private fun edit(table: String, key: String, fields: JsonObject) = buildJsonObject {
        require(key.isNotBlank()) { "配置标识缺失，请刷新" }
        put("keyPath", table); put("mergeStrategy", "upsert")
        // ID 和 Hook key 是字面数据，不能拼进按点解析的 keyPath。
        put("value", buildJsonObject { put(key, fields) })
    }
    fun plugin(id: String, enabled: Boolean) = edit("plugins", id, buildJsonObject { put("enabled", enabled) })
    fun hook(approved: JsonObject, current: JsonObject, trust: Boolean): JsonObject {
        require(approved.string("key").isNotBlank() && approved.string("key") == current.string("key")) { "规则身份已变化，请刷新" }
        require(current["isManaged"]?.jsonPrimitive?.booleanOrNull == false && current.string("trustStatus") != "managed") { "该规则由策略管理或状态不明，不能修改" }
        require(current.string("trustStatus") in setOf("trusted", "untrusted", "modified")) { "未知信任状态，不能修改" }
        require(approved.string("currentHash").isNotBlank() && approved.string("currentHash") == current.string("currentHash")) { "规则内容已变化，请重新查看并确认" }
        require(approved.string("sourcePath") == current.string("sourcePath") && approved.string("pluginId") == current.string("pluginId") && approved.string("environmentId") == current.string("environmentId")) { "规则来源已变化，请刷新" }
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
}
