package cn.codex.android
import android.content.Context
import android.util.AtomicFile
import cn.codex.core.original.OriginalAppServerOptions
import kotlinx.serialization.json.*
import java.io.File
/** 只保存无凭证的进程启动偏好；损坏时明确失败。 */
object OriginalAppServerPreferences {
    private fun file(context:Context)=AtomicFile(File(context.noBackupFilesDir,"原版核心启动选项.json"))
    @Synchronized fun read(context:Context):JsonObject{
        val file=file(context)
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return buildJsonObject{}
        return file.openRead().use{input->val bytes=input.readNBytes(8193);require(bytes.size<=8192);OriginalAppServerOptions.parse(bytes.toString(Charsets.UTF_8))}
    }
    @Synchronized fun write(context:Context,text:String){
        val options=OriginalAppServerOptions.parse(text);val file=file(context);val output=file.startWrite()
        try{output.write(options.toString().toByteArray());file.finishWrite(output)}catch(failure:Exception){file.failWrite(output);throw failure}
    }
}
