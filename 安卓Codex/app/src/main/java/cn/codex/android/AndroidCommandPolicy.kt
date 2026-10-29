package cn.codex.android
import android.content.Context
import cn.codex.core.original.AndroidCommandPolicyDocument
import java.io.File
/** 策略只由宿主在进程启动时冻结；工具请求环境变量不能覆盖核心进程中的策略。 */
object AndroidCommandPolicy {
    fun enabled(context:Context)=context.getSharedPreferences("执行策略",Context.MODE_PRIVATE).getBoolean("restrictedCommands",false)
    fun save(context:Context,enabled:Boolean){
        val app=context.applicationContext as CodexApplication
        check(!app.originalCore.state.value.connected && !app.originalCore.state.value.loading && !app.originalCore.state.value.closing && !app.nativeEndpoints.running && !app.originalCore.cli.state.value.busy && !app.fullTerminal.state.value.running && !app.controller.state.value.busy && app.configurationJournal.active.value==null){"请先结束任务、关闭核心和全部连接服务，再修改启动策略"}
        check(context.getSharedPreferences("执行策略",Context.MODE_PRIVATE).edit().putBoolean("restrictedCommands",enabled).commit()){"执行策略保存失败"}
    }
    fun environment(context:Context):Map<String,String>{
        if(!enabled(context))return emptyMap()
        val project=File(context.filesDir,"工作区/原版项目").apply{mkdirs()}.canonicalFile
        val helper=File(context.applicationInfo.nativeLibraryDir,"libcodex_sandbox.so").canonicalFile
        check(helper.isFile && helper.canExecute()){"受限命令模式所需程序缺失，请安装含对应产物的包；不会降级执行"}
        return mapOf(AndroidCommandPolicyDocument.ENV to AndroidCommandPolicyDocument.create(project.path,helper.path).toString())
    }
}
