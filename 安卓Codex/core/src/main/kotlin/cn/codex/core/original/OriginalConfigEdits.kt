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

    val hookEvents = setOf("PreModelRequest", "PostModelRequest", "PreToolUse", "PermissionRequest",
        "PostToolUse", "PreCompact", "PostCompact", "SessionStart", "SessionEnd", "UserPromptSubmit",
        "SubagentStart", "SubagentStop", "Stop", "Interrupt")

    /** 严格对照 codex-rs/protocol/src/hooks.rs 的四种处理器，阻止无效的规则写入。 */
    private fun hookString(value: JsonElement?, field: String): String {
        val p = value as? JsonPrimitive
        require(p != null && p.isString) { "$field 必须为字符串" }
        return p.content
    }
    private fun hookUnsigned(value: JsonElement?, field: String) {
        val p = value as? JsonPrimitive
        require(p != null && !p.isString && p.longOrNull != null && p.longOrNull!! >= 0) { "$field 必须为非负整数" }
    }
    private fun hookRepresentable(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonArray -> value.all(::hookRepresentable)
        is JsonObject -> value.values.all(::hookRepresentable)
        is JsonPrimitive -> value.isString || value.booleanOrNull != null ||
            (value.longOrNull != null) || (value.doubleOrNull?.isFinite() == true)
    }
    private fun validateHookHandler(handler: JsonObject) {
        val type = hookString(handler["type"], "处理器类型")
        val base = setOf("type", "timeout", "statusMessage")
        val fields = when (type) {
            "command" -> base + setOf("command", "commandWindows", "command_windows", "async", "additionalContextLimit")
            "mcp_tool" -> base + setOf("server", "tool", "input")
            "prompt", "agent" -> setOf("type")
            else -> throw IllegalArgumentException("原版不支持的规则处理器：$type")
        }
        require(handler.keys.all { it in fields }) { "$type 包含上游不支持的字段" }
        when (type) {
            "command" -> {
                require(hookString(handler["command"], "command").isNotBlank()) { "command 不可为空" }
                handler["commandWindows"]?.let { hookString(it, "commandWindows") }
                handler["command_windows"]?.let { hookString(it, "command_windows") }
                handler["async"]?.let { p ->
                    require(p is JsonPrimitive && !p.isString && p.booleanOrNull != null) { "async 必须是布尔值" }
                }
                handler["additionalContextLimit"]?.let { hookUnsigned(it, "additionalContextLimit") }
            }
            "mcp_tool" -> {
                require(hookString(handler["server"], "server").isNotBlank() &&
                    hookString(handler["tool"], "tool").isNotBlank()) { "MCP 处理器需要服务和工具名" }
                handler["input"]?.let { value ->
                    require(value is JsonObject && value.values.all(::hookRepresentable)) { "MCP input 必须是可转 TOML 的对象，不能含 null" }
                }
            }
        }
        handler["timeout"]?.let { hookUnsigned(it, "timeout") }
        handler["statusMessage"]?.let { hookString(it, "statusMessage") }
    }

    fun hookRules(previous: JsonObject, rules: JsonObject): List<JsonObject> {
        require(rules.keys.all { it in hookEvents }) { "规则事件名不正确；信任状态请在规则卡片中修改" }
        require(rules.values.all { it is JsonArray }) { "每个事件需要填写匹配组数组" }
        for ((event, value) in rules) {
            (value as JsonArray).forEachIndexed { index, raw ->
                require(raw is JsonObject) { "$event 第 ${index + 1} 组必须是 JSON 对象" }
                require(raw.keys.all { it in setOf("matcher", "hooks") }) { "匹配组只能包含 matcher/hooks" }
                raw["matcher"]?.let { hookString(it, "matcher") }
                val handlers = raw["hooks"] as? JsonArray
                require(handlers != null && handlers.isNotEmpty()) { "规则组至少需要一个处理器" }
                handlers.forEach { item ->
                    require(item is JsonObject) { "处理器必须是 JSON 对象" }
                    validateHookHandler(item)
                }
            }
        }
        // 配置中的 state 由独立的信任/启停操作维护。编辑时绝不重写其内容。
        return ((previous.keys - "state") + rules.keys).map { event -> buildJsonObject {
            put("keyPath", childPath("hooks", event))
            put("mergeStrategy", "replace")
            put("value", rules[event] ?: JsonNull)
        } }
    }

}
