package cn.codex.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import cn.codex.core.original.*
import kotlinx.serialization.json.*

@Composable fun OriginalQueuePanel(host:OriginalCoreHost,onRun:()->Unit){
    val core by host.state.collectAsState();val state by host.queue.state.collectAsState();val op by host.queue.operations.state.collectAsState()
    var draft by remember(core.threadId){mutableStateOf("")}
    var editing by remember(core.threadId){mutableStateOf<JsonObject?>(null)}
    var edit by remember{mutableStateOf("")};var raw by remember{mutableStateOf(false)}
    var action by remember(core.threadId){mutableStateOf<String?>(null)}
    var target by remember(core.threadId){mutableStateOf<JsonObject?>(null)}
    var approvedOrder by remember{mutableStateOf(emptyList<JsonObject>())}
    var order by remember{mutableStateOf(emptyList<String>())};var formError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(state.threadId,state.revision,op.busy){if(!op.busy && op.error==null && state.threadId!=null && state.dirty)host.queue.refresh()}
    val usable=host.selectedThreadUsable() && core.connected
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("待办队列",style=MaterialTheme.typography.titleLarge)
        Text("加入后可能立即开始，或在当前回合结束后自动继续，会使用模型额度和工具。停止当前任务后，剩余待办会保留。",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft,{draft=it},label={Text("下一项任务")},modifier=Modifier.fillMaxWidth())
        Row{Button(onClick={action="add"},enabled=host.canAddQueue() && !op.busy && draft.isNotBlank()){Text("加入待办")}
            TextButton(onClick={host.queue.refresh()},enabled=!op.busy){Text("刷新")}}
        Text(op.notice,style=MaterialTheme.typography.bodySmall)
        op.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(op.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.dirty)Text(if(op.busy)"队列有变化，正在读取实际内容。"else "队列有变化，请刷新核查。",style=MaterialTheme.typography.bodySmall)
        val rows=state.page.entries
        rows.forEachIndexed{index,item->
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("待办 ${index+1} · ${item.string("id").takeLast(8)}",style=MaterialTheme.typography.labelMedium)
                val input=OriginalQueueData.input(item)
                input.mapNotNull{it as? JsonObject}.forEach{node->if(node.string("type")=="text")SelectionContainer{Text(node.string("text").let{if(it.length>512)it.take(512)+"…（展开查看完整输入）"else it})}else Text("附件或工具输入：${node.string("type")}",style=MaterialTheme.typography.bodySmall)}
                val detail=remember(item){input.toString()}
                ExpandableCard("查看完整输入",detail,Icons.Outlined.Info)
                Row{
                    TextButton(onClick={target=item;action="start"},enabled=usable && !op.busy && !core.working && core.turnId==null){Text("执行此项")}
                    TextButton(onClick={editing=item;val value=OriginalQueueData.editText(item);raw=value==null;edit=value ?: input.toString();formError=null},enabled=usable && !op.busy){Text("编辑")}
                    TextButton(onClick={target=item;action="delete"},enabled=usable && !op.busy){Text("删除")}
                }
                if(rows.size>1 && state.page.nextCursor==null && !state.page.repeatedCursor)Row{
                    fun move(to:Int){val ids=rows.map{it.string("id")}.toMutableList();val id=ids.removeAt(index);ids.add(to,id);approvedOrder=rows.toList();order=ids;action="order"}
                    TextButton(onClick={move(index-1)},enabled=index>0 && usable && !op.busy){Text("上移")}
                    TextButton(onClick={move(index+1)},enabled=index<rows.lastIndex && usable && !op.busy){Text("下移")}
                }
            }}
        }
        if(state.page.nextCursor!=null)TextButton(onClick={host.queue.refresh(true)},enabled=!op.busy){Text("加载更多；加载完整队列后可排序")}
        if(state.page.repeatedCursor)Text("分页标识重复，请重新刷新后再排序。",color=MaterialTheme.colorScheme.error)
        if(rows.isEmpty() && !op.busy)Text("暂无待办")
        Text("多端同时修改时，请先刷新并重新确认；原版没有队列的原子版本比较。",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("下一步预测",style=MaterialTheme.typography.titleMedium)
        Text("上传的原版尚未实现 thread/prediction/request，请求入口暂不可用。已收到的预测建议仍会在会话同步卡显示。",style=MaterialTheme.typography.bodySmall)
    }
    editing?.let{item->AlertDialog(onDismissRequest={editing=null},title={Text("编辑待办")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(OriginalQueueData.editText(item)!=null)Row{Checkbox(raw,{raw=it;edit=if(it)OriginalQueueData.input(item).toString() else OriginalQueueData.editText(item).orEmpty()});Text("编辑完整输入JSON",Modifier.padding(top=12.dp))}
        Text(if(raw)"按原版UserInput数组编辑，其他类型由核心验证。"else "只替换文字，保留附件；文字变化会清除该段原有内嵌标注。",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(edit,{edit=it},modifier=Modifier.fillMaxWidth().heightIn(max=240.dp),label={Text(if(raw)"完整输入JSON"else "文字内容")})
        formError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={runCatching{if(raw)OriginalQueueData.parseInput(edit)else OriginalQueueData.replaceText(item,edit)}.onSuccess{host.queue.update(item,it);editing=null}.onFailure{formError=it.message}},enabled=!op.busy && usable){Text("确认保存")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})}
    action?.let{kind->AlertDialog(onDismissRequest={action=null},title={Text(when(kind){"add"->"加入并允许核心执行？";"delete"->"删除这项待办？";"start"->"执行这项待办？";else->"调整队列顺序？"})},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){SelectionContainer{Text(when(kind){"add"->draft+"\n\n加入后可能自动执行，使用本应用的工具权限和服务额度。";"start"->target?.get("input").toString()+"\n核心可能继续执行后续待办。";"delete"->target?.get("input").toString();else->order.mapIndexed{i,id->"${i+1}. $id"}.joinToString("\n")})}}},confirmButton={TextButton(onClick={
        when(kind){"add"->{host.addQueued(draft);draft=""};"delete"->target?.let(host.queue::delete);"start"->target?.let{host.startQueued(it);onRun()};"order"->host.queue.reorder(approvedOrder,order)};action=null
    },enabled=!op.busy && usable && (kind!="add" || host.canAddQueue())){Text("确认")}},dismissButton={TextButton(onClick={action=null}){Text("取消")}})}
}
