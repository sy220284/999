package cn.codex.core.original
import kotlinx.serialization.json.*
import java.net.URI
/** 专用调试核心连接文件；不经argv、日志或普通工具环境传递能力凭证。 */
class AndroidDebugConnection(val address:String,private val credential:String){
    init{validateAddress(address);require(credential.matches(Regex("[A-Za-z0-9_-]{32,128}")))}
    fun document()=buildJsonObject{put("address",address);put("credential",credential)}.toString()
    override fun toString()="AndroidDebugConnection(redacted)"
    companion object {
        private fun validateAddress(value:String){val uri=URI(value);require(uri.scheme=="ws" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.isNullOrEmpty())}
        fun address(receipt:String,pid:Int):String{require(receipt.toByteArray().size<=4096);val row=Json.parseToJsonElement(receipt).jsonObject;require(row.getValue("pid").jsonPrimitive.int==pid);return row.getValue("address").jsonPrimitive.content.also{validateAddress(it)}}
    }
}
