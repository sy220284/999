package cn.codex.android
import android.content.Context
import cn.codex.core.original.OriginalCliPlan
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID

data class OriginalCliResult(val exitCode:Int,val output:String)
/** 每次命令拥有自己的进程、凭证桥与退出状态；不经过shell，不自动重放。 */
class OriginalCliRunner(private val context:Context,private val scope:CoroutineScope,private val workspace:File,private val home:File) {
    suspend fun execute(plan:OriginalCliPlan):OriginalCliResult=withContext(Dispatchers.IO){
        val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_cli.so")
        check(binary.isFile && binary.canExecute()){ "当前包没有原版CLI，请先生成并随包安装对应ABI产物" }
        val environment=System.getenv().toMutableMap()
        environment.putAll(AndroidLanguageRuntime.prepare(context));environment.putAll(AndroidToolPaths.environment(context))
        val vault=OriginalVaultServer(context,scope);var process:NativeCoreProcess?=null;var expected:File?=null
        try{
            val arguments=mutableListOf("-c","cli_auth_credentials_store=\"keyring\"","-c","mcp_oauth_credentials_store=\"keyring\"")
            arguments+=plan.arguments
            if(plan.arguments.firstOrNull()=="execpolicy"){
                for(index in 0 until plan.arguments.indexOf("--"))if(plan.arguments[index]=="-r"){
                    val relative=plan.arguments[index+1];require(!File(relative).isAbsolute){"规则文件请填写当前项目相对路径"}
                    val rule=File(workspace,relative).canonicalFile
                    require(rule.path.startsWith(workspace.canonicalPath+File.separator) && rule.isFile && rule.length()<=256*1024){"规则文件不在项目内、不存在或超过256KiB"}
                }
            }
            val reviewed=plan.expectedDiff
            if(reviewed!=null){
                expected=File(context.cacheDir,"approved-diff-${UUID.randomUUID()}.patch").apply{writeText(reviewed,Charsets.UTF_8)}
                arguments+=listOf("--expected-diff-file",requireNotNull(expected).absolutePath)
            }
            environment["CODEX_HOME"]=home.absolutePath;environment["HOME"]=home.absolutePath
            environment["TMPDIR"]=context.cacheDir.absolutePath;environment["SHELL"]="/system/bin/sh"
            environment["CODEX_CA_CERTIFICATE"]=requireNotNull(environment["SSL_CERT_FILE"]);environment.remove("SSL_CERT_DIR")
            environment["PATH"]=(context.applicationContext as CodexApplication).toolPath
            environment["CODEX_ANDROID_VAULT_SOCKET"]=vault.path;environment["CODEX_ANDROID_VAULT_HOST_PID"]=android.os.Process.myPid().toString()
            for(key in listOf("OPENAI_API_KEY","CODEX_API_KEY","CODEX_ANDROID_API_KEY","CODEX_ANDROID_VERIFICATION_SOCKET"))environment.remove(key)
            val child=NativeCoreProcess(binary,arguments,environment,workspace).also{process=it};vault.authorize(child.pid)
            coroutineScope{
                val stdout=async(Dispatchers.IO){
                    val bytes=child.input.readNBytes(2*1024*1024+1);check(bytes.size<=2*1024*1024){ "返回内容超过2MiB，请缩小范围后重新读取" }
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
                val stderr=launch(Dispatchers.IO){child.errors.use{input->val bytes=ByteArray(8192);while(input.read(bytes)>=0){ /* stderr可能含凭证，不写日志和持久文件 */ }}}
                try{
                    withTimeout(plan.timeoutMs){
                        child.output.use{it.write(plan.input.toByteArray(Charsets.UTF_8));it.flush()}
                        var code=child.pollExit();while(code==null){delay(25);code=child.pollExit()}
                        OriginalCliResult(requireNotNull(code),stdout.await()).also{stderr.join()}
                    }
                }finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
            }
        }finally{withContext(NonCancellable+Dispatchers.IO){process?.close();vault.close();expected?.delete()}}
    }
}
