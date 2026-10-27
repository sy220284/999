package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*

/** 按用户要求，本轮只提交测试源码，未执行。 */
class AppServerConnectionTest {
    private class Wire : Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val incoming = PipedInputStream(64 * 1024)
        val fromServer = PipedOutputStream(incoming)
        private val outgoing = PipedInputStream(64 * 1024)
        private val toServer = PipedOutputStream(outgoing)
        val requests = outgoing.bufferedReader()
        val rpc = AppServerConnection(incoming, toServer, scope, { fromServer.close() })
        suspend fun read(): JsonObject = withContext(Dispatchers.IO) { Json.parseToJsonElement(requests.readLine()).jsonObject }
        fun send(value: JsonObject) { fromServer.write((value.toString() + "\n").toByteArray()); fromServer.flush() }
        fun response(id: JsonElement, text: String) = send(buildJsonObject { put("id", id); put("result", JsonPrimitive(text)) })
        override fun close() { rpc.close(); requests.close(); scope.cancel() }
    }
    @Test fun concurrentResponsesMayArriveOutOfOrder() = runBlocking {
        Wire().use { wire ->
            val first = async { wire.rpc.request("first") }
            val second = async { wire.rpc.request("second") }
            val a = wire.read(); val b = wire.read()
            wire.response(b.getValue("id"), b.getValue("method").jsonPrimitive.content)
            wire.response(a.getValue("id"), a.getValue("method").jsonPrimitive.content)
            assertEquals("first", first.await().jsonPrimitive.content)
            assertEquals("second", second.await().jsonPrimitive.content)
        }
    }
    @Test fun approvalKeepsStringIdAndReaderContinuesWhileUserDecides() = runBlocking {
        Wire().use { wire ->
            val message = async { wire.rpc.messages.first() as ServerMessage.Request }
            wire.send(buildJsonObject { put("id", "approval-1"); put("method", "item/fileChange/requestApproval"); put("params", buildJsonObject {}) })
            val pending = message.await()
            val request = async { wire.rpc.request("account/read") }
            val frame = wire.read(); wire.response(frame.getValue("id"), "account")
            assertEquals("account", request.await().jsonPrimitive.content)
            wire.rpc.respond(pending.id, buildJsonObject { put("decision", "decline") })
            val answer = wire.read()
            assertEquals(JsonPrimitive("approval-1"), answer["id"])
            assertEquals("decline", answer.getValue("result").jsonObject.getValue("decision").jsonPrimitive.content)
        }
    }
    @Test fun closeFailsAllPendingRequests() = runBlocking {
        Wire().use { wire ->
            val pending = async { runCatching { wire.rpc.request("wait") } }
            wire.read(); wire.rpc.close()
            assertIs<IOException>(pending.await().exceptionOrNull())
        }
    }
    @Test fun cancellationDoesNotConsumeAnotherRequestsResponse() = runBlocking {
        Wire().use { wire ->
            val stale = async { wire.rpc.request("cancelled") }
            val oldFrame = wire.read(); stale.cancelAndJoin()
            val current = async { wire.rpc.request("current") }
            val newFrame = wire.read()
            wire.response(oldFrame.getValue("id"), "stale")
            wire.response(newFrame.getValue("id"), "fresh")
            assertEquals("fresh", current.await().jsonPrimitive.content)
        }
    }
    @Test fun timeoutDoesNotCloseHealthyConnection() = runBlocking {
        Wire().use { wire ->
            val timeout = async { runCatching { wire.rpc.request("wait", timeoutMs = 20) } }
            wire.read()
            assertIs<TimeoutCancellationException>(timeout.await().exceptionOrNull())
            val next = async { wire.rpc.request("next") }
            val frame = wire.read(); wire.response(frame.getValue("id"), "ok")
            assertEquals("ok", next.await().jsonPrimitive.content)
        }
    }
    @Test fun initializeWaitsForResponseThenAcknowledgesAndUnitRequestsOmitParams() = runBlocking {
        Wire().use { wire ->
            val initialize = async { OriginalCodexClient(wire.rpc).initialize() }
            val frame = wire.read()
            assertEquals("initialize", frame.getValue("method").jsonPrimitive.content)
            assertEquals(true, frame.getValue("params").jsonObject.getValue("capabilities").jsonObject.getValue("experimentalApi").jsonPrimitive.boolean)
            wire.response(frame.getValue("id"), "ready")
            assertEquals("initialized", wire.read().getValue("method").jsonPrimitive.content)
            initialize.await()
            val logout = async { OriginalCodexClient(wire.rpc).logout() }
            val out = wire.read(); assertFalse(out.containsKey("params")); wire.response(out.getValue("id"), "ok"); logout.await()
        }
    }
    @Test fun malformedTruncatedAndOversizedFramesFailInsteadOfHanging() = runBlocking {
        for (bytes in listOf("{", "[]\n", "{\"message\":\"${"x".repeat(100)}\"}\n")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val rpc = AppServerConnection(ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream(), scope, {}, maxFrameBytes = 64)
            val failure = runCatching { rpc.messages.first() }.exceptionOrNull()
            assertNotNull(failure)
            rpc.close(); scope.cancel()
        }
    }
    @Test fun rpcErrorsPreserveCodeAndDoNotPretendSuccess() = runBlocking {
        Wire().use { wire ->
            val result = async { runCatching { wire.rpc.request("unsupported") } }
            val frame = wire.read()
            wire.send(buildJsonObject { put("id", frame.getValue("id")); put("error", buildJsonObject { put("code", -32601); put("message", "unknown") }) })
            assertEquals(-32601, (result.await().exceptionOrNull() as RpcException).code)
        }
    }
    @Test fun completedItemReplacesDeltasWithoutDuplicatingAndHistoryKeepsUnknownTools() {
        val streamed = OriginalThreadView.delta(emptyList(), "a", "agentMessage", "你好")
        val full = OriginalThreadView.item(buildJsonObject { put("id", "a"); put("type", "agentMessage"); put("text", "你好世界") })
        val result = OriginalThreadView.upsert(streamed, full)
        assertEquals(1, result.size); assertEquals("你好世界", result.single().text)
        val raw = buildJsonObject { put("id", "new"); put("type", "futureTool"); put("custom", "preserved") }
        assertEquals(raw, OriginalThreadView.item(raw).data)
        val changes = buildJsonArray { add(buildJsonObject { put("path", "a.txt"); put("diff", "+new"); put("kind", buildJsonObject { put("type", "update"); put("move_path", "b.txt") }) }) }
        val patched = OriginalThreadView.patch(emptyList(), "file", changes).single()
        assertTrue(patched.text.contains("a.txt → b.txt")); assertTrue(patched.text.contains("+new"))
    }
    @Test fun saturatedNotificationQueueFailsPendingRpcInsteadOfDeadlockingReader() = runBlocking {
        Wire().use { wire ->
            val pending = async { runCatching { wire.rpc.request("must-not-hang", timeoutMs = 5000) } }
            wire.read() // 留有未返回的请求；暂时不消费通知流
            for (i in 0 until 100) {
                val sent = runCatching { wire.send(buildJsonObject { put("method", "test/flood"); put("params", buildJsonObject { put("n", i) }) }) }
                if (sent.isFailure) break
            }
            val result = withTimeout(3000) { pending.await() }
            assertIs<IOException>(result.exceptionOrNull())
            assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
        }
    }
    @Test fun invalidUtf8IsNotSilentlyReplacedInsideJsonMessages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // JSON 字符串中插入非法 UTF-8 字节；原版协议只能接收严格 UTF-8。
        val data = byteArrayOf(123, 34, 109, 101, 116, 104, 111, 100, 34, 58, 34, 0xC3.toByte(), 34, 125, 10)
        val rpc = AppServerConnection(ByteArrayInputStream(data), ByteArrayOutputStream(), scope, {})
        try {
            assertFails { withTimeout(3000) { rpc.messages.first() } }
        } finally { rpc.close(); scope.cancel() }
    }

    @Test fun closingDiscardsQueuedEventsAndPreservesTheIoFailureForAnActiveCollector() = runBlocking {
        Wire().use { wire ->
            wire.send(buildJsonObject { put("method", "thread/started"); put("params", buildJsonObject { put("threadId", "old") }) })
            val barrier = async { wire.rpc.request("barrier") }
            val frame = wire.read(); wire.response(frame.getValue("id"), "read")
            barrier.await() // reader 已处理前一帧，通知仍在队列中。
            wire.rpc.close()
            val received = mutableListOf<ServerMessage>()
            val failure = runCatching { withTimeout(3000) { wire.rpc.messages.collect { received += it } } }.exceptionOrNull()
            assertTrue(received.isEmpty()); assertIs<IOException>(failure)
            assertTrue(currentCoroutineContext().isActive)
        }
    }
    @Test fun overflowingQueueDoesNotDeliverPreviouslyQueuedApprovalsAfterDisconnect() = runBlocking {
        Wire().use { wire ->
            val pending = async { runCatching { wire.rpc.request("pending") } }
            wire.read()
            for (i in 0..70) {
                if (runCatching { wire.send(buildJsonObject { put("id", "old-$i"); put("method", "item/fileChange/requestApproval") }) }.isFailure) break
            }
            assertIs<IOException>(withTimeout(3000) { pending.await() }.exceptionOrNull())
            val received = mutableListOf<ServerMessage>()
            val failure = runCatching { withTimeout(3000) { wire.rpc.messages.collect { received += it } } }.exceptionOrNull()
            assertTrue(received.isEmpty()); assertIs<IOException>(failure)
        }
    }

}
