package cn.codex.core.original

import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.*
import java.util.concurrent.Executors
import cn.codex.core.string
import org.junit.Test
import kotlin.test.*

/** 行为测试源码，按当前授权不编译、不运行。 */
class OriginalCompletionTest {
    @Test fun projectRootsRequireAbsolutePathsAndPreserveDistinctRoots() {
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.roots("relative")}
        assertEquals(2,OriginalCompletionData.roots("/a\n/b\n/a").size)
    }
    @Test fun readStateUsesTheRevisionActuallySeenAndTaggedOperation() {
        val p=OriginalCompletionData.readState("thread","seen-revision",true)
        assertEquals(JsonPrimitive("seen-revision"),p["expectedRevision"])
        assertEquals(JsonPrimitive("read"),p.getValue("operation").jsonObject["type"])
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.readState("t","",false)}
    }
    @Test fun settingsPreserveExplicitNullButRejectIdentityAndUnknownFields() {
        val p=OriginalCompletionData.settings("""{"model":"m","serviceTier":null}""",false)
        assertEquals(JsonNull,p["serviceTier"])
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.settings("""{"threadId":"other"}""",false)}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.settings("""{"permissions":"p","sandboxPolicy":{"type":"danger-full-access"}}""",false)}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.settings("{}",true)}
    }
    @Test fun environmentTokensRequireSecureOrExactLoopbackDestination() {
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.environment("e","ws://remote.example/","secret","")}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.environment("e","ws://127.0.0.1.evil/","secret","")}
        assertEquals(JsonPrimitive("secret"),OriginalCompletionData.environment("e","wss://example.invalid/","secret","")["authBearerToken"])
        assertEquals(JsonPrimitive("secret"),OriginalCompletionData.environment("e","ws://127.0.0.1:9000/","secret","")["authBearerToken"])
    }
    @Test fun verificationValidatesUnpaddedChallengeAndUtf8DisplayLimits() {
        OriginalCompletionData.verification("AQ","验证","说明")
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.verification("AQ==","验证","")}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.verification("AQ","中".repeat(86),"")}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.verification("","验证","")}
    }
    @Test fun itemIdsMustBeNonemptyUniqueAndBounded() {
        assertEquals(2,OriginalCompletionData.itemIds("a\nb").size)
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.itemIds("")}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.itemIds((1..101).joinToString("\n"))}
    }
    @Test fun pageCursorCyclesAreRejectedAndRowsRemainAvailable() {
        val a=OriginalCompletionPage().append(Json.parseToJsonElement("""{"data":[{"id":"a"}],"nextCursor":"c"}""").jsonObject,null)
        assertFailsWith<IllegalArgumentException>{a.append(Json.parseToJsonElement("""{"data":[],"nextCursor":"c"}""").jsonObject,"c")}
        assertEquals(1,a.rows.size)
    }
    private class Wire(scope:CoroutineScope):Closeable {
        private val input=PipedInputStream(65536);private val server=PipedOutputStream(input)
        private val requests=PipedInputStream(65536);private val reader=requests.bufferedReader()
        val rpc=AppServerConnection(input,PipedOutputStream(requests),scope,{server.close()})
        suspend fun next()=withTimeout(3000){runInterruptible(Dispatchers.IO){Json.parseToJsonElement(reader.readLine()).jsonObject}}
        fun reply(request:JsonObject,result:JsonObject){server.write((buildJsonObject{put("id",request.getValue("id"));put("result",result)}.toString()+"\n").toByteArray());server.flush()}
        override fun close(){rpc.close();reader.close()}
    }
    @Test fun readingOuterReceiptThenMarkingUsesExactlyTheSeenRevision()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val c=OriginalHistoryController(scope,{"t"},{false},OriginalMutationLifecycle.None)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.read()}
            wire.reply(wire.next(),Json.parseToJsonElement("""{"thread":{"id":"t"},"readState":{"revision":"seen","firstUnread":null}}""").jsonObject)
            withTimeout(3000){c.operations.state.first{!it.busy}}
            val detail=c.state.value.detail!!
            withContext(dispatcher){c.mark(detail,false)}
            val write=wire.next();assertEquals("thread/readState/update",write.string("method"))
            val params=write.getValue("params").jsonObject
            assertEquals("seen",params.string("expectedRevision"));assertEquals("unread",params.getValue("operation").jsonObject.string("type"))
            wire.reply(write,Json.parseToJsonElement("""{"readState":{"revision":"new","firstUnread":{"type":"threadStart"}}}""").jsonObject)
            withTimeout(3000){c.operations.state.first{!it.busy}}
            assertEquals("new",c.state.value.detail!!.getValue("readState").jsonObject.string("revision"))
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun cancellationUsesIssuedSampleIdAndHasItsOwnDistinctWireId()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val client=OriginalCodexClient(wire.rpc);val issued=CompletableDeferred<JsonElement>()
            val sample=async{client.sampleThread("t","last","prompt"){issued.complete(it)}}
            val request=wire.next();val id=withTimeout(3000){issued.await()};assertEquals(request["id"],id)
            val cancel=async{client.cancelSample(id)};val cancellation=wire.next()
            assertNotEquals(id,cancellation["id"]);assertEquals(id,cancellation.getValue("params").jsonObject["requestId"])
            wire.reply(cancellation,buildJsonObject{});cancel.await()
            assertFalse(sample.isCompleted)
            wire.reply(request,Json.parseToJsonElement("""{"sourceTurnId":"last","responseId":"response","text":"done"}""").jsonObject)
            assertEquals("done",sample.await().jsonObject.string("text"))
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun missingVerificationProviderNeverSendsEnrollmentOrInventsProof()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val c=OriginalConnectionsController(scope,{"t"},{false},OriginalMutationLifecycle.None)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.verification(true)}
            val status=wire.next();assertEquals("userVerification/status",status.string("method"))
            wire.reply(status,Json.parseToJsonElement("""{"credentialId":null,"unavailableReason":"providerUnavailable"}""").jsonObject)
            withTimeout(3000){c.operations.state.first{!it.busy}}
            assertNull(c.state.value.verificationRequest);assertNull(c.state.value.proof)
            assertTrue(c.operations.state.value.error.orEmpty().contains("不支持"))
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun unknownProjectCreationKeepsTheSameIdentityAcrossRebind()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var stored:JsonObject?=null
        val store=object:OriginalProjectRequestStore {
            override fun read()=stored
            override fun write(value:JsonObject){stored=value}
            override fun clear(key:String?){if(key==null || stored?.string("key")==key)stored=null}
        }
        try{Wire(scope).use{wire->
            val c=OriginalProjectsController(scope,{false},OriginalMutationLifecycle.None,store)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.create("project","/a","",false)}
            val request=wire.next();val key=request.getValue("params").jsonObject.string("idempotencyKey")
            wire.rpc.close();withTimeout(3000){c.operations.state.first{!it.busy}}
            withContext(dispatcher){c.bind(null)}
            assertEquals(key,c.state.value.creationKey);assertNotNull(c.state.value.creationPayload)
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun noncanonicalBase64AndOversizedPageAreRejectedBeforeFurtherWork() {
        assertFailsWith<IllegalArgumentException>{OriginalCompletionData.verification("AB","验证","")}
        val large=buildJsonObject{put("data",buildJsonArray{add(buildJsonObject{put("text","a".repeat(4*1024*1024))})})}
        assertFailsWith<IllegalArgumentException>{OriginalCompletionPage().append(large,null)}
    }
    @Test fun mcpStreamAcceptsEarlyKnownEventsBoundsPayloadAndStopsTheSameIdentity()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val c=OriginalConnectionsController(scope,{"t"},{false},OriginalMutationLifecycle.None)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.subscribe("codex_apps","events","{}")}
            val start=wire.next();val id=start.getValue("params").jsonObject.string("subscriptionId")
            withContext(dispatcher){
                c.streamEvent(buildJsonObject{put("subscriptionId","other");put("payload","ignore")})
                assertTrue(c.state.value.streamEvents.isEmpty())
                c.streamEvent(buildJsonObject{put("subscriptionId",id);put("payload","x".repeat(40000))})
                assertEquals(JsonPrimitive(true),c.state.value.streamEvents.single()["truncated"])
                assertTrue(c.state.value.streamEvents.single().string("preview").length<=32768)
            }
            wire.reply(start,buildJsonObject{});withTimeout(3000){c.operations.state.first{!it.busy}}
            withContext(dispatcher){c.unsubscribe(id)}
            val stop=wire.next();assertEquals("mcpServer/event/stream/stop",stop.string("method"));assertEquals(id,stop.getValue("params").jsonObject.string("subscriptionId"))
            wire.reply(stop,buildJsonObject{});withTimeout(3000){c.operations.state.first{!it.busy}}
            assertTrue(c.state.value.subscriptions.isEmpty())
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun bedrockCredentialKindsReachOriginalLoginWithoutStatePersistence()=runBlocking{
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val api=OriginalCodexClient(wire.rpc)
            val key=scope.async{api.loginBedrock("secret-api","us-east-1")};val first=wire.next()
            assertEquals("amazonBedrock",first.getValue("params").jsonObject.string("type"));assertEquals("secret-api",first.getValue("params").jsonObject.string("apiKey"));wire.reply(first,buildJsonObject{});key.await()
            val keys=scope.async{api.loginBedrockKeys("access","secret","session","us-east-1")};val second=wire.next();val params=second.getValue("params").jsonObject
            assertEquals("amazonBedrockAccessKeys",params.string("type"));assertEquals("session",params.string("sessionToken"));wire.reply(second,buildJsonObject{});keys.await()
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun detachedReviewUsesReturnedThreadAndActualTerminalStatus()=runBlocking{
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val c=OriginalDetachedReview(scope,{false},OriginalMutationLifecycle.None)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.start("source",OriginalSessionActions.reviewTarget("uncommittedChanges"))}
            val request=wire.next();assertEquals("detached",request.getValue("params").jsonObject.string("delivery"))
            wire.reply(request,buildJsonObject{put("reviewThreadId","review");put("turn",buildJsonObject{put("id","review-turn");put("status","completed")})})
            withTimeout(3000){c.operations.state.first{!it.busy}}
            assertEquals("source",c.state.value.source);assertEquals("review",c.state.value.thread);assertEquals("completed",c.state.value.result?.string("status"))
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun overviewDiscoveryMergesTwoSourcesAndKeepsUnshownRows()=runBlocking{
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try{Wire(scope).use{wire->
            val discovery=OriginalAgentsDiscovery();val result=scope.async{discovery.next(OriginalCodexClient(wire.rpc),1)}
            repeat(2){val request=wire.next();val other=request.getValue("params").jsonObject.getValue("sourceKinds").jsonArray.isNotEmpty();wire.reply(request,buildJsonObject{put("data",buildJsonArray{add(buildJsonObject{put("id",if(other)"exec"else"interactive");put("updatedAt",if(other)20 else 10);put("ephemeral",false)})})})}
            assertEquals("exec",result.await().rows.single().string("id"))
            val rest=discovery.next(OriginalCodexClient(wire.rpc),10);assertEquals("interactive",rest.rows.single().string("id"));assertFalse(rest.more)
        }}finally{scope.cancel();dispatcher.close()}
    }
}
