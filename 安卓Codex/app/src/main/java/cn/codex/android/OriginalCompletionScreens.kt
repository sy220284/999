package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

private data class CompletionConfirmation(val title:String,val detail:String,val action:()->Unit)
@Composable private fun CompletionDialog(value:CompletionConfirmation?,dismiss:()->Unit,enabled:Boolean) {
    value?.let{confirmation->AlertDialog(onDismissRequest=dismiss,title={Text(confirmation.title)},text={SelectionContainer{Text(confirmation.detail.take(6000),Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()))}},
        confirmButton={TextButton(enabled=enabled,onClick={dismiss();confirmation.action()}){Text("确认执行")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})}
}
@Composable private fun CompletionHeader(title:String,status:OriginalOperationStatus,onBack:()->Unit) {
    TextButton(onClick=onBack){Text("返回工作区")}
    Text(title,style=MaterialTheme.typography.headlineSmall)
    Text(status.notice,color=MaterialTheme.colorScheme.primary)
    if(status.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    status.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
}
@Composable private fun CompletionValue(title:String,value:JsonElement?) {
    value?.let{var expanded by remember(it){mutableStateOf(false)};val rendered=remember(it){it.toString()}
        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp)){
            Text(title,style=MaterialTheme.typography.titleSmall)
            SelectionContainer{Text(if(expanded)rendered.take(32_768)else rendered.take(700),style=MaterialTheme.typography.bodySmall)}
            if(rendered.length>700)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起" else "展开详情（最多32KiB）")}
        }}}
}
@Composable private fun CompletionField(label:String,value:String,change:(String)->Unit,enabled:Boolean=true){OutlinedTextField(value,change,label={Text(label)},enabled=enabled,modifier=Modifier.fillMaxWidth())}
private val completionPadding=PaddingValues(20.dp)

