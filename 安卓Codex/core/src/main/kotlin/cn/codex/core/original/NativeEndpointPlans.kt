package cn.codex.core.original
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*
enum class NativeEndpointKind(val title:String){PROXY("本机模型代理"),TUNNEL("HTTP/3连接隧道"),EXECUTOR("本机执行服务"),DAEMON("共享核心服务")}
data class NativeEndpointPlan(val kind:NativeEndpointKind,val arguments:List<String>)
/** 身份贯穿启动/就绪/停止，晚到事件不能复活或覆盖另一轮服务。 */
class NativeEndpointGeneration {
    private var id:String?=null
    private var stopping=false
    var running=false
        private set
    var listening=false
        private set
    @Synchronized fun start(value:String){check(!running);id=value;running=true;listening=false;stopping=false}
    @Synchronized fun ready(value:String):Boolean{if(id!=value || !running || stopping)return false;listening=true;return true}
    @Synchronized fun stop(value:String):Boolean{if(id!=value || !running)return false;stopping=true;listening=false;return true}
    @Synchronized fun finish(value:String):Boolean{if(id!=value)return false;running=false;listening=false;return true}
}
object NativeEndpointPlans {
    fun origin(value:String):String{
        val uri=URI(value);require(uri.scheme.equals("https",true) && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.orEmpty() in setOf("","/") && (uri.port==-1 || uri.port in 1..65535)){"代理地址需要不含凭证、路径或查询的HTTPS来源"}
        return "https://"+uri.host.lowercase(Locale.ROOT)+if(uri.port in setOf(-1,443))""else ":${uri.port}"
    }
    private fun port(value:Int){require(value in 0..65535){"端口须为0（自动）或1—65535"}}
    fun proxy(upstream:String,listen:Int,receipt:String,clientTokenFile:String):NativeEndpointPlan{
        port(listen)
        val u=URI(upstream);require(u.scheme=="https" && !u.host.isNullOrBlank() && u.rawUserInfo==null && u.rawFragment==null && u.rawQuery==null){"上游需要不含凭证或查询的HTTPS地址"}
        return NativeEndpointPlan(NativeEndpointKind.PROXY,listOf("responses-api-proxy","--port",listen.toString(),"--upstream-url",u.toASCIIString(),"--server-info",receipt,"--client-token-file",clientTokenFile,"--max-request-bytes","16777216","--exit-on-stdin-close"))
    }
    fun tunnel(proxy:String,target:String,listen:Int,origins:String):NativeEndpointPlan{
        port(listen)
        val trusted=origin(proxy)
        val u=URI("https://$target")
        require(!u.host.isNullOrBlank() && u.port in 1..65535 && u.rawUserInfo==null && u.path.isEmpty() && u.rawQuery==null && u.rawFragment==null){"目标需为主机:端口，IPv6请使用方括号"}
        return NativeEndpointPlan(NativeEndpointKind.TUNNEL,listOf("tcp-tunnel","--proxy-url",trusted,"--proxy-origins-file",origins,"--target",target,"--listen-addr","127.0.0.1:$listen","--auth-token-stdin","--auth-token-updates-stdin","--connect-headers-stdin","--diagnostics-json"))
    }
    fun executor(tokenHash:String,listen:Int):NativeEndpointPlan{port(listen);require(tokenHash.matches(Regex("[a-f0-9]{64}")));return NativeEndpointPlan(NativeEndpointKind.EXECUTOR,listOf("exec-server","--listen","ws://127.0.0.1:$listen","--ws-auth","capability-token","--ws-token-sha256",tokenHash,"--exit-on-stdin-close"))}
    fun daemon(tokenHash:String,listen:Int,remote:Boolean):NativeEndpointPlan{port(listen);require(tokenHash.matches(Regex("[a-f0-9]{64}")));return NativeEndpointPlan(NativeEndpointKind.DAEMON,listOf("app-server","--listen","ws://127.0.0.1:$listen","--ws-auth","capability-token","--ws-token-sha256",tokenHash,"--managed-daemon")+if(remote)listOf("--remote-control")else emptyList())}
    fun readyLine(kind:NativeEndpointKind,line:String):String?{
        if(kind !in setOf(NativeEndpointKind.TUNNEL,NativeEndpointKind.EXECUTOR))return null
        val raw=if(kind==NativeEndpointKind.TUNNEL)line.takeIf{it.startsWith("LISTENING ")}?.removePrefix("LISTENING ")else line.takeIf{it.startsWith("ws://")}
        raw ?: return null
        val pattern=if(kind==NativeEndpointKind.TUNNEL)Regex("127\\.0\\.0\\.1:([0-9]{1,5})")else Regex("ws://127\\.0\\.0\\.1:([0-9]{1,5})")
        val match=pattern.matchEntire(raw) ?: return null
        return raw.takeIf{match.groupValues[1].toInt() in 1..65535}
    }
    fun metadata(value:String):String{
        val array=Json.parseToJsonElement(value.ifBlank{"[]"}).jsonArray;require(array.size<=16)
        val seen=mutableSetOf<String>()
        for(part in array){val pair=part.jsonArray;require(pair.size==2 && pair.all{it is JsonPrimitive && it.isString})
        val name=pair[0].jsonPrimitive.content.lowercase(Locale.ROOT)
        val text=pair[1].jsonPrimitive.content
            require(name.matches(Regex("x-[a-z0-9-]+")) && !name.startsWith("x-forwarded-") && name!="x-real-ip" && seen.add(name)){"附加头需为不重复的x-扩展头，不能覆盖认证或转发信息"}
            require(text.toByteArray().size<=4096 && text.all{it.code in 32..126}){"附加头值须为不含控制符的ASCII内容，最多4KiB"}
        }
        val encoded=array.toString();require(encoded.toByteArray().size+1<=16384);return encoded
    }
    fun credential(value:String,maximum:Int):ByteArray{val bytes=value.toByteArray(Charsets.US_ASCII);require(value.isNotEmpty() && value.all{it.code in 33..126} && bytes.size<=maximum){"凭证为空、过长或含不支持的字符"};return bytes}
}
