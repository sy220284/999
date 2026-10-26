package cn.codex.android

import android.content.Context
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable data class McpConnection(val name: String, val url: String)
/** 无凭证的 Streamable HTTP MCP；OAuth/stdio 单独标为未移植，不能伪装成连接成功。 */
class McpTools(private val context: Context): ToolExtension {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val configFile get() = File(context.filesDir, "MCP连接.json")
    fun connections(): List<McpConnection> = if (configFile.exists()) codec.decodeFromString(configFile.readText()) else emptyList()
    fun save(connections: List<McpConnection>) {
        connections.forEach { require(it.url.startsWith("https://")) { "MCP 服务须使用 HTTPS" }; require(it.name.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "连接标识使用 1–32 位字母、数字或下划线" } }
        configFile.writeText(codec.encodeToString(connections))
    }
    override fun specs() = listOf(ToolSpec("mcp", "使用已配置的 MCP 服务。action 为 list_tools/call_tool/read_resource；server 为连接标识，name 为工具名或资源 URI，arguments 为对象。外部服务需联网", buildJsonObject {
        put("type", "object"); put("properties", buildJsonObject { put("server", obj("type" to "string")); put("action", obj("type" to "string")); put("name", obj("type" to "string")); put("arguments", obj("type" to "object")) }); put("required", buildJsonArray { add("server"); add("action") })
    }))
    override suspend fun execute(call: ToolCall): String = withContext(Dispatchers.IO) {
        val a = call.arguments
        val server = connections().firstOrNull { it.name == a.string("server") } ?: error("MCP 连接不存在")
        val init = rpc(server, "initialize", buildJsonObject { put("protocolVersion", "2025-03-26"); put("capabilities", JsonObject(emptyMap())); put("clientInfo", obj("name" to "codex-android", "version" to "0.1.0")) })
        val sid = init.second
        rpc(server, "notifications/initialized", JsonObject(emptyMap()), sid, notification = true)
        val (method, params) = when (a.string("action")) {
            "list_tools" -> "tools/list" to JsonObject(emptyMap())
            "call_tool" -> "tools/call" to buildJsonObject { put("name", a.string("name")); put("arguments", a["arguments"] ?: JsonObject(emptyMap())) }
            "read_resource" -> "resources/read" to obj("uri" to a.string("name"))
            else -> error("未知 MCP 操作")
        }
        rpc(server, method, params, sid).first.toString()
    }
    private suspend fun rpc(server: McpConnection, method: String, params: JsonObject, session: String? = null, notification: Boolean = false): Pair<JsonElement, String?> {
        val payload = buildJsonObject { put("jsonrpc", "2.0"); if (!notification) put("id", 1); put("method", method); put("params", params) }
        val builder = Request.Builder().url(server.url).header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-03-26").post(payload.toString().toRequestBody("application/json".toMediaType()))
        session?.let { builder.header("Mcp-Session-Id", it) }
        val request = http.newCall(builder.build())
        return coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { request.cancel() } }
            try {
                request.execute().use { response ->
                    check(response.isSuccessful) { "MCP HTTP ${response.code}" }
                    if (notification) return@use JsonNull to session
                    val raw = if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                        var matched: JsonObject? = null
                        val parser = SseParser { line -> val e = Json.parseToJsonElement(line).jsonObject; if (e["id"]?.jsonPrimitive?.intOrNull == 1) matched = e }
                        val source = response.body!!.source()
                        while (matched == null && !source.exhausted()) {
                            currentCoroutineContext().ensureActive()
                            parser.line(source.readUtf8Line() ?: break)
                        }
                        (matched ?: error("MCP 未返回对应结果")).toString()
                    } else response.body!!.string()
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    check(obj["error"] == null) { "MCP 错误：${obj["error"]}" }
                    (obj["result"] ?: JsonNull) to response.header("Mcp-Session-Id")
                }
            } finally { watcher.cancel() }
        }
    }
}