@Composable fun OriginalProjectsScreen(host:OriginalCoreHost,onBack:()->Unit) {
    val c=host.projects;val state by c.state.collectAsState();val status by c.operations.state.collectAsState()
    var name by rememberSaveable{mutableStateOf("")};var roots by rememberSaveable{mutableStateOf(host.commandWorkspace())};var threads by rememberSaveable{mutableStateOf("")};var before by rememberSaveable{mutableStateOf("")}
    var confirmation by remember{mutableStateOf<CompletionConfirmation?>(null)}
    val idle=status.connected && !status.busy
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=completionPadding,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{CompletionHeader("项目",status,onBack)
            Text("整理手机上的项目目录与会话。删除项目记录不等于删除目录；修改前会核对详情，原协议没有原子版本锁。",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.refresh()},enabled=idle){Text("读取项目")};TextButton(onClick={c.refresh(true)},enabled=idle && state.page.next!=null){Text("下一页")}}
            if(state.dirty)Text("项目已有变动，请刷新列表或重新读取详情。",color=MaterialTheme.colorScheme.primary)
            if(state.page.rows.isEmpty())Text("尚无项目记录，读取后可在下方新建或导入。")
        }
        items(state.page.rows.distinctBy{it.string("id")},key={it.string("id")}){project->Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(16.dp)){
            Text(project.string("name"),style=MaterialTheme.typography.titleMedium);Text(project.string("id"),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={c.read(project.string("id"))},enabled=idle){Text("查看并编辑")}
        }}}
        item{Text("新建或导入",style=MaterialTheme.typography.titleMedium);CompletionField("项目名称",name,{name=it},!status.busy);CompletionField("手机根目录，每行一个",roots,{roots=it},!status.busy);CompletionField("导入的会话标识，每行一个（可留空）",threads,{threads=it},!status.busy)
            Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={val n=name;val r=roots;confirmation=CompletionConfirmation("新建项目？","$n\n$r"){c.create(n,r,"",false)}},enabled=idle){Text("新建")}
                TextButton(onClick={val n=name;val r=roots;val t=threads;confirmation=CompletionConfirmation("导入项目？","项目：$n\n目录：$r\n会话：$t"){c.create(n,r,t,true)}},enabled=idle){Text("导入")}}
            if(state.creationPayload!=null || state.recoveryError!=null){
                CompletionValue("待核查的原创建请求",state.creationPayload)
                state.creationPayload?.let{pending->TextButton(onClick={
                    val n=pending.string("name");val paths=pending.getValue("roots").jsonArray.joinToString("\n"){it.jsonObject.string("path")}
                    val ids=pending.getValue("threads").jsonArray.joinToString("\n"){it.jsonPrimitive.content};val importing=pending.getValue("import").jsonPrimitive.boolean
                    confirmation=CompletionConfirmation("核查后重发同一创建请求？","将使用原幂等标识和原内容，不创建新的请求身份。\n$n\n$paths"){c.create(n,paths,ids,importing)}
                },enabled=idle){Text("核查后重发原请求")}}
                state.recoveryError?.let{Text(it,color=MaterialTheme.colorScheme.error)};Text("上次创建结果未完全确认，请先核查项目列表。重发相同内容会沿用同一幂等标识。",color=MaterialTheme.colorScheme.error)
                TextButton(onClick={confirmation=CompletionConfirmation("另建创建请求？","请确认已经核查上次结果；新的请求可能产生另一个项目。"){c.newCreation()}},enabled=idle){Text("核查后另建请求")}}
        }
        state.selected?.let{selected->item{
            var editName by remember(selected){mutableStateOf(selected.string("name"))}
            var editRoots by remember(selected){mutableStateOf((selected["roots"] as? JsonArray).orEmpty().joinToString("\n"){it.jsonObject.string("path")})}
            CompletionValue("所选项目详情",selected);CompletionField("修改名称",editName,{editName=it},idle);CompletionField("修改根目录，每行一个",editRoots,{editRoots=it},idle)
            Button(onClick={val n=editName;val r=editRoots;confirmation=CompletionConfirmation("保存项目修改？","$n\n$r"){c.save(selected,n,r)}},enabled=idle){Text("保存修改")}
            CompletionField("移动到此项目之前（空白为末尾）",before,{before=it},idle)
            Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={val b=before.takeIf{it.isNotBlank()};confirmation=CompletionConfirmation("调整项目顺序？","项目：${selected.string("name")}\n目标：${b ?: "末尾"}"){c.move(selected,b)}},enabled=idle){Text("移动")}
                TextButton(onClick={confirmation=CompletionConfirmation("删除项目记录？","${selected.string("name")}\n不会由客户端删除目录文件，关联会话按原版规则处理。"){c.delete(selected)}},enabled=idle){Text("删除记录")}}
        }}
    }
    CompletionDialog(confirmation,{confirmation=null},idle)
}

