package cn.codex.core.original

import kotlinx.serialization.json.*

/** 精确对应固定上游 config/src/mcp_types.rs 的高级策略字段，保留未改动的配置。 */
object OriginalMcpAdvanced {
    val modes = setOf("auto", "prompt", "writes", "approve")
    private val variable = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun validate(input: JsonObject): JsonObject {
        require(input.containsKey("command") != input.containsKey("url")) { "只能设置一种 MCP 传输方式（command 或 url）" }
        fun nameList(key: String) {
            (input[key] ?: return).let { value ->
                require(value is JsonArray && value.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) { "$key 需要非空字符串数组" }
                require(value.map { it.jsonPrimitive.content }.distinct().size == value.size) { "$key 含有重复工具名称" }
            }
        }
        nameList("enabled_tools"); nameList("disabled_tools")
        input["default_tools_approval_mode"]?.let { mode ->
            require(mode is JsonPrimitive && mode.isString && mode.content in modes) { "默认审批模式不受支持" }
        }
        for (key in listOf("startup_timeout_sec", "tool_timeout_sec")) {
            input[key]?.let { raw ->
                val value = (raw as? JsonPrimitive)?.doubleOrNull
                require(value != null && value.isFinite() && value >= 0.0) { "$key 需要非负秒数" }
            }
        }
        input["tools"]?.let { raw ->
            require(raw is JsonObject) { "tools 必须是按工具名分组的对象" }
            raw.forEach { (name, settings) ->
                require(name.isNotBlank() && settings is JsonObject) { "工具名和工具策略必须有效" }
                require(settings.keys.all { it in setOf("approval_mode", "output_token_limit") }) { "工具策略包含固定上游不支持的字段" }
                settings["approval_mode"]?.let { mode ->
                    require(mode is JsonPrimitive && mode.isString && mode.content in modes) { "工具 $name 的审批模式不受支持" }
                }
                settings["output_token_limit"]?.let { value ->
                    require(value is JsonPrimitive && value.longOrNull != null && value.longOrNull!! > 0) { "工具 $name 的输出预算必须是正整数" }
                }
            }
        }
        input["env"]?.let { raw ->
            require(raw is JsonObject && raw.all { (name, value) -> variable.matches(name) && value is JsonPrimitive && value.isString }) { "env 需要环境变量名到字符串的映射" }
        }
        input["env_vars"]?.let { raw ->
            require(raw is JsonArray && raw.all { value ->
                when (value) {
                    is JsonPrimitive -> value.isString && variable.matches(value.content)
                    is JsonObject -> value.keys.all { it in setOf("name", "source") } &&
                        (value["name"] as? JsonPrimitive)?.content?.let(variable::matches) == true &&
                        (value["source"] as? JsonPrimitive)?.content in setOf(null, "local", "remote")
                    else -> false
                }
            }) { "env_vars 只支持变量名或 name/source(local、remote) 引用" }
        }
        input["bearer_token_env_var"]?.let { raw ->
            require(raw is JsonPrimitive && raw.isString && variable.matches(raw.content)) { "Bearer 令牌请填写环境变量名" }
        }
        if (input.containsKey("url")) {
            require(!input.containsKey("env") && !input.containsKey("env_vars") && !input.containsKey("cwd")) { "HTTP 服务不能带 stdio 环境配置" }
        }
        return input
    }

    private fun lines(input: String) = input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    fun lists(input: JsonObject, allowed: String, denied: String, defaultApproval: String): JsonObject {
        require(defaultApproval.isBlank() || defaultApproval in modes) { "不支持的默认审批模式" }
        val out = input.toMutableMap()
        for ((key, value) in listOf("enabled_tools" to allowed, "disabled_tools" to denied)) {
            val names = lines(value)
            if (names.isEmpty()) out.remove(key) else out[key] = JsonArray(names.map(::JsonPrimitive))
        }
        if (defaultApproval.isBlank()) out.remove("default_tools_approval_mode")
        else out["default_tools_approval_mode"] = JsonPrimitive(defaultApproval)
        return validate(JsonObject(out))
    }

    fun tool(input: JsonObject, toolName: String, approval: String, budget: String): JsonObject {
        require(toolName.isNotBlank()) { "请填写工具原始名称" }
        require(approval.isBlank() || approval in modes) { "工具审批模式不正确" }
        val value = budget.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        require(budget.isBlank() || value != null && value > 0) { "输出预算必须是正整数" }
        val tools = ((input["tools"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        val settings = ((tools[toolName] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        if (approval.isBlank()) settings.remove("approval_mode") else settings["approval_mode"] = JsonPrimitive(approval)
        if (budget.isBlank()) settings.remove("output_token_limit") else settings["output_token_limit"] = JsonPrimitive(value!!)
        if (settings.isEmpty()) tools.remove(toolName) else tools[toolName] = JsonObject(settings)
        val fields = input.toMutableMap()
        if (tools.isEmpty()) fields.remove("tools") else fields["tools"] = JsonObject(tools)
        return validate(JsonObject(fields))
    }

    /** env_vars 是对原版已有变量的引用；不会把密钥本身作为表单输入保存。 */
    fun reference(input: JsonObject, name: String, source: String): JsonObject {
        require(variable.matches(name)) { "环境变量名只能包含字母、数字和下划线，且不能以数字开头" }
        require(source in setOf("local", "remote", "remove")) { "环境来源仅支持本机或远端" }
        require(input.containsKey("command")) { "env_vars 仅适用于 stdio 服务" }
        val current = (input["env_vars"] as? JsonArray).orEmpty()
        val kept = current.filter { item ->
            val existing = if (item is JsonObject) item["name"]?.jsonPrimitive?.content else item.jsonPrimitive.content
            existing != name
        }
        val next = if (source == "remove") kept else kept + buildJsonObject { put("name", name); put("source", source) }
        val fields = input.toMutableMap()
        if (next.isEmpty()) fields.remove("env_vars") else fields["env_vars"] = JsonArray(next)
        return validate(JsonObject(fields))
    }
}
