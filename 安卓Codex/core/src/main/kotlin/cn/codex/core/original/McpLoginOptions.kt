package cn.codex.core.original
import kotlinx.serialization.json.*
/** 原版每次登录参数；不会写回MCP持久配置。 */
data class McpLoginOptions(val scopes:List<String> = emptyList(),val registration:String?=null,val timeoutSeconds:Int=180){
    fun params(name:String,thread:String?):JsonObject{
        require(name.isNotBlank() && name.length<=1024)
        require(registration==null || registration in setOf("auto","cimd","dcr"));require(timeoutSeconds in 1..3600)
        require(scopes.size<=64 && scopes.distinct().size==scopes.size && scopes.all{it.isNotBlank() && it.length<=1024 && it.none{c->c.isISOControl() || c==' '}})
        return buildJsonObject{put("name",name);thread?.let{put("threadId",it)};registration?.let{put("clientRegistration",it)};if(scopes.isNotEmpty())put("scopes",JsonArray(scopes.map(::JsonPrimitive)));put("timeoutSecs",timeoutSeconds)}
    }
}
