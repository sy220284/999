package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.Base64

object OriginalCompletionData {
    fun roots(text:String):JsonArray {
        val paths=text.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        require(paths.isNotEmpty() && paths.all{File(it).isAbsolute}){"项目根目录必须是手机上的绝对路径"}
        return JsonArray(paths.map{buildJsonObject{put("path",it)}})
    }
    fun itemIds(text:String):JsonArray {
        val ids=text.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        require(ids.size in 1..100){"请提供1至100个条目标识，每行一个"}
        return JsonArray(ids.map(::JsonPrimitive))
    }
    fun readState(thread:String,revision:String,read:Boolean):JsonObject {
        require(thread.isNotBlank() && revision.isNotBlank()){"请先读取并查看会话状态"}
        return buildJsonObject{put("threadId",thread);put("expectedRevision",revision);put("operation",buildJsonObject{put("type",if(read)"read" else "unread")})}
    }
    fun settings(text:String,turn:Boolean):JsonObject {
        val value=Json.parseToJsonElement(text).jsonObject
        val allowed=if(turn)setOf("approvalsReviewer","model","effort","summary","serviceTier") else setOf("disabledPluginIds","cwd","approvalPolicy","approvalsReviewer","sandboxPolicy","permissions","model","serviceTier","effort","summary","collaborationMode","personality")
        require(value.isNotEmpty() && value.keys.all{it in allowed}){"设置为空或包含不支持的字段；会话/回合身份不能在设置内修改"}
        require(value["permissions"]==null || value["permissions"]==JsonNull || value["sandboxPolicy"]==null || value["sandboxPolicy"]==JsonNull){"权限配置和沙箱策略不能同时修改"}
        value["cwd"]?.takeIf{it!=JsonNull}?.let{require(File(it.jsonPrimitive.content).isAbsolute){"工作目录需要绝对路径"}}
        return value
    }
    fun environment(id:String,url:String,token:String,requestId:String):JsonObject {
        require(id.isNotBlank()){"请填写环境标识"}
        val uri=URI(url);require(uri.scheme in setOf("ws","wss") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null){"请输入有效的WebSocket地址"}
        val local=uri.host in setOf("localhost","127.0.0.1","::1","[::1]")
        require((token.isBlank() && requestId.isBlank()) || uri.scheme=="wss" || local){"携带凭证需要加密连接或精确的本机回环地址"}
        return buildJsonObject{put("environmentId",id);put("execServerUrl",url);if(token.isNotBlank())put("authBearerToken",token);if(requestId.isNotBlank())put("websocketRequestId",requestId)}
    }
    fun verification(challenge:String,title:String,description:String):JsonObject {
        require(challenge.matches(Regex("[A-Za-z0-9_-]+")) && challenge.length<=5462 && Base64.getUrlDecoder().decode(challenge).let{it.size in 1..4096 && Base64.getUrlEncoder().withoutPadding().encodeToString(it)==challenge}){"挑战需为1至4096字节的无填充base64url"}
        require(title.toByteArray().size in 1..256 && description.toByteArray().size<=4096){"验证标题或说明超过UTF-8长度限制"}
        return buildJsonObject{put("challenge",challenge);put("title",title);put("description",description)}
    }
}

data class OriginalCompletionPage(val rows:List<JsonObject> = emptyList(),val next:String?=null,val cursors:Set<String> = emptySet()) {
    fun append(response:JsonObject,cursor:String?):OriginalCompletionPage {
        val data=response.getValue("data").jsonArray.map{it.jsonObject}
        val next=response.string("nextCursor").ifBlank{null}
        val seen=if(cursor==null)emptySet()else cursors+cursor
        require(next==null || next !in seen){"服务返回重复分页游标，请重新读取"}
        val combined=if(cursor==null)data else rows+data
        require(combined.size<=2000 && combined.sumOf{it.toString().toByteArray(Charsets.UTF_8).size.toLong()}<=4L*1024*1024){"已达到2000条或4MiB内容上限，请缩小范围或重新读取"}
        return OriginalCompletionPage(combined,next,seen)
    }
}
