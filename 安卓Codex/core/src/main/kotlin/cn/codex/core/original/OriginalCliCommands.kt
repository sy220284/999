package cn.codex.core.original

/** 固定原版CLI的明确业务命令；参数按数组传递，不经shell。 */
data class OriginalCliPlan(val title:String,val arguments:List<String>,val mutating:Boolean=false,val input:String="",val expectedDiff:String?=null,val timeoutMs:Long=120_000)
object OriginalCliCommands {
    private fun identity(value:String):String{require(value.isNotBlank() && !value.startsWith('-') && value.length<=8192 && value.none{it=='\u0000' || it=='\n' || it=='\r'}){"标识不能为空、过长或包含控制字符/选项前缀"};return value}
    fun cloudEnvironments()=OriginalCliPlan("读取可用云端环境",listOf("cloud","environments"))
    fun cloudList(environment:String?,cursor:String?=null)=OriginalCliPlan("读取云任务",buildList{addAll(listOf("cloud","list","--json","--limit","20"));environment?.takeIf{it.isNotBlank()}?.let{addAll(listOf("--env",identity(it)))};cursor?.let{addAll(listOf("--cursor",identity(it)))}})
    fun cloudCreate(environment:String,prompt:String,branch:String?,attempts:Int):OriginalCliPlan {
        require(prompt.isNotBlank() && prompt.toByteArray().size<=64*1024 && '\u0000' !in prompt && attempts in 1..4){"任务最多64KiB，生成方案数量须为1至4"}
        return OriginalCliPlan("创建云任务",buildList{addAll(listOf("cloud","exec","--env",identity(environment),"--attempts",attempts.toString()));branch?.takeIf{it.isNotBlank()}?.let{addAll(listOf("--branch",identity(it)))};add("--");add(prompt)},true)
    }
    fun cloudStatus(task:String)=OriginalCliPlan("读取云任务状态",listOf("cloud","status",identity(task)))
    fun cloudDiff(task:String,attempt:Int):OriginalCliPlan{require(attempt in 1..4){"尝试编号须为1至4"};return OriginalCliPlan("读取云任务差异",listOf("cloud","diff",identity(task),"--attempt",attempt.toString()))}
    fun cloudApply(task:String,attempt:Int,diff:String):OriginalCliPlan{require(diff.isNotBlank() && diff.toByteArray().size<=2*1024*1024);return cloudDiff(task,attempt).copy(title="应用已预览云任务差异",arguments=listOf("cloud","apply",identity(task),"--attempt",attempt.toString()),mutating=true,expectedDiff=diff)}
    fun doctor(json:Boolean=true,summary:Boolean=false,all:Boolean=false,ascii:Boolean=false,feedback:Boolean=false):OriginalCliPlan {
        require(!json || !summary && !all && !ascii){"JSON与人类报告显示选项不能同时使用"}
        return OriginalCliPlan("检查安装、账号与运行环境",buildList{add("doctor");if(json)add("--json")else{add("--no-color");if(summary)add("--summary");if(all)add("--all");if(ascii)add("--ascii")};if(feedback)add("--feedback")})
    }
    fun latestDiff(task:String)=OriginalCliPlan("预览任务最新差异",listOf("apply","--preview","--",identity(task)))
    fun latestApply(task:String,diff:String):OriginalCliPlan{require(diff.isNotBlank() && diff.toByteArray().size<=2*1024*1024);return OriginalCliPlan("应用任务最新差异",listOf("apply",identity(task)),true,expectedDiff=diff)}
    fun models(bundled:Boolean)=OriginalCliPlan("读取原版模型目录",listOf("debug","models")+if(bundled)listOf("--bundled")else emptyList())
    fun promptInput(prompt:String,images:List<String>):OriginalCliPlan{
        require(prompt.toByteArray().size<=65536 && '\u0000' !in prompt && images.size<=8)
        return OriginalCliPlan("查看模型可见输入",buildList{addAll(listOf("debug","prompt-input"));images.forEach{require(',' !in it);addAll(listOf("--image",identity(it)))};if(prompt.isNotEmpty()){add("--");add(prompt)}})
    }
    fun traceReduce(bundle:String)=OriginalCliPlan("归约会话跟踪",listOf("debug","trace-reduce",identity(bundle),"--output","@ANDROID_EXPORT@/state.json"),true)
    fun clearMemories()=OriginalCliPlan("清除原版本地记忆",listOf("debug","clear-memories"),true)
    fun debugMessage(message:String):OriginalCliPlan{require(message.isNotBlank() && message.toByteArray().size<=65536 && '\u0000' !in message);return OriginalCliPlan("发送原版协议诊断消息",listOf("debug","app-server","send-message-v2","--",message),true,timeoutMs=600_000)}
    fun schema(kind:String,experimental:Boolean):OriginalCliPlan{
        require(kind in setOf("generate-ts","generate-json-schema","generate-internal-json-schema") && (kind!="generate-internal-json-schema" || !experimental))
        return OriginalCliPlan("导出原版协议文件",listOf("app-server",kind,"--out","@ANDROID_EXPORT@")+if(experimental)listOf("--experimental")else emptyList(),true)
    }
    fun rollouts(apply:Boolean)=OriginalCliPlan(if(apply)"迁移旧会话历史"else"检查旧会话历史",listOf("migrate-rollouts","--json")+if(apply)listOf("--apply")else emptyList(),apply,timeoutMs=600_000)
    fun nfsMigration()=OriginalCliPlan("迁移数据库至DELETE模式",listOf("migrate-nfs-db"),true,input="y\n",timeoutMs=600_000)
    fun completion(shell:String):OriginalCliPlan{require(shell in setOf("bash","zsh","fish","powershell","elvish"));return OriginalCliPlan("生成终端补全",listOf("completion",shell))}
    fun execPolicy(rules:List<String>,command:List<String>,resolve:Boolean):OriginalCliPlan {
        require(rules.size in 1..20 && command.size in 1..256 && command.sumOf{it.toByteArray().size}<=65536 && command.none{'\u0000' in it}){"请提供规则文件和有效的命令参数数组"}
        return OriginalCliPlan("检查命令执行策略",buildList{addAll(listOf("execpolicy","check","--pretty"));rules.forEach{addAll(listOf("-r",identity(it)))};if(resolve)add("--resolve-host-executables");add("--");addAll(command)})
    }

    fun stdioToUds(socket:String):List<String>{
        require(socket.startsWith('/') && socket.length<=4096 && socket.none{it=='\u0000' || it=='\n' || it=='\r'}){"请填写本机Unix服务的绝对socket路径"}
        return listOf("stdio-to-uds",socket)
    }

}
