package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class OriginalConnectionsState(val environmentId:String="",val environment:JsonObject?=null,val environmentStatus:JsonObject?=null,
    val remote:JsonObject?=null,val remoteRevision:Long=0,val pairing:JsonObject?=null,val claimed:Boolean?=null,val clients:OriginalCompletionPage=OriginalCompletionPage(),
    val bedrock:JsonObject?=null,val requirements:JsonObject?=null,val verification:JsonObject?=null,val proof:JsonObject?=null,
    val verificationRequest:JsonElement?=null,val cancelBusy:Boolean=false,val subscriptions:Map<String,JsonObject> = emptyMap(),val streamEvents:List<JsonObject> = emptyList())
class OriginalConnectionsController(private val scope:CoroutineScope,private val thread:()->String?,active:()->Boolean,lifecycle:OriginalMutationLifecycle) {
    val operations=OriginalOperationRunner(scope,active,lifecycle)
    private val mutable=MutableStateFlow(OriginalConnectionsState());val state=mutable.asStateFlow()
    private var api:OriginalCodexClient?=null;private var cancelJob:Job?=null
    fun bind(client:OriginalCodexClient?){cancelJob?.cancel();api=client;operations.bind(client);mutable.value=OriginalConnectionsState()}
    private fun update(stamp:Long,f:(OriginalConnectionsState)->OriginalConnectionsState){if(operations.current(stamp))mutable.update(f)}
    fun remoteChanged(params:JsonObject){mutable.update{it.copy(remote=params,remoteRevision=it.remoteRevision+1)}}
    fun streamEvent(params:JsonObject){
        val subscription=params.string("subscriptionId");if(subscription !in state.value.subscriptions)return
        val text=params.toString();val bounded=if(text.length<=32768)params else buildJsonObject{put("subscriptionId",subscription);put("truncated",true);put("preview",text.take(32768))}
        mutable.update{it.copy(streamEvents=(it.streamEvents+bounded).takeLast(100))}
    }
    fun environment(id:String)=operations.run{api,stamp->require(id.isNotBlank());val status=api.environmentStatus(id).jsonObject;update(stamp){it.copy(environmentId=id,environmentStatus=status,environment=null)}
        if(status.string("status")=="ready"){val info=api.environmentInfo(id).jsonObject;update(stamp){it.copy(environment=info)}};operations.notice(stamp,"环境状态已读取；未自动恢复离线环境")}
    fun addEnvironment(id:String,url:String,token:String,requestId:String)=operations.run(true){api,stamp->val params=OriginalCompletionData.environment(id,url,token,requestId);api.addEnvironment(params)
        val status=api.environmentStatus(id).jsonObject;val info=if(status.string("status")=="ready")api.environmentInfo(id).jsonObject else null;update(stamp){it.copy(environmentId=id,environmentStatus=status,environment=info)};operations.notice(stamp,"执行环境已请求加入并读取实际信息")}
    private suspend fun remote(api:OriginalCodexClient,stamp:Long){val revision=state.value.remoteRevision;val result=api.remoteStatus().jsonObject;update(stamp){if(it.remoteRevision==revision)it.copy(remote=result)else it}}
    fun remoteStatus()=operations.run{api,stamp->remote(api,stamp);operations.notice(stamp,"远程控制状态已读取")}
    fun enableRemote(enabled:Boolean,ephemeral:Boolean)=operations.run(true){api,stamp->if(enabled)api.enableRemote(ephemeral)else api.disableRemote(ephemeral);remote(api,stamp);operations.notice(stamp,"远程设置已请求变更，请核对实际连接状态")}
    fun pairing(manual:Boolean)=operations.run(true){api,stamp->val result=api.startPairing(manual).jsonObject;update(stamp){it.copy(pairing=result,claimed=null)};operations.notice(stamp,"配对码已生成；只向你信任的设备提供")}
    fun pairingStatus(approved:JsonObject)=operations.run{api,stamp->require(state.value.pairing==approved){"配对请求已改变"};val expires=approved["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0
        require(expires>System.currentTimeMillis()/1000){"配对码已过期，请重新生成"};val result=api.pairingStatus(approved.string("pairingCode")).jsonObject;update(stamp){it.copy(claimed=result["claimed"]?.jsonPrimitive?.booleanOrNull)};operations.notice(stamp,"配对领取状态已读取")}
    fun clients(more:Boolean=false){val environment=state.value.remote?.string("environmentId").orEmpty();val cursor=if(more)state.value.clients.next ?: return else null
        operations.run{api,stamp->require(environment.isNotBlank()){"远程控制未返回环境标识"};val result=api.remoteClients(environment,cursor).jsonObject;update(stamp){if(it.remote?.string("environmentId")==environment)it.copy(clients=it.clients.append(result,cursor))else it};operations.notice(stamp,"已配对设备已读取")}}
    fun revoke(environment:String,client:String)=operations.run(true){api,stamp->require(state.value.remote?.string("environmentId")==environment && client.isNotBlank()){"远程环境已改变"};api.revokeRemoteClient(environment,client)
        val result=api.remoteClients(environment,null).jsonObject;update(stamp){it.copy(clients=OriginalCompletionPage().append(result,null))};operations.notice(stamp,"撤销请求已返回，设备列表已刷新")}
    fun discoverBedrock()=operations.run{api,stamp->val result=api.discoverBedrock().jsonObject;update(stamp){it.copy(bedrock=result)};operations.notice(stamp,"已读取手机核心可见的AWS配置；不会导入桌面凭证")}
    fun bedrock(profile:String?,region:String)=operations.run(true){api,stamp->require(region.isNotBlank());api.setupBedrock(profile?.takeIf{it.isNotBlank()},region);val result=api.bedrockRequirements().jsonObject;update(stamp){it.copy(requirements=result)};operations.notice(stamp,"Bedrock配置请求已返回；具体可用性需真实账户验证")}
    fun requirements()=operations.run{api,stamp->val result=api.bedrockRequirements().jsonObject;update(stamp){it.copy(requirements=result)};operations.notice(stamp,"GovCloud要求已读取，false不代表完整合规认证")}
    fun verificationStatus()=operations.run{api,stamp->val result=api.verificationStatus().jsonObject;update(stamp){it.copy(verification=result,proof=null)};operations.notice(stamp,"已读取原版验证能力；Android本机提供者尚待实现")}
    fun verification(enroll:Boolean,challenge:String="",title:String="",description:String="")=operations.run(true){api,stamp->
        val fresh=api.verificationStatus().jsonObject;update(stamp){it.copy(verification=fresh)}
        require(fresh["unavailableReason"]==null || fresh["unavailableReason"]==JsonNull || enroll && fresh.string("unavailableReason")=="credentialMissing"){"当前原版不支持此平台的验证提供者；未执行登记或签名"}
        var completed=false;var issuedRequest:JsonElement?=null
        try{val result=if(enroll)api.enrollVerification{request->issuedRequest=request;update(stamp){it.copy(verificationRequest=request)}}else api.verifyUser(OriginalCompletionData.verification(challenge,title,description)){request->issuedRequest=request;update(stamp){it.copy(verificationRequest=request)}}
            completed=true;update(stamp){it.copy(proof=result.jsonObject)};val status=api.verificationStatus().jsonObject;update(stamp){it.copy(verification=status)};operations.notice(stamp,"验证提供者返回如下结果")
        }catch(failure:RpcException){completed=true;throw failure}finally{
            val request=issuedRequest
            if(!completed && request!=null)withContext(NonCancellable){try{withTimeout(3000){api.cancelVerification(request)}}catch(_:Exception){ }finally{api.connection.close()}}
            update(stamp){it.copy(verificationRequest=null)}
        }
    }
    fun deleteVerification()=operations.run(true){api,stamp->api.deleteVerification();val result=api.verificationStatus().jsonObject;update(stamp){it.copy(verification=result,proof=null)};operations.notice(stamp,"验证凭证删除请求已返回")}
    fun cancelVerification(){val id=state.value.verificationRequest ?: return;val client=api ?: return;if(state.value.cancelBusy)return;mutable.update{it.copy(cancelBusy=true)}
        cancelJob=scope.launch{try{client.cancelVerification(id)}catch(failure:Exception){if(failure is CancellationException)throw failure;if(api===client)mutable.update{it.copy(proof=buildJsonObject{put("cancelError",failure.message)})}}finally{if(api===client)mutable.update{it.copy(cancelBusy=false)}}}}
    fun subscribe(server:String,name:String,arguments:String){val id=thread() ?: return
        operations.run(true){api,stamp->require(server=="codex_apps" && name.isNotBlank()){"原版事件订阅仅支持托管应用服务codex_apps"};require(state.value.subscriptions.size<20){"请先停止部分订阅"};val args=Json.parseToJsonElement(arguments)
            val subscription=UUID.randomUUID().toString();val entry=buildJsonObject{put("threadId",id);put("server",server);put("name",name);put("status","starting")}
            update(stamp){it.copy(subscriptions=it.subscriptions+(subscription to entry))}
            try{api.startMcpStream(id,server,subscription,name,args);update(stamp){it.copy(subscriptions=it.subscriptions+(subscription to JsonObject(entry+("status" to JsonPrimitive("active")))))};operations.notice(stamp,"订阅已启动；事件如下")}
            catch(failure:Exception){update(stamp){it.copy(subscriptions=it.subscriptions+(subscription to JsonObject(entry+("status" to JsonPrimitive("unknown")))))};throw failure}
        }
    }
    fun unsubscribe(id:String)=operations.run(false){api,stamp->require(id in state.value.subscriptions);api.stopMcpStream(id);update(stamp){it.copy(subscriptions=it.subscriptions-id)};operations.notice(stamp,"服务已确认停止订阅")}
}
