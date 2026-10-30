package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class OriginalQueueState(val threadId:String?=null,val page:OriginalSessionPage<JsonObject> = OriginalSessionPage(),val revision:Long=0,val loadedRevision:Long = -1) {
    val dirty get()=loadedRevision!=revision
}
object OriginalQueueData {
    fun parseInput(text:String):JsonArray {
        val value=Json.parseToJsonElement(text) as? JsonArray ?: error("输入必须是UserInput数组")
        require(value.isNotEmpty() && value.all{it is JsonObject}){"请提供非空输入对象数组"};return value
    }
    fun input(item:JsonObject)=item["input"] as? JsonArray ?: error("待办缺少输入内容")
    fun editText(item:JsonObject):String? {
        val texts=input(item).filter{(it as? JsonObject)?.string("type")=="text"}
        return when(texts.size){0->"";1->texts.single().jsonObject.string("text");else->null}
    }
    fun replaceText(item:JsonObject,text:String):JsonArray {
        require(text.isNotBlank()){"请填写待办内容"}
        val previous=input(item);val count=previous.count{(it as? JsonObject)?.string("type")=="text"}
        require(count<=1){"包含多段文字，请编辑完整输入JSON，避免合并或丢失内容"}
        if(count==0)return JsonArray(previous+OriginalCodexClient.textInput(text))
        return JsonArray(previous.map{raw->
            val node=raw as? JsonObject
            if(node?.string("type")=="text" && node.string("text")!=text)JsonObject(node+mapOf("text"to JsonPrimitive(text),"text_elements"to buildJsonArray{}))else raw
        })
    }
    fun requireOrder(current:List<String>,proposed:List<String>) {
        require(current.size==proposed.size && proposed.distinct().size==proposed.size && current.toSet()==proposed.toSet()){"排序必须包含当前队列的每一项且只出现一次，请先加载完整队列"}
    }
    fun label(failure:Exception):Exception {
        val message=failure.message.orEmpty()
        val text=when{
            message.contains("user message queue is unavailable")->"当前核心未启用消息队列服务"
            message.contains("ephemeral thread")->"临时会话不支持持久待办队列"
            message.contains("archived")->"请先恢复已归档会话"
            message.contains("queued submission not found")->"待办已变化或开始执行，请刷新"
            else->message.ifBlank{"队列操作失败，请核查实际状态"}
        }
        return IllegalStateException(text,failure)
    }
}
class OriginalQueueController(scope:CoroutineScope,activeTask:()->Boolean,lifecycle:OriginalMutationLifecycle=OriginalMutationLifecycle.None) {
    val operations=OriginalOperationRunner(scope,activeTask,lifecycle)
    private val mutable=MutableStateFlow(OriginalQueueState())
    val state=mutable.asStateFlow()
    private var api:OriginalCodexClient?=null
    fun bind(client:OriginalCodexClient?){api=client;operations.bind(client);mutable.value=OriginalQueueState()}
    fun selectThread(id:String?){if(id==state.value.threadId)return;check(!operations.state.value.mutating){"队列变更尚未结束"};operations.bind(api);mutable.value=OriginalQueueState(threadId=id)}
    fun changed(id:String){if(id==state.value.threadId)mutable.update{it.copy(revision=it.revision+1)}}
    private fun update(stamp:Long,thread:String,change:(OriginalQueueState)->OriginalQueueState){if(operations.current(stamp) && state.value.threadId==thread)mutable.update(change)}
    private suspend fun readPage(client:OriginalCodexClient,stamp:Long,thread:String,more:Boolean=false) {
        val requested=if(more)state.value.page.nextCursor ?: return else null
        val revision=state.value.revision
        val result=client.listQueue(thread,requested).jsonObject
        val entries=(result["data"] as? JsonArray)?.map{it.jsonObject} ?: error("队列响应缺少data")
        require(entries.all{it.string("id").isNotBlank()}){"队列条目缺少标识"}
        val next=result.string("nextCursor").ifBlank{null}
        update(stamp,thread){it.copy(page=(if(more)it.page else OriginalSessionPage()).append(entries,next,requested ?: "first"){row->row.string("id")},loadedRevision=if(more && it.dirty)it.loadedRevision else revision)}
    }
    fun refresh(more:Boolean=false){val thread=state.value.threadId ?: return;operations.run{client,stamp->try{readPage(client,stamp,thread,more);operations.notice(stamp,"已读取实际待办队列")}catch(failure:Exception){if(failure is CancellationException)throw failure;throw OriginalQueueData.label(failure)}}}
    suspend fun all(client:OriginalCodexClient,thread:String):List<JsonObject> {
        val result=mutableListOf<JsonObject>();val seen=mutableSetOf<String>();var cursor:String?=null
        do{
            val page=client.listQueue(thread,cursor).jsonObject
            result+=(page["data"] as? JsonArray)?.map{it.jsonObject} ?: error("队列响应缺少data")
            cursor=page.string("nextCursor").ifBlank{null}
            require(cursor==null || seen.add(cursor!!)){"核心返回重复分页标识，请刷新后再操作"}
        }while(cursor!=null)
        require(result.map{it.string("id")}.distinct().size==result.size){"队列分页期间发生变化，请刷新"}
        return result
    }
    suspend fun verify(client:OriginalCodexClient,thread:String,approved:JsonObject) {
        val revision=state.value.revision
        val actual=all(client,thread).firstOrNull{it.string("id")==approved.string("id")}
        require(actual==approved && (thread!=state.value.threadId || revision==state.value.revision)){"待办内容或队列已变化，请刷新并重新确认"}
    }
    private fun mutate(action:suspend(OriginalCodexClient,Long,String)->Unit) {
        val thread=state.value.threadId ?: return
        operations.run(true){client,stamp->try{action(client,stamp,thread);readPage(client,stamp,thread)}catch(failure:Exception){if(failure is CancellationException)throw failure;throw OriginalQueueData.label(failure)}}
    }
    fun add(text:String)=mutate{client,stamp,thread->require(text.isNotBlank());val message=UUID.randomUUID().toString();val item=client.addQueue(thread,OriginalCodexClient.textInput(text),message).jsonObject["queuedSubmission"] as? JsonObject ?: error("加入结果缺少queuedSubmission");require(item.string("id").isNotBlank() && item.string("clientUserMessageId")==message){"加入队列的结果标识不匹配，请刷新核查"};operations.notice(stamp,"已加入待办，核心可能立即或在当前回合结束后执行")}
    fun prepareStart(approved:JsonObject,onReady:()->Unit){val thread=state.value.threadId ?: return;operations.run{client,stamp->verify(client,thread,approved);if(operations.current(stamp) && state.value.threadId==thread)onReady()}}
    fun update(approved:JsonObject,input:JsonArray)=mutate{client,stamp,thread->
        require(input.isNotEmpty() && input.all{it is JsonObject}){"输入必须是非空对象数组"};verify(client,thread,approved)
        val result=client.updateQueue(thread,approved.string("id"),input).jsonObject["queuedSubmission"] as? JsonObject ?: error("更新结果缺少queuedSubmission")
        require(result.string("id")==approved.string("id")){"更新结果标识不匹配，请刷新核查"};operations.notice(stamp,"已更新并读取队列")
    }
    fun delete(approved:JsonObject)=mutate{client,stamp,thread->verify(client,thread,approved)
        val deleted=(client.deleteQueue(thread,approved.string("id")).jsonObject["deleted"] as? JsonPrimitive)?.booleanOrNull ?: error("删除结果缺少deleted")
        operations.notice(stamp,if(deleted)"待办已删除" else "待办已不在队列中")
    }
    fun reorder(approved:List<JsonObject>,ids:List<String>)=mutate{client,stamp,thread->
        OriginalQueueData.requireOrder(approved.map{it.string("id")},ids)
        val revision=state.value.revision;val current=all(client,thread)
        require(current==approved && revision==state.value.revision){"队列已变化，请刷新并重新排序"}
        client.reorderQueue(thread,ids)
        require(all(client,thread).map{it.string("id")}==ids){"已请求排序，但实际队列又有变化，请刷新核查"};operations.notice(stamp,"已保存顺序并读取队列")
    }
}
