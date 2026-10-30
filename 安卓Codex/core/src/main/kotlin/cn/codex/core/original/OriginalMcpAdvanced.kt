package cn.codex.core.original

import kotlinx.serialization.json.*
import java.net.URI

/**
 * 与资料库固定源码 codex-rs/config/src/mcp_types.rs 的 RawMcpServerConfig 逐项对齐。
 * 编辑原版用户层完整条目：先校验全部字段，再交由 config/batchWrite 做版本写入。
 * 校验不访问网络、不读写密钥，也不擅自增加任何默认权限。
 */
object OriginalMcpAdvanced {
    val modes = setOf("auto", "prompt", "writes", "approve")
    private val variable = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val common = setOf("name", "environment_id", "auth", "startup_timeout_sec", "startup_timeout_ms",
        "tool_timeout_sec", "enabled", "required", "startup_readiness", "supports_parallel_tool_calls",
        "tool_input_schema_max_bytes", "omit_tools_from", "default_tools_approval_mode", "enabled_tools",
        "disabled_tools", "scopes", "oauth", "oauth_resource", "tools")
    private val stdio = setOf("command", "args", "env", "env_vars", "cwd")
    private val remote = setOf("url", "http_headers", "env_http_headers", "bearer_token_env_var",
        "http_headers_helper")
    private val forbidden = "bearer_token"
    private fun string(value: JsonElement?, field: String): String {
        val p = value as? JsonPrimitive
        require(p != null && p.isString) { "$field 必须是字符串" }
        return p.content
    }
    private fun objectValue(value: JsonElement?, field: String): JsonObject {
        return value as? JsonObject ?: throw IllegalArgumentException("$field 必须是对象")
    }
    private fun nonNegative(value: JsonElement?, field: String, allowZero: Boolean): Long {
        val p = value as? JsonPrimitive
        require(p != null && !p.isString && p.longOrNull != null &&
            (allowZero && p.longOrNull!! >= 0 || !allowZero && p.longOrNull!! > 0)) { "$field 必须是${if (allowZero) "非负" else "正"}整数" }
        return p.longOrNull!!
    }
    private fun seconds(value: JsonElement?, field: String) {
        val p = value as? JsonPrimitive
        val number = p?.takeUnless { it.isString }?.doubleOrNull
        require(number != null && number.isFinite() && number >= 0) { "$field 必须是非负秒数" }
    }
    private fun stringList(value: JsonElement?, field: String, unique: Boolean = false, allowBlank: Boolean = false) {
        val array = value as? JsonArray ?: throw IllegalArgumentException("$field 必须是字符串数组")
        val items = array.map { string(it, field) }
        if (!allowBlank) require(items.all { it.isNotBlank() }) { "$field 不能有空项" }
        if (unique) require(items.size == items.distinct().size) { "$field 含重复项" }
    }
    private fun stringMap(value: JsonElement?, field: String, envNames: Boolean = false) {
        objectValue(value, field).forEach { (name, item) ->
            require(name.isNotBlank() && (!envNames || variable.matches(name))) { "$field 的变量名无效" }
            string(item, "$field.$name")
        }
    }
    private fun checkHttpUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null) { "HTTP 服务地址无效或包含账号信息/片段" }
    }
    private fun checkOAuth(raw: JsonElement, auth: String) {
        val oauth = objectValue(raw, "oauth")
        val allowed = setOf("client_id", "client_secret", "token_endpoint_auth_method", "callback_url",
            "callback_port", "authorization_server_issuer")
        require(oauth.keys.all { it in allowed }) { "oauth 包含上游不支持的字段" }
        for (key in allowed - "callback_port") oauth[key]?.let { string(it, "oauth.$key") }
        oauth["callback_port"]?.let { require(nonNegative(it, "oauth.callback_port", true) <= 65535) }
        val secret = oauth["client_secret"]?.let { string(it, "oauth.client_secret") }
        if (secret != null) {
            require(secret.isNotBlank() && !oAuthClientId(oauth).isNullOrBlank()) { "OAuth 客户端密钥需要非空 client_id" }
        }
        oauth["token_endpoint_auth_method"]?.let {
            require(string(it, "oauth.token_endpoint_auth_method") in setOf("client_secret_basic", "client_secret_post")) {
                "EMA 令牌认证只接受 client_secret_basic 或 client_secret_post"
            }
            require(auth == "ema_auth" && secret != null) { "token_endpoint_auth_method 只可用于带客户端密钥的 ema_auth" }
        }
        if (oauth.containsKey("authorization_server_issuer")) require(auth == "ema_auth") { "issuer 仅用于 ema_auth" }
    }
    private fun oAuthClientId(oauth: JsonObject) = (oauth["client_id"] as? JsonPrimitive)?.contentOrNull

    fun validate(input: JsonObject): JsonObject {
        require(forbidden !in input) { "固定上游不接受明文 bearer_token，改用环境引用或 OAuth" }
        require(input.keys.all { it in common || it in stdio || it in remote }) { "存在上游 RawMcpServerConfig 不支持的字段" }
        val hasStdio = input.containsKey("command")
        val hasHttp = input.containsKey("url")
        require(hasStdio != hasHttp) { "必须且只能指定 command 或 url 其中一种传输方式" }
        if (hasStdio) {
            require(input.keys.none { it in remote } && input["auth"] == null &&
                input["oauth"] == null && input["oauth_resource"] == null) { "stdio 不接受 HTTP 授权/请求头字段" }
            require(string(input["command"], "command").isNotBlank()) { "命令不能为空" }
            input["cwd"]?.let { string(it, "cwd") }
            input["args"]?.let { stringList(it, "args", allowBlank = true) }
            input["env"]?.let { stringMap(it, "env", true) }
            input["env_vars"]?.let { raw ->
                val items = raw as? JsonArray ?: throw IllegalArgumentException("env_vars 必须是数组")
                val names = items.map { item ->
                    if (item is JsonObject) {
                        require(item.keys.all { it in setOf("name", "source") }) { "env_vars 对象只接受 name/source" }
                        val name = string(item["name"], "env_vars.name")
                        item["source"]?.let { require(string(it, "env_vars.source") in setOf("local", "remote")) { "env_vars 来源只支持 local/remote" } }
                        name
                    } else string(item, "env_vars")
                }
                require(names.all(variable::matches) && names.distinct().size == names.size) { "env_vars 的变量名无效或重复" }
            }
        } else {
            require(input.keys.none { it in stdio }) { "HTTP 服务不可携带 stdio 命令、参数或环境" }
            checkHttpUrl(string(input["url"], "url"))
            input["bearer_token_env_var"]?.let { require(variable.matches(string(it, "bearer_token_env_var"))) { "请填写环境变量名" } }
            input["http_headers"]?.let { stringMap(it, "http_headers") }
            input["env_http_headers"]?.let { stringMap(it, "env_http_headers") }
            input["http_headers_helper"]?.let { require(string(it, "http_headers_helper").isNotBlank()) { "请求头辅助程序不能为空" } }
            if (input.containsKey("http_headers_helper"))
                require((input["environment_id"]?.let { string(it, "environment_id") } ?: "local") == "local") { "请求头辅助程序只能在本机环境使用" }
        }
        val auth = input["auth"]?.let { string(it, "auth") } ?: "oauth"
        require(auth in setOf("oauth", "chatgpt", "ema_auth")) { "不支持的 MCP 授权方式" }
        input["oauth"]?.let { checkOAuth(it, auth) }
        for (key in listOf("name", "environment_id", "oauth_resource")) input[key]?.let { string(it, key) }
        for (key in listOf("enabled", "required", "supports_parallel_tool_calls")) input[key]?.let {
            val p = it as? JsonPrimitive
            require(p != null && !p.isString && p.booleanOrNull != null) { "$key 必须是布尔值" }
        }
        for (key in listOf("startup_timeout_sec", "tool_timeout_sec")) input[key]?.let { seconds(it, key) }
        input["startup_timeout_ms"]?.let { nonNegative(it, "startup_timeout_ms", true) }
        input["tool_input_schema_max_bytes"]?.let { nonNegative(it, "tool_input_schema_max_bytes", false) }
        input["startup_readiness"]?.let { require(string(it, "startup_readiness") in setOf("connection", "catalog")) { "启动就绪模式无效" } }
        input["omit_tools_from"]?.let { stringList(it, "omit_tools_from"); require(it.jsonArray.all { v -> v.jsonPrimitive.content in setOf("direct", "deferred", "code_mode") }) { "工具暴露位置无效" } }
        for (key in listOf("enabled_tools", "disabled_tools", "scopes")) input[key]?.let { stringList(it, key, true) }
        input["default_tools_approval_mode"]?.let { require(string(it, "default_tools_approval_mode") in modes) { "默认审批模式无效" } }
        input["tools"]?.let { raw ->
            objectValue(raw, "tools").forEach { (name, value) ->
                require(name.isNotBlank()) { "工具名称不能为空" }
                val settings = objectValue(value, "tools.$name")
                require(settings.keys.all { it in setOf("approval_mode", "output_token_limit") }) { "工具策略字段不受上游支持" }
                settings["approval_mode"]?.let { require(string(it, "tools.$name.approval_mode") in modes) { "工具审批模式无效" } }
                settings["output_token_limit"]?.let { nonNegative(it, "tools.$name.output_token_limit", false) }
            }
        }
        return input
    }

    private fun lines(value: String): List<String> = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    fun lists(input: JsonObject, allowed: String, denied: String, defaultApproval: String): JsonObject {
        require(defaultApproval.isBlank() || defaultApproval in modes) { "默认工具审批模式无效" }
        val fields = input.toMutableMap()
        for ((key, text) in listOf("enabled_tools" to allowed, "disabled_tools" to denied)) {
            val names = lines(text)
            if (names.isEmpty()) fields.remove(key) else fields[key] = JsonArray(names.map(::JsonPrimitive))
        }
        if (defaultApproval.isBlank()) fields.remove("default_tools_approval_mode")
        else fields["default_tools_approval_mode"] = JsonPrimitive(defaultApproval)
        return validate(JsonObject(fields))
    }
    fun tool(input: JsonObject, name: String, approval: String, budget: String): JsonObject {
        require(name.isNotBlank() && approval in modes + "") { "工具名或审批模式不正确" }
        val limit = budget.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        require(budget.isBlank() || limit != null && limit > 0) { "输出预算必须为正整数" }
        val tools = (input["tools"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val settings = (tools[name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (approval.isBlank()) settings.remove("approval_mode") else settings["approval_mode"] = JsonPrimitive(approval)
        if (budget.isBlank()) settings.remove("output_token_limit") else settings["output_token_limit"] = JsonPrimitive(limit!!)
        if (settings.isEmpty()) tools.remove(name) else tools[name] = JsonObject(settings)
        val fields = input.toMutableMap()
        if (tools.isEmpty()) fields.remove("tools") else fields["tools"] = JsonObject(tools)
        return validate(JsonObject(fields))
    }
    /** 仅增加本机/远端变量名引用，不让用户在此输入凭证明文。 */
    fun reference(input: JsonObject, name: String, source: String): JsonObject {
        require(variable.matches(name)) { "环境变量名格式无效" }
        require(source in setOf("local", "remote", "remove")) { "变量来源无效" }
        require(input.containsKey("command")) { "仅 stdio 可以使用 env_vars" }
        val existing = (input["env_vars"] as? JsonArray).orEmpty().filter { item ->
            (if (item is JsonObject) item["name"]?.jsonPrimitive?.content else item.jsonPrimitive.content) != name
        }
        val updated = if (source == "remove") existing else existing + buildJsonObject {
            put("name", name); put("source", source)
        }
        val fields = input.toMutableMap()
        if (updated.isEmpty()) fields.remove("env_vars") else fields["env_vars"] = JsonArray(updated)
        return validate(JsonObject(fields))
    }
}
