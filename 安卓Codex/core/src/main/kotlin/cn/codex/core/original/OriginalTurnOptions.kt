package cn.codex.core.original
import kotlinx.serialization.json.*
/** 固定原版TurnStartParams的可选字段；会话身份和输入由宿主独占。 */
object OriginalTurnOptions {
    private val allowed=setOf("disabledPluginIds","clientUserMessageId","turnTrigger","parentTurnId","rootTurnId","toolOutput","responsesapiClientMetadata","responsesapiClientMetadataPatch","additionalContext","environments","cwd","runtimeWorkspaceRoots","approvalPolicy","approvalsReviewer","sandboxPolicy","permissions","model","serviceTier","serviceTierForTurn","effort","summary","personality","outputSchema","collaborationMode","multiAgentMode","cyberAccessProgram")
    fun parse(text:String):JsonObject{
        require(text.toByteArray().size<=256*1024){"回合设置超过256KiB"}
        val value=Json.parseToJsonElement(text).jsonObject
        require(value.keys.all{it in allowed}){"回合设置包含未知字段；会话身份和输入需使用宿主控件"}
        require(value["permissions"]==null || value["permissions"]==JsonNull || value["sandboxPolicy"]==null || value["sandboxPolicy"]==JsonNull){"权限配置与沙箱不能同时指定"}
        return value
    }
}
