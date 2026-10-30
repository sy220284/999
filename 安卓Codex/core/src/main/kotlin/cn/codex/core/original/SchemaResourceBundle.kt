package cn.codex.core.original
import cn.codex.core.string
import kotlinx.serialization.json.*
import java.net.URI
/** 用户明确选择的离线规则；只作本次请求的验证输入，不联网或跨请求保存。 */
object SchemaResourceBundle {
    fun parse(text:String):List<JsonObject>{
        require(text.toByteArray().size<=256*1024){"规则文件超过256KiB"}
        val value=Json.parseToJsonElement(text)
        val items=when(value){is JsonArray->value;is JsonObject->(value["schemas"] as? JsonArray) ?: JsonArray(listOf(value));else->error("需要规则对象或规则数组")}
        require(items.size in 1..32);val ids=mutableSetOf<String>()
        return items.map{val document=it.jsonObject;val id=document.string("\$id");val uri=URI(id);require(id.isNotBlank() && uri.isAbsolute && uri.rawFragment.isNullOrEmpty() && ids.add(uri.normalize().toString().substringBefore('#'))){"每份规则需有不重复的绝对\$id标识，不能包含片段"};document}
    }
}
