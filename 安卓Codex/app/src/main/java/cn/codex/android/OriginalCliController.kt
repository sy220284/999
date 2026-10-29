package cn.codex.android
import cn.codex.core.original.*
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

data class CloudDiffPreview(val task:String,val attempt:Int,val text:String,val token:String=UUID.randomUUID().toString(),val latest:Boolean=false) {
    val sha256:String get()=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
data class OriginalCliState(val busy:Boolean=false,val title:String="云任务与本机维护",val output:String="",val artifact:String?=null,val error:String?=null,
    val environments:List<JsonObject> = emptyList(),val environmentsTruncated:Boolean=false,val migrationInspected:Boolean=false,val tasks:List<JsonObject> = emptyList(),val cursor:String?=null,val environment:String?=null,val preview:CloudDiffPreview?=null)
class OriginalCliController(private val scope:CoroutineScope,private val busyElsewhere:()->Boolean,
    private val lifecycle:OriginalMutationLifecycle,private val execute:suspend(OriginalCliPlan)->OriginalCliResult) {
    private val mutable=MutableStateFlow(OriginalCliState());val state=mutable.asStateFlow();private var job:Job?=null
    private fun run(plan:OriginalCliPlan,consume:(String)->Unit={}){
        if(state.value.busy)return
        if(busyElsewhere()){mutable.update{it.copy(error="请先断开原版连接，并结束其他执行或配置任务")};return}
        val ticket=try{lifecycle.begin()}catch(_:Exception){mutable.update{it.copy(error="无法保存本次操作记录，请先核查未结束记录")};return}
        mutable.update{it.copy(busy=true,title=plan.title,error=null,output="",artifact=null,migrationInspected=if(plan.mutating)false else it.migrationInspected)}
        job=scope.launch{var confirmed=false
            try{
                val result=execute(plan)
                if(!plan.mutating)confirmed=true // 只读命令已真实退出，诊断失败不留下未知写入记录
                check(result.output.toByteArray().size<=2*1024*1024){"命令返回超出预算"}
                mutable.update{it.copy(output=result.output.take(65536),artifact=result.artifact)}
                check(result.exitCode==0){"原版命令退出码${result.exitCode}，请核查账号、环境、文件冲突或权限；本次不会自动重试"}
                consume(result.output);confirmed=true
            }catch(error:CancellationException){mutable.update{it.copy(error="操作已停止，请核查已发生的远程或文件变更")};throw error} catch(error:Exception){mutable.update{it.copy(error=error.message ?: "操作未完成，请核查实际结果")}} finally{try{lifecycle.finish(ticket,confirmed)}catch(_:Exception){mutable.update{it.copy(error="操作记录保存失败，请核查实际结果")}}finally{mutable.update{it.copy(busy=false)}}}
        }
    }
    fun environments()=run(OriginalCliCommands.cloudEnvironments()){text->
        val result=Json.parseToJsonElement(text).jsonObject;val rows=result.getValue("environments").jsonArray.map{it.jsonObject}
        require(rows.size<=200 && rows.all{it.string("id").isNotBlank()})
        mutable.update{it.copy(environments=rows,environmentsTruncated=result["truncated"]?.jsonPrimitive?.booleanOrNull==true)}
    }
    fun list(environment:String?,more:Boolean=false){
        val env=environment?.trim()?.takeIf{it.isNotEmpty()}
        if(more && (env!=state.value.environment || state.value.cursor==null))return
        val cursor=if(more)state.value.cursor else null
        run(OriginalCliCommands.cloudList(env,cursor)){text->
            val page=Json.parseToJsonElement(text).jsonObject
            val tasks=page.getValue("tasks").jsonArray.map{it.jsonObject};require(tasks.size<=20)
            require(tasks.all{it.string("id").isNotBlank()})
            val next=(page["cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf{it.isNotBlank()};require(next==null || next.length<=8192)
            val all=if(more)(state.value.tasks+tasks).distinctBy{it.string("id")}else tasks;require(all.size<=200){"本页最多保留200个任务，请缩小环境范围"}
            mutable.update{it.copy(tasks=all,cursor=next,environment=env)}
        }
    }
    fun create(environment:String,prompt:String,branch:String?,attempts:Int)=run(OriginalCliCommands.cloudCreate(environment,prompt,branch,attempts))
    fun status(task:String)=run(OriginalCliCommands.cloudStatus(task))
    fun diff(task:String,attempt:Int)=run(OriginalCliCommands.cloudDiff(task,attempt)){text->
        require(text.isNotBlank() && text.toByteArray().size<=128*1024){"差异为空或超过128KiB预览预算，请在原服务细化任务后重试"}
        mutable.update{it.copy(preview=CloudDiffPreview(task,attempt,text))}
    }
    fun latestDiff(task:String)=run(OriginalCliCommands.latestDiff(task)){text->
        require(text.isNotBlank() && text.toByteArray().size<=128*1024){"差异为空或超过128KiB预览预算"}
        mutable.update{it.copy(preview=CloudDiffPreview(task,0,text,latest=true))}
    }
    fun apply(approved:CloudDiffPreview){
        if(state.value.preview?.token!=approved.token)return
        run(if(approved.latest)OriginalCliCommands.latestApply(approved.task,approved.text)else OriginalCliCommands.cloudApply(approved.task,approved.attempt,approved.text)){mutable.update{it.copy(preview=null)}}
    }
    fun invalidateMaintenance(){mutable.update{it.copy(migrationInspected=false)}}
    fun maintain(plan:OriginalCliPlan){
        require(plan.arguments.firstOrNull() in setOf("doctor","migrate-rollouts","migrate-nfs-db","completion","execpolicy","debug","app-server"))
        if(plan.arguments.first()=="migrate-rollouts" && plan.mutating)check(state.value.migrationInspected){"请先检查本次旧会话历史报告"}
        run(plan){text->if(plan.arguments.first()=="migrate-rollouts" && !plan.mutating){Json.parseToJsonElement(text);mutable.update{it.copy(migrationInspected=true)}}}
    }
    fun stop(){job?.cancel()}
}
