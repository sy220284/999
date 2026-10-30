package cn.codex.core.original
import kotlinx.serialization.json.*
/** 只传递实际合并的desktop表；解释与根分配仍由固定原版WorktreeSettings执行。 */
data class WorktreeConfiguration(val desktop:JsonObject){
    fun request(base:String?):JsonObject{
        require(base==null || base.isNotBlank() && base.length<=1024 && base.none{it.isISOControl()}){"请输入有效Git版本，不得含控制字符"}
        return buildJsonObject{put("desktop",desktop);put("base",base?.let(::JsonPrimitive) ?: JsonNull)}
    }
    companion object {
        fun from(response:JsonObject):WorktreeConfiguration{
            val config=response["config"] as? JsonObject ?: throw IllegalArgumentException("核心未返回有效合并配置")
            val desktop=config["desktop"]
            require(desktop==null || desktop==JsonNull || desktop is JsonObject){"原版desktop配置不是对象"}
            return WorktreeConfiguration(desktop as? JsonObject ?: buildJsonObject{})
        }
    }
}
