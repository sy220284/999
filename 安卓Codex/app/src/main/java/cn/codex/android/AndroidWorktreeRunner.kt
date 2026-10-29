package cn.codex.android
import android.content.Context
import kotlinx.coroutines.*
import java.io.File
/** 固定原版worktree库的有限入口；不通过shell，不传账号凭证。 */
class AndroidWorktreeRunner(private val context:Context,private val workspace:File,private val home:File){
    suspend fun execute(arguments:List<String>):String=withContext(Dispatchers.IO){
        require(arguments.firstOrNull() in setOf("list","create","bind","remove") && arguments.size in 2..4)
        val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_worktree.so");check(binary.isFile && binary.canExecute()){"当前包缺少原版工作树程序"}
        val env=AndroidToolPaths.environment(context).toMutableMap().apply{put("PATH",(context.applicationContext as CodexApplication).toolPath);put("CODEX_HOME",home.absolutePath);put("HOME",home.absolutePath);put("TMPDIR",context.cacheDir.absolutePath);put("SHELL","/system/bin/sh")}
        val child=NativeCoreProcess(binary,arguments,env,workspace)
        try{coroutineScope{
            val result=async{val bytes=child.input.readNBytes(1024*1024+1);require(bytes.size<=1024*1024);bytes.toString(Charsets.UTF_8)}
            val errors=launch{child.errors.use{stream->val bytes=ByteArray(8192);while(stream.read(bytes)>=0){ }}}
            try{withTimeout(120_000){child.output.close();var code=child.pollExit();while(code==null){delay(25);code=child.pollExit()};check(code==0){"原版工作树操作未成功，请检查Git产物、仓库和目录；不会自动重放"};result.await().also{errors.join()}}}finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
        }}finally{child.close()}
    }
}
