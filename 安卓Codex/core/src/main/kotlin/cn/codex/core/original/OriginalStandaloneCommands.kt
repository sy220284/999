package cn.codex.core.original
import kotlinx.serialization.json.*
/** 原版CLI参数白名单，保持参数数组/stdio/退出码语义，不用普通对话替代独立exec。 */
object OriginalStandaloneCommands {
    private val booleans=setOf("strict-config","json","ephemeral","ignore-user-config","ignore-rules","skip-git-repo-check","oss","approve-for-me","dangerously-bypass-approvals-and-sandbox","dangerously-bypass-hook-trust","worktree")
    private val strings=setOf("model","local-provider","profile","sandbox","thread-source","cyber-access-program","output-schema")
    private val arrays=setOf("image","add-dir")
    fun exec(prompt:String,options:JsonObject,action:String="start",session:String=""):OriginalCliPlan{
        require(prompt.isNotBlank() && prompt.toByteArray().size<=65536 && '\u0000' !in prompt)
        require(action in setOf("start","resume","resume-last","fork","review-uncommitted","review-base","review-commit","review-custom"))
        require(options.keys.all{it in booleans || it in strings || it in arrays || it=="output-last-message" || it=="all" || it=="review-title"}){"包含未知原版执行选项"}
        fun flag(key:String)=(options[key] as? JsonPrimitive)?.booleanOrNull==true
        require(!flag("approve-for-me") || !flag("dangerously-bypass-approvals-and-sandbox") && "sandbox" !in options){"自动审查与沙箱/跳过审批选项冲突"}
        val review=action.startsWith("review-")
        require(!review || "output-schema" !in options && "cyber-access-program" !in options){"原版审查不支持输出Schema或Cyber程序"}
        if("all" in options)require(action in setOf("resume","resume-last") && options["all"] is JsonPrimitive && options["all"]?.jsonPrimitive?.booleanOrNull!=null)
        val title=options["review-title"]?.let{require(action=="review-commit" && it is JsonPrimitive && it.isString && it.content.length<=8192 && it.content.none{c->c.isISOControl()});it.content}
        val args=mutableListOf("exec","--color=never")
        for((key,raw)in options){
            when{
                key in booleans->{require(raw is JsonPrimitive && !raw.isString && raw.booleanOrNull!=null);if(raw.boolean)args+="--$key"}
                key in strings->{require(raw is JsonPrimitive && raw.isString && raw.content.isNotBlank() && raw.content.length<=8192 && raw.content.none{it.isISOControl()});val value=raw.content
                    if(key=="sandbox")require(value in setOf("read-only","workspace-write","danger-full-access"))
                    if(key=="local-provider")require(value in setOf("ollama","lmstudio"))
                    if(key=="cyber-access-program")require(value in setOf("standard","daybreak_blue","daybreak_red"))
                    args+="--$key=$value"}
                key in arrays->{require(raw is JsonArray && raw.size<=32);raw.forEach{part->require(part is JsonPrimitive && part.isString && part.content.isNotBlank() && part.content.length<=8192 && part.content.none{it.isISOControl()} && (key!="image" || ',' !in part.content));args+="--$key=${part.content}"}}
                key=="output-last-message"->{require(raw is JsonPrimitive && !raw.isString && raw.booleanOrNull!=null);if(raw.boolean)args+="--output-last-message=@ANDROID_EXPORT@/last-message.txt"}
            }
        }
        if(action in setOf("resume","fork","review-base","review-commit"))require(session.isNotBlank() && !session.startsWith('-') && session.length<=8192 && session.none{it.isISOControl()}){"请输入有效的会话或版本标识"}
        when(action){
            "start"->args+=listOf("--","-")
            "resume"->{args+="resume";if(flag("all"))args+="--all";args+=listOf(session,"--","-")}
            "resume-last"->{args+=listOf("resume","--last");if(flag("all"))args+="--all";args+=listOf("--","-")}
            "fork"->args+=listOf("fork",session,"--","-")
            "review-uncommitted"->args+=listOf("review","--uncommitted")
            "review-base"->args+=listOf("review","--base=$session")
            "review-commit"->{args+=listOf("review","--commit=$session");title?.let{args+="--title=$it"}}
            "review-custom"->args+=listOf("review","--","-")
        }
        require(args.sumOf{it.toByteArray().size}<=65536){"执行参数超过64KiB预算"}
        return OriginalCliPlan("运行原版独立执行",args,true,if(review && action!="review-custom")""else prompt,timeoutMs=1_800_000)
    }
    fun loginToken(token:String,apiKey:Boolean):OriginalCliPlan{
        require(token.isNotBlank() && token.toByteArray().size<=65536 && token.all{it.code in 33..126}){"凭证为空、过长或含控制字符"}
        return OriginalCliPlan(if(apiKey)"导入原版API密钥"else"导入原版访问令牌",listOf("login",if(apiKey)"--with-api-key"else"--with-access-token"),true,input=token+"\n")
    }
    fun loginDevice(issuer:String,client:String):OriginalCliPlan{
        require(client.length<=1024 && client.none{it.isISOControl()})
        val args=mutableListOf("login","--device-auth")
        if(issuer.isNotBlank()){val url=java.net.URI(issuer);require(url.scheme=="https" && !url.host.isNullOrBlank() && url.userInfo==null && url.query==null && url.fragment==null);args+="--experimental_issuer=${url.toASCIIString()}"}
        if(client.isNotBlank())args+="--experimental_client-id=$client"
        return OriginalCliPlan("原版设备码登录",args,true,timeoutMs=900_000)
    }
    fun version()=OriginalCliPlan("读取原版CLI版本",listOf("--version"))
}
