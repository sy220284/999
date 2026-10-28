package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 队列行为测试源码，本阶段不运行编译型测试。 */
class OriginalQueueTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    @Test fun editingOneTextPreservesAttachmentsAndClearsOnlyItsObsoleteTextSpans() {
        val item=obj("""{"id":"q","input":[{"type":"text","text":"old","text_elements":[{"start":0}]},{"type":"image","url":"https://example.invalid/a.png"}],"clientUserMessageId":"client"}""")
        val changed=OriginalQueueData.replaceText(item,"new")
        assertEquals("new",changed[0].jsonObject.string("text"));assertTrue(changed[0].jsonObject.getValue("text_elements").jsonArray.isEmpty())
        assertEquals(item.getValue("input").jsonArray[1],changed[1])
        assertEquals(item.getValue("input"),OriginalQueueData.replaceText(item,"old"))
    }
    @Test fun multipleTextNodesRequireFullInputEditingAndOrderMustContainEveryIdOnce() {
        val item=obj("""{"id":"q","input":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")
        assertNull(OriginalQueueData.editText(item))
        assertFailsWith<IllegalArgumentException>{OriginalQueueData.replaceText(item,"merged")}
        OriginalQueueData.requireOrder(listOf("a","b"),listOf("b","a"))
        assertFailsWith<IllegalArgumentException>{OriginalQueueData.requireOrder(listOf("a","b"),listOf("a","a"))}
        assertFailsWith<IllegalArgumentException>{OriginalQueueData.requireOrder(listOf("a","b"),listOf("a"))}
    }
    @Test fun rawInputRequiresObjectsAndDoesNotInventMissingUserInput() {
        assertFailsWith<IllegalArgumentException>{OriginalQueueData.parseInput("[]")}
        assertFailsWith<IllegalArgumentException>{OriginalQueueData.parseInput("[1]")}
        assertEquals(1,OriginalQueueData.parseInput("[{\"type\":\"text\",\"text\":\"真实输入\"}]").size)
    }
    private class Wire(scope:CoroutineScope):Closeable {
        private val input=PipedInputStream(65536);private val server=PipedOutputStream(input)
        private val requests=PipedInputStream(65536);private val reader=requests.bufferedReader()
        val rpc=AppServerConnection(input,PipedOutputStream(requests),scope,{server.close()})
        suspend fun read()=withTimeout(3000){runInterruptible(Dispatchers.IO){Json.parseToJsonElement(reader.readLine()).jsonObject}}
        fun reply(request:JsonObject,result:JsonObject){server.write((buildJsonObject{put("id",request.getValue("id"));put("result",result)}.toString()+"\n").toByteArray());server.flush()}
        override fun close(){rpc.close();reader.close()}
    }
    @Test fun changedEntryIsRejectedBeforeTheUpdateRpcAndMutationRecordRemainsUnconfirmed()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val outcomes=mutableListOf<Boolean>()
        val lifecycle=object:OriginalMutationLifecycle {
            override fun begin()="ticket"
            override fun finish(ticket:String,confirmed:Boolean){outcomes+=confirmed}
        }
        try {Wire(scope).use{wire->
            val c=OriginalQueueController(scope,{false},lifecycle)
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.selectThread("t")}
            val approved=obj("""{"id":"q","input":[{"type":"text","text":"old"}],"clientUserMessageId":"c"}""")
            withContext(dispatcher){c.update(approved,OriginalQueueData.replaceText(approved,"new"))}
            val read=wire.read();assertEquals("thread/queue/list",read.string("method"))
            wire.reply(read,obj("""{"data":[{"id":"q","input":[{"type":"text","text":"changed elsewhere"}],"clientUserMessageId":"c"}],"nextCursor":null}"""))
            withTimeout(3000){c.operations.state.first{!it.busy}}
            assertTrue(c.operations.state.value.error.orEmpty().contains("变化"));assertEquals(listOf(false),outcomes)
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun notificationDuringReadKeepsTheSnapshotMarkedStaleAndThreadSwitchDropsOldResults()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try {Wire(scope).use{wire->
            val c=OriginalQueueController(scope,{false})
            withContext(dispatcher){c.bind(OriginalCodexClient(wire.rpc));c.selectThread("t");c.refresh()}
            val read=wire.read()
            withContext(dispatcher){c.changed("t")}
            wire.reply(read,obj("""{"data":[],"nextCursor":null}"""))
            withTimeout(3000){c.operations.state.first{!it.busy}}
            assertTrue(c.state.value.dirty)
            withContext(dispatcher){c.refresh()};val old=wire.read()
            withContext(dispatcher){c.selectThread("other")}
            wire.reply(old,obj("""{"data":[{"id":"old"}],"nextCursor":null}"""))
            withContext(dispatcher){assertEquals("other",c.state.value.threadId);assertTrue(c.state.value.page.entries.isEmpty())}
        }}finally{scope.cancel();dispatcher.close()}
    }
    @Test fun queueRuntimeSnapshotDoesNotOverwriteAnotherThreadAndMarksFinishedTurns() {
        val lifecycle=OriginalRuntimeLifecycle();val epoch=lifecycle.beginConnection()
        lifecycle.accept(epoch,OriginalLifecycleEvent.ThreadSelected("t","a"))
        lifecycle.accept(epoch,OriginalLifecycleEvent.QueueSnapshot("other",null,listOf("a")))
        assertEquals("a",lifecycle.state.turnId)
        lifecycle.accept(epoch,OriginalLifecycleEvent.QueueSnapshot("t",null,listOf("a","b")))
        lifecycle.accept(epoch,OriginalLifecycleEvent.TurnStarted("t","b"));assertNull(lifecycle.state.turnId)
        lifecycle.accept(epoch,OriginalLifecycleEvent.StartRequested("pending"))
        lifecycle.accept(epoch,OriginalLifecycleEvent.StartRejected("pending"));assertNull(lifecycle.state.pendingStartRequestId)
    }
}
