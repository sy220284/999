#!/usr/bin/env python3
"""队列静态协议/业务/UI核对，及固定原版预测缺口证明；不编译、不执行模型。"""
import argparse,csv,json,re,sys,ast
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--parser-path');a=p.parse_args()
r=Path(__file__).resolve().parents[1];source=Path(a.source)/'codex-rs'
client=(r/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt').read_text()
controller=(r/'core/src/main/kotlin/cn/codex/core/original/OriginalQueueController.kt').read_text()
host=(r/'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
ui=(r/'app/src/main/java/cn/codex/android/OriginalQueuePanel.kt').read_text()
checks={
 'thread/queue/add':('addQueue','ThreadQueueAddParams',['thread_id','input','client_user_message_id']),
 'thread/queue/list':('listQueue','ThreadQueueListParams',['thread_id','cursor','limit']),
 'thread/queue/update':('updateQueue','ThreadQueueUpdateParams',['thread_id','queued_submission_id','input']),
 'thread/queue/delete':('deleteQueue','ThreadQueueDeleteParams',['thread_id','queued_submission_id']),
 'thread/queue/reorder':('reorderQueue','ThreadQueueReorderParams',['thread_id','queued_submission_ids']),
 'thread/queue/start':('startQueue','ThreadQueueStartParams',['thread_id','queued_submission_id'])}
rows=list(csv.DictReader((r/'文档/实验协议执行清单.csv').open()))
assert rows==json.loads((r/'app/src/main/assets/实验协议进度.json').read_text()) and len(rows)==71
rust=(source/'app-server-protocol/src/protocol/v2/thread.rs').read_text()
for method,(function,typename,fields)in checks.items():
    declaration=next(line for line in client.splitlines()if f'suspend fun {function}('in line)
    assert f'call("{method}"'in declaration,method
    body=re.search(r'pub struct '+typename+r'\s*\{(.*?)^}',rust,re.S|re.M).group(1)
    for field in fields:assert re.search(r'pub\s+'+field+r'\s*:',body),(method,field)
    assert re.search(r'\.'+function+r'\(',controller+host),method
    assert '已接入'in next(x for x in rows if x['method']==method)['status'],method
assert 'OriginalQueuePanel('in(r/'app/src/main/java/cn/codex/android/OriginalSessionScreen.kt').read_text()
for name in ['addQueued','startQueued']:assert 'host.'+name+'('in ui,name
assert 'awaitQueueDrained(api,thread,epoch' in host
assert 'queue.changed(params.string("threadId"))'in host
processor=(source/'app-server/src/message_processor.rs').read_text()
assert 'ClientRequest::ThreadPredictionRequest { .. } => Err(method_not_found('in processor
assert 'thread/prediction/request is not implemented yet'in processor
assert '固定原版尚未实现'in next(x for x in rows if x['method']=='thread/prediction/request')['status']
assert 'thread/prediction/request'in ui
# 自动执行是固定队列服务的实际行为，不以空队列写入ACK当作任务终态。
queue_source=(source/'ext/queue/src/service.rs').read_text()
assert 'self.wake_if_loaded(thread_id).await'in queue_source
assert 'fn on_thread_idle' in queue_source
for f in ['核对待办队列.py','更新实验协议进度.py']:ast.parse((r/'工具'/f).read_text())
if a.parser_path:
    sys.path.insert(0,a.parser_path)
    from tree_sitter import Language,Parser
    import tree_sitter_kotlin
    parser=Parser(Language(tree_sitter_kotlin.language()))
    names=['OriginalQueueController.kt','OriginalQueueTest.kt','OriginalQueuePanel.kt','OriginalCoreHost.kt','OriginalCoreHostTest.kt','OriginalCodexClient.kt','OriginalSessionActions.kt','OriginalRuntimeLifecycle.kt','OriginalCoreScreen.kt','OriginalSessionScreen.kt','CapabilitiesScreen.kt']
    for name in names:
        path=next(r.rglob(name));assert not parser.parse(path.read_bytes()).root_node.has_error,path
    print('11份本批Kotlin语法通过。')
print('6队列方法、参数/业务/中文入口、通知与前台等待、原版预测缺口和71项资产一致性通过；未编译、未运行测试。')
