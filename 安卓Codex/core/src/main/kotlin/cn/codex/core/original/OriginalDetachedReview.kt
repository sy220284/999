package cn.codex.core.original
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class DetachedReviewState(val source:String?=null,val thread:String?=null,val turn:String?=null,val status:String="",val result:JsonObject?=null,val stopBusy:Boolean=false,val stopError:String?=null)
/** 单独跟踪原版返回的审查会话/回合；轮询真实终态，前台记录持有到结束。 */
class OriginalDetachedReview(private val scope:CoroutineScope,active:()->Boolean,lifecycle:OriginalMutationLifecycle){
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(DetachedReviewState());val state=mutable.asStateFlow()
    private var client:OriginalCodexClient?=null
    private var control:Job?=null
    fun bind(api:OriginalCodexClient?){control?.cancel();operations.bind(api);client=api;mutable.value=DetachedReviewState()}
    fun start(source:String,target:JsonObject)=operations.run(true){api,stamp->
        require(source.isNotBlank());mutable.value=DetachedReviewState(source=source,status="正在创建独立审查")
        var thread:String?=null;var turn:String?=null;var completed=false
        try{withTimeout(1_800_000){
            val response=api.review(source,target,"detached").jsonObject
            thread=response.string("reviewThreadId");turn=response.getValue("turn").jsonObject.string("id")
            require(!thread.isNullOrBlank() && thread!=source && !turn.isNullOrBlank()){"原版没有返回独立审查身份"}
            if(operations.current(stamp))mutable.update{it.copy(thread=thread,turn=turn,status="独立审查进行中")}
            var actual=response.getValue("turn").jsonObject
            while(actual.string("status") !in setOf("completed","failed","interrupted")){
                delay(1000)
                val page=api.turns(requireNotNull(thread)).jsonObject
                actual=page.getValue("data").jsonArray.map{it.jsonObject}.firstOrNull{it.string("id")==turn} ?: error("没有在原版审查历史中找到指定回合")
            }
            completed=true
            if(operations.current(stamp))mutable.update{it.copy(status="独立审查已结束：${actual.string("status")}",result=actual)}
            operations.notice(stamp,"独立审查真实终态已读取，可打开审查会话查看完整结果")
        }}catch(failure:RpcException){if(thread==null && failure.code in setOf(-32600,-32601,-32602))completed=true;throw failure}finally{
            if(!completed)withContext(NonCancellable){
                try{if(!thread.isNullOrBlank() && !turn.isNullOrBlank())withTimeout(3000){api.interrupt(requireNotNull(thread),requireNotNull(turn))}}
                finally{api.connection.close()}
            }
        }
    }
    fun stop(){
        val api=client ?: return;if(!operations.state.value.busy || state.value.stopBusy)return
        val thread=state.value.thread;val turn=state.value.turn
        if(thread==null || turn==null){api.connection.close();return}
        mutable.update{it.copy(stopBusy=true,stopError=null)}
        control=scope.launch{try{api.interrupt(thread,turn)}catch(cancel:CancellationException){throw cancel}catch(failure:Exception){if(client===api)mutable.update{it.copy(stopError=failure.message ?: "停止请求未确认")}}finally{if(client===api)mutable.update{it.copy(stopBusy=false)}}}
    }
}
