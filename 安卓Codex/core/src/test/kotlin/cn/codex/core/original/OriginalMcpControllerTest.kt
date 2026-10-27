package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 测试源码，未执行。使用实际双向 RPC 编解码，模拟配置及运行状态。 */
class OriginalMcpControllerTest {
    private class Wire(scope: CoroutineScope) : Closeable {
        private val input = PipedInputStream(65536)
        private val server = PipedOutputStream(input)
        private val receiver = PipedInputStream(65536)
        private val output = PipedOutputStream(receiver)
        private val reader = receiver.bufferedReader()
        val rpc = AppServerConnection(input, output, scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(request: JsonObject, result: JsonElement) = send(buildJsonObject { put("id", request.getValue("id")); put("result", result) })
        fun fail(request: JsonObject, message: String) = send(buildJsonObject { put("id", request.getValue("id")); put("error", buildJsonObject { put("code", -32000); put("message", message) }) })
        private fun send(message: JsonObject) { server.write((message.toString() + "\n").toByteArray()); server.flush() }
        fun pending() = reader.ready()
        override fun close() { rpc.close(); reader.close() }
    }
    private val configured = buildJsonObject { put("url", "https://example.com/mcp"); put("enabled", true) }
    private fun config(version: String) = buildJsonObject {
        put("config", buildJsonObject { put("mcp_servers", buildJsonObject { put("demo", configured) }) })
        put("layers", buildJsonArray { add(buildJsonObject {
            put("name", buildJsonObject { put("type", "user"); put("file", "/config.toml") })
            put("version", version); put("config", buildJsonObject { put("mcp_servers", buildJsonObject { put("demo", configured) }) })
        }) })
    }
    private fun page(next: String? = null, name: String = "demo") = buildJsonObject {
        put("data", buildJsonArray { add(buildJsonObject { put("name", name); put("runtimeStatus", "connected"); put("pluginId", JsonNull) }) })
        put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
    }
    private suspend fun load(wire: Wire, version: String, next: String? = null) {
        val read = wire.read(); assertEquals("config/read", read.getValue("method").jsonPrimitive.content)
        assertEquals("/project", read.getValue("params").jsonObject.getValue("cwd").jsonPrimitive.content)
        wire.reply(read, config(version))
        val inventory = wire.read(); assertEquals("mcpServerStatus/list", inventory.getValue("method").jsonPrimitive.content)
        wire.reply(inventory, page(next))
    }
    @Test fun savedConfigAndFailedReconnectRemainDistinctAndNoImplicitRetryOccurs() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { wire ->
            val controller = OriginalMcpController(scope, "/project", { false }, { "thread-1" })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh() }
            load(wire, "v1")
            val loaded = withTimeout(3000) { controller.state.first { !it.busy && it.snapshot != null } }
            withContext(dispatcher) { controller.toggle(loaded.entries.single(), loaded.snapshot!!) }
            val write = wire.read()
            assertEquals("config/batchWrite", write.getValue("method").jsonPrimitive.content)
            assertEquals("v1", write.getValue("params").jsonObject.getValue("expectedVersion").jsonPrimitive.content)
            wire.reply(write, buildJsonObject { put("status", "ok") })
            val reload = wire.read(); assertEquals("config/mcpServer/reload", reload.getValue("method").jsonPrimitive.content)
            wire.fail(reload, "connect failed")
            load(wire, "v2")
            val after = withTimeout(3000) { controller.state.first { !it.busy && it.snapshot?.version == "v2" } }
            assertTrue(after.notice.contains("已保存")); assertTrue(after.notice.contains("重新连接失败"))
            assertTrue(after.entries.single().enabled) // 读取真实值，不能乐观地把开关翻转。
            assertFalse(wire.pending())
        } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun conflictLeavesEditorOpenAndReconnectIsNeverSent() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { wire ->
            val controller = OriginalMcpController(scope, "/project", { false }, { null })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh() }
            load(wire, "v1"); withTimeout(3000) { controller.state.first { !it.busy && it.snapshot != null } }
            withContext(dispatcher) { controller.edit("demo") }
            val editor = controller.state.value.editor!!
            withContext(dispatcher) { controller.save(editor, "demo", configured) }
            wire.fail(wire.read(), "Configuration was modified since last read")
            val after = withTimeout(3000) { controller.state.first { !it.busy && it.error != null } }
            assertNotNull(after.editor); assertEquals("v1", after.snapshot?.version)
            assertFalse(wire.pending())
        } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun nextPagePreservesThreadAndStopsRepeatedCursors() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { wire ->
            val controller = OriginalMcpController(scope, "/project", { false }, { "thread-1" })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh() }
            load(wire, "v1", "next"); withTimeout(3000) { controller.state.first { !it.busy && it.nextCursor != null } }
            withContext(dispatcher) { controller.more() }
            val request = wire.read(); val params = request.getValue("params").jsonObject
            assertEquals("next", params.getValue("cursor").jsonPrimitive.content)
            assertEquals("thread-1", params.getValue("threadId").jsonPrimitive.content)
            wire.reply(request, page("next", "second"))
            val after = withTimeout(3000) { controller.state.first { !it.busy && it.inventory.size == 2 } }
            assertNull(after.nextCursor); assertNotNull(after.inventoryError)
        } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun reboundConnectionCannotReuseEditorOrCommitItsVersion() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { old -> Wire(scope).use { fresh ->
            val controller = OriginalMcpController(scope, "/project", { false }, { null })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(old.rpc)); controller.refresh() }
            load(old, "v1"); withTimeout(3000) { controller.state.first { !it.busy && it.snapshot != null } }
            withContext(dispatcher) { controller.edit("demo") }
            val editor = controller.state.value.editor!!
            withContext(dispatcher) { controller.bind(OriginalCodexClient(fresh.rpc)); controller.save(editor, "demo", configured) }
            val after = withTimeout(3000) { controller.state.first { !it.busy && it.error != null } }
            assertNull(after.editor); assertFalse(fresh.pending())
        } } } finally { scope.cancel(); dispatcher.close() }
    }
}
