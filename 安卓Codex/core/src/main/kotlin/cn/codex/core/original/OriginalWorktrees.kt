package cn.codex.core.original
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class OriginalWorktreeState(val rows:List<JsonObject> = emptyList(),val thread:JsonObject?=null,val retained:String?=null)
class OriginalWorktrees(scope:CoroutineScope,active:()->Boolean,lifecycle:OriginalMutationLifecycle,private val source:String,private val execute:suspend(List<String>)->String){
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalWorktreeState());val state=mutable.asStateFlow()
    fun bind(api:OriginalCodexClient?){operations.bind(api);mutable.value=OriginalWorktreeState()}
    fun refresh()=operations.run{_,stamp->val rows=Json.parseToJsonElement(execute(listOf("list",source))).jsonArray.map{it.jsonObject};require(rows.size<=200);if(operations.current(stamp))mutable.update{it.copy(rows=rows)};operations.notice(stamp,"已读取原版托管工作树")}
    fun create(fork:String?,model:String?)=operations.run(true){api,stamp->
        val checkout=Json.parseToJsonElement(execute(listOf("create",source))).jsonObject
        val root=checkout.string("root");val cwd=checkout.string("cwd");require(root.isNotBlank() && cwd.isNotBlank())
        if(operations.current(stamp))mutable.update{it.copy(retained=root)}
        // 会话请求一旦发出，结果不明时保留目录；绝不删除可能已经被核心/其他客户端使用的工作树。
        val result=if(fork==null)api.start(cwd,model)else api.fork(fork,cwd)
        val thread=result.jsonObject.getValue("thread").jsonObject;require(thread.string("cwd")==cwd && thread.string("id").isNotBlank()){"核心未使用新工作树目录，请核查保留目录"}
        execute(listOf("bind",source,root,thread.string("id")))
        val listed=Json.parseToJsonElement(execute(listOf("list",source))).jsonArray.map{it.jsonObject}
        if(operations.current(stamp))mutable.update{it.copy(thread=thread,retained=null,rows=listed)}
        operations.notice(stamp,"工作树与会话已绑定；尚未发送模型任务")
    }
    fun remove(approved:JsonObject)=operations.run(true){api,stamp->
        val rows=Json.parseToJsonElement(execute(listOf("list",source))).jsonArray.map{it.jsonObject};require(rows.any{it==approved}){"工作树信息已变化，请重新读取"}
        val owner=approved.string("ownerThreadId")
        if(owner.isNotBlank()){
            val thread=api.read(owner,false).jsonObject.getValue("thread").jsonObject;require(thread.string("id")==owner)
            require((thread["status"] as? JsonObject)?.string("type") in setOf("idle","notLoaded")){"关联会话仍在运行，请先停止并核查"}
            api.unsubscribe(owner)
            val fresh=api.read(owner,false).jsonObject.getValue("thread").jsonObject
            require((fresh["status"] as? JsonObject)?.string("type")=="notLoaded"){"关联会话仍被客户端持有，未清理目录"}
        }
        execute(listOf("remove",source,approved.string("root")))
        val after=Json.parseToJsonElement(execute(listOf("list",source))).jsonArray.map{it.jsonObject};if(operations.current(stamp))mutable.update{it.copy(rows=after)}
        operations.notice(stamp,"原版管理器已清理工作树；有本地或忽略文件时会拒绝删除")
    }
}
