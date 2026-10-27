package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 测试源码；本阶段未执行。 */
class OriginalExtensionsTest {
    private fun plugin(id: String = "demo@market", policy: String = "AVAILABLE", installed: Boolean = false) = OriginalPlugin("market", null, buildJsonObject {
        put("id", id); put("name", "demo"); put("availability", "AVAILABLE"); put("installPolicy", policy); put("installed", installed)
    })
    @Test fun adminAndUnknownPoliciesCannotBeInstalledByGenericSuccessButtons() {
        assertNull(plugin().installBlock)
        assertNotNull(plugin(policy = "NOT_AVAILABLE").installBlock)
        assertNotNull(plugin(policy = "FUTURE_POLICY").installBlock)
        val disabled = plugin().let { it.copy(summary = JsonObject(it.summary + ("availability" to JsonPrimitive("DISABLED_BY_ADMIN")))) }
        assertNotNull(disabled.installBlock)
        assertNotNull(plugin(installed = true).installBlock)
    }
    @Test fun marketplaceIdentitySurvivesDuplicateNamesAndNullInterfaceDoesNotEraseMetadata() {
        val first = plugin().let { it.copy(summary = JsonObject(it.summary + ("interface" to buildJsonObject { put("displayName", "有名字") }))) }
        val second = first.copy(marketplace = "other", marketplacePath = "/second/market.json")
        assertNotEquals(first.key, second.key)
        assertEquals("market", first.locator().getValue("remoteMarketplaceName").jsonPrimitive.content)
        assertFalse(first.locator().containsKey("marketplacePath"))
        assertEquals("/second/market.json", second.locator().getValue("marketplacePath").jsonPrimitive.content)
        val installed = first.copy(summary = JsonObject(first.summary + mapOf("installed" to JsonPrimitive(true), "interface" to JsonNull)))
        val merged = OriginalExtensions.combine(OriginalCatalog(listOf(first, second), listOf("catalog")), OriginalCatalog(listOf(installed), listOf("catalog")))
        assertEquals(2, merged.plugins.size); assertEquals("有名字", merged.plugins.first().displayName)
        assertTrue(merged.plugins.first().installed); assertEquals(1, merged.errors.size)
    }
    @Test fun hookCompletionUpdatesOneRunAndKeepsFailureOutput() {
        fun run(status: String) = OriginalExtensions.hookRun(buildJsonObject { put("id", "r1"); put("eventName", "preToolUse"); put("status", status); put("entries", buildJsonArray { add(buildJsonObject { put("kind", "stderr"); put("text", "真实错误") }) }) })
        val result = OriginalThreadView.upsert(listOf(run("running")), run("failed"))
        assertEquals(1, result.size); assertEquals("failed", result.single().status); assertTrue(result.single().text.contains("真实错误"))
    }
    private class Wire(scope: CoroutineScope) : Closeable {
        private val input = PipedInputStream(65536)
        private val server = PipedOutputStream(input)
        private val receiver = PipedInputStream(65536)
        private val output = PipedOutputStream(receiver)
        val reader = receiver.bufferedReader()
        val rpc = AppServerConnection(input, output, scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(id: JsonElement, result: JsonElement) { server.write((buildJsonObject { put("id", id); put("result", result) }.toString() + "\n").toByteArray()); server.flush() }
        fun fail(id: JsonElement) { server.write((buildJsonObject { put("id", id); put("error", buildJsonObject { put("code", -32000); put("message", "offline") }) }.toString() + "\n").toByteArray()); server.flush() }
        override fun close() { rpc.close(); reader.close() }
    }
    @Test fun pluginRpcPreservesLocatorAttemptIdAndUninstallIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { Wire(scope).use { wire ->
            val api = OriginalCodexClient(wire.rpc)
            val install = async { api.installPlugin(plugin(), "attempt-1") }
            val request = wire.read(); val params = request.getValue("params").jsonObject
            assertEquals("plugin/install", request.getValue("method").jsonPrimitive.content)
            assertEquals("attempt-1", params.getValue("installAttemptId").jsonPrimitive.content)
            assertEquals("market", params.getValue("remoteMarketplaceName").jsonPrimitive.content)
            wire.reply(request.getValue("id"), buildJsonObject { put("appsNeedingAuth", buildJsonArray {}) }); install.await()
            val uninstall = async { api.uninstallPlugin("demo@market") }
            val removal = wire.read(); assertEquals("demo@market", removal.getValue("params").jsonObject.getValue("pluginId").jsonPrimitive.content)
            wire.reply(removal.getValue("id"), buildJsonObject {}); uninstall.await()
        } } finally { scope.cancel() }
    }
    @Test fun offlineCatalogStillLoadsInstalledHooksAndReconnectIgnoresOldResponse() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { old -> Wire(scope).use { fresh ->
            val controller = OriginalExtensionsController(scope, "/project") { false }
            withContext(dispatcher) { controller.bind(OriginalCodexClient(old.rpc)); controller.refresh() }
            val stale = old.read()
            withContext(dispatcher) { controller.bind(OriginalCodexClient(fresh.rpc)); controller.refresh() }
            old.reply(stale.getValue("id"), buildJsonObject { put("marketplaces", buildJsonArray {}) })
            val catalog = fresh.read(); fresh.fail(catalog.getValue("id"))
            val installed = fresh.read(); assertEquals("plugin/installed", installed.getValue("method").jsonPrimitive.content)
            fresh.reply(installed.getValue("id"), buildJsonObject { put("marketplaces", buildJsonArray { add(buildJsonObject { put("name", "market"); put("plugins", buildJsonArray { add(plugin(installed = true).summary) }) }) }) })
            val hooks = fresh.read(); assertEquals("hooks/list", hooks.getValue("method").jsonPrimitive.content)
            fresh.reply(hooks.getValue("id"), buildJsonObject { put("data", buildJsonArray {}) })
            val state = withTimeout(3000) { controller.state.first { !it.busy && it.plugins.isNotEmpty() } }
            assertEquals(1, state.plugins.size); assertTrue(state.plugins.single().installed)
            assertTrue(state.marketplaceErrors.any { it.contains("offline") })
            withContext(dispatcher) { controller.bind(null) }
            assertFalse(controller.state.value.connected); assertTrue(controller.state.value.plugins.isEmpty())
        } } } finally { scope.cancel(); dispatcher.close() }
    }
}
