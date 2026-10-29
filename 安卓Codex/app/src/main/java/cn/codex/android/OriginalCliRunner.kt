package cn.codex.android
import android.content.Context
import cn.codex.core.original.OriginalCliPlan
import cn.codex.core.original.AndroidCommandPolicyDocument
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID

data class OriginalCliResult(val exitCode:Int,val output:String,val artifact:String?=null)
/** 每次命令拥有自己的进程、凭证桥与退出状态；不经过shell，不自动重放。 */
class OriginalCliRunner(private val context:Context,private val scope:CoroutineScope,private val workspace:File,private val home:File) {
    suspend fun execute(plan:OriginalCliPlan):OriginalCliResult=withContext(Dispatchers.IO){
        if(plan.arguments.firstOrNull()=="android-sandbox")return@withContext AndroidSandboxRunner(context,workspace).execute(plan.arguments)
        val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_cli.so")
        check(binary.isFile && binary.canExecute()){ "当前包没有原版CLI，请先生成并随包安装对应ABI产物" }
        val environment=System.getenv().toMutableMap()
        environment.putAll(AndroidLanguageRuntime.prepare(context));environment.putAll(AndroidToolPaths.environment(context))
        val vault=OriginalVaultServer(context,scope);var process:NativeCoreProcess?=null;var expected:File?=null;var debug:AndroidDebugCore?=null;var verification:OriginalVerificationServer?=null
        try{
            val arguments=mutableListOf("-c","cli_auth_credentials_store=\"keyring\"","-c","mcp_oauth_credentials_store=\"keyring\"")
            var artifact:File?=null
            val exportToken="@ANDROID_EXPORT@"
            if(plan.arguments.firstOrNull()=="exec" || plan.arguments.any{it.startsWith(exportToken)}){
                require(plan.arguments.firstOrNull()=="exec" || plan.arguments.take(2) in listOf(listOf("app-server","generate-ts"),listOf("app-server","generate-json-schema"),listOf("app-server","generate-internal-json-schema"),listOf("debug","trace-reduce")))
                val root=File(context.filesDir,"导出").apply{check(isDirectory || mkdirs())}
                artifact=File(root,UUID.randomUUID().toString()).apply{check(mkdir())}
            }
            arguments+=plan.arguments.map{if(it.startsWith(exportToken) || plan.arguments.firstOrNull()=="exec" && it.startsWith("--output-last-message=$exportToken"))it.replace(exportToken,requireNotNull(artifact).absolutePath)else it}
            fun projectInput(raw:String,directory:Boolean):File{
                require(!File(raw).isAbsolute){"输入文件需填写当前项目相对路径"}
                val path=File(workspace,raw).canonicalFile
                require(path.path.startsWith(workspace.canonicalPath+File.separator) && if(directory)path.isDirectory else path.isFile && path.length()<=20L*1024*1024){"输入不在项目内、不存在或超过20MiB"}
                return path
            }
            environment.remove("CODEX_ANDROID_PRETTIER_SCRIPT")
            plan.formatterScript?.let{script->
                require(plan.arguments.take(2)==listOf("app-server","generate-ts"))
                val entry=projectInput(script,false);val frontend=File(context.applicationInfo.nativeLibraryDir,"libcodex_prettier.so")
                check(frontend.isFile && frontend.canExecute() && File(context.applicationInfo.nativeLibraryDir,"libcodex_node.so").canExecute()){"当前包没有真实Node格式化启动程序，未执行占位格式化"}
                val at=arguments.indexOf("@ANDROID_PRETTIER@");check(at>=0);arguments[at]=frontend.absolutePath;environment["CODEX_ANDROID_PRETTIER_SCRIPT"]=entry.absolutePath
            }
            if(plan.arguments.take(2)==listOf("debug","trace-reduce")){val path=projectInput(plan.arguments[2],true);arguments[arguments.indexOf("trace-reduce")+1]=path.absolutePath}
            if(plan.arguments.take(2)==listOf("debug","prompt-input")){
                val end=plan.arguments.indexOf("--").let{if(it<0)plan.arguments.size else it}
                for(i in 2 until end)if(plan.arguments[i]=="--image")projectInput(plan.arguments[i+1],false)
            }
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
            for(key in listOf("OPENAI_API_KEY","CODEX_API_KEY","CODEX_ANDROID_API_KEY","CODEX_ANDROID_VERIFICATION_SOCKET","CODEX_ANDROID_DAEMON_PARENT_STDIN","CODEX_ANDROID_DAEMON_RECEIPT"))environment.remove(key)
            environment.remove("CODEX_ANDROID_DEBUG_CONNECTION_FILE");environment.remove("CODEX_ANDROID_LOGIN_EVENTS")
            if(plan.arguments.take(2)==listOf("login","--device-auth"))environment["CODEX_ANDROID_LOGIN_EVENTS"]="1"
            if(plan.arguments.take(3)==listOf("debug","app-server","send-message-v2")){
                val helper=AndroidDebugCore(context,scope,workspace,home).also{debug=it};helper.start();environment.putAll(helper.environment)
            }
            if(plan.arguments.firstOrNull()=="exec"){
                val saved=SecureSettings(context).load();val settings=NativeOriginalEngineFactory.settings(context,saved,home)
                val originalPlan=arguments.drop(4);arguments.clear();arguments+=settings.arguments;arguments+=originalPlan
                environment.putAll(settings.environment)
                environment["CODEX_ANDROID_VAULT_SOCKET"]=vault.path;environment["CODEX_ANDROID_VAULT_HOST_PID"]=android.os.Process.myPid().toString()
                val bridge=OriginalVerificationServer(context,scope).also{verification=it};environment["CODEX_ANDROID_VERIFICATION_SOCKET"]=bridge.path
                for(arg in plan.arguments){if(arg.startsWith("--image="))projectInput(arg.substringAfter('='),false);if(arg.startsWith("--output-schema="))projectInput(arg.substringAfter('='),false)}
            }else{environment.remove("CODEX_ACCESS_TOKEN");environment.remove("CODEX_ANDROID_FORWARD_AUTH");environment.remove("CODEX_ANDROID_EXECUTOR_EVENTS");environment.remove(AndroidCommandPolicyDocument.ENV);environment.putAll(AndroidCommandPolicy.environment(context))}
            val child=NativeCoreProcess(binary,arguments,environment,workspace).also{process=it};vault.authorize(child.pid);verification?.authorize(child.pid)
            coroutineScope{
                val stdout=async(Dispatchers.IO){
                    if(plan.arguments.take(2)==listOf("login","--device-auth")){
                        val all=ByteArrayOutputStream();val line=ByteArrayOutputStream()
                        while(true){val byte=child.input.read();if(byte<0)break;check(all.size()<65536){"设备码输出超过预算，已停止"};all.write(byte)
                            if(byte==10){val event=line.toString(Charsets.UTF_8.name());line.reset();if(event.isNotBlank())plan.progress?.invoke(event)}else{check(line.size()<4096){"设备码事件超过预算，已停止"};line.write(byte)}}
                        all.toString(Charsets.UTF_8.name())
                    }else{
                        val bytes=child.input.readNBytes(2*1024*1024+1);check(bytes.size<=2*1024*1024){ "返回内容超过2MiB，请缩小范围后重新读取；任务可能已生效" }
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                }
                val stderr=launch(Dispatchers.IO){child.errors.use{input->val bytes=ByteArray(8192);while(input.read(bytes)>=0){ /* stderr可能含凭证，不写日志和持久文件 */ }}}
                val writer=launch(Dispatchers.IO){child.output.use{it.write(plan.input.toByteArray(Charsets.UTF_8));it.flush()}}
                try{
                    withTimeout(plan.timeoutMs){
                        var code=child.pollExit();while(code==null){delay(25);code=child.pollExit()}
                        val output=stdout.await()
                        if(plan.arguments.firstOrNull()=="exec"){
                            val file=File(requireNotNull(artifact),if("--json" in plan.arguments)"events.jsonl"else"output.txt")
                            java.nio.file.Files.writeString(file.toPath(),output,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)
                        }
                        OriginalCliResult(requireNotNull(code),output,artifact?.absolutePath).also{writer.join();stderr.join()}
                    }
                }finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
            }
        }finally{withContext(NonCancellable+Dispatchers.IO){process?.close();debug?.close();verification?.close();vault.close();expected?.delete()}}
    }
}
