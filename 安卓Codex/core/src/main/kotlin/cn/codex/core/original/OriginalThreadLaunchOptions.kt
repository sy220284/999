package cn.codex.core.original
import kotlinx.serialization.json.*
/** 固定原版新会话选项；当前目录、模型和手机工具定义由宿主负责。 */
object OriginalThreadLaunchOptions {
    private val allowed=setOf("modelProvider","allowProviderModelFallback","serviceTier","runtimeWorkspaceRoots","approvalPolicy","approvalsReviewer","sandbox","permissions","config","serviceName","baseInstructions","developerInstructions","personality","multiAgentMode","ephemeral","historyMode","sessionStartSource","threadSource","projectId","daybreakEnabled","environments","selectedCapabilityRoots","mockExperimentalField","experimentalRawEvents")
    fun inherit(value:JsonObject,profile:String?,settings:JsonObject?):JsonObject{
        if(profile==null || "permissions" in value || "sandbox" in value)return value
        val selected=buildJsonObject{put("permissions",profile);settings?.get("approvalPolicy")?.let{put("approvalPolicy",it)};settings?.get("approvalsReviewer")?.let{put("approvalsReviewer",it)}}
        return JsonObject(selected+value)
    }
    fun parse(text:String):JsonObject{
        require(text.toByteArray().size<=256*1024){"新会话设置超过256KiB"}
        val value=Json.parseToJsonElement(text).jsonObject
        require(value.keys.all{it in allowed}){"新会话设置包含未知或宿主持有的字段"}
        require(value["sandbox"]==null || value["sandbox"]==JsonNull || value["permissions"]==null || value["permissions"]==JsonNull){"权限方案和沙箱不能同时指定"}
        value["config"]?.takeIf{it!=JsonNull}?.jsonObject?.keys?.forEach{key->require(key.matches(Regex("[A-Za-z0-9_.-]+")) && key.substringBefore('.') !in setOf("cli_auth_credentials_store","mcp_oauth_credentials_store","auth_keyring_backend","shell_environment_policy","auth","codex_home")){"新会话配置不能覆盖手机凭证或宿主环境策略"}}
        return value
    }
}
