package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 协议行为测试源码，本阶段不执行编译型测试。 */
class OriginalExperimentalFlowsTest {
    private class Wire(scope: CoroutineScope) : Closeable {
        private val input = PipedInputStream(65536)
        private val server = PipedOutputStream(input)
        private val requests = PipedInputStream(65536)
        private val reader = requests.bufferedReader()
        val rpc = AppServerConnection(input,PipedOutputStream(requests),scope,{ server.close() })
        suspend fun read() = withTimeout(3000) { runInterruptible(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject } }
        fun reply(request: JsonObject) { server.write((buildJsonObject { put("id",request.getValue("id"));put("result",buildJsonObject {}) }.toString()+"\n").toByteArray());server.flush() }
        override fun close() { rpc.close();reader.close() }
    }
    @Test fun processAcknowledgementDoesNotFinishForegroundTaskButEarlyExitIsRetained() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val outcomes=mutableListOf<Boolean>()
        val lifecycle=object:OriginalMutationLifecycle {
            override fun begin()="ticket"
            override fun finish(ticket:String,confirmed:Boolean){outcomes+=confirmed}
        }
        try { Wire(scope).use { wire ->
            val controller=OriginalCommandController(scope,{false},lifecycle)
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.run("pwd","/p",false,60000,experimental=true)}
            val request=wire.read();assertEquals("process/spawn",request.string("method"))
            val id=request.getValue("params").jsonObject.string("processHandle")
            wire.reply(request)
            withContext(dispatcher){ assertTrue(controller.state.value.running);assertTrue(outcomes.isEmpty())
                controller.processExited(buildJsonObject { put("processHandle",id);put("exitCode",0);put("stdout","done");put("stderr","") }) }
            withTimeout(3000){controller.state.first{!it.running}}
            assertEquals(listOf(true),outcomes);assertEquals("done",controller.state.value.output)
        } } finally { scope.cancel();dispatcher.close() }
    }
    @Test fun realtimeClosedBeforeStartAcknowledgementDoesNotReopenSession() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalRealtimeController(scope,{false})
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.start("t",false)}
            val request=wire.read();assertEquals("thread/realtime/start",request.string("method"))
            assertEquals("websocket",request.getValue("params").jsonObject.getValue("transport").jsonObject.string("type"))
            withContext(dispatcher){controller.notification("thread/realtime/closed",buildJsonObject { put("threadId","t");put("reason","done") });assertFalse(controller.acceptsStarted("t"))}
            wire.reply(request)
            withTimeout(3000){controller.state.first{!it.running}}
            assertFalse(controller.state.value.ready)
        } } finally { scope.cancel();dispatcher.close() }
    }
    @Test fun realtimeStopAcknowledgementKeepsWaitingUntilClosedNotification() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalRealtimeController(scope,{false})
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.start("t",false)}
            val start=wire.read();wire.reply(start)
            withContext(dispatcher){controller.notification("thread/realtime/started",buildJsonObject { put("threadId","t");put("realtimeSessionId","s") })}
            withTimeout(3000){controller.state.first{it.ready}}
            withContext(dispatcher){controller.stop()};val stop=wire.read();wire.reply(stop)
            withContext(dispatcher){assertTrue(controller.state.value.running);controller.notification("thread/realtime/closed",buildJsonObject { put("threadId","t") })}
            withTimeout(3000){controller.state.first{!it.running}}
        } } finally { scope.cancel();dispatcher.close() }
    }
    @Test fun searchResultsAreBoundToTheExactSessionAndQueryAndResetOnDisconnect() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalSearchController(scope,"/p")
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.search("one")}
            val start=wire.read();val id=start.getValue("params").jsonObject.string("sessionId");wire.reply(start)
            val update=wire.read();wire.reply(update)
            withTimeout(3000){controller.state.first{!it.busy}}
            withContext(dispatcher){
                controller.notification("fuzzyFileSearch/sessionUpdated",buildJsonObject { put("sessionId",id);put("query","old");put("files",buildJsonArray {add(buildJsonObject {put("path","wrong")})}) })
                assertTrue(controller.state.value.files.isEmpty())
                controller.notification("fuzzyFileSearch/sessionUpdated",buildJsonObject { put("sessionId",id);put("query","one");put("files",buildJsonArray {add(buildJsonObject {put("path","right")})}) })
                assertEquals("right",controller.state.value.files.single().string("path"))
                controller.bind(null);assertTrue(controller.state.value.files.isEmpty());assertNull(controller.state.value.sessionId)
            }
        } } finally { scope.cancel();dispatcher.close() }
    }
    @Test fun processExitBeforeAcknowledgementFinishesWithoutWaitingForTheLateReply() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalCommandController(scope,{false})
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.run("pwd","/p",false,60000,experimental=true)}
            val request=wire.read();val id=request.getValue("params").jsonObject.string("processHandle")
            withContext(dispatcher){controller.processExited(buildJsonObject{put("processHandle",id);put("exitCode",0);put("stdout","early");put("stderr","")})}
            withTimeout(3000){controller.state.first{!it.running}}
            wire.reply(request)
            assertEquals("early",controller.state.value.output)
        } } finally {scope.cancel();dispatcher.close()}
    }
    @Test fun leavingDuringSearchStartupQueuesStopAndDoesNotIssueAnExtraQuery() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalSearchController(scope,"/p")
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.search("query")}
            val start=wire.read()
            withContext(dispatcher){controller.stop()};wire.reply(start)
            val stop=wire.read();assertEquals("fuzzyFileSearch/sessionStop",stop.string("method"))
            withContext(dispatcher){controller.stop()};wire.reply(stop)
            withTimeout(3000){controller.state.first{!it.busy && it.sessionId==null}}
            withContext(dispatcher){controller.search("next")}
            val next=wire.read();wire.reply(next)
            val query=wire.read();assertEquals("fuzzyFileSearch/sessionUpdate",query.string("method"));wire.reply(query)
        } } finally {scope.cancel();dispatcher.close()}
    }
    @Test fun stopCanPreemptAnUnacknowledgedTextInputWithoutReplayingIt() = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try { Wire(scope).use { wire ->
            val controller=OriginalRealtimeController(scope,{false})
            withContext(dispatcher){controller.bind(OriginalCodexClient(wire.rpc));controller.start("t",false)}
            val start=wire.read();wire.reply(start)
            withContext(dispatcher){controller.notification("thread/realtime/started",buildJsonObject{put("threadId","t")})}
            withTimeout(3000){controller.state.first{it.ready}}
            withContext(dispatcher){controller.send("text")};val text=wire.read()
            withContext(dispatcher){controller.stop()};val stop=wire.read()
            assertEquals("thread/realtime/stop",stop.string("method"));wire.reply(text);wire.reply(stop)
            withContext(dispatcher){controller.notification("thread/realtime/closed",buildJsonObject{put("threadId","t")})}
            withTimeout(3000){controller.state.first{!it.running && !it.controlBusy}}
        } } finally {scope.cancel();dispatcher.close()}
    }

}
