package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*

data class OriginalRealtimeState(val connected:Boolean=false,val running:Boolean=false,val ready:Boolean=false,val controlBusy:Boolean=false,
    val threadId:String?=null,val sessionId:String?=null,val status:String="实时会话尚未开始",val error:String?=null,val voices:JsonObject?=null,val voicesBusy:Boolean=false,val webRtc:Boolean=false)
/** 前台操作记录持有到真实closed；启动/停止ACK均不能代替会话终态。 */
class OriginalRealtimeController(private val scope:CoroutineScope,private val busyElsewhere:()->Boolean,
    private val lifecycle:OriginalMutationLifecycle=OriginalMutationLifecycle.None) {
    private val mutable=MutableStateFlow(OriginalRealtimeState())
    val state=mutable.asStateFlow()
    private var api:OriginalCodexClient?=null
    private var generation=0L
    private var job:Job?=null
    private var control:Job?=null
    private var voicesJob:Job?=null
    private var controlSerial=0L
    private var stopRequested=false
    private var submitted=false
    private var finished:CompletableDeferred<Unit>?=null
    private var started:CompletableDeferred<Unit>?=null
    private var acknowledged:CompletableDeferred<Unit>?=null
    fun bind(client:OriginalCodexClient?) {
        generation++;voicesJob?.cancel();controlSerial++;stopRequested=false;job?.cancel();control?.cancel();finished?.cancel();started?.cancel();acknowledged?.cancel()
        finished=null;started=null;acknowledged=null;api=client;mutable.value=if(client==null)state.value.copy(connected=false,running=false,ready=false,controlBusy=false,status="连接已结束，请核查最后会话结果") else OriginalRealtimeState(connected=true)
    }
    fun start(thread:String,audioOutput:Boolean,options:JsonObject=buildJsonObject{},webRtcOffer:(suspend ()->String)?=null) {
        if(state.value.running)return
        if(busyElsewhere()){mutable.update{it.copy(error="请先结束其他执行任务或配置变更")};return}
        val client=api ?: return
        require(thread.isNotBlank())
        val ticket=try{lifecycle.begin()}catch(failure:Exception){mutable.update{it.copy(error=failure.message)};return}
        val stamp=generation
        val end=CompletableDeferred<Unit>();val begin=CompletableDeferred<Unit>();val ack=CompletableDeferred<Unit>()
        controlSerial++;control?.cancel();stopRequested=false;submitted=false
        finished=end;started=begin;acknowledged=ack
        mutable.value=OriginalRealtimeState(connected=true,running=true,threadId=thread,webRtc=webRtcOffer!=null,status="正在建立实时会话")
        job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            var confirmed=false
            try {
                val offer=webRtcOffer?.invoke()
                coroutineScope {
                    submitted=true
                    val response=async{client.startRealtime(thread,audioOutput,offer,options)}
                    select<Unit> {
                        response.onAwait {
                            ack.complete(Unit)
                            if(!end.isCompleted)withTimeout(60_000){select<Unit>{begin.onAwait{ };end.onAwait{ }}}
                            if(!end.isCompleted && stamp==generation && finished===end)mutable.update{it.copy(ready=true,status="实时会话进行中")}
                            end.await()
                        }
                        end.onAwait {response.cancel()}
                    }
                }
                confirmed=true
            } catch(cancelled:CancellationException) {
                if(cancelled !is TimeoutCancellationException)throw cancelled
                if(stamp==generation){mutable.update{it.copy(error="实时会话确认超时，已关闭核心，请核对会话结果")};client.connection.close()}
            } catch(failure:Exception) {
                if(stamp==generation){mutable.update{it.copy(error=failure.message ?: "实时会话失败")};if(failure !is RpcException || failure.code !in setOf(-32601,-32602))client.connection.close()}
            } finally {
                try{lifecycle.finish(ticket,confirmed && stamp==generation)}catch(failure:Exception){if(stamp==generation)mutable.update{it.copy(error="实时会话记录保存失败，请核查结果")}}
                finally{if(stamp==generation && finished===end)mutable.update{it.copy(running=false,ready=false,status=if(confirmed)it.status else if(!submitted)"媒体准备已结束，未发送服务启动请求" else "实时会话结果待核查")}}
            }
        }
    }
    private fun control(action:suspend(OriginalCodexClient,String,CompletableDeferred<Unit>)->Unit) {
        val client=api ?: return;val thread=state.value.threadId ?: return;val end=finished ?: return
        if(!state.value.running || state.value.controlBusy)return
        val stamp=generation;val serial=++controlSerial;mutable.update{it.copy(controlBusy=true,error=null)}
        control=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try{action(client,thread,end)}catch(failure:Exception){if(failure is CancellationException && failure !is TimeoutCancellationException)throw failure
                if(stamp==generation && finished===end && serial==controlSerial){mutable.update{it.copy(error=failure.message ?: "操作结果未确认，不会自动重发")};if(failure is TimeoutCancellationException)client.connection.close()}}
            finally{if(stamp==generation && finished===end && serial==controlSerial)mutable.update{it.copy(controlBusy=false)}}
        }
    }
    fun voices() {
        val client=api ?: return;if(state.value.voicesBusy)return
        val stamp=generation;mutable.update{it.copy(voicesBusy=true,error=null)}
        voicesJob=scope.launch{try{val result=client.realtimeVoices().jsonObject;if(stamp==generation)mutable.update{it.copy(voices=result)}}catch(failure:Exception){if(failure is CancellationException)throw failure;if(stamp==generation)mutable.update{it.copy(error=failure.message ?: "声音列表读取失败")}}finally{if(stamp==generation)mutable.update{it.copy(voicesBusy=false)}}}
    }
    fun speech(text:String) {
        if(!state.value.ready || text.isBlank())return
        control{client,thread,_ -> client.appendRealtimeSpeech(thread,text)}
    }
    fun send(text:String) {
        if(!state.value.ready || text.isBlank())return
        control{client,thread,_ -> client.appendRealtimeText(thread,text)}
    }
    suspend fun sendAudio(bytes:ByteArray,rate:Int=24_000,channels:Int=1) {
        val client=api ?: error("核心已断开");val thread=state.value.threadId ?: error("没有实时会话")
        check(state.value.running && state.value.ready){"实时会话尚未就绪"}
        client.appendRealtimeAudio(thread,bytes,rate,channels)
    }
    fun stop() {
        if(!state.value.running || stopRequested)return
        stopRequested=true
        if(!submitted){job?.cancel();return}
        if(state.value.controlBusy){controlSerial++;control?.cancel();mutable.update{it.copy(controlBusy=false)}}
        control{client,thread,end ->
            try {
                val ack=acknowledged ?: return@control
                withTimeout(60_000){select<Unit>{ack.onAwait{ };end.onAwait{ }}}
                if(!end.isCompleted){client.stopRealtime(thread);mutable.update{it.copy(status="已请求结束，等待会话关闭")};withTimeout(10_000){end.await()}}
            } finally {if(finished===end)stopRequested=false}
        }
    }
    fun acceptsStarted(thread:String)=state.value.threadId!=thread || state.value.running && finished?.isCompleted==false
    fun notification(method:String,p:JsonObject) {
        if(!state.value.running || p.string("threadId")!=state.value.threadId)return
        when(method){
            "thread/realtime/started"->{if(finished?.isCompleted==true)return;started?.complete(Unit);mutable.update{it.copy(sessionId=p.string("realtimeSessionId").ifBlank{null})}}
            "thread/realtime/closed"->{mutable.update{it.copy(ready=false,status="实时会话已关闭：${p.string("reason")}")};finished?.complete(Unit)}
            "thread/realtime/error"->mutable.update{it.copy(error=p.string("message","实时服务返回错误"))}
        }
    }
}
