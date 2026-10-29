package cn.codex.core.original
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class WorktreeCreationPreview(val configuration:WorktreeConfiguration,val root:String,val fork:String?,val model:String?,val base:String?)
data class OriginalWorktreeState(val rows:List<JsonObject> = emptyList(),val thread:JsonObject?=null,val retained:String?=null,val settings:JsonObject?=null,val preview:WorktreeCreationPreview?=null)
class OriginalWorktrees(scope:CoroutineScope,active:()->Boolean,lifecycle:OriginalMutationLifecycle,private val source:String,private val execute:suspend(List<String>,JsonObject)->String){
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalWorktreeState());val state=mutable.asStateFlow()
    fun bind(api:OriginalCodexClient?){operations.bind(api);mutable.value=OriginalWorktreeState()}
    private suspend fun config(api:OriginalCodexClient)=WorktreeConfiguration.from(api.config(source).jsonObject)
    private suspend fun invoke(arguments:List<String>,configuration:WorktreeConfiguration,base:String?=null)=execute(arguments,configuration.request(base))
    fun refresh()=operations.run{api,stamp->val configuration=config(api);val settings=Json.parseToJsonElement(invoke(listOf("settings",source),configuration)).jsonObject;val rows=Json.parseToJsonElement(invoke(listOf("list",source),configuration)).jsonArray.map{it.jsonObject};require(rows.size<=200);if(operations.current(stamp))mutable.update{it.copy(rows=rows,settings=settings)};operations.notice(stamp,"已读取原版托管工作树")}
    fun prepareCreate(fork:String?,model:String?,base:String?=null)=operations.run{api,stamp->
        val configuration=config(api);configuration.request(base)
        val settings=Json.parseToJsonElement(invoke(listOf("settings",source),configuration)).jsonObject
        val root=settings.string("root");require(root.isNotBlank())
        if(operations.current(stamp))mutable.update{it.copy(preview=WorktreeCreationPreview(configuration,root,fork,model,base),settings=settings)}
        operations.notice(stamp,"已核对创建目录与版本，请确认后创建")
    }
    fun dismissCreate(){mutable.update{it.copy(preview=null)}}
    fun create(approved:WorktreeCreationPreview)=operations.run(true){api,stamp->
        require(state.value.preview===approved){"创建预览已经变化"}
        val configuration=config(api);require(configuration==approved.configuration){"工作树配置已变化，请重新核对创建目录"}
        val fork=approved.fork;val model=approved.model;val base=approved.base
        if(operations.current(stamp))mutable.update{it.copy(preview=null)}
        val checkout=Json.parseToJsonElement(invoke(listOf("create",source),configuration,base)).jsonObject
        val root=checkout.string("root");val cwd=checkout.string("cwd");require(root.isNotBlank() && cwd.isNotBlank())
        if(operations.current(stamp))mutable.update{it.copy(retained=root)}
        // 会话请求一旦发出，结果不明时保留目录；绝不删除可能已经被核心/其他客户端使用的工作树。
        val result=if(fork==null)api.start(cwd,model)else api.fork(fork,cwd)
        val thread=result.jsonObject.getValue("thread").jsonObject;require(thread.string("cwd")==cwd && thread.string("id").isNotBlank()){"核心未使用新工作树目录，请核查保留目录"}
        require(config(api)==configuration){"工作树配置已变化，保留新目录与会话，请核查后手动绑定"}
        invoke(listOf("bind",source,root,thread.string("id")),configuration)
        val listed=Json.parseToJsonElement(invoke(listOf("list",source),configuration)).jsonArray.map{it.jsonObject}
        if(operations.current(stamp))mutable.update{it.copy(thread=thread,retained=null,rows=listed)}
        operations.notice(stamp,"工作树与会话已绑定；尚未发送模型任务")
    }
    suspend fun managedRows(api:OriginalCodexClient):List<JsonObject>{
        val configuration=config(api)
        return Json.parseToJsonElement(invoke(listOf("list",source),configuration)).jsonArray.map{it.jsonObject}.also{require(it.size<=200)}
    }
    fun remove(approved:JsonObject)=operations.run(true){api,stamp->
        val configuration=config(api)
        val rows=Json.parseToJsonElement(invoke(listOf("list",source),configuration)).jsonArray.map{it.jsonObject};require(rows.any{it==approved}){"工作树信息已变化，请重新读取"}
        val owner=approved.string("ownerThreadId")
        if(owner.isNotBlank()){
            val thread=api.read(owner,false).jsonObject.getValue("thread").jsonObject;require(thread.string("id")==owner)
            require((thread["status"] as? JsonObject)?.string("type") in setOf("idle","notLoaded")){"关联会话仍在运行，请先停止并核查"}
            api.unsubscribe(owner)
            val fresh=api.read(owner,false).jsonObject.getValue("thread").jsonObject
            require((fresh["status"] as? JsonObject)?.string("type")=="notLoaded"){"关联会话仍被客户端持有，未清理目录"}
        }
        require(config(api)==configuration){"工作树配置已变化，请重新读取"}
        invoke(listOf("remove",source,approved.string("root")),configuration)
        val after=Json.parseToJsonElement(invoke(listOf("list",source),configuration)).jsonArray.map{it.jsonObject};if(operations.current(stamp))mutable.update{it.copy(rows=after)}
        operations.notice(stamp,"原版管理器已清理工作树；有本地或忽略文件时会拒绝删除")
    }
}