@Composable fun OriginalHistoryScreen(host:OriginalCoreHost,onOpen:(String)->Unit,onBack:()->Unit) {
    val c=host.history;val state by c.state.collectAsState();val status by c.operations.state.collectAsState();val core by host.state.collectAsState()
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)};var turn by rememberSaveable{mutableStateOf("")};var ids by rememberSaveable{mutableStateOf("")}
    var tab by rememberSaveable{mutableStateOf(0)};var confirmation by remember{mutableStateOf<CompletionConfirmation?>(null)}
    val idle=status.connected && !status.busy;val thread=core.threadId;val same=thread!=null && state.threadId==thread
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=completionPadding,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{CompletionHeader("搜索与会话记录",status,onBack);Text("当前会话：${thread ?: "未选择"}",style=MaterialTheme.typography.bodySmall)
            ScrollableTabRow(selectedTabIndex=tab){listOf("搜索","时间线","后台终端").forEachIndexed{i,title->Tab(selected=tab==i,onClick={tab=i},text={Text(title)})}}
        }
        if(tab==0){
            item{CompletionField("会话或段落关键词",query,{query=it});Row(Modifier.horizontalScroll(rememberScrollState())){Checkbox(archived,{archived=it});Text("查找归档会话",Modifier.padding(top=12.dp))}
                Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.search(query,archived)},enabled=idle && query.isNotBlank()){Text("搜索会话")};TextButton(onClick={c.search(query,archived,true)},enabled=idle && state.searches.next!=null && query==state.query && archived==state.archived){Text("更多会话")}}
                Text("结果词：${state.query}",style=MaterialTheme.typography.bodySmall)
            }
            items(state.searches.rows){row->val result=(row["thread"] as? JsonObject) ?: row
                CompletionValue("${result.string("name").ifBlank{result.string("id")}}",row)
                TextButton(onClick={onOpen(result.string("id"))},enabled=idle && result.string("id").isNotBlank() && !core.working && core.turnId==null){Text("打开会话")}}
            item{Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={c.occurrences(query)},enabled=idle && thread!=null && query.isNotBlank()){Text("搜索当前会话段落")};TextButton(onClick={c.occurrences(query,true)},enabled=idle && same && query==state.occurrenceQuery && state.occurrences.next!=null){Text("更多段落")}}}
            if(same)items(state.occurrences.rows){row->CompletionValue("匹配段落",row)
                TextButton(onClick={turn=row.string("turnId");ids=row.string("itemId");c.items(turn,ids)},enabled=idle){Text("读取对应完整条目")}}
        }
        if(tab==1){
            item{Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.timeline()},enabled=idle && thread!=null){Text("读取时间线")};TextButton(onClick={c.timeline(true)},enabled=idle && same && state.timeline.next!=null){Text("后续记录")}}}
            if(same)items(state.timeline.rows){row->CompletionValue("${row.string("type")} · ${row.string("turnId")}",row)}
            item{TextButton(onClick={c.read()},enabled=idle && thread!=null){Text("读取会话与阅读版本")}}
            if(same)state.detail?.let{detail->item{CompletionValue("会话阅读状态",detail["readState"])
                Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={confirmation=CompletionConfirmation("标记已读？","只使用你刚读取的版本；新消息导致冲突时需要重新查看。"){c.mark(detail,true)}},enabled=idle){Text("标记已读")}
                    TextButton(onClick={confirmation=CompletionConfirmation("标记未读？","会话：${detail.string("id")}"){c.mark(detail,false)}},enabled=idle){Text("标记未读")}}}}
            item{CompletionField("回合标识",turn,{turn=it});CompletionField("条目标识，每行一个",ids,{ids=it})
                Button(onClick={c.items(turn,ids)},enabled=idle && thread!=null){Text("读取指定条目")}}
            if(same)items(state.items){row->CompletionValue("完整条目",row)}
        }
        if(tab==2){
            item{Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.terminals()},enabled=idle && thread!=null){Text("读取后台终端")};TextButton(onClick={c.terminals(true)},enabled=idle && same && state.terminals.next!=null){Text("更多终端")}}
                TextButton(onClick={confirmation=CompletionConfirmation("清理当前会话后台终端？","会话：$thread\n可能停止正在运行的命令；请求返回后仍需核查退出情况。"){c.clean()}},enabled=idle && thread!=null){Text("清理后台终端")}}
            if(same)items(state.terminals.rows){terminal->CompletionValue("${terminal.string("command")}",terminal)
                TextButton(onClick={confirmation=CompletionConfirmation("终止此进程？","${terminal.string("processId")}\n${terminal.string("command")}"){c.terminate(terminal)}},enabled=idle){Text("终止此进程")}}
        }
    }
    CompletionDialog(confirmation,{confirmation=null},idle)
}

