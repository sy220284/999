package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class OriginalHistoryState(val query:String="",val archived:Boolean=false,val searches:OriginalCompletionPage=OriginalCompletionPage(),val threadId:String?=null,
    val occurrenceQuery:String="",val occurrences:OriginalCompletionPage=OriginalCompletionPage(),val timeline:OriginalCompletionPage=OriginalCompletionPage(),
    val terminals:OriginalCompletionPage=OriginalCompletionPage(),val detail:JsonObject?=null,val items:List<JsonObject> = emptyList())
class OriginalHistoryController(scope:CoroutineScope,private val thread:()->String?,active:()->Boolean,lifecycle:OriginalMutationLifecycle) {
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalHistoryState());val state=mutable.asStateFlow()
    fun bind(api:OriginalCodexClient?){operations.bind(api);mutable.value=OriginalHistoryState()}
    private fun update(stamp:Long,id:String?=null,f:(OriginalHistoryState)->OriginalHistoryState){if(operations.current(stamp) && (id==null || thread()==id))mutable.update(f)}
    fun search(query:String,archived:Boolean,more:Boolean=false) {
        val cursor=if(more && state.value.query==query && state.value.archived==archived)state.value.searches.next ?: return else null
        operations.run{api,stamp->require(query.isNotBlank()){"请输入搜索词"};val result=api.searchThreads(query,archived,cursor).jsonObject
            update(stamp){it.copy(query=query,archived=archived,searches=it.searches.append(result,cursor))};operations.notice(stamp,"会话搜索已返回")}
    }
    private fun select(id:String){if(state.value.threadId!=id)mutable.update{it.copy(threadId=id,occurrences=OriginalCompletionPage(),timeline=OriginalCompletionPage(),terminals=OriginalCompletionPage(),detail=null,items=emptyList())}}
    fun read(){val id=thread() ?: return;select(id);operations.run{api,stamp->val response=api.read(id,false).jsonObject;val value=JsonObject(response.getValue("thread").jsonObject+("readState" to (response["readState"] ?: JsonNull)));update(stamp,id){it.copy(detail=value)};operations.notice(stamp,"会话已读取，可按此版本标记已读或未读")}}
    fun mark(approved:JsonObject,read:Boolean){val id=approved.string("id");val revision=(approved["readState"] as? JsonObject)?.string("revision").orEmpty()
        operations.run(true){api,stamp->require(thread()==id){"当前会话已改变"};val value=api.updateReadState(id,revision,read).jsonObject.getValue("readState")
            update(stamp,id){it.copy(detail=JsonObject(approved+("readState" to value)))};operations.notice(stamp,"服务已确认阅读状态；冲突不会自动重试")}}
    fun occurrences(query:String,more:Boolean=false){val id=thread() ?: return;select(id)
        val cursor=if(more && query==state.value.occurrenceQuery)state.value.occurrences.next ?: return else null
        operations.run{api,stamp->require(query.isNotBlank());val response=api.searchOccurrences(id,query,cursor).jsonObject;update(stamp,id){it.copy(occurrenceQuery=query,occurrences=it.occurrences.append(response,cursor))};operations.notice(stamp,"匹配段落已读取")}}
    fun timeline(more:Boolean=false){val id=thread() ?: return;select(id);val cursor=if(more)state.value.timeline.next ?: return else null
        operations.run{api,stamp->val response=api.timeline(id,cursor).jsonObject;update(stamp,id){it.copy(timeline=it.timeline.append(response,cursor))};operations.notice(stamp,"时间线已读取")}}
    fun items(turn:String,ids:String){val id=thread() ?: return;select(id);operations.run{api,stamp->require(turn.isNotBlank());val response=api.readItems(id,turn,OriginalCompletionData.itemIds(ids)).jsonObject;update(stamp,id){it.copy(items=response.getValue("data").jsonArray.map{row->row.jsonObject})};operations.notice(stamp,"指定条目已读取")}}
    private suspend fun terminals(api:OriginalCodexClient,stamp:Long,id:String,cursor:String?){val response=api.backgroundTerminals(id,cursor).jsonObject;update(stamp,id){it.copy(terminals=it.terminals.append(response,cursor))}}
    fun terminals(more:Boolean=false){val id=thread() ?: return;select(id);val cursor=if(more)state.value.terminals.next ?: return else null;operations.run{api,stamp->terminals(api,stamp,id,cursor);operations.notice(stamp,"后台终端已读取")}}
    fun terminate(approved:JsonObject){val id=state.value.threadId ?: return;val process=approved.string("processId")
        operations.run(true){api,stamp->require(thread()==id && process.isNotBlank()){"会话或终端已改变"};val result=api.terminateBackgroundTerminal(id,process).jsonObject
            terminals(api,stamp,id,null);operations.notice(stamp,if(result["terminated"]?.jsonPrimitive?.booleanOrNull==true)"服务确认已终止该进程" else "服务未终止该进程，请查看最新列表")}}
    fun clean(){val id=thread() ?: return;operations.run(true){api,stamp->api.cleanBackgroundTerminals(id);terminals(api,stamp,id,null);operations.notice(stamp,"已请求清理后台终端；ACK不代表进程已退出，请核查最新列表")}}
}
