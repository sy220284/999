package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

interface OriginalProjectRequestStore {
    fun read():JsonObject?
    fun write(value:JsonObject)
    fun clear(key:String?=null)
}

data class OriginalProjectsState(val page:OriginalCompletionPage=OriginalCompletionPage(),val selected:JsonObject?=null,
    val revision:Long=0,val dirty:Boolean=false,val creationKey:String=UUID.randomUUID().toString(),val creationPayload:JsonObject?=null,val recoveryError:String?=null)
class OriginalProjectsController(scope:CoroutineScope,active:()->Boolean,lifecycle:OriginalMutationLifecycle,private val store:OriginalProjectRequestStore?=null) {
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalProjectsState());val state=mutable.asStateFlow()
    fun bind(api:OriginalCodexClient?){
        operations.bind(api)
        val loaded=runCatching{store?.read()}
        mutable.value=loaded.fold(onSuccess={value->if(value==null)OriginalProjectsState()else{
            val key=value.string("key");val payload=value["payload"] as? JsonObject
            if(key.isBlank() || payload==null)OriginalProjectsState(recoveryError="项目请求记录损坏，请核查列表后明确新建请求") else OriginalProjectsState(creationKey=key,creationPayload=payload)
        }},onFailure={OriginalProjectsState(recoveryError="上次项目请求读取失败，请核查项目列表后明确新建请求")})
    }
    fun changed(){mutable.update{it.copy(revision=it.revision+1,dirty=true)}}
    private fun update(stamp:Long,f:(OriginalProjectsState)->OriginalProjectsState){if(operations.current(stamp))mutable.update(f)}
    private suspend fun list(api:OriginalCodexClient,stamp:Long,cursor:String?){
        val revision=state.value.revision;val page=state.value.page.append(api.projects(cursor).jsonObject,cursor)
        update(stamp){it.copy(page=page,dirty=it.revision!=revision)}
    }
    fun refresh(more:Boolean=false){val cursor=if(more)state.value.page.next ?: return else null;operations.run{api,stamp->list(api,stamp,cursor);operations.notice(stamp,"项目已读取")}}
    fun read(id:String)=operations.run{api,stamp->val project=api.project(id).jsonObject.getValue("project").jsonObject;require(project.string("id")==id);update(stamp){it.copy(selected=project)};operations.notice(stamp,"项目详情已读取")}
    fun newCreation(){if(!operations.state.value.busy){store?.clear();mutable.update{it.copy(creationKey=UUID.randomUUID().toString(),creationPayload=null,recoveryError=null)}}}
    fun create(name:String,paths:String,threads:String,import:Boolean) {
        operations.run(true){api,stamp->
            require(state.value.recoveryError==null){state.value.recoveryError.orEmpty()};require(name.isNotBlank()){"请填写项目名称"}
            val roots=OriginalCompletionData.roots(paths)
            val ids=threads.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
            val payload=buildJsonObject{put("name",name);put("roots",roots);put("threads",JsonArray(ids.map(::JsonPrimitive)));put("import",import)}
            val old=state.value.creationPayload;require(old==null || old==payload){"上次创建结果待核查；修改内容前请明确新建请求"}
            val key=state.value.creationKey
            withContext(Dispatchers.IO){store?.write(buildJsonObject{put("key",key);put("payload",payload)})}
            update(stamp){it.copy(creationPayload=payload)}
            val response=if(import)api.importProject(name,roots,JsonArray(ids.map(::JsonPrimitive)),key)else api.createProject(name,roots,key)
            val project=response.jsonObject.getValue("project").jsonObject
            require(project.string("id").isNotBlank()){"服务未返回项目标识，请核查列表"}
            withContext(Dispatchers.IO){store?.clear(key)}
            update(stamp){it.copy(selected=project,creationPayload=null,creationKey=UUID.randomUUID().toString())}
            list(api,stamp,null);operations.notice(stamp,"项目已保存，请查看详情")
        }
    }
    private suspend fun verify(api:OriginalCodexClient,approved:JsonObject):String {
        val id=approved.string("id");require(id.isNotBlank())
        val actual=api.project(id).jsonObject.getValue("project").jsonObject
        require(actual==approved){"项目已变化，请重新读取并确认"};return id
    }
    fun save(approved:JsonObject,name:String,paths:String)=operations.run(true){api,stamp->
        require(name.isNotBlank());val roots=OriginalCompletionData.roots(paths);val id=verify(api,approved)
        val project=api.updateProject(id,name,roots).jsonObject.getValue("project").jsonObject
        require(project.string("id")==id);update(stamp){it.copy(selected=project)};list(api,stamp,null);operations.notice(stamp,"项目已更新；多端并发请核查最终详情")
    }
    fun move(approved:JsonObject,before:String?)=operations.run(true){api,stamp->val id=verify(api,approved);require(before!=id);api.moveProject(id,before);list(api,stamp,null);operations.notice(stamp,"项目顺序已重新读取")}
    fun delete(approved:JsonObject)=operations.run(true){api,stamp->val id=verify(api,approved);api.deleteProject(id);update(stamp){it.copy(selected=null)};list(api,stamp,null);operations.notice(stamp,"删除请求已返回，列表已刷新；目录文件未由客户端删除")}
}
