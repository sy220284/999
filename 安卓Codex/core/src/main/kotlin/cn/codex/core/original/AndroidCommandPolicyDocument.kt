package cn.codex.core.original
import java.io.File
import kotlinx.serialization.json.*
object AndroidCommandPolicyDocument {
    const val ENV="CODEX_ANDROID_COMMAND_SANDBOX"
    fun create(project:String,program:String,runtime:String?=null,tools:String?=null):JsonObject{
        require((runtime==null)==(tools==null)){"只读运行库和工具根需同时指定"}
        require(File(project).isAbsolute && File(project).parentFile!=null && File(program).isAbsolute && File(program).name=="libcodex_sandbox.so")
        return buildJsonObject{put("version",1);put("project",project);put("program",program);runtime?.let{require(File(it).isAbsolute && File(it).name=="内置运行环境");put("runtime",it)};tools?.let{require(File(it).isAbsolute && File(it).name=="原版工具");put("tools",it)}}
    }
}
