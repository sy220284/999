package cn.codex.core.original
import kotlinx.serialization.json.*
/** 身份、传输、输出由宿主持有；其余原版启动选项原样保留，包括显式null。 */
object OriginalRealtimeOptions {
    private val allowed=setOf("clientManagedHandoffs","delegationAckFiller","flushTranscriptTailOnSessionEnd","codexResponsesAsItems","codexResponseItemPrefix","codexResponseHandoffMode","backendReasoningStatus","codexResponseHandoffChannelPrefixes","model","includeStartupContext","initialItems","realtimeStartInstructions","realtimeEndInstructions","prompt","realtimeSessionId","voiceSessionId","version","voice")
    fun parse(text:String):JsonObject {
        require(text.toByteArray().size<=65536){"实时设置超过64KiB"}
        val value=Json.parseToJsonElement(text).jsonObject
        require(value.keys.all{it in allowed}){"实时设置包含未知字段或宿主持有的身份/传输字段"}
        value["version"]?.takeIf{it!=JsonNull}?.let{require(it.jsonPrimitive.content in setOf("v1","v2","v3"))}
        value["initialItems"]?.takeIf{it!=JsonNull}?.let{items->require(items is JsonArray && items.size<=128);items.forEach{row->require(row is JsonObject && row.keys==setOf("role","text") && row["role"]?.jsonPrimitive?.content in setOf("user","assistant","developer") && row["text"] is JsonPrimitive && row["text"]?.jsonPrimitive?.isString==true)}}
        return value
    }
}
