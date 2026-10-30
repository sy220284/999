package cn.codex.android

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.codex.core.string
import kotlinx.serialization.json.*

@Composable fun OriginalSearchScreen(host:OriginalCoreHost,onOpen:(String)->Unit,onBack:()->Unit) {
    val state by host.search.state.collectAsState()
    var query by remember{mutableStateOf("")}
    BackHandler(onBack=onBack)
    DisposableEffect(host){onDispose{host.search.stop()}}
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("持续搜索项目",style=MaterialTheme.typography.headlineSmall)
            Text("搜索范围：${host.commandWorkspace()}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(query,{query=it},label={Text("文件名或路径关键词")},modifier=Modifier.fillMaxWidth())
            Row{Button(onClick={host.search.search(query)},enabled=state.connected && !state.busy && query.isNotBlank()){Text("搜索")}
                TextButton(onClick={host.search.stop()},enabled=state.sessionId!=null){Text("停止搜索")}}
            Text(state.status,color=MaterialTheme.colorScheme.primary)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Text("最多显示200条结果；离开此页会停止搜索。",style=MaterialTheme.typography.bodySmall)
        }
        items(state.files,key={it.string("root")+"/"+it.string("path")}){file->
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(16.dp)){
                Text(file.string("file_name").ifBlank{file.string("path")},style=MaterialTheme.typography.titleSmall)
                SelectionContainer{Text(file.string("root")+"/"+file.string("path"),style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={val path=java.io.File(file.string("path"));onOpen(if(path.isAbsolute)path.path else java.io.File(file.string("root"),path.path).path)}){Text("在文件页查看")}
            }}
        }
        if(state.completed && state.files.isEmpty())item{Text("没有匹配的文件，可换一个关键词。")}
    }
}

