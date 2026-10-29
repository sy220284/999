package cn.codex.android

import androidx.test.platform.app.InstrumentationRegistry
import cn.codex.core.*
import cn.codex.core.original.AppServerConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 可控 stdio 对端验证宿主生命周期，不代表真实 Rust 引擎或设备行为已经通过。仅提交，未执行。 */
class OriginalCoreHostTest {
    private class FakeSession(scope: CoroutineScope, val deferClose: Boolean = false) : OriginalEngineSession {
        private val incoming = PipedInputStream(65536)
        private val serverOutput = PipedOutputStream(incoming)
        private val requestsPipe = PipedInputStream(65536)
        private val clientOutput = PipedOutputStream(requestsPipe)
        private val reader = requestsPipe.bufferedReader()
        val closed = AtomicInteger()
        private val disposed = AtomicBoolean()
        val turnStarts = AtomicInteger()
        val clientResponses = AtomicInteger()
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        var initializeReplies = true
        var failClose = false
        var threadCwd=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"工作区/原版项目").apply{mkdirs()}
        var resumedTurn: String? = null
        var holdResume = false
        var turn: suspend (FakeSession, JsonObject) -> Unit = { _, _ -> }
        var review: suspend (FakeSession, JsonObject) -> Unit = { _, _ -> }
        var verify:suspend(FakeSession,JsonObject)->Unit={_,_->}
        var queueImplemented=false
        val queueRows=mutableListOf<JsonObject>()
        @Volatile var runtimeTurn:String?=null
        var queueStart:suspend(FakeSession,JsonObject)->Unit={_,_->}
        var shell: suspend (FakeSession, JsonObject) -> Unit = { _, _ -> }
        override val connection = AppServerConnection(incoming, clientOutput, scope, { serverOutput.close() })
        private val loop = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val request = Json.parseToJsonElement(line).jsonObject
                    received.send(request)
                    if (!request.containsKey("method")) { clientResponses.incrementAndGet(); continue }
                    if (!request.containsKey("id")) continue
                    when (request.string("method")) {
                        "initialize" -> if (initializeReplies) reply(request, buildJsonObject {})
                        "account/read" -> reply(request, buildJsonObject { put("account", JsonNull) })
                        "thread/list" -> reply(request, buildJsonObject { put("data", buildJsonArray {}) })
                        "thread/start", "thread/resume" -> if (!holdResume || request.string("method") == "thread/start") reply(request, thread(resumedTurn,threadCwd))
                        "thread/queue/list" -> if(queueImplemented)reply(request,buildJsonObject{put("data",JsonArray(queueRows.toList()));put("nextCursor",JsonNull)}) else emit(buildJsonObject{put("id",request.getValue("id"));put("error",buildJsonObject{put("code",-32601);put("message","queue unsupported in fake")})})
                        "userVerification/verify" -> verify(this@FakeSession,request)
                        "userVerification/cancel" -> reply(request,buildJsonObject{})
                        "thread/queue/start" -> queueStart(this@FakeSession,request)
                        "thread/read" -> reply(request,buildJsonObject{put("thread",JsonObject(thread(runtimeTurn,threadCwd).getValue("thread").jsonObject + ("status" to buildJsonObject{put("type",if(runtimeTurn==null)"idle"else "active");put("activeFlags",buildJsonArray{})})))})
                        "turn/start" -> { turnStarts.incrementAndGet(); turn(this@FakeSession, request) }
                        "review/start" -> review(this@FakeSession, request)
                        "thread/shellCommand" -> shell(this@FakeSession, request)
                        "turn/interrupt", "test/barrier" -> reply(request, buildJsonObject {})
                        else -> emit(buildJsonObject { put("id", request.getValue("id")); put("error", buildJsonObject { put("code", -32601); put("message", "fake endpoint does not implement this method") }) })
                    }
                }
            } catch (_: IOException) { } finally { received.close() }
        }
        @Synchronized fun emit(frame: JsonObject) { serverOutput.write((frame.toString() + "\n").toByteArray()); serverOutput.flush() }
        fun reply(request: JsonObject, value: JsonObject) = emit(buildJsonObject { put("id", request.getValue("id")); put("result", value) })
        fun notify(method: String, params: JsonObject) = emit(buildJsonObject { put("method", method); put("params", params) })
        fun started(id:String){runtimeTurn=id;notify("turn/started", buildJsonObject { put("threadId", "thread"); put("turn", obj("id" to id, "status" to "inProgress")) })}
        fun completed(id:String){if(runtimeTurn==id)runtimeTurn=null;notify("turn/completed", buildJsonObject { put("threadId", "thread"); put("turn", obj("id" to id, "status" to "completed")) })}
        suspend fun next(method: String): JsonObject = withTimeout(5000) { while (true) { val frame = received.receive(); if (frame.string("method") == method) return@withTimeout frame }; error("unreachable") }
        override fun close() { closed.incrementAndGet(); if (!deferClose) dispose(); if (failClose) throw IOException("test close failure") }
        fun dispose() { if (disposed.compareAndSet(false, true)) { connection.close(); loop.cancel(); runCatching { reader.close() } } }
        companion object {
            fun thread(active: String? = null,cwd:File=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"工作区/原版项目")) = buildJsonObject {
                put("thread", buildJsonObject {
                    put("id", "thread"); put("model", "test-model");put("cwd",cwd.absolutePath)
                    put("turns", buildJsonArray { if (active != null) add(buildJsonObject { put("id", active); put("status", "inProgress"); put("items", buildJsonArray {}) }) })
                })
            }
        }
    }
    private suspend fun ready(host: OriginalCoreHost) = withTimeout(5000) { host.state.first { it.connected && !it.loading } }
    private suspend fun closed(host: OriginalCoreHost) = withTimeout(5000) { host.state.first { !it.connected && !it.closing && !it.loading } }
    private fun host(scope: CoroutineScope, factory: OriginalEngineFactory, timeout: Long = 5000): OriginalCoreHost {
        lateinit var host: OriginalCoreHost
        host = OriginalCoreHost(InstrumentationRegistry.getInstrumentation().targetContext, engineFactory = factory,
            configLoader = { ApiConfig(model = "test-model") }, startupTimeoutMs = timeout, scope = scope,
            startTask = { text -> scope.launch { host.runReserved(text) }; Unit }, otherTaskRunning = { false })
        return host
    }
    @Test fun projectSelectionRebindsSearchConfigurationFilesAndCliWithoutOldResults()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val peer=FakeSession(scope)
        val folder=File(peer.threadCwd,"project-context-${java.util.UUID.randomUUID()}").apply{mkdirs()};peer.threadCwd=folder
        val host=host(scope,OriginalEngineFactory{peer})
        try{
            host.connect();ready(host);val oldSearch=host.search;val oldMcp=host.mcp
            host.openThread("thread")?.join()
            assertEquals(folder.canonicalPath,host.commandWorkspace());assertEquals(folder.canonicalFile,host.files.root.canonicalFile)
            assertNotSame(oldSearch,host.search);assertNotSame(oldMcp,host.mcp);assertFalse(oldMcp.state.value.connected)
            host.search.search("marker")
            val search=withTimeout(5000){var next=peer.received.receive();while(next.string("method")!="fuzzyFileSearch/sessionStart")next=peer.received.receive();next}
            assertEquals(folder.absolutePath,search.getValue("params").jsonObject.getValue("roots").jsonArray.first().jsonPrimitive.content)
            host.mcp.refresh()
            val config=withTimeout(5000){var next=peer.received.receive();while(next.string("method")!="config/read")next=peer.received.receive();next}
            assertEquals(folder.absolutePath,config.getValue("params").jsonObject.getValue("cwd").jsonPrimitive.content)
        }finally{host.close();scope.cancel();peer.dispose();folder.deleteRecursively()}
    }
    @Test fun startupTimeoutClosesOneCreatedSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = AtomicInteger(); var session: FakeSession? = null
        val host = host(scope, OriginalEngineFactory { launch -> calls.incrementAndGet(); FakeSession(launch.scope).also { it.initializeReplies = false; session = it } }, 1000)
        try {
            withContext(Dispatchers.Main) { host.connect() }
            withTimeout(5000) { host.state.first { it.error?.contains("启动超时") == true && !it.closing } }
            assertEquals(1, calls.get()); assertEquals(1, session!!.closed.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session?.dispose(); scope.cancel() }
    }
    @Test fun completionBeforeStartResponseDoesNotReactivateOrReplayTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val held = CompletableDeferred<JsonObject>()
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.turn = { server, request -> server.started("A"); held.complete(request) } } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.send("test") }
            val request = held.await(); withTimeout(5000) { host.state.first { it.turnId == "A" } }
            session.completed("A"); session.reply(request, buildJsonObject { put("turn", obj("id" to "A")) })
            val state = withTimeout(5000) { host.state.first { !it.working && it.status == "任务完成" } }
            assertNull(state.turnId); assertEquals(1, session.turnStarts.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun oldCompletionDoesNotFinishActiveTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.turn = { server, request -> server.reply(request, buildJsonObject { put("turn", obj("id" to "A")) }); server.started("A") } } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.send("test") }; withTimeout(5000) { host.state.first { it.turnId == "A" } }
            session.completed("old")
            session.notify("item/agentMessage/delta", obj("threadId" to "thread", "turnId" to "A", "itemId" to "barrier", "delta" to "barrier"))
            val state = withTimeout(5000) { host.state.first { it.items.any { item -> item.id == "barrier" } } }
            assertEquals("A", state.turnId); assertTrue(state.working)
            session.completed("A"); withTimeout(5000) { host.state.first { !it.working } }
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun resumeDoesNotSendTurnStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.resumedTurn = "resumed" } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && it.turnId == "resumed" } }
            assertEquals(0, session.turnStarts.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun reconnectDiscardsLateResumeFromPreviousSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sessions = mutableListOf<FakeSession>()
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope, deferClose = sessions.isEmpty()).also { if (sessions.isEmpty()) it.holdResume = true; sessions += it } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            val held = sessions[0].next("thread/resume")
            withContext(Dispatchers.Main) { host.close() }; closed(host)
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            sessions[0].reply(held, FakeSession.thread("stale-turn"))
            sessions[0].connection.request("test/barrier")
            sessions[1].notify("warning", obj("message" to "current-connection-barrier"))
            val state = withTimeout(5000) { host.state.first { it.status == "current-connection-barrier" } }
            assertNull(state.threadId); assertNull(state.turnId); assertTrue(state.connected)
        } finally { withContext(Dispatchers.Main) { host.close() }; sessions.forEach { it.dispose() }; scope.cancel() }
    }
    @Test fun closingDuringFactoryAcquisitionDisposesLateSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val acquired = CompletableDeferred<FakeSession>(); val release = CompletableDeferred<Unit>()
        val host = host(scope, OriginalEngineFactory { launch ->
            withContext(NonCancellable) {
                val session = FakeSession(launch.scope); acquired.complete(session)
                release.await(); session
            }
        })
        var session: FakeSession? = null
        try {
            val startup = withContext(Dispatchers.Main) { host.connect() }
            session = acquired.await()
            withContext(Dispatchers.Main) { host.close() }; closed(host)
            release.complete(Unit); startup?.join()
            assertEquals(1, session.closed.get()); assertFalse(host.state.value.connected)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { host.close() }; session?.dispose(); scope.cancel() }
    }
    @Test fun staleApprovalCannotAnswerNewConnectionWithSameRequestId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sessions = mutableListOf<FakeSession>()
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { sessions += it } })
        fun request() = buildJsonObject { put("id", "approval"); put("method", "item/tool/requestUserInput"); put("params", buildJsonObject { put("questions", buildJsonArray {}) }) }
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            sessions[0].emit(request())
            val old = withTimeout(5000) { host.state.first { it.requests.isNotEmpty() } }.requests.single()
            withContext(Dispatchers.Main) { host.close() }; closed(host)
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            sessions[1].emit(request())
            withTimeout(5000) { host.state.first { it.requests.isNotEmpty() } }
            withContext(Dispatchers.Main) { host.answer(old, buildJsonObject { put("answers", buildJsonObject {}) }) }.join()
            sessions[1].connection.request("test/barrier")
            assertEquals(0, sessions[1].clientResponses.get()); assertEquals(1, host.state.value.requests.size)
        } finally { withContext(Dispatchers.Main) { host.close() }; sessions.forEach { it.dispose() }; scope.cancel() }
    }
    @Test fun closeFailureBecomesVisibleStateInsteadOfUncaughtApplicationFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.failClose = true } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.close() }
            val state = closed(host)
            assertTrue(state.error.orEmpty().contains("回收")); assertFalse(state.connected)
        } finally { session.dispose(); scope.cancel() }
    }
    @Test fun interruptAcknowledgementWaitsForMatchingCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.turn = { server, request -> server.reply(request, buildJsonObject { put("turn", obj("id" to "A")) }); server.started("A") } } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.send("test") }; withTimeout(5000) { host.state.first { it.turnId == "A" } }
            withContext(Dispatchers.Main) { host.stop() }
            session.next("turn/interrupt"); session.connection.request("test/barrier")
            session.notify("warning", obj("message" to "interrupt-ack-barrier"))
            val state = withTimeout(5000) { host.state.first { it.status == "interrupt-ack-barrier" } }
            assertEquals("A", state.turnId); assertTrue(state.working)
            session.completed("A"); withTimeout(5000) { host.state.first { !it.working } }
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun reviewUsesTheSameForegroundTurnLifecycleEvenWhenCompletionArrivesBeforeAck() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val held = CompletableDeferred<JsonObject>()
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { server -> session = server; server.review = { current, request -> current.started("R"); held.complete(request) } } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && it.threadId == "thread" } }
            withContext(Dispatchers.Main) { host.startReview("uncommittedChanges", "", "") }
            val request = held.await()
            assertEquals("inline", request.getValue("params").jsonObject.getValue("delivery").jsonPrimitive.content)
            session.completed("R")
            session.reply(request, buildJsonObject { put("reviewThreadId", "thread"); put("turn", obj("id" to "R")) })
            val finished = withTimeout(5000) { host.state.first { !it.working && it.status == "任务完成" } }
            assertNull(finished.turnId); assertEquals(0, session.turnStarts.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun idleThreadStatusDoesNotFinishAnActiveTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.turn = { server, request -> server.reply(request, buildJsonObject { put("turn", obj("id" to "A")) }); server.started("A") } } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.send("test") }; withTimeout(5000) { host.state.first { it.turnId == "A" } }
            session.notify("thread/status/changed", buildJsonObject { put("threadId", "thread"); put("status", obj("type" to "idle")) })
            val state = withTimeout(5000) { host.state.first { it.threadStatuses["thread"]?.get("type") == JsonPrimitive("idle") } }
            assertEquals("A", state.turnId); assertTrue(state.working)
            session.completed("A"); withTimeout(5000) { host.state.first { !it.working } }
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }

    @Test fun shellAcknowledgementWaitsForActualTurnCompletionAndKeepsZeroTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it; it.shell = { server, request -> assertEquals(JsonPrimitive(0),request.getValue("params").jsonObject["timeoutMs"]);server.started("S");server.reply(request,buildJsonObject {}) } } })
        try {
            withContext(Dispatchers.Main) { host.connect() };ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") };withTimeout(5000) { host.state.first { !it.loading && it.threadId=="thread" } }
            withContext(Dispatchers.Main) { host.shellCommand("printf test","0") }
            withTimeout(5000) { host.state.first { it.turnId=="S" } }
            assertTrue(host.state.value.working)
            session.completed("S");withTimeout(5000) { host.state.first { !it.working } }
            assertNull(host.state.value.turnId);assertEquals(0,session.turnStarts.get())
        } finally { withContext(Dispatchers.Main) { host.close() };session.dispose();scope.cancel() }
    }

    @Test fun legacyCallbacksUseTheSameQueueAndResolvedRequestsCannotBeAnswered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            session.emit(buildJsonObject { put("id", "legacy"); put("method", "execCommandApproval"); put("params", buildJsonObject {
                put("conversationId", "thread"); put("callId", "call"); put("command", buildJsonArray { add("pwd") }); put("cwd", "/project")
            }) })
            val request = withTimeout(5000) { host.state.first { it.requests.isNotEmpty() } }.requests.single()
            session.notify("serverRequest/resolved", buildJsonObject { put("threadId", "thread"); put("requestId", "legacy") })
            withTimeout(5000) { host.state.first { it.requests.isEmpty() } }
            withContext(Dispatchers.Main) { host.answer(request, OriginalLegacyApproval.decision(true)) }.join()
            session.connection.request("test/barrier")
            assertEquals(0, session.clientResponses.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun liveProgressIgnoresOtherThreadsAndClearsOnViewChangeAndDisconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it } })
        fun plan(thread: String, turn: String) = buildJsonObject {
            put("threadId", thread); put("turnId", turn); put("plan", buildJsonArray {})
        }
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && it.threadId == "thread" } }
            session.notify("turn/plan/updated", plan("other", "foreign"))
            session.notify("turn/plan/updated", plan("thread", "current"))
            val state = withTimeout(5000) { host.state.first { "current" in it.turnProgress } }
            assertFalse("foreign" in state.turnProgress)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && it.turnProgress.isEmpty() } }
            session.notify("turn/plan/updated", plan("thread", "again"))
            withTimeout(5000) { host.state.first { "again" in it.turnProgress } }
            withContext(Dispatchers.Main) { host.close() }; closed(host)
            assertTrue(host.state.value.turnProgress.isEmpty())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }

    @Test fun deletedActiveThreadEndsWaitingBeforeAcknowledgementWithoutClosingOtherCoreWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val held = CompletableDeferred<JsonObject>()
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also {
            session = it; it.turn = { server, request -> server.started("A"); held.complete(request) }
        } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.send("test") }; held.await()
            session.notify("thread/deleted", obj("threadId" to "thread"))
            withTimeout(5000) { host.state.first { !it.working && it.error?.contains("会话") == true } }
            assertTrue(host.state.value.connected); assertNull(host.state.value.turnId)
            assertFalse(host.selectedThreadUsable())
            session.reply(held.await(), buildJsonObject { put("turn", obj("id" to "A", "status" to "inProgress")) })
            session.started("A"); session.notify("warning", obj("message" to "late-start-barrier"))
            withTimeout(5000) { host.state.first { it.status == "late-start-barrier" } }
            assertNull(host.state.value.turnId); assertEquals(0, session.closed.get())
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }
    @Test fun backgroundLifecycleDoesNotSwitchSelectionAndRevertBlocksSendUntilReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it } })
        try {
            withContext(Dispatchers.Main) { host.connect() }; ready(host)
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && it.threadId == "thread" } }
            session.notify("thread/started", buildJsonObject { put("thread", obj("id" to "background", "name" to "后台会话")) })
            session.notify("thread/closed", obj("threadId" to "background"))
            session.notify("thread/reverted", obj("threadId" to "thread"))
            withTimeout(5000) { host.state.first { it.threadUpdates.threads["thread"]?.historyVersion != null } }
            assertEquals("thread", host.state.value.threadId); assertFalse(host.selectedThreadUsable())
            withContext(Dispatchers.Main) { host.send("must not start") }
            assertEquals(0, session.turnStarts.get())
            withContext(Dispatchers.Main) { host.openThread("thread") }
            withTimeout(5000) { host.state.first { !it.loading && host.selectedThreadUsable() } }
        } finally { withContext(Dispatchers.Main) { host.close() }; session.dispose(); scope.cancel() }
    }

    @Test fun dynamicToolWaitsForApprovalThenReturnsActualFileContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it } })
        val file = java.io.File(host.commandWorkspace(),"dynamic-tool-test.txt")
        try {
            file.writeText("手机文件正文")
            withContext(Dispatchers.Main) { host.connect() };ready(host)
            session.emit(buildJsonObject { put("id","dynamic");put("method","item/tool/call");put("params",buildJsonObject {
                put("threadId","thread");put("turnId","turn");put("callId","call");put("namespace",JsonNull);put("tool","android_project_read");put("arguments",obj("path" to file.name))
            }) })
            val request = withTimeout(5000) { host.state.first { it.requests.isNotEmpty() } }.requests.single()
            assertEquals(0,session.clientResponses.get())
            withContext(Dispatchers.Main) { host.executeAndroidTool(request) }
            withTimeout(5000) { host.state.first { it.requests.isEmpty() && !it.toolBusy } }
            session.connection.request("test/barrier")
            assertEquals(1,session.clientResponses.get())
            assertTrue(host.state.value.events.notices.any { it.body.contains("手机文件正文") })
            withContext(Dispatchers.Main) { host.close() };closed(host)
            assertTrue(host.state.value.events.notices.isEmpty())
        } finally { file.delete();withContext(Dispatchers.Main) { host.close() };session.dispose();scope.cancel() }
    }
    @Test fun externalProviderRequestReturnsSpecificErrorWithoutExposingCredentials() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: FakeSession
        val host = host(scope, OriginalEngineFactory { launch -> FakeSession(launch.scope).also { session = it } })
        try {
            withContext(Dispatchers.Main) { host.connect() };ready(host)
            session.emit(buildJsonObject { put("id","attest");put("method","attestation/generate");put("params",buildJsonObject {}) })
            val state = withTimeout(5000) { host.state.first { it.events.notices.any { row -> row.key == "attestation/generate" } } }
            assertTrue(state.requests.isEmpty());assertTrue(state.connected)
            assertTrue(state.events.notices.single().body.contains("证明提供者"))
        } finally { withContext(Dispatchers.Main) { host.close() };session.dispose();scope.cancel() }
    }

    @Test fun experimentalClockCallbackReturnsWholeUnixSecondsWithoutOpeningADialog() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var session:FakeSession
        val host=host(scope,OriginalEngineFactory{launch->FakeSession(launch.scope).also{session=it}})
        try{
            withContext(Dispatchers.Main){host.connect()};ready(host)
            val before=System.currentTimeMillis()/1000L
            session.emit(buildJsonObject{put("id","clock");put("method","currentTime/read");put("params",obj("threadId" to "thread"))})
            val response=withTimeout(5000){
                var frame=session.received.receive()
                while(frame["id"]!=JsonPrimitive("clock"))frame=session.received.receive()
                frame
            }
            val seconds=response.getValue("result").jsonObject.getValue("currentTimeAt").jsonPrimitive.long
            assertTrue(seconds in before..(System.currentTimeMillis()/1000L));assertTrue(host.state.value.requests.isEmpty())
        }finally{withContext(Dispatchers.Main){host.close()};session.dispose();scope.cancel()}
    }

    @Test fun queuedStartWaitsForAutomaticFollowupTurnAndDoesNotSubmitANormalPrompt()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var session:FakeSession
        val item=buildJsonObject{put("id","q");put("input",OriginalCodexClient.textInput("queued"));put("clientUserMessageId","c")}
        val host=host(scope,OriginalEngineFactory{launch->FakeSession(launch.scope).also{
            session=it;it.queueImplemented=true;it.queueRows+=item
            it.queueStart={server,request->server.queueRows.clear();server.started("Q");server.completed("Q");server.started("follow");server.reply(request,buildJsonObject{put("turn",obj("id" to "Q","status" to "inProgress"))})}
        }})
        try{
            withContext(Dispatchers.Main){host.connect()};ready(host)
            withContext(Dispatchers.Main){host.openThread("thread")};withTimeout(5000){host.state.first{!it.loading && it.threadId=="thread"}}
            withContext(Dispatchers.Main){host.startQueued(item)}
            withTimeout(5000){host.state.first{it.queueMonitoring && it.turnId=="follow"}}
            assertTrue(host.state.value.working);assertEquals(0,session.turnStarts.get())
            session.completed("follow")
            withTimeout(5000){host.state.first{!it.working}}
            assertTrue(host.state.value.connected);assertNull(host.state.value.turnId)
        }finally{withContext(Dispatchers.Main){host.close()};session.dispose();scope.cancel()}
    }

    @Test fun nativeApprovalReturnsOnlyTheProofForItsPendingRequest()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var session:FakeSession
        val proof=obj("credentialId" to "local-key","signature" to "signed-challenge")
        val host=host(scope,OriginalEngineFactory{launch->FakeSession(launch.scope).also{session=it;it.verify={server,request->server.reply(request,buildJsonObject{put("proof",proof)})}}})
        try{
            withContext(Dispatchers.Main){host.connect()};ready(host)
            session.emit(buildJsonObject{put("id","native");put("method","mcpServer/elicitation/request");put("params",obj("mode" to "openai/userVerification","challenge" to "AQ","title" to "允许一次操作","description" to "仅此挑战"))})
            val request=withTimeout(5000){host.state.first{it.requests.isNotEmpty()}}.requests.single()
            withContext(Dispatchers.Main){host.verifyElicitation(request)}
            val response=withTimeout(5000){var frame=session.received.receive();while(frame["id"]!=JsonPrimitive("native"))frame=session.received.receive();frame}
            assertEquals("accept",response.getValue("result").jsonObject.string("action"))
            assertEquals(proof,response.getValue("result").jsonObject["content"])
            withTimeout(5000){host.state.first{it.requests.isEmpty() && !it.toolBusy}}
        }finally{withContext(Dispatchers.Main){host.close()};session.dispose();scope.cancel()}
    }
    @Test fun withdrawnNativeApprovalCancelsItsExactRpcAndClosesUnknownWork()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var session:FakeSession
        val host=host(scope,OriginalEngineFactory{launch->FakeSession(launch.scope).also{session=it}})
        try{
            withContext(Dispatchers.Main){host.connect()};ready(host)
            session.emit(buildJsonObject{put("id","native");put("method","mcpServer/elicitation/request");put("params",obj("mode" to "openai/userVerification","challenge" to "AQ","title" to "批准","description" to ""))})
            val request=withTimeout(5000){host.state.first{it.requests.isNotEmpty()}}.requests.single()
            withContext(Dispatchers.Main){host.verifyElicitation(request)}
            val verify=session.next("userVerification/verify")
            session.notify("serverRequest/resolved",buildJsonObject{put("requestId","native")})
            val cancel=session.next("userVerification/cancel")
            assertEquals(verify["id"],cancel.getValue("params").jsonObject["requestId"])
            assertNotEquals(verify["id"],cancel["id"])
            closed(host);assertEquals(0,session.clientResponses.get())
        }finally{withContext(Dispatchers.Main){host.close()};session.dispose();scope.cancel()}
    }

}
