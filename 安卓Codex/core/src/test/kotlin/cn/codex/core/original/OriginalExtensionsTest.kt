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
    @Test fun versionedConfigWritePropagatesFailureWithoutAutomaticRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { Wire(scope).use { wire ->
            val api = OriginalCodexClient(wire.rpc)
            val write = async { runCatching { api.writeConfig(OriginalConfigSnapshot("/config.toml", "v7"), OriginalConfigEdits.plugin("demo@market", false)) } }
            val request = wire.read()
            assertEquals("config/batchWrite", request.getValue("method").jsonPrimitive.content)
            val params = request.getValue("params").jsonObject
            assertEquals("v7", params.getValue("expectedVersion").jsonPrimitive.content)
            assertEquals("/config.toml", params.getValue("filePath").jsonPrimitive.content)
            wire.fail(request.getValue("id"))
            assertTrue(write.await().isFailure)
            // wrapper 只发送一次；冲突不能改用新版本再提交用户尚未确认的内容。
            assertFalse(wire.reader.ready())
        } } finally { scope.cancel() }
    }
    @Test fun policyOverrideShowsEffectivePluginStateInsteadOfOptimisticToggle() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { wire ->
            val api = OriginalCodexClient(wire.rpc)
            val controller = OriginalExtensionsController(scope, "/project") { false }
            fun catalog(enabled: Boolean) = buildJsonObject { put("marketplaces", buildJsonArray { add(buildJsonObject {
                put("name", "market"); put("plugins", buildJsonArray { add(plugin(installed = true).let { JsonObject(it.summary + ("enabled" to JsonPrimitive(enabled))) }) })
            }) }) }
            suspend fun reload(version: String) {
                repeat(2) { val request = wire.read(); wire.reply(request.getValue("id"), catalog(true)) }
                val hooks = wire.read(); wire.reply(hooks.getValue("id"), buildJsonObject { put("data", buildJsonArray {}) })
                val config = wire.read(); wire.reply(config.getValue("id"), buildJsonObject { put("layers", buildJsonArray { add(buildJsonObject {
                    put("name", buildJsonObject { put("type", "user"); put("file", "/config.toml") }); put("version", version)
                }) }) })
            }
            withContext(dispatcher) { controller.bind(api); controller.refresh() }
            reload("v1")
            val first = withTimeout(3000) { controller.state.first { !it.busy && it.configSnapshot != null } }
            withContext(dispatcher) { controller.togglePlugin(first.plugins.single()) }
            val installed = wire.read(); wire.reply(installed.getValue("id"), catalog(true))
            val write = wire.read(); assertEquals("config/batchWrite", write.getValue("method").jsonPrimitive.content)
            assertEquals("v1", write.getValue("params").jsonObject.getValue("expectedVersion").jsonPrimitive.content)
            wire.reply(write.getValue("id"), buildJsonObject { put("status", "okOverridden"); put("overriddenMetadata", buildJsonObject { put("message", "管理员覆盖") }) })
            reload("v2")
            val effective = withTimeout(3000) { controller.state.first { !it.busy && it.configSnapshot?.version == "v2" } }
            assertTrue(effective.plugins.single().enabled)
            assertEquals("管理员覆盖", effective.error)
        } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun sessionMutationsCarryExplicitGoalOriginStableAttachmentKeysAndNullSection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { Wire(scope).use { wire ->
            val api = OriginalCodexClient(wire.rpc)
            val goal = async { api.setGoal("thread-1", "完成任务", "active", 1000) }
            val g = wire.read().also { assertEquals("thread/goal/set", it.getValue("method").jsonPrimitive.content) }
            assertEquals("user", g.getValue("params").jsonObject.getValue("origin").jsonPrimitive.content)
            wire.reply(g.getValue("id"), buildJsonObject {}); goal.await()
            val attachment = async { api.addAttachment("thread-1", "note", "stable-key", buildJsonObject { put("text", "正文") }) }
            val a = wire.read(); assertEquals("stable-key", a.getValue("params").jsonObject.getValue("identityKey").jsonPrimitive.content)
            wire.reply(a.getValue("id"), buildJsonObject {}); attachment.await()
            val move = async { api.moveSection("thread-1", null) }
            val m = wire.read(); assertEquals(JsonNull, m.getValue("params").jsonObject["sectionId"])
            wire.reply(m.getValue("id"), buildJsonObject {}); move.await()
            val revert = async { api.revert("thread-1", "turn-2") }
            val r = wire.read(); assertEquals("turn-2", r.getValue("params").jsonObject.getValue("beforeTurnId").jsonPrimitive.content)
            wire.reply(r.getValue("id"), buildJsonObject { put("itemsBackwardsCursor", "anchor") }); assertEquals("anchor", revert.await().jsonObject.getValue("itemsBackwardsCursor").jsonPrimitive.content)
        } } finally { scope.cancel() }
    }
    @Test fun standaloneCommandStopAcknowledgementWaitsForFinalResponseAndPreservesSplitUtf8() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val finished = mutableListOf<Boolean>()
        val lifecycle = object : OriginalMutationLifecycle { override fun begin() = "ticket"; override fun finish(ticket: String, confirmed: Boolean) { finished += confirmed } }
        try { Wire(scope).use { wire ->
            val controller = OriginalCommandController(scope, { false }, lifecycle)
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.run("echo 中文", "/project", false, 5000) }
            val start = wire.read(); assertEquals("command/exec", start.getValue("method").jsonPrimitive.content)
            val id = start.getValue("params").jsonObject.getValue("processId").jsonPrimitive.content
            val bytes = "中文".toByteArray(Charsets.UTF_8)
            withContext(dispatcher) {
                for (part in listOf(bytes.copyOfRange(0, 2), bytes.copyOfRange(2, bytes.size))) controller.output(buildJsonObject { put("processId", id); put("stream", "stdout"); put("deltaBase64", java.util.Base64.getEncoder().encodeToString(part)) })
                controller.stop()
            }
            val stop = wire.read(); assertEquals("command/exec/terminate", stop.getValue("method").jsonPrimitive.content)
            wire.reply(stop.getValue("id"), buildJsonObject {})
            val waiting = withTimeout(3000) { controller.state.first { !it.controlBusy && it.status.contains("等待命令结束") } }
            assertTrue(waiting.running)
            wire.reply(start.getValue("id"), buildJsonObject { put("exitCode", 0); put("stdout", ""); put("stderr", "") })
            val complete = withTimeout(3000) { controller.state.first { !it.running } }
            assertEquals("中文", complete.output); assertEquals(listOf(true), finished)
        } } finally { scope.cancel(); dispatcher.close() }
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
            val config = fresh.read(); assertEquals("config/read", config.getValue("method").jsonPrimitive.content)
            fresh.reply(config.getValue("id"), buildJsonObject {})
            val state = withTimeout(3000) { controller.state.first { !it.busy && it.plugins.isNotEmpty() } }
            assertEquals(1, state.plugins.size); assertTrue(state.plugins.single().installed)
            assertTrue(state.marketplaceErrors.any { it.contains("offline") })
            withContext(dispatcher) { controller.bind(null) }
            assertFalse(controller.state.value.connected); assertTrue(controller.state.value.plugins.isEmpty())
        } } } finally { scope.cancel(); dispatcher.close() }
    }
}
