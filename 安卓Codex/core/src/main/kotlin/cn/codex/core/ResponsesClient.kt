package cn.codex.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ResponsesClient(private val config: ApiConfig, private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).connectTimeout(30, TimeUnit.SECONDS).build()) {
    private fun endpoint(path: String): HttpUrl {
        val base = config.endpoint.trimEnd('/').toHttpUrl()
        require(base.isHttps || (config.allowLoopbackHttp && base.host in setOf("localhost", "127.0.0.1", "::1"))) { "模型服务需要 HTTPS；本机模型可允许回环 HTTP" }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "服务地址不能包含凭证、查询参数或片段" }
        return (config.endpoint.trimEnd('/') + "/" + path).toHttpUrl()
    }
    private fun request(path: String) = Request.Builder().url(endpoint(path)).header("Authorization", "Bearer ${config.apiKey}")
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val call = http.newCall(request("models").get().build())
        cancellableCall(call) { response ->
            check(response.isSuccessful) { "获取模型失败（HTTP ${response.code}）" }
            Json.parseToJsonElement(response.body!!.string()).jsonObject["data"]!!.jsonArray.map { it.jsonObject.string("id") }.sorted()
        }
    }
    suspend fun respond(input: JsonArray, tools: List<ToolSpec>, instructions: String = "", emit: (AgentEvent) -> Unit): JsonArray = withContext(Dispatchers.IO) {
        require(config.model.isNotBlank()) { "请在设置中选择模型" }
        require(config.apiKey.isNotBlank() || config.allowLoopbackHttp) { "请先在设置中填写 API 密钥" }
        val body = buildJsonObject {
            put("model", config.model); put("input", input); put("stream", true); put("store", false)
            put("instructions", instructions)
            if (tools.isNotEmpty()) put("tools", JsonArray(tools.map { it.wire() }))
            if (config.reasoning.isNotBlank()) put("reasoning", obj("effort" to config.reasoning))
        }
        val call = http.newCall(request("responses").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        cancellableCall(call) { response ->
            check(response.isSuccessful) { "模型请求失败（HTTP ${response.code}）：${response.body?.string()?.take(1000) ?: "无错误正文"}" }
            var result: JsonArray? = null
            val parser = SseParser { raw ->
                if (raw != "[DONE]") {
                    val event = Json.parseToJsonElement(raw).jsonObject
                    when (event.string("type")) {
                        "response.output_text.delta" -> emit(AgentEvent.Delta(event.string("delta")))
                        "response.reasoning_summary_text.delta" -> emit(AgentEvent.Delta(event.string("delta"), true))
                        "response.completed" -> {
                            val complete = event.getValue("response").jsonObject
                            result = complete["output"]?.jsonArray ?: error("回复缺少输出")
                            complete["usage"]?.jsonObject?.let { emit(AgentEvent.Usage(it.int("input_tokens", 0), it.int("output_tokens", 0))) }
                        }
                        "error", "response.failed", "response.incomplete" -> error("模型未完成本次请求：${event.toString().take(1000)}")
                    }
                }
            }
            val source = response.body!!.source()
            while (result == null && !source.exhausted()) { currentCoroutineContext().ensureActive(); parser.line(source.readUtf8Line() ?: break) }
            result ?: throw IncompleteResponse()
        }
    }
    private suspend fun <T> cancellableCall(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            val response = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response) else response.close()
                    }
                })
            }
            response.use { block(it) }
        } finally { watcher.cancel() }
    }
}
