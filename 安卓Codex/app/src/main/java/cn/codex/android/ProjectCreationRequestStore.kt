package cn.codex.android

import android.content.Context
import android.util.AtomicFile
import cn.codex.core.string
import cn.codex.core.original.OriginalProjectRequestStore
import kotlinx.serialization.json.*
import java.io.File

/** 未知创建结果跨重连保留相同幂等身份，不包含令牌或密钥。 */
class ProjectCreationRequestStore(context:Context):OriginalProjectRequestStore {
    private val file=AtomicFile(File(context.noBackupFilesDir,"原版项目创建请求.json"))
    @Synchronized override fun read():JsonObject? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        return file.openRead().bufferedReader().use{Json.parseToJsonElement(it.readText()).jsonObject}
    }
    @Synchronized override fun write(value:JsonObject){
        val out=file.startWrite()
        try{out.write(value.toString().toByteArray(Charsets.UTF_8));file.finishWrite(out)}catch(failure:Exception){file.failWrite(out);throw failure}
        check(read()==value){"项目请求记录保存失败"}
    }
    @Synchronized override fun clear(key:String?){if(key==null || read()?.string("key")==key)file.delete()}
}
