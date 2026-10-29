package cn.codex.core.original
import kotlinx.serialization.json.*
import java.net.URI

enum class RemoteExecutorAuth { ACCOUNT,AGENT_IDENTITY,AWS,SCOPED }
object RemoteExecutorPlan {
    fun build(url:String,environment:String,name:String,auth:RemoteExecutorAuth,profile:String,region:String,service:String,forward:String?=null,concurrent:Int=1,strict:Boolean=false):NativeEndpointPlan{
        require(concurrent in 1..64){"并发数量应为1到64"}
        val uri=URI(url);require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.port in -1..65535 && uri.port!=0)
        require(environment.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) && name.length<=256 && name.none{it.code<32})
        val args=mutableListOf("exec-server","--remote",uri.toASCIIString(),"--environment-id=$environment","--remote-transport",if(auth==RemoteExecutorAuth.AWS)"direct"else"noise","--exit-on-stdin-close")
        args+="--concurrent-requests=$concurrent";if(strict)args+="--strict-config"
        if(name.isNotBlank())args+="--name=$name"
        when(auth){
            RemoteExecutorAuth.ACCOUNT->Unit
            RemoteExecutorAuth.AGENT_IDENTITY->args+="--use-agent-identity-auth"
            RemoteExecutorAuth.SCOPED->args+="--executor-credential-stdin"
            RemoteExecutorAuth.AWS->{
                require(profile.isBlank() || profile.length<=256 && profile.none{it.code<32});require(region.matches(Regex("[a-z0-9-]{1,64}")) && service.matches(Regex("[a-z0-9-]{1,64}")))
                args+=listOf("--aws-sigv4","--aws-region=$region","--aws-service=$service");if(profile.isNotBlank())args+="--aws-profile=$profile"
            }
        }
        if(forward!=null){
            require(auth!=RemoteExecutorAuth.AWS){"原版Direct不支持forward"}
            val destination=URI(forward)
            require(destination.scheme=="ws" && destination.host=="127.0.0.1" && destination.port in 1..65535 && destination.rawPath.isNullOrEmpty() && destination.rawUserInfo==null && destination.rawQuery==null && destination.rawFragment==null){"只能转发本次已认证本机执行器"}
            args+=listOf("forward","--connect=$forward")
        }
        return NativeEndpointPlan(NativeEndpointKind.REMOTE_EXECUTOR,args,mapOf("CODEX_ANDROID_EXECUTOR_EVENTS" to "1"))
    }
    fun credential(environment:String,token:String,expires:Long,now:Long=System.currentTimeMillis()/1000):ByteArray{
        require(environment.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) && token.isNotBlank() && token.all{it.code in 33..126} && expires>now)
        val value=buildJsonObject{put("version",1);put("environment_id",environment);put("token",token);put("expires_at",expires)}.toString()+"\n"
        return value.toByteArray().also{require(it.size<=16384){"凭证帧超过原版16KiB预算"}}
    }
}
