package cn.codex.android
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.codex.core.string
import cn.codex.core.original.OriginalInputContract
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 媒体保存在当前项目；选择器结果绑定连接、会话和目录，旧结果不能进入新会话。 */
@Composable fun OriginalInputAttachments(host:OriginalCoreHost,input:JsonArray,onChange:(JsonArray)->Unit,enabled:Boolean){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var importing by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    var owner by remember{mutableStateOf<Triple<Long,String?,String>?>(null)}
    var advanced by remember{mutableStateOf(false)};var rawInput by remember{mutableStateOf("[]")}
    var kind by remember{mutableStateOf("image")};var location by remember{mutableStateOf("")};var name by remember{mutableStateOf("")}
    val currentInputs by rememberUpdatedState(input);val change by rememberUpdatedState(onChange)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        val expected=owner;owner=null
        if(uri!=null && expected!=null){importing=true;scope.launch{
            var created:File?=null
            try{
                val mime=context.contentResolver.getType(uri).orEmpty();require(mime.startsWith("image/") || mime.startsWith("audio/")){"请选择图片或音频"}
                val file=withContext(Dispatchers.IO){
                    val root=File(expected.third).canonicalFile;val folder=File(root,".codex-input").apply{check(isDirectory || mkdir())}.canonicalFile
                    require(folder.toPath().startsWith(root.toPath())){"媒体目录越出项目"}
                    val bytes=context.contentResolver.openInputStream(uri).use{source->requireNotNull(source);source.readNBytes(20*1024*1024+1)};require(bytes.size<=20*1024*1024){"媒体超过20MiB"}
                    val suffix=when(mime){"image/png"->"png";"image/jpeg"->"jpg";"image/webp"->"webp";"audio/wav","audio/x-wav"->"wav";"audio/mpeg"->"mp3";"audio/mp4"->"m4a";else->android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf{it.matches(Regex("[a-zA-Z0-9]{1,10}"))} ?: "media"}
                    File(folder,"${UUID.randomUUID()}.$suffix").also{target->Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use{out->created=target;out.write(bytes)}}
                }
                check(host.state.value.connectionEpoch==expected.first && host.state.value.threadId==expected.second && host.commandWorkspace()==expected.third){"会话或目录已变化，请重新选择媒体"}
                val row=buildJsonObject{put("type",if(mime.startsWith("image/"))"localImage"else"localAudio");put("path",file.absolutePath)}
                val next=JsonArray(currentInputs+row);OriginalInputContract.input("",next);change(next);created=null;error=null
            }catch(cancel:CancellationException){throw cancel}catch(failure:Exception){error=failure.message ?: "媒体读取失败"}
            finally{withContext(NonCancellable+Dispatchers.IO){created?.delete()};importing=false}
        }}
    }
    Column{
        TextButton(onClick={owner=Triple(host.state.value.connectionEpoch,host.state.value.threadId,host.commandWorkspace());picker.launch(arrayOf("image/*","audio/*"))},enabled=enabled && !importing && input.size<32){Text(if(importing)"正在导入媒体…"else"添加图片或音频")}
        input.forEachIndexed{index,raw->
            val row=raw.jsonObject
            val label=row.string("name").ifBlank{row.string("path").substringAfterLast('/').ifBlank{row.string("url",row.string("fileId"))}}
            Row{Text("${index+1}. ${row.string("type")} · $label",Modifier.weight(1f));TextButton(onClick={onChange(JsonArray(input.filterIndexed{i,_->i!=index}))},enabled=enabled && !importing){Text("移除")}}
        }
        Row(Modifier.horizontalScroll(rememberScrollState())){listOf("image" to "图片地址","audio" to "音频地址","skill" to "技能","mention" to "提及").forEach{(value,label)->FilterChip(kind==value,{kind=value},label={Text(label)},enabled=enabled && !importing)}}
        if(kind in setOf("skill","mention"))OutlinedTextField(name,{name=it},label={Text("显示名称")},enabled=enabled,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(location,{location=it},label={Text(if(kind in setOf("skill","mention"))"当前项目内路径"else"媒体URL")},enabled=enabled,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={try{val row=buildJsonObject{put("type",kind);if(kind in setOf("skill","mention")){put("name",name);put("path",location)}else put("url",location)};val next=JsonArray(input+row);OriginalInputContract.input("",next);onChange(next);location="";error=null}catch(failure:Exception){error=failure.message}},enabled=enabled && !importing && location.isNotBlank() && input.size<32){Text("加入本次输入")}
        TextButton(onClick={advanced=!advanced;if(advanced)rawInput=input.toString()}){Text(if(advanced)"收起原版输入字段"else"编辑原版输入字段（详情、文件ID、文字标记等）")}
        if(advanced){
            OutlinedTextField(rawInput,{rawInput=it},label={Text("UserInput JSON数组")},enabled=enabled && !importing,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={try{val parsed=Json.parseToJsonElement(rawInput).jsonArray;OriginalInputContract.input("",parsed);onChange(parsed);error=null}catch(failure:Exception){error=failure.message}},enabled=enabled && !importing){Text("应用输入字段")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