@Composable fun OriginalAdvancedScreen(host:OriginalCoreHost,onBack:()->Unit) {
    val c=host.advanced;val state by c.state.collectAsState();val status by c.operations.state.collectAsState();val core by host.state.collectAsState()
    var tab by rememberSaveable{mutableStateOf(0)};var patch by rememberSaveable{mutableStateOf("{}")};var turn by rememberSaveable{mutableStateOf("")};var prompt by rememberSaveable{mutableStateOf("")};var query by rememberSaveable{mutableStateOf("")}
    var confirmation by remember{mutableStateOf<CompletionConfirmation?>(null)};val idle=status.connected && !status.busy
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=completionPadding,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{CompletionHeader("会话设置与记忆",status,onBack);Text("当前会话：${core.threadId ?: "未选择"}",style=MaterialTheme.typography.bodySmall)
            ScrollableTabRow(selectedTabIndex=tab){listOf("设置","记忆","采样","发现").forEachIndexed{i,title->Tab(selected=tab==i,onClick={tab=i},text={Text(title)})}}}
        if(tab==0)item{
            TextButton(onClick={c.settings()},enabled=idle && core.threadId!=null){Text("读取会话设置")};CompletionValue("当前快照",state.threadSettings)
            Text("高级编辑只修改明确提供的字段。serviceTier:null清除档位，省略则保留。权限和沙箱变化需要仔细核对。",style=MaterialTheme.typography.bodySmall)
            CompletionField("设置字段（JSON对象）",patch,{patch=it},idle)
            state.threadSettings?.takeIf{it.string("id")==core.threadId}?.let{approved->
                Button(onClick={val text=patch;confirmation=CompletionConfirmation("修改后续会话设置？","会话：${approved.string("id")}\n$text"){c.saveSettings(approved,text)}},enabled=idle){Text("保存会话设置")}
                CompletionField("当前回合标识",turn,{turn=it},idle)
                TextButton(onClick={val text=patch;val id=turn;confirmation=CompletionConfirmation("修改指定活动回合？","回合：$id\n$text\n已捕获的步骤与审批保留原审核者。"){c.saveSettings(approved,text,id)}},enabled=idle && turn.isNotBlank() && turn==core.turnId){Text("仅修改此回合")}}
        }
        if(tab==1)item{
            Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.readMemory()},enabled=idle && core.threadId!=null){Text("读取会话记忆")};TextButton(onClick={c.memoryStatus()},enabled=idle){Text("全局状态")}}
            CompletionValue("会话摘要",state.memory);CompletionValue("全局记忆状态",state.memoryStatus)
            Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={confirmation=CompletionConfirmation("启用当前会话记忆？","记忆将按原版规则参与后续会话处理。"){c.memoryMode(true)}},enabled=idle && core.threadId!=null){Text("启用记忆")}
                TextButton(onClick={confirmation=CompletionConfirmation("禁用当前会话记忆？","不会由客户端删除历史文件。"){c.memoryMode(false)}},enabled=idle && core.threadId!=null){Text("禁用记忆")}}
            TextButton(onClick={confirmation=CompletionConfirmation("重置全部记忆？","将清理核心的全局记忆数据库和记忆目录内容，无法由此操作撤销。"){c.resetMemory()}},enabled=idle){Text("重置全局记忆")}
            TextButton(onClick={confirmation=CompletionConfirmation("启动历史文件压缩？","原版在后台执行维护，接受请求不代表已经压缩完成。"){c.compress()}},enabled=idle){Text("压缩历史文件")}
            HorizontalDivider();Text("外部表单等待计数",style=MaterialTheme.typography.titleSmall)
            Text("仅用于你正在管理的外部交互；增加会暂停超时计时，完成后需对应减少。",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={confirmation=CompletionConfirmation("增加等待计数？","当前会话会暂停相关超时计时，请在交互结束后减少。"){c.elicitation(true)}},enabled=idle && core.threadId!=null){Text("增加等待")}
                TextButton(onClick={confirmation=CompletionConfirmation("减少等待计数？","服务将返回剩余计数和暂停状态。"){c.elicitation(false)}},enabled=idle && core.threadId!=null){Text("减少等待")}}
            CompletionValue("等待计数",state.elicitation)
        }
        if(tab==2)item{
            Text("采样会向模型发送提示、消耗用量，不写入主会话历史。必须先读取当前最后一个已结束回合。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={c.sampleSource()},enabled=idle && core.threadId!=null){Text("读取采样来源")};CompletionValue("来源快照",state.threadSettings)
            CompletionField("采样提示",prompt,{prompt=it},idle)
            state.threadSettings?.takeIf{it.string("id")==core.threadId}?.let{source->Button(onClick={val text=prompt;confirmation=CompletionConfirmation("调用模型采样？","会话：${source.string("id")}\n$text\n会使用服务额度。"){c.sample(text,source)}},enabled=idle && prompt.isNotBlank()){Text("确认采样")}}
            TextButton(onClick={c.cancelSample()},enabled=state.sampleRequest!=null && !state.cancelBusy){Text("取消当前采样")};CompletionValue("采样结果",state.sample)
        }
        if(tab==3){
            item{CompletionField("插件搜索词",query,{query=it});Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={c.searchPlugins(query)},enabled=idle && query.isNotBlank()){Text("搜索插件")};TextButton(onClick={c.searchPlugins(query,true)},enabled=idle && query==state.pluginQuery && state.plugins.next!=null){Text("更多结果")}}}
            items(state.plugins.rows){row->CompletionValue("插件搜索结果",row)}
            item{TextButton(onClick={c.modes()},enabled=idle){Text("读取协作模式")};CompletionValue("协作模式（可用于会话设置）",state.modes)
                TextButton(onClick={c.diagnostics()},enabled=idle){Text("读取核心诊断")};CompletionValue("进程与指标",state.diagnostics)}
        }
    }
    CompletionDialog(confirmation,{confirmation=null},idle)
}

