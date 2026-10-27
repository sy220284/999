package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 上游 stdio：每行一个 JSON 对象；stdout 只能承载协议，stderr 由宿主另行排空。 */
class AppServerConnection(
    private val input: InputStream,
    private val output: OutputStream,
    parent: CoroutineScope,
    private val terminate: () -> Unit,
    private val maxFrameBytes: Int = 16 * 1024 * 1024
) : Closeable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val ids = AtomicLong()
    private val closed = AtomicBoolean()
    private val writeLock = Any()
    private val incoming = Channel<ServerMessage>(64)
    // 单一消费者按顺序处理事件；审批 UI 必须把请求加入队列后立即返回，不能阻塞此流。
    val messages = incoming.receiveAsFlow()
    private val reader = scope.launch {
        try {
            val buffered = BufferedInputStream(input)
            while (isActive) {
                val line = readFrame(buffered) ?: throw EOFException("原版核心已关闭协议连接")
                if (line.isBlank()) continue
                val message = Json.parseToJsonElement(line) as? JsonObject
                    ?: throw IOException("原版核心返回了非对象协议帧")
                val method = message["method"]?.jsonPrimitive?.contentOrNull
                val id = message["id"]
                if (method != null) {
                    val params = message["params"] ?: JsonNull
                    incoming.send(if (id != null && id != JsonNull) ServerMessage.Request(id, method, params)
                        else ServerMessage.Notification(method, params))
                } else if (id != null && id != JsonNull) {
                    val waiter = pending.remove(id.toString()) ?: continue
                    val error = message["error"] as? JsonObject
                    if (error != null) waiter.completeExceptionally(RpcException(
                        error["code"]?.jsonPrimitive?.intOrNull ?: -32603,
                        error["message"]?.jsonPrimitive?.contentOrNull ?: "原版核心请求失败"))
                    else if (message.containsKey("result")) waiter.complete(message.getValue("result"))
                    else waiter.completeExceptionally(IOException("响应缺少 result/error"))
                } else throw IOException("原版核心返回了无方法和编号的协议帧")
            }
        } catch (error: Throwable) {
            shutdown(error)
        }
    }

    private val lifecycle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { shutdown(IOException("连接所属任务已结束")) }
    }

    suspend fun request(method: String, params: JsonElement? = null, timeoutMs: Long = 60_000): JsonElement {
        check(!closed.get()) { "原版核心未连接" }
        val id = JsonPrimitive(ids.incrementAndGet())
        val waiter = CompletableDeferred<JsonElement>()
        pending[id.toString()] = waiter
        val writing = AtomicBoolean(true)
        val writeDeadline = scope.launch {
            delay(timeoutMs)
            if (writing.get()) shutdown(IOException("原版核心写入超时，连接已关闭；不会自动重放任务"))
        }
        try {
            return withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { send(buildJsonObject { put("id", id); put("method", method); if (params != null) put("params", params) }) }
                writing.set(false)
                waiter.await()
            }
        } finally {
            writing.set(false); writeDeadline.cancel()
            pending.remove(id.toString(), waiter)
            waiter.cancel()
        }
    }
    suspend fun notify(method: String, params: JsonElement? = null) = withContext(Dispatchers.IO) {
        send(buildJsonObject { put("method", method); if (params != null) put("params", params) })
    }
    suspend fun respond(id: JsonElement, result: JsonElement) = withContext(Dispatchers.IO) {
        send(buildJsonObject { put("id", id); put("result", result) })
    }
    suspend fun reject(id: JsonElement, code: Int = -32601, message: String = "Android 客户端尚未实现此请求") = withContext(Dispatchers.IO) {
        send(buildJsonObject { put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) }) })
    }
    private fun send(frame: JsonObject) = synchronized(writeLock) {
        check(!closed.get()) { "原版核心未连接" }
        val bytes = frame.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFrameBytes) { "协议请求过大" }
        output.write(bytes); output.write(10); output.flush()
    }
    private fun readFrame(stream: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = stream.read()
            if (value < 0) {
                if (bytes.size() != 0) throw EOFException("原版核心在协议帧结束前退出")
                return null
            }
            if (value == 10) return bytes.toString(Charsets.UTF_8.name())
            if (bytes.size() >= maxFrameBytes) throw IOException("原版核心协议帧超过上限")
            bytes.write(value)
        }
    }
    private fun shutdown(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(cause) }; pending.clear()
        incoming.close(cause)
        // 必须先终止进程再关管道，防止另一个线程持有输入流锁时 close 永久等待。
        runCatching(terminate)
        runCatching { output.close() }; runCatching { input.close() }
        owner.cancel()
    }
    override fun close() { shutdown(IOException("原版核心连接已关闭")) }
}

sealed interface ServerMessage {
    data class Notification(val method: String, val params: JsonElement) : ServerMessage
    data class Request(val id: JsonElement, val method: String, val params: JsonElement) : ServerMessage
}
class RpcException(val code: Int, message: String) : IOException(message)
