package cn.codex.android
import android.util.AtomicFile
import cn.codex.core.original.NativeEndpointKind
import cn.codex.core.string
import kotlinx.serialization.json.*
import java.io.File
/** 只存服务身份/类型/时间，不存地址、凭证、参数或重启指令。 */
class NativeEndpointRecords(directory:File){
    data class Entry(val id:String,val kind:NativeEndpointKind,val startedAt:Long)
    private val file=AtomicFile(File(directory,"服务操作记录.json"))
    var error:String?=null
        private set
    var entries:List<Entry> = read()
        private set
    private fun read():List<Entry>{
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return emptyList()
        return try{file.openRead().use{stream->val bytes=stream.readNBytes(65537)
        require(bytes.size<=65536)
            val values=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        require(values.size<=100)
            values.map{val row=it.jsonObject
        Entry(row.string("id"),NativeEndpointKind.valueOf(row.string("kind")),row.getValue("startedAt").jsonPrimitive.long)}
        }}catch(_:Exception){error="上次服务操作记录无法读取，请核查实际进程状态"
        emptyList()}
    }
    @Synchronized fun write(next:List<Entry>){
        require(next.size<=100)
        val out=file.startWrite()
        try{out.write(JsonArray(next.map{buildJsonObject{put("id",it.id)
        put("kind",it.kind.name)
        put("startedAt",it.startedAt)}}).toString().toByteArray())
        file.finishWrite(out)
        entries=next} catch(failure:Exception){file.failWrite(out)
        error="服务记录保存失败，请核查实际状态"
        throw failure}
    }
    @Synchronized fun acknowledge(active:Set<String>){write(entries.filter{it.id in active})
        error=null}
}
