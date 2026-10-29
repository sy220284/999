package cn.codex.android
import android.content.Context
import kotlinx.coroutines.*
import java.io.File
class AndroidSandboxRunner(private val context:Context,private val workspace:File){
    suspend fun execute(arguments:List<String>):OriginalCliResult=withContext(Dispatchers.IO){
        require(arguments.firstOrNull()=="android-sandbox")
        val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_sandbox.so");check(binary.isFile && binary.canExecute()){"当前包没有受限执行程序；未执行普通命令作为替代"}
        val probe=arguments==listOf("android-sandbox","probe")
        val args=if(probe)listOf("probe")else{require(arguments.size==3 && arguments[1]=="run" && arguments[2].toByteArray().size<=65536);listOf("run-extended",workspace.canonicalPath,context.applicationInfo.nativeLibraryDir,workspace.canonicalPath,File(context.filesDir,"内置运行环境").apply{mkdirs()}.canonicalPath,File(context.filesDir,"原版工具").apply{mkdirs()}.canonicalPath,"--","/system/bin/sh","-c",arguments[2])}
        val env=AndroidLanguageRuntime.prepare(context).filterKeys{it in setOf("CODEX_ANDROID_NODE_HOME","CODEX_ANDROID_PYTHON_HOME")}+mapOf("PATH" to ((context.applicationContext as CodexApplication).toolPath),"HOME" to workspace.canonicalPath,"TMPDIR" to workspace.canonicalPath,"LANG" to "C.UTF-8","SHELL" to "/system/bin/sh","npm_config_script_shell" to "/system/bin/sh","LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir)
        val child=NativeCoreProcess(binary,args,env,workspace)
        try{coroutineScope{
            val stdout=async{child.input.readNBytes(1024*1024+1).also{require(it.size<=1024*1024)}.toString(Charsets.UTF_8)}
            val stderr=async{child.errors.readNBytes(65537).also{require(it.size<=65536)}.toString(Charsets.UTF_8)}
            try{withTimeout(120_000){child.output.close();var code=child.pollExit();while(code==null){delay(25);code=child.pollExit()};OriginalCliResult(requireNotNull(code),stdout.await()+"\n"+stderr.await())}}finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
        }}finally{child.close()}
    }
}
