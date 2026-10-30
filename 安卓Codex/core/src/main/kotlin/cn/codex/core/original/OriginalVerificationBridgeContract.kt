package cn.codex.core.original

import kotlinx.serialization.json.*
import java.util.Base64

/** 本机桥接只有固定原版哈希命名空间；不接受工具自己选择别的Keystore别名。 */
object OriginalVerificationBridgeContract {
    const val LIMIT=16*1024
    private const val PREFIX="com.openai.codex.user-verification.plugin-service.v1."
    fun namespace(value:String):String {
        require(value.startsWith(PREFIX)){"验证身份格式不正确"}
        val suffix=value.removePrefix(PREFIX)
        require(suffix.matches(Regex("[A-Za-z0-9_-]{43}")))
        val bytes=Base64.getUrlDecoder().decode(suffix)
        require(bytes.size==32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)==suffix)
        return value
    }
    fun request(value:JsonObject):JsonObject {
        require(value.keys.all{it in setOf("operation","namespace","challenge","title","description")})
        val operation=value.getValue("operation").jsonPrimitive.content
        require(operation in setOf("device","status","ensure","delete","verify"))
        if(operation!="device")namespace(value.getValue("namespace").jsonPrimitive.content)
        if(operation=="verify")OriginalCompletionData.verification(value.getValue("challenge").jsonPrimitive.content,value.getValue("title").jsonPrimitive.content,value.getValue("description").jsonPrimitive.content)
        require(value.toString().toByteArray(Charsets.UTF_8).size<=LIMIT)
        return value
    }
}
