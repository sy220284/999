package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class OriginalAdvancedState(val memory:JsonObject?=null,val memoryStatus:JsonObject?=null,val threadSettings:JsonObject?=null,
    val sample:JsonObject?=null,val sampleRequest:JsonElement?=null,val cancelBusy:Boolean=false,val elicitation:JsonObject?=null,
    val plugins:OriginalCompletionPage=OriginalCompletionPage(),val pluginQuery:String="",val modes:JsonObject?=null,val diagnostics:JsonObject?=null)
class OriginalAdvancedController(private val scope:CoroutineScope,private val thread:()->String?,private val cwd:String,active:()->Boolean,lifecycle:OriginalMutationLifecycle) {
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalAdvancedState());val state=mutable.asStateFlow()
    private var api:OriginalCodexClient?=null;private var cancelJob:Job?=null
    fun bind(client:OriginalCodexClient?){cancelJob?.cancel();operations.bind(client);api=client;mutable.value=OriginalAdvancedState()}
    private fun update(stamp:Long,id:String?=null,f:(OriginalAdvancedState)->OriginalAdvancedState){if(operations.current(stamp) && (id==null || thread()==id))mutable.update(f)}
    private fun id()=requireNotNull(thread()){"请先选择会话"}
    fun settings(){val id=thread() ?: return;operations.run{api,stamp->val response=api.read(id,false).jsonObject.getValue("thread").jsonObject;update(stamp,id){it.copy(threadSettings=response)};operations.notice(stamp,"会话设置快照已读取")}}
    fun saveSettings(approved:JsonObject,text:String,turn:String?=null) {
        val id=approved.string("id")
        operations.run(turn==null){api,stamp->require(thread()==id){"当前会话已改变"};val patch=OriginalCompletionData.settings(text,turn!=null)
            val actual=api.read(id,false).jsonObject.getValue("thread").jsonObject
            require(actual==approved){"会话已变化，请重新读取后确认设置"}
            if(turn==null)api.updateThreadSettings(id,patch)else{require(turn.isNotBlank());val result=api.updateTurnSettings(id,turn,patch).jsonObject;operations.notice(stamp,"回合设置状态：${result.string("status")}")}
            val fresh=api.read(id,false).jsonObject.getValue("thread").jsonObject;update(stamp,id){it.copy(threadSettings=fresh)}
            if(turn==null)operations.notice(stamp,"设置请求已返回，已重新读取会话；权限调整请核对实际结果")
        }
    }
    fun readMemory(){val id=thread() ?: return;operations.run{api,stamp->val result=api.threadMemory(id).jsonObject;update(stamp,id){it.copy(memory=result)};operations.notice(stamp,"记忆摘要已读取")}}
    fun memoryStatus()=operations.run{api,stamp->val result=api.memoryStatus().jsonObject;update(stamp){it.copy(memoryStatus=result)};operations.notice(stamp,"全局记忆状态已读取")}
    fun memoryMode(enabled:Boolean){val id=thread() ?: return;operations.run(true){api,stamp->api.setMemoryMode(id,enabled);val result=api.threadMemory(id).jsonObject;update(stamp,id){it.copy(memory=result)};operations.notice(stamp,"记忆模式已请求变更，摘要已重新读取")}}
    fun resetMemory()=operations.run(true){api,stamp->api.resetMemory();val result=api.memoryStatus().jsonObject;update(stamp){it.copy(memoryStatus=result,memory=null)};operations.notice(stamp,"全局记忆重置请求已返回，最新状态如下")}
    fun compress()=operations.run(true){api,stamp->api.compressRollouts();operations.notice(stamp,"历史压缩已被接受；原版在后台执行，未宣告完成")}
    fun elicitation(pause:Boolean){val id=thread() ?: return;operations.run(true){api,stamp->val result=(if(pause)api.incrementElicitation(id)else api.decrementElicitation(id)).jsonObject;update(stamp,id){it.copy(elicitation=result)};operations.notice(stamp,"计数和超时暂停状态已由服务返回")}}
    fun sample(prompt:String,approved:JsonObject) {
        val id=approved.string("id")
        operations.run(true){api,stamp->
            require(thread()==id && prompt.isNotBlank());val snapshot=api.read(id,true).jsonObject.getValue("thread").jsonObject
            require(snapshot==approved){"来源会话已变化，请重新读取完整会话后确认"}
            val latest=snapshot.getValue("turns").jsonArray.lastOrNull()?.jsonObject ?: error("会话没有可采样回合")
            require(latest.string("status") in setOf("completed","failed","interrupted")){"来源回合尚未结束"}
            var completed=false;var issuedRequest:JsonElement?=null
            try {val result=api.sampleThread(id,latest.string("id"),prompt){request->issuedRequest=request;update(stamp,id){it.copy(sampleRequest=request,sample=null)}}.jsonObject
                completed=true;update(stamp,id){it.copy(sample=result)};operations.notice(stamp,"采样已返回；不写入会话历史")
            } catch(failure:RpcException){completed=true;throw failure} finally {
                val request=issuedRequest
                if(!completed && request!=null)withContext(NonCancellable){try{withTimeout(3000){api.cancelSample(request)}}catch(_:Exception){ }finally{api.connection.close()}}
                update(stamp){it.copy(sampleRequest=null)}
            }
        }
    }
    fun sampleSource(){val id=thread() ?: return;operations.run{api,stamp->val result=api.read(id,true).jsonObject.getValue("thread").jsonObject;update(stamp,id){it.copy(threadSettings=result)};operations.notice(stamp,"完整来源回合已读取，供采样前确认")}}
    fun cancelSample() {
        val request=state.value.sampleRequest ?: return;val client=api ?: return;if(state.value.cancelBusy)return
        mutable.update{it.copy(cancelBusy=true)}
        cancelJob=scope.launch{try{client.cancelSample(request);if(api===client && state.value.sampleRequest==request)mutable.update{it.copy(sample=buildJsonObject{put("status","取消信号已发送，等待原采样请求结束")})}}catch(failure:Exception){if(failure is CancellationException)throw failure;if(api===client && state.value.sampleRequest==request)mutable.update{it.copy(sample=buildJsonObject{put("cancelError",failure.message)})}}finally{if(api===client)mutable.update{it.copy(cancelBusy=false)}}}
    }
    fun searchPlugins(query:String,more:Boolean=false){val cursor=if(more && state.value.pluginQuery==query)state.value.plugins.next ?: return else null;operations.run{api,stamp->require(query.isNotBlank());val result=api.searchPlugins(query,cwd,cursor).jsonObject;update(stamp){it.copy(pluginQuery=query,plugins=it.plugins.append(result,cursor))};operations.notice(stamp,"插件搜索已返回；安装请进入插件管理核对来源和权限")}}
    fun modes()=operations.run{api,stamp->val result=api.collaborationModes().jsonObject;update(stamp){it.copy(modes=result)};operations.notice(stamp,"协作模式已读取，按服务返回的模式配置会话")}
    fun diagnostics()=operations.run{api,stamp->val result=api.serverDiagnostics().jsonObject;update(stamp){it.copy(diagnostics=result)};operations.notice(stamp,"核心进程诊断已读取，仅保留本次连接内存")}
}