@Composable fun OriginalConnectionsScreen(host:OriginalCoreHost,onBack:()->Unit) {
    val c=host.connections;val state by c.state.collectAsState();val status by c.operations.state.collectAsState();val core by host.state.collectAsState()
    var tab by rememberSaveable{mutableStateOf(0)};var env by rememberSaveable{mutableStateOf("")};var url by rememberSaveable{mutableStateOf("")};var token by remember{mutableStateOf("")};var requestId by remember{mutableStateOf("")}
    var ephemeral by rememberSaveable{mutableStateOf(true)};var manual by rememberSaveable{mutableStateOf(false)};var profile by rememberSaveable{mutableStateOf("")};var region by rememberSaveable{mutableStateOf("")}
    var server by rememberSaveable{mutableStateOf("codex_apps")};var stream by rememberSaveable{mutableStateOf("")};var args by rememberSaveable{mutableStateOf("{}")}
    var challenge by remember{mutableStateOf("")};var title by rememberSaveable{mutableStateOf("")};var description by rememberSaveable{mutableStateOf("")}
    var confirmation by remember{mutableStateOf<CompletionConfirmation?>(null)};val idle=status.connected && !status.busy
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=completionPadding,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{CompletionHeader("连接、订阅与认证",status,onBack)
            ScrollableTabRow(selectedTabIndex=tab){listOf("环境","远程设备","MCP订阅","Bedrock","用户验证").forEachIndexed{i,t->Tab(selected=tab==i,onClick={tab=i},text={Text(t)})}}}
        if(tab==0)item{
            Text("连接你自己的执行环境。手机独立运行无需添加远程环境；添加后所选环境可能在其他设备执行命令。",style=MaterialTheme.typography.bodySmall)
            CompletionField("环境标识",env,{env=it},idle);CompletionField("执行服务WebSocket地址",url,{url=it},idle)
            OutlinedTextField(token,{token=it},label={Text("Bearer凭证（仅本次内存）")},visualTransformation=PasswordVisualTransformation(),enabled=idle,modifier=Modifier.fillMaxWidth())
            CompletionField("可选WebSocket请求标识",requestId,{requestId=it},idle)
            Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={val e=env;val u=url;val t=token;val r=requestId;confirmation=CompletionConfirmation("连接执行环境？","标识：$e\n地址：$u\n凭证将发送给此地址，重连由核心处理。"){c.addEnvironment(e,u,t,r);token=""}},enabled=idle){Text("添加环境")}
                TextButton(onClick={c.environment(env)},enabled=idle && env.isNotBlank()){Text("读取状态")}}
            CompletionValue("环境状态",state.environmentStatus);CompletionValue("环境信息",state.environment)
        }
        if(tab==1){
            item{Text("允许信任的设备访问原版远程控制能力。需要服务端账号支持；配对码仅显示在本页，不自动复制、分享或打开。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={c.remoteStatus()},enabled=idle){Text("读取连接状态")};CompletionValue("远程控制",state.remote)
                Row(Modifier.horizontalScroll(rememberScrollState())){Checkbox(ephemeral,{ephemeral=it},enabled=idle);Text("仅本次启用，不持久保存",Modifier.padding(top=12.dp))}
                Row(Modifier.horizontalScroll(rememberScrollState())){Button(onClick={val temporary=ephemeral;confirmation=CompletionConfirmation("启用远程控制？","允许已授权的远程设备访问本机核心；请确认信任设备。\n仅本次：$temporary"){c.enableRemote(true,temporary)}},enabled=idle){Text("启用")}
                    TextButton(onClick={val temporary=ephemeral;confirmation=CompletionConfirmation("停用远程控制？","连接中的远程设备可能被断开。\n仅本次：$temporary"){c.enableRemote(false,temporary)}},enabled=idle){Text("停用")}}
                Row(Modifier.horizontalScroll(rememberScrollState())){Checkbox(manual,{manual=it},enabled=idle);Text("同时生成手动配对码",Modifier.padding(top=12.dp))}
                TextButton(onClick={val m=manual;confirmation=CompletionConfirmation("为可信设备生成配对码？","码将在有效期内允许设备领取配对，请勿向陌生人提供。"){c.pairing(m)}},enabled=idle){Text("生成配对码")}
                CompletionValue("配对信息与过期时间",state.pairing)
                state.pairing?.let{approved->TextButton(onClick={c.pairingStatus(approved)},enabled=idle){Text("检查配对是否领取")}}
                state.claimed?.let{Text(if(it)"配对已被领取" else "配对尚未领取")}
                Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={c.clients()},enabled=idle){Text("读取已配对设备")};TextButton(onClick={c.clients(true)},enabled=idle && state.clients.next!=null){Text("更多设备")}}
            }
            items(state.clients.rows){client->CompletionValue("${client.string("displayName").ifBlank{client.string("clientId")}}",client)
                TextButton(onClick={val environment=state.remote?.string("environmentId").orEmpty();val id=client.string("clientId");confirmation=CompletionConfirmation("撤销此设备？","设备：${client.string("displayName")}\n标识：$id\n环境：$environment"){c.revoke(environment,id)}},enabled=idle){Text("撤销访问")}}
        }
        if(tab==2){
            item{Text("订阅原版托管应用服务codex_apps的事件，离开本页仍由核心连接持有；可在下方明确停止。断连会结束该连接的订阅。",style=MaterialTheme.typography.bodySmall)
                CompletionField("MCP服务名",server,{server=it},idle);CompletionField("订阅工具名",stream,{stream=it},idle);CompletionField("订阅参数（JSON）",args,{args=it},idle)
                Button(onClick={val s=server;val n=stream;val a=args;confirmation=CompletionConfirmation("启动MCP事件订阅？","会话：${core.threadId}\n服务：$s\n订阅：$n\n参数：$a\n会调用服务端工具，可能产生服务用量。"){c.subscribe(s,n,a)}},enabled=idle && core.threadId!=null){Text("启动订阅")}
            }
            state.subscriptions.forEach{(id,entry)->item(key=id){CompletionValue("订阅 $id",entry);TextButton(onClick={confirmation=CompletionConfirmation("停止此订阅？","服务：${entry.string("server")}\n订阅：$id"){c.unsubscribe(id)}},enabled=idle){Text("停止订阅")}}}
            items(state.streamEvents){event->CompletionValue("订阅事件",event)}
        }
        if(tab==3)item{
            Text("读取原版核心可见的AWS配置，选择现有profile或环境凭证。此页不收集AWS密钥。",style=MaterialTheme.typography.bodySmall)
            Button(onClick={c.discoverBedrock()},enabled=idle){Text("发现AWS配置")};CompletionValue("可用配置",state.bedrock)
            CompletionField("Profile名称（空白为环境凭证）",profile,{profile=it},idle);CompletionField("AWS区域",region,{region=it},idle)
            TextButton(onClick={val p=profile.takeIf{it.isNotBlank()};val r=region;confirmation=CompletionConfirmation("切换Bedrock认证配置？","凭证来源：${p ?: "环境变量"}\n区域：$r\n后续模型请求将使用此配置。"){c.bedrock(p,r)}},enabled=idle && region.isNotBlank()){Text("使用此配置")}
            TextButton(onClick={c.requirements()},enabled=idle){Text("检查GovCloud要求")};CompletionValue("要求检查结果",state.requirements)
        }
        if(tab==4)item{
            Text("Android验证桥使用硬件保护的P256密钥与系统强生物认证。实际可用性取决于手机和随包核心；本机创建凭证不代表已完成服务端登记。",color=MaterialTheme.colorScheme.primary)
            Button(onClick={c.verificationStatus()},enabled=idle){Text("核查验证能力")};CompletionValue("实际提供者状态",state.verification)
            val supported=state.verification!=null && (state.verification?.get("unavailableReason")==JsonNull || state.verification?.get("unavailableReason")==null || state.verification?.string("unavailableReason")=="credentialMissing")
            TextButton(onClick={confirmation=CompletionConfirmation("创建本机验证凭证？","创建或复用此账号的本机密钥；服务端登记需要服务提供者另行完成。"){c.verification(true)}},enabled=idle && supported){Text("创建本机凭证")}
            CompletionField("无填充base64url挑战",challenge,{challenge=it},idle);CompletionField("系统验证标题",title,{title=it},idle);CompletionField("系统验证说明",description,{description=it},idle)
            TextButton(onClick={val ch=challenge;val t=title;val d=description;confirmation=CompletionConfirmation("为此挑战生成身份签名？","标题：$t\n说明：$d\n挑战：$ch"){c.verification(false,ch,t,d)}},enabled=idle && supported){Text("验证并签名")}
            TextButton(onClick={confirmation=CompletionConfirmation("删除验证凭证？","将删除此账号的本机验证密钥，可能需要重新登记。"){c.deleteVerification()}},enabled=idle && supported){Text("删除凭证")}
            TextButton(onClick={c.cancelVerification()},enabled=state.verificationRequest!=null && !state.cancelBusy){Text("取消当前验证")};CompletionValue("提供者返回结果",state.proof)
        }
    }
    CompletionDialog(confirmation,{confirmation=null},idle)
}

