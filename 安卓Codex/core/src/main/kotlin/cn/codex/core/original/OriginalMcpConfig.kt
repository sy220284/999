package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.net.URI

data class OriginalMcpEntry(val name: String, val userConfig: JsonObject?, val effectiveConfig: JsonObject?, val runtime: JsonObject?) {
    val editable get() = userConfig != null && runtime?.string("pluginId").isNullOrBlank()
    val enabled get() = (effectiveConfig ?: userConfig)?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: (runtime?.string("runtimeStatus") != "disabled")
}

/** 配置编辑只替换单个用户条目；其他服务器、同条目未编辑的高级设置均保留。 */
object OriginalMcpConfig {
    fun userServers(response: JsonObject): JsonObject =
        ((OriginalConfigEdits.userLayer(response)?.get("config") as? JsonObject)?.get("mcp_servers") as? JsonObject) ?: buildJsonObject {}
    fun entries(response: JsonObject?, runtime: List<JsonObject>): List<OriginalMcpEntry> {
        val user = response?.let(::userServers) ?: buildJsonObject {}
        val effective = ((response?.get("config") as? JsonObject)?.get("mcp_servers") as? JsonObject) ?: buildJsonObject {}
        val states = runtime.associateBy { it.string("name") }
        return (user.keys + effective.keys + states.keys).sorted().map { name ->
            OriginalMcpEntry(name, user[name] as? JsonObject, effective[name] as? JsonObject, states[name])
        }
    }
    fun edit(name: String, config: JsonObject?): JsonObject {
        return buildJsonObject {
            put("keyPath", OriginalConfigEdits.childPath("mcp_servers", name)); put("mergeStrategy", "replace"); put("value", config ?: JsonNull)
        }
    }
    private val stdioFields = setOf("command", "args", "cwd", "env", "env_vars")
    private val httpFields = setOf("url", "bearer_token", "bearer_token_env_var", "http_headers", "env_http_headers", "http_headers_helper", "auth", "oauth", "oauth_resource")
    fun form(original: JsonObject, url: String, command: String, args: String, cwd: String, envVars: String,
        bearerEnv: String, scopes: String, startup: String, timeout: String, enabled: Boolean, http: Boolean): JsonObject {
        val switching = if (http) original.containsKey("command") else original.containsKey("url")
        val fields = original.toMutableMap()
        if (switching) (if (http) stdioFields else httpFields).forEach(fields::remove)
        fun text(key: String, value: String) { if (value.isBlank()) fields.remove(key) else fields[key] = JsonPrimitive(value.trim()) }
        fun lines(key: String, value: String) {
            val entries = value.lineSequence().filter { it.isNotBlank() }.toList()
            if (entries.isEmpty()) fields.remove(key) else fields[key] = JsonArray(entries.map(::JsonPrimitive))
        }
        fun seconds(key: String, value: String) {
            if (value.isBlank()) { fields.remove(key); return }
            val number = value.toDoubleOrNull()
            require(number != null && number.isFinite() && number >= 0) { "超时需要填写不小于 0 的秒数" }
            fields[key] = JsonPrimitive(number)
        }
        if (http) {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "请填写有效的 HTTP/HTTPS 服务地址，不在地址中填写账号密码" }
            text("url", url); text("bearer_token_env_var", bearerEnv); lines("scopes", scopes)
        } else {
            require(command.isNotBlank()) { "请填写手机上实际存在的程序路径" }
            text("command", command)
            val oldArgs = (original["args"] as? JsonArray).orEmpty().joinToString("\n") { it.jsonPrimitive.content }
            if (switching || args != oldArgs) {
                if (args.isEmpty()) fields.remove("args") else fields["args"] = JsonArray(args.split('\n').map(::JsonPrimitive))
            }
            text("cwd", cwd)
            // 原版 env_vars 也支持带 source 的对象；未编辑时保留原始对象，避免降级成字符串。
            val names = (original["env_vars"] as? JsonArray).orEmpty().joinToString("\n") { if (it is JsonObject) it.string("name") else it.jsonPrimitive.content }
            if (envVars != names || switching) lines("env_vars", envVars)
        }
        seconds("startup_timeout_sec", startup); seconds("tool_timeout_sec", timeout)
        // 秒数优先，移除等价旧字段以免两个设置相互冲突。
        fields.remove("startup_timeout_ms")
        fields["enabled"] = JsonPrimitive(enabled)
        return JsonObject(fields)
    }
}
