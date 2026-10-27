package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 行为测试源码，当前未执行；可控 stdio 对端，无真实网络或账号调用。 */
class OriginalRemainingFlowsTest {
    private class Wire(scope: CoroutineScope) : Closeable {
        private val input = PipedInputStream(65536); private val server = PipedOutputStream(input)
        private val output = PipedInputStream(65536); val reader = output.bufferedReader()
        val rpc = AppServerConnection(input, PipedOutputStream(output), scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(request: JsonObject, value: JsonObject) = send(buildJsonObject { put("id", request.getValue("id")); put("result", value) })
        fun fail(request: JsonObject) = send(buildJsonObject { put("id", request.getValue("id")); put("error", buildJsonObject { put("code", -32000); put("message", "结果待核查") }) })
        private fun send(value: JsonObject) { server.write((value.toString()+"\n").toByteArray()); server.flush() }
        override fun close() { rpc.close(); reader.close() }
    }
    private fun scenario(block: suspend (CoroutineDispatcher, CoroutineScope, Wire) -> Unit) = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(); val scope = CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { block(dispatcher,scope,it) } } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun failedCreditRequestReusesItsPersistedKeyAndCreditAfterRebinding() = scenario { dispatcher, scope, wire ->
        val store = object : OriginalCreditRequestStore {
            var saved: OriginalCreditRequest? = null
            override fun read() = saved
            override fun write(request: OriginalCreditRequest) { saved=request }
            override fun clear() { saved=null }
        }
        val controller=OriginalServicesController(scope,{null},{false},OriginalMutationLifecycle.None,store)
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.consumeCredit("credit-A") }
        val request=wire.read(); val params=request.getValue("params").jsonObject
        assertEquals(params.string("idempotencyKey"),store.saved?.key)
        wire.fail(request); withTimeout(3000) { controller.operations.state.first { !it.busy && it.error!=null } }
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.consumeCredit("credit-B") }
        val retry=wire.read().getValue("params").jsonObject
        assertEquals(params,retry); assertEquals("credit-A",retry.string("creditId"))
    }
    @Test fun importCompletionBeforeAckSurvivesLateProgressAndWaitsForHistoryRead() = scenario { dispatcher,scope,wire ->
        val controller=OriginalMigrationController(scope,"/project",{false},OriginalMutationLifecycle.None)
        val item=buildJsonObject { put("itemType","CONFIG");put("description","配置");put("cwd",JsonNull);put("details",JsonNull) }
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.detect(false,"/project","","","","") }
        wire.reply(wire.read(),buildJsonObject { put("items",buildJsonArray { add(item) });put("connectors",buildJsonArray {}) })
        withTimeout(3000) { controller.operations.state.first { !it.busy } }
        withContext(dispatcher) { controller.importSelected(listOf(item),"","","") }
        val request=wire.read()
        val completed=buildJsonObject { put("importId","import-A");put("itemTypeResults",buildJsonArray { add(buildJsonObject { put("itemType","CONFIG");put("successes",buildJsonArray {});put("failures",buildJsonArray { add(buildJsonObject { put("message","冲突") }) }) }) }) }
        withContext(dispatcher) { controller.importProgress(completed,true);controller.importProgress(buildJsonObject { put("importId","import-A");put("itemTypeResults",buildJsonArray {}) },false) }
        wire.reply(request,buildJsonObject { put("importId","import-A") })
        val history=wire.read(); assertEquals("externalAgentConfig/import/readHistories",history.string("method"))
        wire.reply(history,buildJsonObject { put("data",buildJsonArray {});put("connectors",buildJsonArray {}) })
        val end=withTimeout(3000) { controller.operations.state.first { !it.busy } }
        assertTrue(end.notice.contains("失败"));assertEquals(1,controller.state.value.runs.getValue("import-A").results.size)
    }
    @Test fun newerGatewayNotificationWinsOverAnOlderReadReply() = scenario { dispatcher,scope,wire ->
        val controller=OriginalServicesController(scope,{null},{false},OriginalMutationLifecycle.None)
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc));controller.gatewayStatus() }
        val request=wire.read()
        withContext(dispatcher) { controller.gatewayChanged(buildJsonObject { put("providerId","gateway");put("status","succeeded");put("authUrl",JsonNull) }) }
        wire.reply(request,buildJsonObject { put("providerId","gateway");put("status","started") })
        withTimeout(3000) { controller.operations.state.first { !it.busy } }
        assertEquals("succeeded",controller.state.value.gateway?.string("status"))
    }
    @Test fun staleLibraryReadCannotRestoreDataAfterDisconnect() = scenario { dispatcher,scope,wire ->
        val controller=OriginalLibraryController(scope,"/project",{null},{false},OriginalMutationLifecycle.None)
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc));controller.refreshShares() }
        val request=wire.read()
        withContext(dispatcher) { controller.bind(null) }
        wire.reply(request,buildJsonObject { put("data",buildJsonArray { add(buildJsonObject { put("plugin",buildJsonObject { put("id","stale") }) }) }) })
        assertFalse(controller.operations.state.value.connected);assertTrue(controller.state.value.shares.isEmpty())
    }
    @Test fun updatingShareContentCanLeaveExistingVisibilityAndRecipientsUntouched() = scenario { _,_,wire ->
        val pending=CoroutineScope(currentCoroutineContext()).async { OriginalCodexClient(wire.rpc).savePluginShare("/plugin","remote",null,null) }
        val request=wire.read(); val params=request.getValue("params").jsonObject
        assertEquals("remote",params.string("remotePluginId"));assertFalse(params.containsKey("discoverability"));assertFalse(params.containsKey("shareTargets"))
        wire.reply(request,buildJsonObject { put("remotePluginId","remote");put("shareUrl","https://example.invalid/share") });pending.await()
    }
    @Test fun busyTaskRejectsMutationBeforeCreatingARequestOrJournalEntry() = scenario { dispatcher,scope,wire ->
        var starts=0
        val lifecycle=object:OriginalMutationLifecycle {
            override fun begin():String { starts++;return "ticket" }
            override fun finish(ticket:String,confirmed:Boolean)=Unit
        }
        val controller=OriginalLibraryController(scope,"/project",{null},{true},lifecycle)
        withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc));controller.removeMarket("market") }
        assertEquals(0,starts);assertFalse(wire.reader.ready());assertNotNull(controller.operations.state.value.error)
    }
}
