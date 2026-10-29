package cn.codex.android

import android.content.Context
import cn.codex.core.ApiConfig
import cn.codex.core.original.AppServerConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class OriginalEngineLaunch(val context:Context,val config:ApiConfig,val workspace:File,val home:File,val scope:CoroutineScope)
interface OriginalEngineSession:Closeable {
    val connection:AppServerConnection
}
fun interface OriginalEngineFactory {
    suspend fun open(launch:OriginalEngineLaunch):OriginalEngineSession
}
class OriginalProcessSettings(val arguments:List<String>,val environment:Map<String,String>){
    override fun toString()="OriginalProcessSettings(redacted)"
}
/** 原版stdio和本机共享核心使用同一组模型、工具、证书与凭证策略。必须在IO线程调用。 */
object NativeOriginalEngineFactory:OriginalEngineFactory {
    @Synchronized fun settings(context:Context,config:ApiConfig,home:File):OriginalProcessSettings {
        AndroidLanguageRuntime.prepare(context)
        val endpoint=URI(config.endpoint)
        require(endpoint.scheme=="https" || config.allowLoopbackHttp && endpoint.scheme=="http" && endpoint.host in setOf("localhost","127.0.0.1","[::1]","::1")){"原版核心仅接受HTTPS或已允许的本机HTTP地址"}
        require(!endpoint.host.isNullOrBlank() && endpoint.rawUserInfo==null && endpoint.rawQuery==null && endpoint.rawFragment==null){"模型地址需要有效主机名，且不可包含凭证、查询或片段"}
        check(home.isDirectory || home.mkdirs())
        File(home,"config.toml").createNewFile()
        val trust=AndroidTrustBundle.write(home)
        val tools=AndroidToolPaths.environment(context)+AndroidTrustBundle.environment(trust)
        val args=mutableListOf("-c","cli_auth_credentials_store=\"keyring\"","-c","mcp_oauth_credentials_store=\"keyring\"","-c","shell_environment_policy.inherit=\"core\"")
        tools.forEach{(key,value)->args+=listOf("-c","shell_environment_policy.set.$key=${JsonPrimitive(value)}")}
        val env=System.getenv().toMutableMap().apply{
            put("PATH",(context.applicationContext as CodexApplication).toolPath)
        putAll(tools)
            put("CODEX_HOME",home.absolutePath)
        put("HOME",home.absolutePath)
        put("TMPDIR",context.cacheDir.absolutePath)
        put("SHELL","/system/bin/sh")
            put("CODEX_CA_CERTIFICATE",trust.absolutePath)
        put("SSL_CERT_FILE",trust.absolutePath)
            remove("SSL_CERT_DIR")
        remove("CODEX_ACCESS_TOKEN")
                remove("OPENAI_API_KEY")
        remove("CODEX_API_KEY")
        remove("CODEX_ANDROID_API_KEY")
            remove("CODEX_ANDROID_VERIFICATION_SOCKET")
        remove("CODEX_ANDROID_VAULT_SOCKET")
        remove("CODEX_ANDROID_VAULT_HOST_PID")
            remove("CODEX_INTERNAL_APP_SERVER_REMOTE_CONTROL_DISABLED")
                remove("CODEX_ANDROID_DEBUG_CONNECTION_FILE")
                remove("CODEX_ANDROID_DAEMON_PARENT_STDIN")
        remove("CODEX_ANDROID_DAEMON_RECEIPT")
        }
        if(config.endpoint.trimEnd('/')!="https://api.openai.com/v1"){
            val base=JsonPrimitive(config.endpoint.trimEnd('/')).toString()
            args+=listOf("-c","model_provider=\"android_service\"","-c","model_providers.android_service={name=\"手机模型服务\",base_url=$base,wire_api=\"responses\",env_key=\"CODEX_ANDROID_API_KEY\"}")
            if(config.apiKey.isNotBlank())env["CODEX_ANDROID_API_KEY"]=config.apiKey
        }
        return OriginalProcessSettings(args,env)
    }
    override suspend fun open(launch:OriginalEngineLaunch):OriginalEngineSession {
        var completed:OriginalEngineSession?=null
        try{return withContext(Dispatchers.IO){create(launch).also{completed=it}}}
        catch(failure:Throwable){withContext(NonCancellable+Dispatchers.IO){runCatching{completed?.close()}}
        throw failure}
    }
    private fun create(launch:OriginalEngineLaunch):OriginalEngineSession {
        val(context,config,workspace,home,scope)=launch
        val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_engine.so")
        check(binary.isFile && binary.canExecute()){ "当前包未包含可执行的原版核心，请生成对应ABI产物" }
        val settings=settings(context,config,home)
        val vault=OriginalVaultServer(context,scope)
        var verification:OriginalVerificationServer?=null
        var child:NativeCoreProcess?=null
        var rpc:AppServerConnection?=null
        return try{
            val verifier=OriginalVerificationServer(context,scope).also{verification=it}
            val env=settings.environment.toMutableMap().apply{
                put("CODEX_ANDROID_VERIFICATION_SOCKET",verifier.path)
        put("CODEX_ANDROID_VAULT_SOCKET",vault.path)
        put("CODEX_ANDROID_VAULT_HOST_PID",android.os.Process.myPid().toString())
            }
            val process=NativeCoreProcess(binary,listOf("--listen","stdio://")+settings.arguments,env,workspace).also{child=it}
            vault.authorize(process.pid)
        verifier.authorize(process.pid)
            scope.launch(Dispatchers.IO){runCatching{process.errors.use{stream->val bytes=ByteArray(4096)
        while(stream.read(bytes)>=0){ }}}}
            val rpcConnection=AppServerConnection(process.input,process.output,scope,process::close).also{rpc=it}
            object:OriginalEngineSession {
                override val connection:AppServerConnection=rpcConnection
                private val closed=AtomicBoolean()
                override fun close(){if(!closed.compareAndSet(false,true))return
        try{rpcConnection.close()
        process.close()}finally{verifier.close()
        vault.close()}}
            }
        }catch(failure:Throwable){runCatching{rpc?.close()}
        runCatching{child?.close()}
        runCatching{verification?.close()}
        runCatching{vault.close()}
        throw failure}
    }
}
