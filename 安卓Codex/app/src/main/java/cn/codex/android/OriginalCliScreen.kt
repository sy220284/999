package cn.codex.android
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.original.*
import cn.codex.core.string
import kotlinx.serialization.json.*

private data class CliConfirmation(val title:String,val body:String,val action:()->Unit)

@Composable fun OriginalCliScreen(host:OriginalCoreHost,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by host.cli.state.collectAsState();val core by host.state.collectAsState()
    var environment by rememberSaveable{mutableStateOf("")};var prompt by rememberSaveable{mutableStateOf("")};var branch by rememberSaveable{mutableStateOf("")}
    var attempts by rememberSaveable{mutableStateOf("1")}
    var task by rememberSaveable{mutableStateOf("")};var attempt by rememberSaveable{mutableStateOf("1")}
    var confirmation by remember{mutableStateOf<CliConfirmation?>(null)};var error by remember{mutableStateOf<String?>(null)}
    var rules by rememberSaveable{mutableStateOf("[\"rules/local.rules\"]")};var command by rememberSaveable{mutableStateOf("[\"git\",\"status\"]")};var resolve by rememberSaveable{mutableStateOf(false)}
    var sandboxCommand by rememberSaveable{mutableStateOf("")}
    var shell by rememberSaveable{mutableStateOf("bash")}
    val endpoints by (LocalContext.current.applicationContext as CodexApplication).nativeEndpoints.state.collectAsState()
    val daemonActive=endpoints[NativeEndpointKind.DAEMON]?.running==true
    var doctorJson by rememberSaveable{mutableStateOf(true)};var doctorSummary by rememberSaveable{mutableStateOf(false)};var doctorAll by rememberSaveable{mutableStateOf(false)};var doctorAscii by rememberSaveable{mutableStateOf(false)};var doctorFeedback by rememberSaveable{mutableStateOf(false)}
    var bundled by rememberSaveable{mutableStateOf(true)};var debugPrompt by rememberSaveable{mutableStateOf("")};var images by rememberSaveable{mutableStateOf("[]")};var trace by rememberSaveable{mutableStateOf("")}
    var schemaKind by rememberSaveable{mutableStateOf("generate-json-schema")};var experimental by rememberSaveable{mutableStateOf(false)}
    var exporting by remember{mutableStateOf(false)};var exportSource by remember{mutableStateOf<String?>(null)};var exportMessage by remember{mutableStateOf<String?>(null)}
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val path=exportSource;exportSource=null
        if(uri!=null && path!=null)scope.launch{exporting=true;try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt").use{out->checkNotNull(out);OriginalArtifactExport.write(File(context.filesDir,"导出"),File(path),out)}};exportMessage="协议文件已导出"}catch(error:Exception){if(error is CancellationException)throw error;exportMessage="导出未完成，目标文件可能只写入部分内容，请核查后删除"}finally{exporting=false}}
    }
    val idle=!exporting && !daemonActive && !state.busy && !core.connected && !core.closing && !core.loading
    fun action(block:()->Unit){try{block();error=null}catch(e:Exception){error=e.message ?: "输入格式不正确"}}
    fun back(){if(state.busy)confirmation=CliConfirmation("停止当前操作并返回？","远程任务或已写入文件可能已生效，返回后请核查结果。"){host.cli.stop();onBack()}else onBack()}
    BackHandler(onBack=::back)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onClick=::back){Text("返回工作区")};Text("云任务与本机维护",style=MaterialTheme.typography.headlineSmall)
            Text("使用同一份原版账号和项目。运行工具期间请保持原版核心断开；云任务需要服务授权，创建后由云端继续执行。",style=MaterialTheme.typography.bodySmall)
            if(daemonActive)Text("共享核心仍在运行，请在本机连接服务页停止后再执行独立维护。",color=MaterialTheme.colorScheme.primary)
            if(core.connected)TextButton(onClick={host.close()},enabled=!core.working && core.turnId==null && !core.loading){Text("断开原版连接，准备工具")}
            Text(state.title,color=MaterialTheme.colorScheme.primary);if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(state.busy)TextButton(onClick={confirmation=CliConfirmation("停止当前操作？","停止本机等待不会撤销已经创建的云任务或文件变更。"){host.cli.stop()}}){Text("停止当前操作")}
        }
        item{TextButton(onClick={host.cli.environments()},enabled=idle){Text("读取可用云端环境")}
            if(state.environmentsTruncated)Text("环境超过200个，仅显示前200个；可直接填写服务提供的环境ID。")
        }
        items(state.environments,key={"env:"+it.string("id")}){row->TextButton(onClick={environment=row.string("id")},enabled=idle){Text(row.string("label",row.string("id"))+" · "+row.string("id"))}}
        item{OutlinedTextField(environment,{environment=it},label={Text("云端环境ID或名称")},enabled=idle,modifier=Modifier.fillMaxWidth())
            Row{TextButton(onClick={action{host.cli.list(environment)}},enabled=idle){Text("读取云任务")};TextButton(onClick={action{host.cli.list(environment,true)}},enabled=idle && state.cursor!=null){Text("加载更多")}}
        }
        items(state.tasks,key={it.string("id")}){row->OutlinedCard(onClick={task=row.string("id")},enabled=idle){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(row.string("title",row.string("id")));Text(row.string("id")+" · "+row["status"].toString(),style=MaterialTheme.typography.bodySmall)}}}
        item{OutlinedTextField(task,{task=it},label={Text("任务ID")},enabled=idle,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(attempt,{attempt=it},label={Text("尝试编号（1—4）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            Row{TextButton(onClick={action{host.cli.status(task)}},enabled=idle && task.isNotBlank()){Text("读取状态")};TextButton(onClick={action{host.cli.diff(task,attempt.toInt())}},enabled=idle && task.isNotBlank()){Text("预览差异")};TextButton(onClick={action{host.cli.latestDiff(task)}},enabled=idle && task.isNotBlank()){Text("最新差异")}}
        }
        state.preview?.let{preview->
            item{Text("已预览：${preview.task} · ${if(preview.latest)"最新代理差异"else"第${preview.attempt}次"}",style=MaterialTheme.typography.titleMedium);Text("SHA256 ${preview.sha256}",style=MaterialTheme.typography.bodySmall)}
            items(preview.text.lines().chunked(30)){lines->SelectionContainer{Text(lines.joinToString("\n"),style=MaterialTheme.typography.bodySmall)}}
            item{Button(onClick={confirmation=CliConfirmation("应用这份云任务差异？","任务：${preview.task}\n来源：${if(preview.latest)"最新代理差异"else"云端第${preview.attempt}次"}\nSHA256：${preview.sha256}\n将修改当前原版项目，内容变化时会停止。"){host.cli.apply(preview)}},enabled=idle){Text("应用已预览差异")}}
        }
        item{Text("创建云任务",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(prompt,{prompt=it},label={Text("交给云端的任务")},enabled=idle,modifier=Modifier.fillMaxWidth(),minLines=3)
            OutlinedTextField(branch,{branch=it},label={Text("Git分支（空白使用原版默认）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(attempts,{attempts=it},label={Text("生成方案数量（1—4）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            Button(onClick={action{val e=environment;val p=prompt;val b=branch;val n=attempts.toInt();OriginalCliCommands.cloudCreate(e,p,b,n)
                confirmation=CliConfirmation("创建云任务并使用服务额度？","环境：$e\n分支：${b.ifBlank{"原版默认"}}\n方案数量：$n\n$p"){host.cli.create(e,p,b,n)}}},enabled=idle && prompt.isNotBlank() && environment.isNotBlank()){Text("创建云任务")}
        }
        item{Text("本机维护",style=MaterialTheme.typography.titleMedium)
            Text("迁移前停止所有使用该数据目录的客户端。数据库迁移会保留原版备份，仍需核查存储空间和并发访问。",style=MaterialTheme.typography.bodySmall)
            Row{Checkbox(doctorJson,{doctorJson=it},enabled=idle);Text("JSON诊断报告",Modifier.padding(top=12.dp))}
            if(!doctorJson){
                Row{Checkbox(doctorSummary,{doctorSummary=it},enabled=idle);Text("仅显示摘要",Modifier.padding(top=12.dp))}
                Row{Checkbox(doctorAll,{doctorAll=it},enabled=idle);Text("展开全部条目",Modifier.padding(top=12.dp))}
                Row{Checkbox(doctorAscii,{doctorAscii=it},enabled=idle);Text("使用ASCII状态标记",Modifier.padding(top=12.dp))}
            }
            Row{Checkbox(doctorFeedback,{doctorFeedback=it},enabled=idle);Text("反馈采集模式（限制数据库扫描，不发送）",Modifier.padding(top=12.dp))}
            TextButton(onClick={action{host.cli.maintain(OriginalCliCommands.doctor(doctorJson,!doctorJson && doctorSummary,!doctorJson && doctorAll,!doctorJson && doctorAscii,doctorFeedback))}},enabled=idle){Text("检查安装、账号与运行环境")}
            TextButton(onClick={host.cli.maintain(OriginalCliCommands.rollouts(false))},enabled=idle){Text("检查旧会话历史")}
            TextButton(onClick={confirmation=CliConfirmation("迁移旧会话历史？","请先检查报告；将把原版符合条件的历史迁移到分页存储，操作失败后先核查，不自动重试。"){host.cli.maintain(OriginalCliCommands.rollouts(true))}},enabled=idle && state.migrationInspected){Text("执行会话迁移")}
            TextButton(onClick={confirmation=CliConfirmation("迁移数据库模式？","我已停止所有访问此数据目录的客户端。将切换配置数据库至DELETE模式并保留WAL原件备份；请保持足够空间。\n应用无法核实其他设备是否仍在访问共享目录。"){host.cli.maintain(OriginalCliCommands.nfsMigration())}},enabled=idle){Text("迁移数据库至DELETE模式")}
            Text("终端补全类型")
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){listOf("bash","zsh","fish","powershell","elvish").forEach{value->FilterChip(shell==value,{shell=value},label={Text(value)},enabled=idle)}}
            TextButton(onClick={host.cli.maintain(OriginalCliCommands.completion(shell))},enabled=idle){Text("生成终端补全脚本")}
        }
        item{Text("受限项目命令",style=MaterialTheme.typography.titleMedium)
            Text("条件式内核限制，仅在Landlock ABI6、系统策略和seccomp均允许时执行；不支持便拒绝，不切换为普通执行。只允许当前项目写入，系统和随包程序只读，网络被禁止。该入口尚未真机验收，普通模型任务并未因此获得沙箱。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={host.cli.maintain(OriginalCliCommands.sandboxProbe())},enabled=idle){Text("查询内核能力（不执行命令）")}
            OutlinedTextField(sandboxCommand,{sandboxCommand=it},label={Text("在当前项目执行的命令")},enabled=idle,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={val command=sandboxCommand;confirmation=CliConfirmation("在受限执行入口运行？","项目：${host.commandWorkspace()}\n命令：$command\n会修改项目；网络不可用，Node/Python完整运行库暂未挂载。内核拒绝时不会执行普通替代命令。"){host.cli.maintain(OriginalCliCommands.sandbox(command))}},enabled=idle && sandboxCommand.isNotBlank()){Text("确认后受限执行")}
        }
        item{Text("命令策略检查",style=MaterialTheme.typography.titleMedium);Text("只按规则评估参数，不执行这条命令。规则文件使用当前项目相对路径。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(rules,{rules=it},label={Text("规则文件路径数组（JSON）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(command,{command=it},label={Text("命令参数数组（JSON）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            Row{Checkbox(resolve,{resolve=it},enabled=idle);Text("按规则定义解析主机程序名",Modifier.padding(top=12.dp))}
            TextButton(onClick={action{
                fun tokens(text:String)=Json.parseToJsonElement(text).jsonArray.map{require(it is JsonPrimitive && it.isString);it.jsonPrimitive.content}
                host.cli.maintain(OriginalCliCommands.execPolicy(tokens(rules),tokens(command),resolve))
            }},enabled=idle){Text("评估执行策略")}
        }
        item{Text("协议与诊断工具",style=MaterialTheme.typography.titleMedium)
            Text("输出文件写入应用的独立导出目录，不覆盖项目文件。完成后可导出ZIP到系统文件；实验协议需与当前核心版本匹配。",style=MaterialTheme.typography.bodySmall)
            listOf("generate-ts" to "TypeScript绑定","generate-json-schema" to "JSON Schema","generate-internal-json-schema" to "内部JSON Schema").forEach{(value,label)->FilterChip(schemaKind==value,{schemaKind=value;if(value=="generate-internal-json-schema")experimental=false},label={Text(label)},enabled=idle)}
            Row{Checkbox(experimental,{experimental=it},enabled=idle && schemaKind!="generate-internal-json-schema");Text("包含实验协议",Modifier.padding(top=12.dp))}
            TextButton(onClick={val k=schemaKind;val x=experimental;confirmation=CliConfirmation("导出原版协议文件？","格式：$k\n包含实验内容：$x\n将新建独立目录。"){host.cli.maintain(OriginalCliCommands.schema(k,x))}},enabled=idle){Text("生成协议文件")}
            Row{Checkbox(bundled,{bundled=it},enabled=idle);Text("只读取内置模型目录",Modifier.padding(top=12.dp))}
            TextButton(onClick={host.cli.maintain(OriginalCliCommands.models(bundled))},enabled=idle){Text("读取模型目录")}
            Text("输入诊断按原版配置生成，不代表当前对话最终发送的完整请求。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(debugPrompt,{debugPrompt=it},label={Text("输入诊断提示或测试消息")},enabled=idle,modifier=Modifier.fillMaxWidth(),minLines=2)
            OutlinedTextField(images,{images=it},label={Text("项目图片路径数组（JSON，可选）")},enabled=idle,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={action{val paths=Json.parseToJsonElement(images).jsonArray.map{require(it is JsonPrimitive && it.isString);it.jsonPrimitive.content};host.cli.maintain(OriginalCliCommands.promptInput(debugPrompt,paths))}},enabled=idle){Text("按原版配置查看输入")}
            TextButton(onClick={val message=debugPrompt;confirmation=CliConfirmation("运行原版协议诊断并使用模型额度？","将启动专用核心发送：\n$message\n原版测试客户端会自动批准命令和文件修改；以本应用权限执行，没有项目沙箱。请只对已备份的可信项目使用。结束或取消将关闭本次专用核心。"){host.cli.maintain(OriginalCliCommands.debugMessage(message))}},enabled=idle && debugPrompt.isNotBlank()){Text("运行原版发送诊断")}
            OutlinedTextField(trace,{trace=it},label={Text("项目中的跟踪包目录")},enabled=idle,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={action{val t=trace;OriginalCliCommands.traceReduce(t);confirmation=CliConfirmation("归约跟踪包？","输入：$t\n归约文件写入新导出目录。"){host.cli.maintain(OriginalCliCommands.traceReduce(t))}}},enabled=idle && trace.isNotBlank()){Text("归约跟踪包")}
            TextButton(onClick={confirmation=CliConfirmation("清除全部原版本地记忆？","将删除当前原版账号数据目录下的本地记忆状态，无法撤销；请先备份需要保留的内容。"){host.cli.maintain(OriginalCliCommands.clearMemories())}},enabled=idle){Text("清除原版本地记忆")}
            state.artifact?.let{path->SelectionContainer{Text("导出目录：$path")};TextButton(onClick={exportSource=path;exportLauncher.launch("codex-protocol.zip")},enabled=idle){Text("导出文件到系统目录")}}
            exportMessage?.let{Text(it)}
        }
        state.doctor?.let{report->
            item{Text("诊断报告 · ${report.generatedAt}",style=MaterialTheme.typography.titleMedium);Text("原版版本：${report.version}；以下保留实际状态，未自动修复。")}
            items(report.checks,key={"doctor:"+it.id}){check->
                var expanded by remember(check.raw){mutableStateOf(false)}
                OutlinedCard{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("${check.title} · ${check.statusLabel}",style=MaterialTheme.typography.titleMedium);Text(check.explanation,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起原版明细"else"查看原版说明与修复建议")}
                    if(expanded)SelectionContainer{Text(check.raw.toString(),style=MaterialTheme.typography.bodySmall)}
                }}
            }
        }
        if(state.output.isNotEmpty())item{Text("命令返回（最多显示65536字符）",style=MaterialTheme.typography.titleMedium);SelectionContainer{Text(state.output,style=MaterialTheme.typography.bodySmall)}}
    }
    confirmation?.let{frozen->AlertDialog(onDismissRequest={confirmation=null},title={Text(frozen.title)},text={SelectionContainer{Text(frozen.body,Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={confirmation=null;action{frozen.action()}},enabled=!state.busy || frozen.title.startsWith("停止")){Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}){Text("返回")}})}
}
