package cn.codex.android
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun AndroidUpdateScreen(onBack:()->Unit){
    val context=LocalContext.current;val app=context.applicationContext as CodexApplication;val updates=app.updates
    val state by updates.state.collectAsState();val scope=rememberCoroutineScope()
    var approved by remember{mutableStateOf<VerifiedAndroidUpdate?>(null)};var error by remember{mutableStateOf<String?>(null)};var preparing by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)updates.import(uri)}
    val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){error="已返回安装器，请核查系统安装结果与应用版本。"}
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onClick=onBack){Text("返回工作区")};Text("应用更新",style=MaterialTheme.typography.headlineSmall)
            Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）。更新APK会一起更新核心；本机文件、账号和项目沿用应用数据。请先结束正在运行的任务。",style=MaterialTheme.typography.bodySmall)
            Text(state.message);(error ?: state.error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(state.busy || preparing)LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick={updates.check()},enabled=!state.busy && !preparing){Text("检查999仓库正式发布")}
            TextButton(onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream"))},enabled=!state.busy && !preparing){Text("选择本地签名APK校验")}
            if(state.busy)TextButton(onClick={updates.stop()}){Text("停止下载或校验")}
        }
        state.release?.let{release->
            item{Text("正式发布 ${release.tag}",style=MaterialTheme.typography.titleMedium);Text("选择后下载APK；只接受带发布摘要的资源，实际包仍需核对身份。",style=MaterialTheme.typography.bodySmall)}
            items(release.assets,key={it.url}){asset->TextButton(onClick={updates.download(asset)},enabled=!state.busy && !preparing){Text("下载 ${asset.name} · ${asset.size/1024/1024}MiB")}}
        }
        state.verified?.let{candidate->item{OutlinedCard{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("待安装 ${candidate.version}（${candidate.versionCode}）");Text("兼容ABI：${candidate.abi}");SelectionContainer{Text("SHA256 ${candidate.sha256}",style=MaterialTheme.typography.bodySmall)}
            Text("已检查包名、较新版本、当前签名、完整核心与系统要求。证书轮换或拆分包需另行审核。最后由系统安装器验证并征求你的安装确认。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={try{context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}catch(_:Exception){error="系统未提供此权限入口，请在系统设置中管理安装来源"}},enabled=!state.busy && !preparing){Text("管理此应用的安装来源权限")}
            Button(onClick={approved=candidate},enabled=!state.busy && !preparing){Text("交给系统安装器")}
            TextButton(onClick={updates.clear()},enabled=!state.busy && !preparing){Text("清除更新文件")}
        }}}}
    }
    approved?.let{candidate->AlertDialog(onDismissRequest={approved=null},title={Text("准备安装更新？")},text={Text("将从当前版本更新至${candidate.version}（${candidate.versionCode}）。请先结束任务；核心和连接将随应用更新中断。系统还会再次确认安装。")},confirmButton={TextButton(onClick={
        approved=null;preparing=true;scope.launch{try{
            check(!app.originalCore.state.value.connected && !app.originalCore.state.value.loading && !app.originalCore.state.value.closing && !app.originalCore.cli.state.value.busy && !app.nativeEndpoints.running && !app.fullTerminal.state.value.running && !app.controller.state.value.busy && !app.gitOperations.state.value.running && app.configurationJournal.active.value==null){"请先结束任务、终端和维护操作，并停止全部本机服务及核心"}
            installer.launch(updates.installationIntent(candidate))
        }catch(failure:Exception){if(failure is CancellationException)throw failure;error=failure.message ?: "无法启动系统安装器"}finally{preparing=false}}
    }){Text("复核并打开安装器")}},dismissButton={TextButton(onClick={approved=null}){Text("返回")}})}
}
