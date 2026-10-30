package cn.codex.core.original
import java.io.File
import java.net.URI
import kotlinx.serialization.json.*
/** 进程级参数与模型配置分开，避免把app-server标志误传给exec。 */
object OriginalAppServerOptions {
    fun parse(text:String):JsonObject{
        require(text.toByteArray().size<=8192)
        val value=Json.parseToJsonElement(text).jsonObject
        require(value.keys.all{it in setOf("strictConfig","analyticsDefaultEnabled","cloudSkillsSnapshot","codeModeHost")}){"包含未知核心启动参数"}
        for(key in listOf("strictConfig","analyticsDefaultEnabled"))value[key]?.let{require(it is JsonPrimitive && !it.isString && it.booleanOrNull!=null)}
        value["codeModeHost"]?.let{val uri=URI(it.jsonPrimitive.content);require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.orEmpty() in setOf("","/")){"代码模式主机须为不含凭证、查询或路径的HTTP/HTTPS来源"}}
        value["cloudSkillsSnapshot"]?.let{require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && !File(it.content).isAbsolute && it.content.none{c->c.isISOControl()}){"技能快照须为当前项目相对文件"}}
        return value
    }
    fun arguments(value:JsonObject,workspace:File):List<String>{
        parse(value.toString())
        return buildList{
            if(value["strictConfig"]?.jsonPrimitive?.booleanOrNull==true)add("--strict-config")
            if(value["analyticsDefaultEnabled"]?.jsonPrimitive?.booleanOrNull==true)add("--analytics-default-enabled")
            value["codeModeHost"]?.let{add("--code-mode-host=${it.jsonPrimitive.content}")}
            value["cloudSkillsSnapshot"]?.let{val file=File(workspace,it.jsonPrimitive.content).canonicalFile;require(file.toPath().startsWith(workspace.canonicalFile.toPath()) && file.isFile && file.length()<=20L*1024*1024){"技能快照不在当前项目内、不存在或超过20MiB"};add("--cloud-skills-snapshot=${file.absolutePath}")}
        }
    }
}
