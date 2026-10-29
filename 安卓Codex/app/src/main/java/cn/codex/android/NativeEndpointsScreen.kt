package cn.codex.android
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.codex.core.original.NativeEndpointKind
import cn.codex.core.original.RemoteExecutorAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable fun NativeEndpointsScreen(host:OriginalCoreHost,onBack:()->Unit){
    val app=LocalContext.current.applicationContext as CodexApplication;val manager=app.nativeEndpoints;val scope=rememberCoroutineScope()
    val states by manager.state.collectAsState();val pending by manager.pending.collectAsState();val storeError by manager.storeError.collectAsState();val core by host.state.collectAsState()
    var kind by rememberSaveable{mutableStateOf(NativeEndpointKind.PROXY)};var port by rememberSaveable{mutableStateOf("0")}
    var upstream by rememberSaveable{mutableStateOf("https://api.openai.com/v1/responses")};var origin by rememberSaveable{mutableStateOf("")};var target by rememberSaveable{mutableStateOf("")}
    var credential by remember{mutableStateOf("")};var headers by remember{mutableStateOf("[]")};var rotation by remember{mutableStateOf("")}
    var remoteUrl by rememberSaveable{mutableStateOf("")};var environmentId by rememberSaveable{mutableStateOf("")};var remoteName by rememberSaveable{mutableStateOf("手机执行器")}
    var remoteAuth by rememberSaveable{mutableStateOf(RemoteExecutorAuth.ACCOUNT)};var expires by remember{mutableStateOf("")}
    var profile by rememberSaveable{mutableStateOf("")};var region by rememberSaveable{mutableStateOf("")};var service by rememberSaveable{mutableStateOf("execute-api")}
    var unix by rememberSaveable{mutableStateOf(false)}
    var remote by rememberSaveable{mutableStateOf(false)};var showToken by remember{mutableStateOf<NativeEndpointKind?>(null)}
    var remotePreference by remember{mutableStateOf("尚未读取未来启动设置")}
    var restarting by remember{mutableStateOf(false)}
    var confirmation by remember{mutableStateOf<Pair<String,()->Unit>?>(null)};var inputError by remember{mutableStateOf<String?>(null)}
    BackHandler(onBack=onBack)
    fun act(action:()->Unit){try{action();inputError=null}catch(failure:Exception){inputError=failure.message ?: "请检查输入"}}
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("本机连接服务",style=MaterialTheme.typography.headlineSmall)
            Text("服务使用手机上的原版CLI，运行时保留前台通知。离开此页会继续托管；系统配额或中断会关闭，重启应用后需你重新启动。",style=MaterialTheme.typography.bodySmall)
            storeError?.let{Text(it,color=MaterialTheme.colorScheme.error)};inputError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            val lost=pending.filter{entry->states.values.none{it.id==entry.id && it.running}}
            if(lost.isNotEmpty() || storeError!=null){Text("有上次服务记录待核查，应用不会按旧PID接管或自动恢复。");TextButton(onClick={manager.acknowledge()}){Text("我已核查中断状态，清除旧记录")}}
            Column{NativeEndpointKind.entries.forEach{value->FilterChip(kind==value,{kind=value;credential="";headers="[]";showToken=null},label={Text(value.title)})}}
        }
        item{
            OutlinedTextField(port,{port=it},label={Text("本机端口（0为自动分配）")},enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
            when(kind){
                NativeEndpointKind.PROXY->{
                    OutlinedTextField(upstream,{upstream=it},label={Text("上游Responses完整HTTPS地址")},enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={scope.launch{runCatching{withContext(Dispatchers.IO){SecureSettings(app).load()}}.onSuccess{upstream=it.endpoint.trimEnd('/')+"/responses";credential=it.apiKey}.onFailure{inputError="读取连接设置失败"}}},enabled=states[kind]?.running!=true){Text("读取当前连接参数")}
                    OutlinedTextField(credential,{credential=it},label={Text("上游API密钥（只传入进程管道）")},visualTransformation=PasswordVisualTransformation(),enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    Text("本机访问使用另一份随机连接密钥，上游密钥不会作为启动参数或日志保存。",style=MaterialTheme.typography.bodySmall)
                }
                NativeEndpointKind.TUNNEL->{
                    OutlinedTextField(origin,{origin=it},label={Text("已批准的HTTP/3代理来源")},enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(target,{target=it},label={Text("连接目标：主机:端口")},enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(credential,{credential=it},label={Text("代理Bearer凭证")},visualTransformation=PasswordVisualTransformation(),enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(headers,{headers=it},label={Text("连接附加头JSON（可选）")},visualTransformation=PasswordVisualTransformation(),enabled=states[kind]?.running!=true,modifier=Modifier.fillMaxWidth())
                    Text("原版TCP监听只绑定本机；本机其他应用可连接该端口并使用此通道。仅在你信任本机应用时短时开启。",style=MaterialTheme.typography.bodySmall)
                }
                NativeEndpointKind.REMOTE_EXECUTOR->{
                    OutlinedTextField(remoteUrl,{remoteUrl=it},label={Text("受信任远程执行注册HTTPS地址")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(environmentId,{environmentId=it},label={Text("服务提供的环境ID")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(remoteName,{remoteName=it},label={Text("执行器名称")},modifier=Modifier.fillMaxWidth())
                    listOf(RemoteExecutorAuth.ACCOUNT to "原版账号 · Noise",RemoteExecutorAuth.AGENT_IDENTITY to "Agent Identity · Noise",RemoteExecutorAuth.AWS to "AWS SigV4 · Direct",RemoteExecutorAuth.SCOPED to "作用域凭证 · Noise").forEach{(value,label)->FilterChip(remoteAuth==value,{remoteAuth=value;credential=""},label={Text(label)},enabled=states[kind]?.running!=true)}
                    if(remoteAuth in setOf(RemoteExecutorAuth.AGENT_IDENTITY,RemoteExecutorAuth.SCOPED))OutlinedTextField(credential,{credential=it},label={Text("受认可服务颁发的凭证")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    if(remoteAuth==RemoteExecutorAuth.SCOPED)OutlinedTextField(expires,{expires=it},label={Text("凭证过期Unix秒")},modifier=Modifier.fillMaxWidth())
                    if(remoteAuth==RemoteExecutorAuth.AWS){OutlinedTextField(profile,{profile=it},label={Text("原版目录中的AWS配置名称（可选）")},modifier=Modifier.fillMaxWidth());OutlinedTextField(region,{region=it},label={Text("AWS区域")},modifier=Modifier.fillMaxWidth());OutlinedTextField(service,{service=it},label={Text("AWS签名服务")},modifier=Modifier.fillMaxWidth())}
                    if(remoteAuth==RemoteExecutorAuth.ACCOUNT)Text("请先完成原版账号登录；手机连接偏好中的密钥不会自动作为注册凭证。",style=MaterialTheme.typography.bodySmall)
                    Text("注册后，获得远程授权的客户端可在本应用权限内执行和读写；普通请求仍没有项目沙箱。账号与AWS配置由原版加载，凭证必须来自真实服务，不会为你生成身份。",style=MaterialTheme.typography.bodySmall)
                }
                NativeEndpointKind.EXECUTOR->Text("持有本次随机连接密钥的客户端，可在本应用权限内读写文件、执行命令；它不是项目级沙箱。",style=MaterialTheme.typography.bodySmall)
                NativeEndpointKind.DAEMON->{
                    Row{Checkbox(unix,{unix=it},enabled=states[kind]?.running!=true);Text("使用私有Unix连接（无本机TCP监听）",Modifier.padding(top=12.dp))}
                    Text("共享同一原版账号和项目，支持多客户端连接；关闭主界面的核心连接也会结束本次共享服务。启动前先结束任务。",style=MaterialTheme.typography.bodySmall)
                    Text(remotePreference,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={scope.launch{remotePreference=try{withContext(Dispatchers.IO){when(manager.savedRemotePreference()){true->"未来启动：启用远控（原版持久设置）";false->"未来启动：停用远控（原版持久设置）";null->"未来启动：由原版账号偏好决定"}}}catch(_:Exception){"持久设置无法读取，请先核查原版配置"}}}){Text("核对未来启动设置")}
                    Text("在连接、订阅与认证页取消“仅本次”后启停远控，会写入原版持久设置。手机仍需你主动启动前台核心。",style=MaterialTheme.typography.bodySmall)
                    Row{Checkbox(remote,{remote=it},enabled=states[kind]?.running!=true);Text("本次启动启用原版远程控制",Modifier.padding(top=12.dp))}}
            }
            Button(onClick={act{
                val selected=kind;val p=if(selected==NativeEndpointKind.REMOTE_EXECUTOR || selected==NativeEndpointKind.DAEMON && unix)0 else port.toInt();val u=upstream;val o=origin;val t=target;val key=credential;val metadata=headers;val r=remote;val ru=remoteUrl;val eid=environmentId;val rn=remoteName;val auth=remoteAuth;val expiry=expires.toLongOrNull() ?: 0L;val pr=profile;val reg=region;val svc=service;val uds=unix
                confirmation=(when(selected){NativeEndpointKind.REMOTE_EXECUTOR->"注册手机远程执行器？\n服务：$ru\n环境：$eid\n认证：$auth\n将允许已授权远端客户端访问本应用可访问的文件与命令。";NativeEndpointKind.PROXY->"启动本机模型代理？\n上游：$u\n端口：$p\n会向该上游发送请求和密钥。";NativeEndpointKind.TUNNEL->"启动连接隧道？\n批准来源：$o\n目标：$t\n本机其他应用也可使用本机监听端口。";NativeEndpointKind.EXECUTOR->"启动执行服务？\n仅本机监听；获得连接密钥的客户端可在本应用权限内执行操作。";NativeEndpointKind.DAEMON->"启动共享核心？\n将关闭当前核心连接，使用保存的账号和模型设置。远程控制：${if(r)"本次启用"else"遵循原配置"}"}) to {
                    credential="";headers="[]"
                    when(selected){NativeEndpointKind.REMOTE_EXECUTOR->manager.startRemoteExecutor(ru,eid,rn,auth,key,expiry,pr,reg,svc);NativeEndpointKind.PROXY->manager.startProxy(u,key,p);NativeEndpointKind.TUNNEL->manager.startTunnel(o,t,p,key,metadata);NativeEndpointKind.EXECUTOR->manager.startExecutor(p);NativeEndpointKind.DAEMON->scope.launch{host.close();withTimeoutOrNull(10_000){host.state.first{!it.connected && !it.closing && !it.loading}}?.let{manager.startDaemon(p,r,uds)} ?: run{inputError="核心尚未关闭，请核查后重试"}}}
                }
            }},enabled=!restarting && states[kind]?.running!=true && !core.working && core.turnId==null && !core.loading){Text("启动${kind.title}")}
        }
        items(states.values.toList(),key={it.kind.name}){status->
            OutlinedCard{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(status.kind.title,style=MaterialTheme.typography.titleMedium);Text(status.message);status.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                if(status.running && !status.ready)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(status.address.isNotBlank())SelectionContainer{Text(status.address)}
                if(status.ready && status.kind in setOf(NativeEndpointKind.PROXY,NativeEndpointKind.EXECUTOR,NativeEndpointKind.DAEMON) && !status.address.startsWith("unix://")){TextButton(onClick={showToken=if(showToken==status.kind)null else status.kind}){Text(if(showToken==status.kind)"隐藏连接密钥"else"显示本次连接密钥")}
                    if(showToken==status.kind)manager.clientCredential(status.kind)?.let{SelectionContainer{Text(it)}}
                    if(status.kind==NativeEndpointKind.PROXY)Text("在连接偏好中填入上面的地址与本次连接密钥。停止代理后，请恢复原连接设置。",style=MaterialTheme.typography.bodySmall)
                }
                if(status.kind==NativeEndpointKind.TUNNEL && status.ready){
                    OutlinedTextField(rotation,{rotation=it},label={Text("替换隧道凭证")},visualTransformation=PasswordVisualTransformation(),enabled=!status.rotating,modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={val value=rotation;confirmation="更新本机隧道凭证？\n不会重放原数据流，也不意味着远端已认可新凭证。" to {rotation="";manager.rotateTunnel(value)}},enabled=!status.rotating && rotation.isNotEmpty()){Text("更新凭证")}
                }
                if(status.kind==NativeEndpointKind.REMOTE_EXECUTOR && status.running && status.credentialSequence>0){
                    OutlinedTextField(rotation,{rotation=it},label={Text("新的作用域凭证")},visualTransformation=PasswordVisualTransformation(),enabled=!status.rotating,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(expires,{expires=it},label={Text("新凭证过期Unix秒")},enabled=!status.rotating,modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={val token=rotation;val end=expires.toLongOrNull() ?: 0;confirmation="替换远程执行凭证？将等待本机顺序确认；结果未知会停止服务。" to {rotation="";manager.rotateRemoteCredential(token,end)}},enabled=!status.rotating && rotation.isNotBlank()){Text("更新作用域凭证")}
                }
                if(status.kind==NativeEndpointKind.DAEMON && status.ready){
                    TextButton(onClick={onBack();host.connect()},enabled=!restarting){Text("连接共享核心")}
                    TextButton(onClick={act{val p=port.toInt();require(p in 0..65535);val r=remote;val uds=unix;val id=status.id
                        confirmation="按当前端口和远控选项重启共享核心？\n本次连接和其他客户端任务将中断，已发生的变更不会撤销。" to {restarting=true;scope.launch{try{host.close();manager.stop(NativeEndpointKind.DAEMON,id);withTimeout(10_000){manager.state.first{it[NativeEndpointKind.DAEMON]?.let{row->row.id!=id || !row.running} ?: true};host.state.first{!it.connected && !it.loading && !it.closing}};check(manager.state.value[NativeEndpointKind.DAEMON]?.id==id){"核心已被其他启动替换，请核查"};manager.startDaemon(p,r,uds)}catch(failure:Exception){if(failure is CancellationException && failure !is TimeoutCancellationException)throw failure;inputError="重启未完成，请核查服务状态后手动处理"}finally{restarting=false}}}
                    }},enabled=!restarting && !core.working && core.turnId==null && !core.loading){Text("按当前设置重启")}
                }
                if(status.running)TextButton(onClick={confirmation="停止${status.kind.title}？\n连接和正在进行的工作将被中断，已发生的远程或文件变更需核查。" to {showToken=null;manager.stop(status.kind,status.id)}},enabled=!status.stopping){Text("停止此服务")}
            }}
        }
    }
    confirmation?.let{frozen->AlertDialog(onDismissRequest={confirmation=null},title={Text("确认本机服务操作")},text={Text(frozen.first,Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={confirmation=null;act(frozen.second)}){Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}){Text("返回")}})}
}
