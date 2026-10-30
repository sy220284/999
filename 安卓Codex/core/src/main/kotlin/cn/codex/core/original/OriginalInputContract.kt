package cn.codex.core.original
import cn.codex.core.string
import java.io.File
import java.net.URI
import kotlinx.serialization.json.*
/** 原版UserInput的完整联合类型；保持输入顺序，不把媒体伪装成文字。 */
object OriginalInputContract {
    fun input(text:String,extra:JsonArray=JsonArray(emptyList())):JsonArray {
        require(text.toByteArray().size<=65536 && extra.size<=32 && extra.toString().toByteArray().size<=256*1024){"输入超过本次预算"}
        val rows=if(text.isEmpty())extra.toList()else OriginalCodexClient.textInput(text)+extra
        require(rows.isNotEmpty()){"请填写任务或选择输入"}
        for(raw in rows){val value=raw as? JsonObject ?: error("输入项必须是对象");val type=value.string("type")
            val allowed=when(type){"text"->setOf("type","text","text_elements");"image"->setOf("type","url","fileId","detail");"localImage"->setOf("type","path","detail");"audio"->setOf("type","url");"localAudio"->setOf("type","path");"skill","mention"->setOf("type","name","path");else->error("未知原版输入类型：$type")}
            require(value.keys.all{it in allowed}){"输入包含未知字段"}
            value["detail"]?.let{require(it is JsonPrimitive && it.isString && it.content in setOf("auto","low","high","original"))}
            when(type){
                "text"->{require(value["text"] is JsonPrimitive && value["text"]?.jsonPrimitive?.isString==true && value["text_elements"] is JsonArray)}
                "image"->{require((value["url"]!=null).xor(value["fileId"]!=null));if(value["url"]!=null)url(value.string("url"))else require(value.string("fileId").isNotBlank())}
                "audio"->url(value.string("url"))
                else->{require(value.string("path").isNotBlank() && '\u0000' !in value.string("path"));if(type in setOf("skill","mention"))require(value.string("name").isNotBlank())}
            }
        }
        return JsonArray(rows)
    }
    private fun url(value:String){val uri=URI(value);require(uri.scheme in setOf("https","http","data") && uri.rawUserInfo==null){"媒体地址必须是HTTP、HTTPS或data地址，不能内嵌账号"};if(uri.scheme!="data")require(!uri.host.isNullOrBlank())}
    fun localPaths(input:JsonArray,root:File):JsonArray=JsonArray(input.map{raw->val row=raw.jsonObject
        if(row.string("type") in setOf("localImage","localAudio","skill","mention")){
            val path=File(row.string("path")).let{if(it.isAbsolute)it else File(root,it.path)}.canonicalFile
            require(path.toPath().startsWith(root.canonicalFile.toPath()) && path.exists()){"本地输入须位于当前项目：${row.string("path")}"}
            if(row.string("type") in setOf("localImage","localAudio"))require(path.isFile && path.length()<=20L*1024*1024){"媒体文件超过20MiB或不是普通文件"}
            JsonObject(row+("path" to JsonPrimitive(path.absolutePath)))
        }else row
    })
}
