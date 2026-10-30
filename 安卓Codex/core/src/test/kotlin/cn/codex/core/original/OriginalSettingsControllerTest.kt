package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 行为测试源码，当前未运行。真实 stdio 编解码对端，不调用账户或模型。 */
class OriginalSettingsControllerTest {
    private class Wire(scope: CoroutineScope) : Closeable {
        val input = PipedInputStream(65536)
        val server = PipedOutputStream(input)
        val receiver = PipedInputStream(65536)
        val reader = receiver.bufferedReader()
        val rpc = AppServerConnection(input, PipedOutputStream(receiver), scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(request: JsonObject, result: JsonElement) { server.write((buildJsonObject { put("id", request.getValue("id")); put("result", result) }.toString() + "\n").toByteArray()); server.flush() }
        override fun close() { rpc.close(); reader.close() }
    }
    private fun feature(enabled: Boolean = false) = buildJsonObject { put("name", "memories"); put("stage", "beta"); put("enabled", enabled) }
    private fun page(cursor: String? = null) = buildJsonObject { put("data", buildJsonArray { add(feature()) }); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull) }
    private fun exercise(block: suspend (CoroutineDispatcher, CoroutineScope, Wire) -> Unit) = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { block(dispatcher, scope, it) } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun threadUsageCarriesTheSelectedThreadAndDoesNotReadOtherEndpoints() = exercise { dispatcher, scope, wire ->
        val controller = OriginalSettingsController(scope, "/project", { false }, { "thread-7" })
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh(OriginalSettingsSection.ThreadUsage) }
        val request = wire.read()
        assertEquals("account/usage/read", request.getValue("method").jsonPrimitive.content)
        assertEquals("thread-7", request.getValue("params").jsonObject.getValue("threadId").jsonPrimitive.content)
        wire.reply(request, buildJsonObject { put("summary", buildJsonObject { put("lifetimeTokens", 42) }) })
        val loaded = withTimeout(3000) { controller.state.first { !it.busy && it.usage != null } }
        assertEquals("thread-7", loaded.usageThread)
        assertFalse(wire.reader.ready())
    }
    @Test fun ignoredFeatureMutationIsNotReportedAsSuccessOrRetried() = exercise { dispatcher, scope, wire ->
        val outcomes = mutableListOf<Boolean>()
        val lifecycle = object : OriginalMutationLifecycle { override fun begin() = "ticket"; override fun finish(ticket: String, confirmed: Boolean) { outcomes += confirmed } }
        val controller = OriginalSettingsController(scope, "/project", { false }, { null }, lifecycle)
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh(OriginalSettingsSection.Features) }
        wire.reply(wire.read(), page()); withTimeout(3000) { controller.state.first { !it.busy && it.features.isNotEmpty() } }
        withContext(dispatcher) { controller.toggle(feature(), true) }
        val request = wire.read(); assertEquals("experimentalFeature/enablement/set", request.getValue("method").jsonPrimitive.content)
        wire.reply(request, buildJsonObject { put("enablement", buildJsonObject {}) })
        val failed = withTimeout(3000) { controller.state.first { !it.busy && it.error != null } }
        assertFalse(failed.features.single().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(listOf(false), outcomes); assertFalse(wire.reader.ready())
    }
    @Test fun repeatedPaginationCursorStopsAndPreservesTheOriginalThread() = exercise { dispatcher, scope, wire ->
        var thread = "thread-A"
        val controller = OriginalSettingsController(scope, "/project", { false }, { thread })
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh(OriginalSettingsSection.Features) }
        wire.reply(wire.read(), page("repeat")); withTimeout(3000) { controller.state.first { !it.busy && it.nextCursor != null } }
        thread = "thread-B"
        withContext(dispatcher) { controller.more() }
        val request = wire.read(); assertEquals("thread-A", request.getValue("params").jsonObject.getValue("threadId").jsonPrimitive.content)
        wire.reply(request, page("repeat"))
        val result = withTimeout(3000) { controller.state.first { !it.busy && it.error != null } }
        assertNull(result.nextCursor); assertEquals(1, result.features.size)
    }
    @Test fun rebindingDropsLateResultsAndTaskLocksBlockMutation() = exercise { dispatcher, scope, wire ->
        val controller = OriginalSettingsController(scope, "/project", { true }, { null })
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh(OriginalSettingsSection.Features) }
        val late = wire.read()
        withContext(dispatcher) { controller.bind(null) }
        wire.reply(late, page())
        delay(30)
        assertFalse(controller.state.value.connected); assertTrue(controller.state.value.features.isEmpty())
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.refresh(OriginalSettingsSection.Features) }
        wire.reply(wire.read(), page()); withTimeout(3000) { controller.state.first { !it.busy && it.features.isNotEmpty() } }
        withContext(dispatcher) { controller.toggle(feature(), true) }
        assertNotNull(controller.state.value.error); assertFalse(wire.reader.ready())
    }
}
