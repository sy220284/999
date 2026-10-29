package cn.codex.core.original
import java.io.File
import kotlinx.serialization.json.*
object AndroidCommandPolicyDocument {
    const val ENV="CODEX_ANDROID_COMMAND_SANDBOX"
    fun create(project:String,program:String):JsonObject{
        require(File(project).isAbsolute && File(project).parentFile!=null && File(program).isAbsolute && File(program).name=="libcodex_sandbox.so")
        return buildJsonObject{put("version",1);put("project",project);put("program",program)}
    }
}
