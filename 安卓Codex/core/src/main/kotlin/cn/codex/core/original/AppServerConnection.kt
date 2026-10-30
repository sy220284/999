package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.util.concurrent.atomic.AtomicReference
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
    private val closed = AtomicReference<Throwable?>(null)
    private val writeLock = Any()
    private data class BufferedMessage(val value: ServerMessage, val size: Int)
    private val bufferedBytes = AtomicLong()
    private val maxBufferedBytes = 32L * 1024 * 1024
    private val incoming = Channel<BufferedMessage>(64, onUndeliveredElement = { item -> bufferedBytes.addAndGet(-item.size.toLong()); Unit })
    // 有界帧数 + 有界字节数：解码后立即释放队列预算，杜绝通知洪泛拖垮手机内存。
    val messages = incoming.receiveAsFlow().map { item ->
        bufferedBytes.addAndGet(-item.size.toLong())
        closed.get()?.let { throw it }
        item.value
    }.catch { failure ->
        currentCoroutineContext().ensureActive()
        throw (closed.get() ?: failure)
    }
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
                    // 不能阻塞协议 reader：否则 UI 回调等待 RPC 响应时，满队列会造成双向死锁。
                    // 保留有界队列；处理速度不足时明确断开并清理 pending，绝不无限积压。
                    val event = if (id != null && id != JsonNull) ServerMessage.Request(id, method, params)
                        else ServerMessage.Notification(method, params)
                    val size = line.toByteArray(Charsets.UTF_8).size
                    val total = bufferedBytes.addAndGet(size.toLong())
                    if (total > maxBufferedBytes || !incoming.trySend(BufferedMessage(event, size)).isSuccess) {
                        bufferedBytes.addAndGet(-size.toLong())
                        throw IOException("原版通知队列超过数量或字节预算，已断开连接以避免协议死锁")
                    }
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

    suspend fun request(method: String, params: JsonElement? = null, timeoutMs: Long = 60_000, onRequestId: (JsonElement) -> Unit = {}): JsonElement {
        check(closed.get() == null) { "原版核心未连接" }
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
                onRequestId(id)
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
        check(closed.get() == null) { "原版核心未连接" }
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
            if (value == 10) return Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            if (bytes.size() >= maxFrameBytes) throw IOException("原版核心协议帧超过上限")
            bytes.write(value)
        }
    }
    private fun shutdown(cause: Throwable) {
        if (!closed.compareAndSet(null, cause)) return
        pending.values.forEach { it.completeExceptionally(cause) }; pending.clear()
        // close 会保留已排队帧；断连必须丢弃旧审批/通知并释放队列预算。
        // Flow 向仍活跃的宿主保留原始错误，宿主据此清理连接与界面状态。
        incoming.cancel(CancellationException("原版协议已结束", cause))
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