@Composable fun OriginalRealtimeScreen(host:OriginalCoreHost,onBack:()->Unit) {
    val core by host.state.collectAsState()
    val state by host.realtime.state.collectAsState()
    val mic by host.microphone.state.collectAsState()
    val sound by host.realtimeAudio.state.collectAsState()
    val media by host.webRtc.state.collectAsState()
    var startupOptions by remember{mutableStateOf("{}")};var chosenVoice by remember{mutableStateOf("")};var startupError by remember{mutableStateOf<String?>(null)}
    var pendingEpoch by remember{mutableStateOf<Long?>(null)}
    var pendingOptions by remember{mutableStateOf<kotlinx.serialization.json.JsonObject?>(null)}
    var useWebRtc by rememberSaveable{mutableStateOf(false)}
    var pendingWebRtcThread by remember{mutableStateOf<String?>(null)}
    var pendingMicrophoneThread by remember{mutableStateOf<String?>(null)}
    val owner=LocalLifecycleOwner.current
    var audioOutput by remember{mutableStateOf(false)}
    var consent by remember{mutableStateOf(false)}
    var text by remember{mutableStateOf("")}
    var exitConfirm by remember{mutableStateOf(false)}
    var leaveAfterStop by remember{mutableStateOf(false)}
    var permissionDenied by remember{mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        permissionDenied=!granted
        val pending=pendingWebRtcThread;val approvedOptions=pendingOptions;val approvedEpoch=pendingEpoch;pendingOptions=null;pendingEpoch=null;val microphoneThread=pendingMicrophoneThread;pendingWebRtcThread=null;pendingMicrophoneThread=null
        if(granted && approvedEpoch==host.state.value.connectionEpoch && approvedOptions!=null && cn.codex.core.original.OriginalRealtimePermission.allows(pending,host.state.value.threadId,owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)))host.realtime.start(requireNotNull(pending),true,options=approvedOptions){host.webRtc.prepare(requireNotNull(pending))}
        else if(granted && pending==null && cn.codex.core.original.OriginalRealtimePermission.allows(microphoneThread,host.state.value.threadId,owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) && microphoneThread==host.realtime.state.value.threadId && host.realtime.state.value.ready && !host.realtime.state.value.controlBusy && !host.realtime.state.value.webRtc)host.microphone.start()
    }
    fun stop(){pendingOptions=null;pendingEpoch=null;pendingWebRtcThread=null;pendingMicrophoneThread=null;host.microphone.stop();host.realtimeAudio.stop();host.webRtc.close();host.realtime.stop()}
    fun back(){if(state.running)exitConfirm=true else onBack()}
    BackHandler(onBack=::back)
    LaunchedEffect(state.running,leaveAfterStop){if(leaveAfterStop && !state.running)onBack()}
    DisposableEffect(owner,host){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);stop()}
    }
    val rt=core.events.realtime[state.threadId ?: core.threadId]
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{
            TextButton(onClick=::back){Text("返回工作区")};Text("实时文字与语音",style=MaterialTheme.typography.headlineSmall)
            Text(state.status,color=MaterialTheme.colorScheme.primary)
            Text("可选WebSocket或WebRTC音频连接原版实时服务。文字、录音会发送给服务提供方并可能产生用量；结束前保留前台运行提示，进入后台会请求结束。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={host.realtime.voices()},enabled=core.connected && !state.voicesBusy){Text("读取可用声音")}
            state.voices?.let{SelectionContainer{Text(it.toString().take(8192),style=MaterialTheme.typography.bodySmall)}}
            OutlinedTextField(chosenVoice,{chosenVoice=it},label={Text("声音名称（留空沿用核心配置）")},enabled=!state.running,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(startupOptions,{startupOptions=it},label={Text("高级启动设置（JSON，可选v1/v2/v3、model、prompt、initialItems等）")},enabled=!state.running,modifier=Modifier.fillMaxWidth())
            startupError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(permissionDenied)Text("未授予麦克风权限，可选择WebSocket继续文字会话。",color=MaterialTheme.colorScheme.error)
            if(core.threadId==null)Text("请先返回工作区创建或选择会话。")
            Row{Checkbox(useWebRtc,{useWebRtc=it;if(it)audioOutput=true},enabled=!state.running);Text("使用WebRTC音频（手动开启麦克风和播放）",Modifier.padding(top=12.dp))}
            Row{Checkbox(audioOutput,{audioOutput=it},enabled=!state.running && !useWebRtc);Text("请求音频回复",Modifier.padding(top=12.dp))}
            Row{Checkbox(consent,{consent=it},enabled=!state.running);Text("我确认开始实时会话并使用服务额度",Modifier.padding(top=12.dp))}
            Button(onClick={core.threadId?.let{thread->try{val parsed=cn.codex.core.original.OriginalRealtimeOptions.parse(startupOptions);val options=if(chosenVoice.isBlank())parsed else kotlinx.serialization.json.JsonObject(parsed+("voice" to kotlinx.serialization.json.JsonPrimitive(chosenVoice)));pendingOptions=options;pendingEpoch=core.connectionEpoch;startupError=null;if(useWebRtc){pendingMicrophoneThread=null;pendingWebRtcThread=thread;permission.launch(Manifest.permission.RECORD_AUDIO)}else host.realtime.start(thread,audioOutput,options=options)}catch(failure:Exception){startupError=failure.message}}},enabled=core.connected && core.threadId!=null && !state.running && pendingWebRtcThread==null && !core.loading && !core.contextSynchronizing && consent && host.selectedThreadUsable()){Text("开始实时会话")}
            if(state.running){
                OutlinedTextField(text,{text=it},label={Text("实时文字输入")},modifier=Modifier.fillMaxWidth())
                Row{TextButton(onClick={host.realtime.send(text);text=""},enabled=state.ready && !state.controlBusy && text.isNotBlank()){Text("发送文字")}
                    TextButton(onClick={host.realtime.speech(text);text=""},enabled=state.ready && !state.controlBusy && text.isNotBlank()){Text("将文字发送为语音")}
                    TextButton(onClick={if(mic.running)host.microphone.stop()else{pendingWebRtcThread=null;pendingMicrophoneThread=state.threadId;permission.launch(Manifest.permission.RECORD_AUDIO)}},enabled=!state.webRtc && (mic.running || state.ready && !state.controlBusy && rt?.sdpReceived!=true)){Text(if(mic.running)"停止录音" else "开始录音")}}
                if(state.webRtc){
                    Text(media.message,style=MaterialTheme.typography.bodySmall);media.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    Row{Switch(media.microphone,{host.webRtc.setMicrophone(it)},enabled=media.ready && state.ready);Text("WebRTC麦克风",Modifier.padding(12.dp))}
                    Row{Switch(media.speaker,{host.webRtc.setSpeaker(it)},enabled=media.ready && state.ready);Text("WebRTC回复播放",Modifier.padding(12.dp))}
                }
                Text(mic.message,style=MaterialTheme.typography.bodySmall)
                Row{Switch(sound.enabled,{if(it)host.realtimeAudio.enable()else host.realtimeAudio.stop()},enabled=!state.webRtc && (sound.enabled || state.ready && !state.controlBusy && rt?.sdpReceived!=true));Text("播放回复音频",Modifier.padding(12.dp))}
                Text(sound.message,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=::stop){Text("结束实时会话")}
            }
            if(state.running && (!state.ready || state.controlBusy))LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Text("WebRTC将协商音频直连；开始时请求麦克风权限，录音和回复播放仍默认关闭。传输失败不会自动开启另一个会话。",style=MaterialTheme.typography.bodySmall)
        }
        rt?.let{data->
            data.items.forEach{(id,body)->item(key="rt:$id"){SelectionContainer{Text(body)}}}
            if(!data.canonicalText){
                data.transcripts.forEachIndexed{index,(role,body)->item(key="text:$index"){SelectionContainer{Text("${if(role=="user")"你" else "Codex"}：$body")}}}
                data.partials.forEach{(role,body)->item(key="partial:$role"){Text("正在转写：$body")}}
            }
        }
    }
    if(exitConfirm)AlertDialog(onDismissRequest={exitConfirm=false},title={Text("结束会话后返回？")},text={Text("将停止录音和播放，并等待核心确认实时会话关闭。")},confirmButton={TextButton(onClick={exitConfirm=false;leaveAfterStop=true;stop()}){Text("结束并返回")}},dismissButton={TextButton(onClick={exitConfirm=false}){Text("继续会话")}})
}
