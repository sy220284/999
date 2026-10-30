package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class OriginalSearchState(val connected: Boolean = false, val busy: Boolean = false, val sessionId: String? = null, val query: String = "", val files: List<JsonObject> = emptyList(), val completed: Boolean = false, val error: String? = null, val status: String = "尚未搜索")
class OriginalSearchController(private val scope: CoroutineScope, private val root: String) {
    private val mutable = MutableStateFlow(OriginalSearchState())
    val state = mutable.asStateFlow()
    private var api: OriginalCodexClient? = null
    private var generation=0L
    private var job: Job?=null
    fun bind(client: OriginalCodexClient?) { generation++;stopAfterBusy=false;stopping=false;retiring=null;job?.cancel();api=client;mutable.value=OriginalSearchState(connected=client!=null) }
    fun search(query: String) {
        if(state.value.busy || query.isBlank())return
        if(state.value.error!=null && state.value.sessionId!=null){mutable.update{it.copy(error="上次搜索状态未确认，请先停止后再搜索")};return}
        stopAfterBusy=false
        val client=api ?: return;val stamp=generation;val old=state.value.sessionId
        val id=UUID.randomUUID().toString();retiring=old
        // 每次新查询用独立ID；原协议无查询序号，避免A→B→A的迟到结果串入。
        mutable.value=OriginalSearchState(connected=true,busy=true,sessionId=id,query=query,status="正在搜索当前项目")
        job=scope.launch {
            try {
                if(old!=null)client.stopSearchSession(old)
                if(stamp==generation)retiring=null
                client.startSearchSession(id,listOf(root))
                if(!stopAfterBusy)client.updateSearchSession(id,query)
                if(stamp==generation)mutable.update { it.copy(status=if(it.completed)"搜索已完成" else "等待搜索结果") }
            } catch(cancelled:CancellationException){if(cancelled !is TimeoutCancellationException)throw cancelled;if(stamp==generation)mutable.update { it.copy(error="搜索超时，请停止后重试") }} catch(failure:Exception){if(stamp==generation)mutable.update { it.copy(error=failure.message ?: "搜索失败，请停止后重试") }} finally{if(stamp==generation){mutable.update { it.copy(busy=false) };if(stopAfterBusy){stopAfterBusy=false;stop()}}}
        }
    }
    fun stop() {
        if(state.value.busy){if(!stopping)stopAfterBusy=true;return}
        val client=api ?: return;val id=state.value.sessionId ?: return;val stamp=generation
        stopping=true
        mutable.update { it.copy(busy=true,error=null) }
        job=scope.launch {
            try {for(target in listOfNotNull(retiring,id).distinct())client.stopSearchSession(target);if(stamp==generation){retiring=null;mutable.update { it.copy(sessionId=null,completed=true,status="搜索已停止") }}} catch(failure:Exception){if(failure is CancellationException && failure !is TimeoutCancellationException)throw failure;if(stamp==generation)mutable.update { it.copy(error="停止结果未确认，请再次停止或关闭核心") }} finally{if(stamp==generation){stopping=false;stopAfterBusy=false;mutable.update { it.copy(busy=false) }}}
        }
    }
    fun notification(method: String,p:JsonObject) {
        if(p.string("sessionId")!=state.value.sessionId)return
        if(method=="fuzzyFileSearch/sessionUpdated" && p.string("query")==state.value.query) mutable.update { it.copy(files=(p["files"] as? JsonArray).orEmpty().mapNotNull { row -> row as? JsonObject }.take(200)) }
        if(method=="fuzzyFileSearch/sessionCompleted")mutable.update { it.copy(completed=true,status="搜索已完成") }
    }
}
