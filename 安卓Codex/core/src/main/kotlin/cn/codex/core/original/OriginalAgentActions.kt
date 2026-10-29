package cn.codex.core.original
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class AgentStopPreview(val thread:String,val turn:String)
data class OriginalAgentActionState(val hidden:Set<String> = emptySet(),val updated:Map<String,JsonObject> = emptyMap(),val stop:AgentStopPreview?=null,val usage:JsonObject?=null)
class OriginalAgentActions(scope:CoroutineScope,active:()->Boolean,lifecycle:OriginalMutationLifecycle){
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalAgentActionState());val state=mutable.asStateFlow()
    fun bind(api:OriginalCodexClient?){operations.bind(api);mutable.value=OriginalAgentActionState()}
    private suspend fun read(api:OriginalCodexClient,id:String)=api.read(id,false).jsonObject.getValue("thread").jsonObject.also{require(it.string("id")==id)}
    private fun update(stamp:Long,row:JsonObject){if(operations.current(stamp))mutable.update{it.copy(updated=(it.updated+(row.string("id") to row)).entries.toList().takeLast(200).associate{entry->entry.toPair()})}}
    fun hide(id:String){mutable.update{it.copy(hidden=(it.hidden+id).take(200).toSet())}}
    fun refreshed(ids:Set<String>){mutable.update{it.copy(updated=it.updated.filterKeys{key->key !in ids})}}
    fun restoreHidden(){mutable.update{it.copy(hidden=emptySet())}}
    fun rename(approved:JsonObject,name:String)=operations.run(true){api,stamp->require(name.isNotBlank() && name.length<=256);val id=approved.string("id");verifyMetadata(approved,read(api,id));api.rename(id,name);update(stamp,read(api,id));operations.notice(stamp,"名称已保存并重新读取")}
    fun pin(approved:JsonObject,pinned:Boolean,before:String?=null)=operations.run(true){api,stamp->val id=approved.string("id");require(before!=id);verifyMetadata(approved,read(api,id));api.moveSection(id,if(pinned)PINNED else null,before);update(stamp,read(api,id));operations.notice(stamp,"置顶与顺序请求已返回，请核对列表")}
    fun fork(approved:JsonObject)=operations.run(true){api,stamp->val id=approved.string("id");verifyMetadata(approved,read(api,id));val thread=api.fork(id).jsonObject.getValue("thread").jsonObject;require(thread.string("id").isNotBlank() && thread.string("id")!=id);update(stamp,thread);operations.notice(stamp,"已分叉新会话，可在总览切换；没有自动发送任务")}
    fun remove(approved:JsonObject,delete:Boolean)=operations.run(true){api,stamp->val id=approved.string("id");verifyMetadata(approved,read(api,id));if(delete)api.deleteThread(id)else api.archive(id,true);if(operations.current(stamp))mutable.update{it.copy(hidden=it.hidden+id,updated=it.updated-id)};operations.notice(stamp,if(delete)"删除请求已返回；请刷新核查"else"归档请求已返回；可从归档页恢复")}
    private suspend fun active(api:OriginalCodexClient,id:String):String?{
        val thread=read(api,id)
        val turns=if(thread.string("historyMode")=="paginated")api.turns(id).jsonObject.getValue("data").jsonArray else api.read(id,true).jsonObject.getValue("thread").jsonObject["turns"] as? JsonArray ?: JsonArray(emptyList())
        return activeTurn(turns)
    }
    fun inspectStop(id:String)=operations.run{api,stamp->val turn=active(api,id);if(operations.current(stamp))mutable.update{it.copy(stop=turn?.let{value->AgentStopPreview(id,value)})};operations.notice(stamp,if(turn==null)"没有读取到进行中的回合"else"已读取进行中的回合，请明确确认停止")}
    fun dismissStop(){mutable.update{it.copy(stop=null)}}
    fun stop(approved:AgentStopPreview)=operations.run(true){api,stamp->require(state.value.stop===approved){"停止预览已变化"};require(active(api,approved.thread)==approved.turn){"任务回合已经变化，请重新核查"};api.interrupt(approved.thread,approved.turn);if(operations.current(stamp))mutable.update{it.copy(stop=null)};update(stamp,read(api,approved.thread));operations.notice(stamp,"已请求停止指定回合；以核心实际状态为准，不撤销已发生变更")}
    fun usage(id:String)=operations.run{api,stamp->val result=api.accountUsage(id).jsonObject;if(operations.current(stamp))mutable.update{it.copy(usage=result)};operations.notice(stamp,"已读取此会话的服务用量")}
    companion object {
        const val PINNED="01984de2-8f74-7c91-a3b2-5c5e937cf318"
        fun verifyMetadata(approved:JsonObject,current:JsonObject){require(approved.string("id").isNotBlank());require(listOf("id","name","cwd","section","projectId","parentThreadId","source").all{approved[it]==current[it]}){"会话元数据已变化，请重新读取后确认"}}
        fun activeTurn(turns:JsonArray):String?{val active=turns.map{it.jsonObject}.filter{it.string("status")=="inProgress"};require(active.size<=1){"返回了多个活动回合，请核查核心状态"};return active.singleOrNull()?.string("id")?.takeIf{it.isNotBlank()}}
    }
}
