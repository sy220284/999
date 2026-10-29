package cn.codex.android
import android.content.Context
import cn.codex.core.original.*
import cn.codex.core.string
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 原版测试客户端只连接本机专用核心；核心由宿主直接启动与授权，不授权工具后代。 */
class AndroidDebugCore(private val context:Context,parent:CoroutineScope,private val workspace:File,private val home:File):Closeable {
    private val job=SupervisorJob(parent.coroutineContext[Job]);private val scope=CoroutineScope(parent.coroutineContext+job+Dispatchers.IO)
    private val directory=File(context.cacheDir,"debug-${UUID.randomUUID()}")
    private val receipt=File(directory,"ready.json");private val connection=File(directory,"connection.json")
    private var process:NativeCoreProcess?=null;private var vault:OriginalVaultServer?=null;private var verifier:OriginalVerificationServer?=null
    private val closed=AtomicBoolean();private val capability=Base64.getUrlEncoder().withoutPadding().encode(SecureRandom().generateSeed(32))
    val environment get()=mapOf("CODEX_ANDROID_DEBUG_CONNECTION_FILE" to connection.absolutePath)
    suspend fun start(){
        try{
            val config=withContext(Dispatchers.IO){SecureSettings(context).load()}
            withContext(Dispatchers.IO){
                check(directory.mkdir())
                val settings=NativeOriginalEngineFactory.settings(context,config,home)
                val v=OriginalVaultServer(context,scope).also{vault=it};val verification=OriginalVerificationServer(context,scope).also{verifier=it}
                val hash=MessageDigest.getInstance("SHA-256").digest(capability).joinToString(""){"%02x".format(it)}
                val env=settings.environment.toMutableMap().apply{
                    remove("CODEX_ANDROID_DEBUG_CONNECTION_FILE")
                    put("CODEX_ANDROID_VAULT_SOCKET",v.path);put("CODEX_ANDROID_VAULT_HOST_PID",android.os.Process.myPid().toString());put("CODEX_ANDROID_VERIFICATION_SOCKET",verification.path)
                    put("CODEX_ANDROID_DAEMON_PARENT_STDIN","1");put("CODEX_ANDROID_DAEMON_RECEIPT",receipt.absolutePath)
                }
                val plan=NativeEndpointPlans.daemon(hash,0,false,true);env.putAll(plan.environment)
                val arguments=settings.arguments+(if(config.model.isNotBlank())listOf("-c","model=${JsonPrimitive(config.model)}")else emptyList())+listOf("-c","sandbox_mode=\"danger-full-access\"")+plan.arguments
                val child=NativeCoreProcess(File(context.applicationInfo.nativeLibraryDir,"libcodex_cli.so"),arguments,env,workspace).also{process=it}
                v.authorize(child.pid);verification.authorize(child.pid)
                for(input in listOf(child.input,child.errors))scope.launch{runCatching{input.use{val bytes=ByteArray(8192);while(it.read(bytes)>=0){ }}}}
            }
            withTimeout(20_000){
                while(true){
                    val address=withContext(Dispatchers.IO){
                        val child=requireNotNull(process);check(child.pollExit()==null){"专用诊断核心已退出"}
                        if(receipt.isFile)runCatching{val bytes=receipt.inputStream().use{it.readNBytes(4097)};require(bytes.size<=4096);AndroidDebugConnection.address(bytes.toString(Charsets.UTF_8),child.pid)}.getOrNull()else null
                    }
                    if(address!=null){withContext(Dispatchers.IO){connection.writeText(AndroidDebugConnection(address,capability.toString(Charsets.US_ASCII)).document())}
                        if(config.apiKey.isNotBlank() && config.endpoint.trimEnd('/')=="https://api.openai.com/v1"){
                            val wire=LoopbackAppServerSocket();var rpc:AppServerConnection?=null
                            try{wire.open(address,capability.toString(Charsets.US_ASCII));val protocol=AppServerConnection(wire.input,wire.output,scope,wire::close).also{rpc=it};val api=OriginalCodexClient(protocol);api.initialize();if(api.account().jsonObject["account"]==JsonNull)api.loginApiKey(config.apiKey)}finally{rpc?.close();wire.close()}
                        }
                        break}
                    delay(25)
                }
            }
        }catch(failure:Throwable){withContext(NonCancellable+Dispatchers.IO){close()};throw failure}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        runCatching{process?.close()};process=null;job.cancel();runCatching{verifier?.close()};runCatching{vault?.close()};capability.fill(0)
        // 只清理宿主创建的普通文件，不递归跟随工具可能写入的路径。
        connection.delete();receipt.delete();directory.delete()
    }
}