@Composable fun OriginalSshScreen(controller:AndroidSshTrust,privateKeyPath:String,allowChanges:Boolean,onBack:()->Unit) {
    val state by controller.state.collectAsState();var host by rememberSaveable{mutableStateOf("")};var key by rememberSaveable{mutableStateOf("")}
    var confirmation by remember{mutableStateOf<CompletionConfirmation?>(null)};var formError by remember{mutableStateOf<String?>(null)}
    val idle=!state.working
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=completionPadding,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("SSH主机校验",style=MaterialTheme.typography.headlineSmall)
            Text(state.message,color=MaterialTheme.colorScheme.primary);state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(state.working)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Git命令默认严格检查主机公钥。请从服务商公布信息或可信渠道取得公钥，并核对指纹；应用不会自动扫描后接受，也不会自动替换旧密钥。",style=MaterialTheme.typography.bodySmall)
            Button(onClick={controller.refresh()},enabled=idle){Text("读取已信任主机")}
            CompletionField("主机名，非默认端口写作[主机]:端口",host,{host=it},idle)
            CompletionField("主机公钥：类型和Base64内容",key,{key=it},idle)
            formError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={runCatching{OriginalSshHostKey.parse(host,key)}.onSuccess{value->
                val old=state.hosts.firstOrNull{it.host==value.host};formError=null
                confirmation=CompletionConfirmation(if(old==null)"信任此主机密钥？"else"替换已信任主机密钥？","主机：${value.host}\n新指纹：${value.fingerprint}\n旧指纹：${old?.fingerprint ?: "没有记录"}\n请与可信来源独立核对，错误的信任可能将连接交给其他服务器。"){controller.save(value,old)}
            }.onFailure{formError=it.message ?: "主机或公钥格式不正确"}},enabled=idle && state.loaded && allowChanges){Text("查看指纹并确认")}
            HorizontalDivider();Text("私钥与口令",style=MaterialTheme.typography.titleMedium)
            Text("私钥路径：$privateKeyPath\n可在交互终端使用ssh-keygen生成带口令的密钥；口令在PTY中输入，应用不代填或缓存。把公钥登记到远端属于另行授权操作。",style=MaterialTheme.typography.bodySmall)
            Text("当前代码已提供构建与安装路径；包内缺少SSH产物时不会假装已连接。应用命令共享本应用权限，不能把此目录当成项目隔离边界。",style=MaterialTheme.typography.bodySmall)
        }
        items(state.hosts,key={it.host}){value->
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(16.dp)){
                Text(value.host,style=MaterialTheme.typography.titleMedium);SelectionContainer{Text(value.fingerprint,style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={confirmation=CompletionConfirmation("移除此主机信任？","${value.host}\n${value.fingerprint}\n后续Git连接会拒绝此主机，直至重新核对并登记。"){controller.remove(value)}},enabled=idle && allowChanges){Text("移除信任")}
            }}
        }
    }
    CompletionDialog(confirmation,{confirmation=null},idle && allowChanges)
}
