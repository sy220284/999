package cn.codex.android

import android.content.Context
import android.content.Intent
import cn.codex.core.ApiConfig
import cn.codex.core.original.*
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface EndpointProcessSpawner {
    fun spawn(executable:File,arguments:List<String>,environment:Map<String,String>,directory:File):OwnedNativeProcess
}
data class NativeEndpointStatus(val id:String,val kind:NativeEndpointKind,val running:Boolean=true,val ready:Boolean=false,val stopping:Boolean=false,val rotating:Boolean=false,val address:String="",val credentialSequence:Long=0,val message:String="等待前台服务启动",val error:String?=null)
class DaemonConnection(val id:String,val address:String,val credential:String){
    override fun toString()="DaemonConnection(redacted)"
}
/** 服务只由本次前台资源身份持有；应用重启不接管旧PID，不重放启动或模型操作。 */
class NativeEndpoints(private val context:Context,private val scope:CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate),
    directory:File=File(context.noBackupFilesDir,"本机服务"),
    private val startForeground:(String)->Unit={id->context.startForegroundService(Intent(context,NativeEndpointService::class.java).putExtra("lease",id))
        Unit},
    private val spawner:EndpointProcessSpawner=EndpointProcessSpawner{file,args,env,cwd->check(file.isFile && file.canExecute()){ "当前包没有原版CLI产物" }
        NativeCoreProcess(file,args,env,cwd)}) {
    private class Lease(val id:String,val kind:NativeEndpointKind,val directory:File,var payload:ByteArray,val plan:(File,ByteArray)->NativeEndpointPlan,val localToken:ByteArray,val remoteId:String?,val scoped:Boolean,val extraEnvironment:MutableMap<String,String>,val dependency:String?){
        var owner:String?=null
        lateinit var job:Job
        var timeout:Job?=null
        var rotation:Job?=null
        @Volatile var ack:CompletableDeferred<Unit>?=null
        var cleanupFailed=false
        @Volatile var credentialSequence=0L
        @Volatile var remoteConnected=false
        @Volatile var initialReady=false
        val writer=Mutex()
        val generation=NativeEndpointGeneration().apply{start(id)}
        @Volatile var process:OwnedNativeProcess?=null
        @Volatile var config:ApiConfig?=null
        @Volatile var verifier:OriginalVerificationServer?=null
    }
    private val root=directory.apply{mkdirs()}
    private val records=NativeEndpointRecords(root)
    private val leases=ConcurrentHashMap<NativeEndpointKind,Lease>()
    private val mutable=MutableStateFlow<Map<NativeEndpointKind,NativeEndpointStatus>>(emptyMap())
        val state=mutable.asStateFlow()
    private val pendingMutable=MutableStateFlow(records.entries)
        val pending=pendingMutable.asStateFlow()
    private val storeErrorMutable=MutableStateFlow(records.error)
        val storeError=storeErrorMutable.asStateFlow()
    val running get()=state.value.values.any{it.running}
    val daemonRunning get()=state.value[NativeEndpointKind.DAEMON]?.running==true
    private val app get()=context.applicationContext as CodexApplication
    private fun status(lease:Lease,update:(NativeEndpointStatus)->NativeEndpointStatus){mutable.update{all->val row=all[lease.kind]
        if(row?.id==lease.id)all+(lease.kind to update(row))else all}}
    private fun randomToken()=Base64.getUrlEncoder().withoutPadding().encode(SecureRandom().generateSeed(32))
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun reject(kind:NativeEndpointKind,message:String){if(state.value[kind]?.running==true)return
        mutable.update{it+(kind to NativeEndpointStatus(UUID.randomUUID().toString(),kind,running=false,message="未启动",error=message))}}
    private fun reserve(kind:NativeEndpointKind,payload:ByteArray,remoteId:String?=null,scoped:Boolean=false,extraEnvironment:Map<String,String> = emptyMap(),dependency:String?=null,plan:(File,ByteArray)->NativeEndpointPlan){
        if(leases.containsKey(kind)){payload.fill(0)
        return}
        var reserved:Lease?=null
        try{
            check(storeErrorMutable.value==null){"请先核查并清除上次服务记录错误"}
            check(app.configurationJournal.active.value==null && !app.originalCore.state.value.loading && !app.originalCore.state.value.closing){"请先结束配置操作或核心启动/关闭"}
            if(kind==NativeEndpointKind.DAEMON)check(!app.originalCore.state.value.connected && !app.originalCore.cli.state.value.busy && !app.fullTerminal.state.value.running && !app.controller.state.value.busy){"请先结束当前任务并断开核心连接"}
            val id=UUID.randomUUID().toString()
        val folder=File(root,id)
        check(folder.mkdir())
            val lease=Lease(id,kind,folder,payload,plan,if(kind in setOf(NativeEndpointKind.TUNNEL,NativeEndpointKind.REMOTE_EXECUTOR))ByteArray(0)else randomToken(),remoteId,scoped,extraEnvironment.toMutableMap(),dependency).also{reserved=it}
            records.write(records.entries+NativeEndpointRecords.Entry(id,kind,System.currentTimeMillis()))
        pendingMutable.value=records.entries
            lease.job=scope.launch(start=CoroutineStart.LAZY){run(lease)}
            leases[kind]=lease
        mutable.update{it+(kind to NativeEndpointStatus(id,kind))}
            try{startForeground(id)}catch(failure:Exception){finishUnstarted(lease,"系统未允许启动前台服务")
        return}
            lease.timeout=scope.launch{delay(10_000)
        if(lease.owner==null)finishUnstarted(lease,"前台服务未及时启动，请保持应用在前台后重试")}
        }catch(failure:Exception){payload.fill(0)
        reserved?.localToken?.fill(0)
        reserved?.extraEnvironment?.clear()
        reserved?.directory?.let{runCatching{deleteOwnedDirectory(it)}}
        if(records.error!=null)storeErrorMutable.value=records.error
        reject(kind,failure.message ?: "无法准备服务")}
    }
    private fun finishUnstarted(lease:Lease,message:String){
        if(lease.owner!=null || !leases.remove(lease.kind,lease))return
        lease.job.cancel()
        lease.timeout?.cancel()
        lease.payload.fill(0)
        lease.localToken.fill(0)
        lease.extraEnvironment.clear()
        runCatching{deleteOwnedDirectory(lease.directory)}.onFailure{lease.cleanupFailed=true;storeErrorMutable.value="服务临时目录未完全清理，请核查"}
        lease.generation.finish(lease.id)
        runCatching{records.write(records.entries.filterNot{it.id==lease.id && !lease.cleanupFailed})}.onFailure{storeErrorMutable.value=records.error ?: "服务记录或临时目录未完成清理，请核查"}
        pendingMutable.value=records.entries
        status(lease){it.copy(running=false,ready=false,message="服务未启动",error=message)}
    }
    fun rejectReserved(id:String,message:String){leases.values.firstOrNull{it.id==id && it.owner==null}?.let{finishUnstarted(it,message)}}
    fun startReserved(id:String,owner:String):Boolean{
        val lease=leases.values.firstOrNull{it.id==id} ?: return false
        if(lease.owner!=null)return lease.owner==owner
        lease.owner=owner
        lease.timeout?.cancel()
        lease.job.start()
        return true
    }
    fun stopOwned(owner:String){leases.values.filter{it.owner==owner}.forEach{stop(it.kind,it.id)}}
    fun stopAll(){leases.values.toList().forEach{stop(it.kind,it.id)}}
    fun stop(kind:NativeEndpointKind,id:String?=state.value[kind]?.id){
        val lease=leases[kind]?.takeIf{it.id==id} ?: return
        if(lease.owner==null){finishUnstarted(lease,"启动已取消")
        return}
        leases.values.filter{it.dependency==lease.id}.forEach{stop(it.kind,it.id)}
        lease.generation.stop(lease.id)
        status(lease){it.copy(ready=false,stopping=true,message="正在关闭服务和连接")}
        lease.localToken.fill(0)
        lease.extraEnvironment.clear()
        runCatching{lease.verifier?.close()}
        lease.job.cancel()
    }
    fun acknowledge(){runCatching{
        val active=leases.values.map{it.id}.toSet()
        records.entries.filter{it.id !in active}.forEach{entry->if(runCatching{UUID.fromString(entry.id).toString()==entry.id}.getOrDefault(false))deleteOwnedDirectory(File(root,entry.id))}
        records.acknowledge(active)
        pendingMutable.value=records.entries
        storeErrorMutable.value=null}.onFailure{storeErrorMutable.value=records.error ?: "服务记录或临时目录未完成清理，请核查"}}
    fun clientCredential(kind:NativeEndpointKind):String?{
        val row=state.value[kind] ?: return null
        val lease=leases[kind] ?: return null
        return lease.localToken.takeIf{row.id==lease.id && row.ready && !row.stopping && it.isNotEmpty()}?.toString(Charsets.US_ASCII)
    }
    fun daemonConnection(config:ApiConfig):DaemonConnection?{
        val row=state.value[NativeEndpointKind.DAEMON] ?: return null
        if(!row.running)return null
        check(row.ready && !row.stopping){"共享核心正在启动或停止，请稍后连接"}
        val lease=leases[NativeEndpointKind.DAEMON] ?: error("共享核心资源已结束")
        val started=lease.config ?: error("共享核心配置不可用")
        check(started.endpoint.trimEnd('/')==config.endpoint.trimEnd('/') && started.apiKey==config.apiKey && started.allowLoopbackHttp==config.allowLoopbackHttp){"模型连接设置已变化，请先重启共享核心"}
        return DaemonConnection(lease.id,row.address,if(row.address.startsWith("unix://"))""else requireNotNull(clientCredential(NativeEndpointKind.DAEMON)))
    }
    fun startProxy(upstream:String,key:String,port:Int){
        try{NativeEndpointPlans.proxy(upstream,port,"/receipt","/token")
        val bytes=NativeEndpointPlans.credential(key,1016)
            reserve(NativeEndpointKind.PROXY,bytes+byteArrayOf(10)){dir,token->File(dir,"client-token").writeBytes(token)
        NativeEndpointPlans.proxy(upstream,port,File(dir,"ready.json").absolutePath,File(dir,"client-token").absolutePath)}
        bytes.fill(0)
        }catch(error:Exception){reject(NativeEndpointKind.PROXY,error.message ?: "代理配置无效")}
    }
    fun startTunnel(origin:String,target:String,port:Int,token:String,metadata:String){
        try{val trusted=NativeEndpointPlans.origin(origin)
        val headers=NativeEndpointPlans.metadata(metadata)
        NativeEndpointPlans.tunnel(trusted,target,port,"/origins")
        val bytes=NativeEndpointPlans.credential(token,65535)
            val payload=(headers+"\n").toByteArray()+bytes+byteArrayOf(10)
        bytes.fill(0)
            reserve(NativeEndpointKind.TUNNEL,payload){dir,_->File(dir,"origins").writeText(trusted+"\n")
        NativeEndpointPlans.tunnel(trusted,target,port,File(dir,"origins").absolutePath)}
        }catch(error:Exception){reject(NativeEndpointKind.TUNNEL,error.message ?: "隧道配置无效")}
    }
    fun startExecutor(port:Int,concurrent:Int=1,strict:Boolean=false,pidNamespace:String="isolate",proxyPrivate:Boolean=false){try{NativeEndpointPlans.executor("a".repeat(64),port,concurrent,strict,pidNamespace,proxyPrivate)
        reserve(NativeEndpointKind.EXECUTOR,ByteArray(0)){_,token->NativeEndpointPlans.executor(sha(token),port,concurrent,strict,pidNamespace,proxyPrivate)}}catch(error:Exception){reject(NativeEndpointKind.EXECUTOR,error.message ?: "执行服务配置无效")}}
    fun startDaemon(port:Int,remote:Boolean,unix:Boolean=false,strict:Boolean=false){try{val startup=OriginalAppServerPreferences.read(context);NativeEndpointPlans.daemon("a".repeat(64),port,remote)
        reserve(NativeEndpointKind.DAEMON,ByteArray(0)){dir,token->val saved=if(remote)null else savedRemotePreference();val selected=if(unix)NativeEndpointPlans.unixDaemon(daemonSocket(dir.name).absolutePath,remote || saved==true,!remote && saved==false,strict)else NativeEndpointPlans.daemon(sha(token),port,remote || saved==true,!remote && saved==false,strict);val options=cn.codex.core.original.OriginalAppServerOptions.arguments(startup,File(context.filesDir,"工作区/原版项目"));selected.copy(arguments=selected.arguments+options.filterNot{it=="--strict-config" && it in selected.arguments})}}catch(error:Exception){reject(NativeEndpointKind.DAEMON,error.message ?: "共享核心配置无效")}}
    fun savedRemotePreference():Boolean?{
        val file=File(context.filesDir,"原版核心数据/app-server-daemon/settings.json")
        if(!file.isFile)return null
        val bytes=file.inputStream().use{it.readNBytes(65537)};require(bytes.size<=65536){"远控设置超过读取预算"}
        val value=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["remoteControlEnabled"] ?: return null
        return requireNotNull((value as? JsonPrimitive)?.booleanOrNull){"远控持久设置损坏，请核查原版配置"}
    }
    fun startRemoteExecutor(url:String,environment:String,name:String,auth:RemoteExecutorAuth,token:String,expires:Long,profile:String,region:String,service:String,forward:Boolean=false,concurrent:Int=1,strict:Boolean=false,pidNamespace:String="isolate",proxyPrivate:Boolean=false){
        try{
            val owned=if(forward)state.value[NativeEndpointKind.EXECUTOR]?.takeIf{it.ready && it.running && !it.stopping} ?: error("请先启动本次已认证本机执行器")else null
            val plan=RemoteExecutorPlan.build(url,environment,name,auth,profile,region,service,owned?.address,concurrent,strict,pidNamespace,proxyPrivate)
            val payload=if(auth==RemoteExecutorAuth.SCOPED)RemoteExecutorPlan.credential(environment,token,expires)else ByteArray(0)
            val extra=mutableMapOf<String,String>()
            if(auth==RemoteExecutorAuth.AGENT_IDENTITY){NativeEndpointPlans.credential(token,65536).fill(0);extra["CODEX_ACCESS_TOKEN"]=token}
            if(owned!=null){check(leases[NativeEndpointKind.EXECUTOR]?.id==owned.id);extra["CODEX_ANDROID_FORWARD_AUTH"]=buildJsonObject{put("address",owned.address);put("credential",requireNotNull(clientCredential(NativeEndpointKind.EXECUTOR)))}.toString()}
            reserve(NativeEndpointKind.REMOTE_EXECUTOR,payload,environment,auth==RemoteExecutorAuth.SCOPED,extra,owned?.id){_,_->plan}
        }catch(failure:Exception){reject(NativeEndpointKind.REMOTE_EXECUTOR,failure.message ?: "远程执行配置无效")}
    }
    private fun remoteEvent(lease:Lease,line:String,ready:CompletableDeferred<String>){
        val value=runCatching{Json.parseToJsonElement(line).jsonObject}.getOrNull() ?: return
        if(value["androidRemoteExecutor"]?.jsonPrimitive?.intOrNull!=1 || value["pid"]?.jsonPrimitive?.intOrNull!=lease.process?.pid || value.string("environmentId")!=lease.remoteId)return
        val sequence=value["credentialSequence"]?.jsonPrimitive?.longOrNull
        if(sequence!=null){
            if(sequence!=lease.credentialSequence+1)return
            lease.credentialSequence=sequence;lease.ack?.complete(Unit);status(lease){it.copy(credentialSequence=sequence)};return
        }
        if(value.string("transport") !in setOf("noise","direct"))return
        when(value.string("state")){
            "connected"->{lease.remoteConnected=true;ready.complete("远程环境：${lease.remoteId}");if(lease.initialReady)status(lease){if(it.stopping)it else it.copy(ready=true,message="远程转发层已连接，等待客户端使用")}}
            "disconnected"->{lease.remoteConnected=false;status(lease){if(it.stopping)it else it.copy(ready=false,message="远程转发连接已断开，恢复由原版处理；请核查任务实际结果")}}
        }
    }
    fun rotateRemoteCredential(token:String,expires:Long){
        val lease=leases[NativeEndpointKind.REMOTE_EXECUTOR] ?: return
        val row=state.value[lease.kind] ?: return
        if(!lease.scoped || !row.running || row.stopping || row.rotating || lease.credentialSequence<1)return
        val bytes=try{RemoteExecutorPlan.credential(requireNotNull(lease.remoteId),token,expires)}catch(_:Exception){status(lease){it.copy(error="替换凭证或过期时间无效")};return}
        val ack=CompletableDeferred<Unit>();lease.ack=ack;status(lease){it.copy(rotating=true,error=null)}
        lease.rotation=scope.launch(Dispatchers.IO){try{withTimeout(15_000){lease.writer.withLock{requireNotNull(lease.process).output.apply{write(bytes);flush()}};ack.await()};status(lease){if(it.stopping || !it.running)it else it.copy(rotating=false,message="已确认本机接受第${lease.credentialSequence}版凭证；不代表远端已接受")}}catch(failure:Exception){if(failure is CancellationException && failure !is TimeoutCancellationException)throw failure;status(lease){it.copy(error="凭证更新结果未确认，已关闭此执行服务")};stop(lease.kind,lease.id)}finally{bytes.fill(0);if(lease.ack===ack)lease.ack=null}}
    }
    fun rotateTunnel(value:String){
        val lease=leases[NativeEndpointKind.TUNNEL] ?: return
        val row=state.value[NativeEndpointKind.TUNNEL] ?: return
        if(!row.ready || row.stopping || row.rotating)return
        val bytes=try{NativeEndpointPlans.credential(value,65535)}catch(_:Exception){status(lease){it.copy(error="更新凭证为空、过长或格式无效")}
        return}
        val ack=CompletableDeferred<Unit>()
        lease.ack=ack
        status(lease){it.copy(rotating=true,error=null)}
        lease.rotation=scope.launch(Dispatchers.IO){
            try{withTimeout(15_000){lease.writer.withLock{requireNotNull(lease.process).output.apply{write(bytes)
        write(10)
        flush()}}
        ack.await()}
        status(lease){if(it.stopping || !it.running)it else it.copy(rotating=false,message="本机已更新连接凭证；现有数据流不会重放")}} catch(cancelled:CancellationException){if(cancelled is TimeoutCancellationException){status(lease){it.copy(error="凭证更新结果未确认，已关闭隧道")}
        stop(lease.kind,lease.id)}else throw cancelled} catch(_:Exception){status(lease){it.copy(error="凭证更新失败，已关闭隧道")}
        stop(lease.kind,lease.id)} finally{bytes.fill(0)
        if(lease.ack===ack)lease.ack=null}
        }
    }
    private fun checkDependency(lease:Lease){
        val id=lease.dependency ?: return
        val row=state.value[NativeEndpointKind.EXECUTOR]
        check(row?.id==id && row.ready && row.running && !row.stopping && leases[NativeEndpointKind.EXECUTOR]?.id==id){"本次转发目标已经停止或替换，未继续注册"}
    }
    private suspend fun run(lease:Lease){
        var vault:OriginalVaultServer?=null
        var verification:OriginalVerificationServer?=null
        var exit:Int?=null
        try{
            withContext(Dispatchers.IO){
                val home=File(context.filesDir,"原版核心数据").apply{mkdirs()}
        val workspace=File(context.filesDir,"工作区/原版项目").apply{mkdirs()}
                val config=if(lease.kind==NativeEndpointKind.DAEMON)SecureSettings(context).load()else ApiConfig()
                lease.config=config
                val settings=NativeOriginalEngineFactory.settings(context,config,home)
                val v=OriginalVaultServer(context,scope).also{vault=it}
                val env=settings.environment.toMutableMap().apply{put("CODEX_ANDROID_VAULT_SOCKET",v.path)
        put("CODEX_ANDROID_VAULT_HOST_PID",android.os.Process.myPid().toString())}
                if(lease.kind==NativeEndpointKind.DAEMON){
                    val verifier=OriginalVerificationServer(context,scope).also{verification=it
        lease.verifier=it}
        env["CODEX_ANDROID_VERIFICATION_SOCKET"]=verifier.path
                    env["CODEX_ANDROID_DAEMON_PARENT_STDIN"]="1"
        env["CODEX_ANDROID_DAEMON_RECEIPT"]=File(lease.directory,"ready.json").absolutePath
                }
                val plan=lease.plan(lease.directory,lease.localToken)
                env.putAll(plan.environment)
                env.putAll(lease.extraEnvironment)
                checkDependency(lease)
                val process=spawner.spawn(File(context.applicationInfo.nativeLibraryDir,"libcodex_cli.so"),settings.arguments+plan.arguments,env,workspace).also{lease.process=it}
                v.authorize(process.pid)
        verification?.authorize(process.pid)
            }
            coroutineScope{
                val process=requireNotNull(lease.process)
        val ready=CompletableDeferred<String>()
                launch(Dispatchers.IO){try{val input=BufferedInputStream(process.input)
        while(isActive){val line=readLine(input) ?: break
                    if(lease.kind==NativeEndpointKind.REMOTE_EXECUTOR)remoteEvent(lease,line,ready)
                    if(line=="AUTH_UPDATED" && lease.kind==NativeEndpointKind.TUNNEL)lease.ack?.complete(Unit)
                    NativeEndpointPlans.readyLine(lease.kind,line)?.let{ready.complete(it)}
                }
        if(!ready.isCompleted)ready.completeExceptionally(IOException("服务未报告监听地址"))}catch(_:Exception){if(!ready.isCompleted)ready.completeExceptionally(IOException("服务输出已关闭"))else if(isActive){status(lease){it.copy(error="服务控制输出中断或超出预算，已停止此服务")};stop(lease.kind,lease.id)}}}
                launch(Dispatchers.IO){try{val input=BufferedInputStream(process.errors)
        while(isActive){val line=readLine(input) ?: break
                    if(lease.kind==NativeEndpointKind.TUNNEL){val diagnostic=runCatching{Json.parseToJsonElement(line).jsonObject}.getOrNull()
        val code=diagnostic?.string("code")
                        if(code in setOf("closed","draining","timeout","rejected"))status(lease){if(it.stopping)it else it.copy(message="隧道状态：$code；原数据流不会重放")}
                    }
                }}catch(_:Exception){if(isActive){status(lease){it.copy(error="服务诊断输出中断或超出预算，已停止此服务")};stop(lease.kind,lease.id)}}}
                if(lease.kind in setOf(NativeEndpointKind.PROXY,NativeEndpointKind.DAEMON))launch(Dispatchers.IO){
                    while(isActive && !ready.isCompleted){val result=readReceipt(lease,process.pid)
        if(result!=null)ready.complete(result)
        else delay(25)}
                }
                try{
                    withContext(Dispatchers.IO){lease.writer.withLock{process.output.write(lease.payload)
        process.output.flush()
        lease.payload.fill(0)
        lease.payload=ByteArray(0)}}
                    val address=withTimeout(if(lease.kind==NativeEndpointKind.REMOTE_EXECUTOR)120_000L else 20_000L){ready.await()}
                    checkDependency(lease)
                    check(withContext(Dispatchers.IO){process.pollExit()}==null){"服务在监听建立后退出"}
                    if(lease.generation.ready(lease.id)){
                        lease.initialReady=true
                        status(lease){if(it.stopping)it else it.copy(ready=lease.kind!=NativeEndpointKind.REMOTE_EXECUTOR || lease.remoteConnected,address=address,message=if(lease.kind==NativeEndpointKind.REMOTE_EXECUTOR)"远程转发连接状态已核对；等待客户端授权使用"else"监听已建立，连接需满足认证与服务条件")}
                    }
                    if(lease.kind==NativeEndpointKind.PROXY)withContext(Dispatchers.IO){File(lease.directory,"client-token").delete()}
                    while(currentCoroutineContext().isActive){exit=withContext(Dispatchers.IO){process.pollExit()}
        if(exit!=null)break
        delay(100)}
                }finally{withContext(NonCancellable+Dispatchers.IO){cleanupProcess(lease)}}
            }
        }catch(cancelled:CancellationException){if(cancelled is TimeoutCancellationException)status(lease){it.copy(error="服务就绪确认超时，已关闭本次资源")}
        else if(!state.value[lease.kind].let{it?.id==lease.id && it.stopping})status(lease){it.copy(error="服务被中断，请核查实际结果")}} catch(_:Exception){status(lease){it.copy(error="本机服务启动或连接失败，请核查CLI产物、凭证和地址")}} finally{
            withContext(NonCancellable+Dispatchers.IO){cleanupProcess(lease)
        runCatching{verification?.close()}
        runCatching{vault?.close()}
        lease.payload.fill(0)
        lease.localToken.fill(0)
        lease.extraEnvironment.clear()
        lease.config=null
        if(lease.kind==NativeEndpointKind.DAEMON)daemonSocket(lease.id).delete()
        runCatching{deleteOwnedDirectory(lease.directory)}.onFailure{lease.cleanupFailed=true;storeErrorMutable.value="服务临时目录未完全清理，请核查"}}
            withContext(NonCancellable+Dispatchers.Main.immediate){
                leases.remove(lease.kind,lease)
        lease.timeout?.cancel()
        lease.generation.finish(lease.id)
                runCatching{records.write(records.entries.filterNot{it.id==lease.id && !lease.cleanupFailed})}.onFailure{storeErrorMutable.value=records.error ?: "服务记录或临时目录未完成清理，请核查"}
        pendingMutable.value=records.entries
                status(lease){it.copy(running=false,ready=false,rotating=false,stopping=false,message=if(it.stopping)"服务已关闭"else "服务已退出${exit?.let{code->"（退出码$code）"}.orEmpty()}")}
            }
        }
    }
    private suspend fun cleanupProcess(lease:Lease){
        lease.rotation?.cancel()
        lease.ack?.cancel()
        leases.values.filter{it.dependency==lease.id}.forEach{stop(it.kind,it.id)}
        val process=lease.process ?: return
        runCatching{process.requestTermination()}
        withTimeoutOrNull(3000){while(runCatching{process.pollExit()==null}.getOrDefault(false))delay(25)}
        runCatching{process.close()}
        lease.process=null
    }
    private fun daemonSocket(id:String)=File(context.cacheDir,"d-${id.take(12)}.sock")
    private fun readReceipt(lease:Lease,pid:Int):String?=runCatching{
        val file=File(lease.directory,"ready.json")
        if(!file.isFile)return@runCatching null
        val bytes=file.inputStream().use{it.readNBytes(4097)}
        require(bytes.size<=4096)
        val value=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(value["pid"]?.jsonPrimitive?.intOrNull==pid)
        if(lease.kind==NativeEndpointKind.PROXY){val port=value["port"]?.jsonPrimitive?.int ?: error("没有端口")
        require(port in 1..65535)
        "http://127.0.0.1:$port/v1"}
        else value.string("address").takeIf{if(it.startsWith("unix://"))it=="unix://${daemonSocket(lease.id).absolutePath}"else it.matches(Regex("ws://127\\.0\\.0\\.1:[0-9]{1,5}")) && java.net.URI(it).port in 1..65535}
    }.getOrNull()
    private fun deleteOwnedDirectory(folder:File){
        require(folder.absoluteFile.parentFile==root.absoluteFile && UUID.fromString(folder.name).toString()==folder.name)
        val path=folder.toPath();if(!java.nio.file.Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))return
        java.nio.file.Files.walkFileTree(path,object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
            override fun visitFile(file:java.nio.file.Path,attributes:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult{java.nio.file.Files.deleteIfExists(file);return java.nio.file.FileVisitResult.CONTINUE}
            override fun postVisitDirectory(directory:java.nio.file.Path,failure:IOException?):java.nio.file.FileVisitResult{if(failure!=null)throw failure;java.nio.file.Files.deleteIfExists(directory);return java.nio.file.FileVisitResult.CONTINUE}
        })
    }
    private fun readLine(input:InputStream):String?{
        val bytes=ByteArrayOutputStream()
        while(true){val b=input.read()
        if(b<0)return if(bytes.size()==0)null else bytes.toString(Charsets.UTF_8.name())
        if(b==10)return bytes.toString(Charsets.UTF_8.name())
        if(bytes.size()>=4096)throw IOException("服务事件超出预算")
        bytes.write(b)}
    }
}
