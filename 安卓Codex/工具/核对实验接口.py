#!/usr/bin/env python3
"""固定Rust实验接口静态接线核对，不执行编译、设备或网络业务。"""
import argparse
import ast
import csv
import json
import re
import sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--parser-path');a=p.parse_args()
r=Path(__file__).resolve().parents[1];source=Path(a.source)/'codex-rs/app-server-protocol/src/protocol'
client=(r/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt').read_text()
checks={
 'process/spawn':('spawnProcess','v2/process.rs','ProcessSpawnParams',['process_handle','command','cwd','tty','stream_stdin','stream_stdout_stderr','output_bytes_cap','timeout_ms','size']),
 'process/writeStdin':('writeProcess','v2/process.rs','ProcessWriteStdinParams',['process_handle','delta_base64','close_stdin']),
 'process/kill':('killProcess','v2/process.rs','ProcessKillParams',['process_handle']),
 'process/resizePty':('resizeProcess','v2/process.rs','ProcessResizePtyParams',['process_handle','size']),
 'fuzzyFileSearch/sessionStart':('startSearchSession','common.rs','FuzzyFileSearchSessionStartParams',['session_id','roots']),
 'fuzzyFileSearch/sessionUpdate':('updateSearchSession','common.rs','FuzzyFileSearchSessionUpdateParams',['session_id','query']),
 'fuzzyFileSearch/sessionStop':('stopSearchSession','common.rs','FuzzyFileSearchSessionStopParams',['session_id']),
 'thread/realtime/start':('startRealtime','v2/realtime.rs','ThreadRealtimeStartParams',['thread_id','output_modality','transport','version']),
 'thread/realtime/appendText':('appendRealtimeText','v2/realtime.rs','ThreadRealtimeAppendTextParams',['thread_id','text','role']),
 'thread/realtime/appendAudio':('appendRealtimeAudio','v2/realtime.rs','ThreadRealtimeAppendAudioParams',['thread_id','audio']),
 'thread/realtime/stop':('stopRealtime','v2/realtime.rs','ThreadRealtimeStopParams',['thread_id']),
}
rows=list(csv.DictReader((r/'文档/实验协议执行清单.csv').open()))
asset=json.loads((r/'app/src/main/assets/实验协议进度.json').read_text())
assert rows==asset and len(rows)==71
for method,(function,file,typename,fields)in checks.items():
    declaration=next(line for line in client.splitlines()if f'suspend fun {function}('in line)
    assert f'call("{method}"'in declaration,method
    body=re.search(r'pub struct '+typename+r'\s*\{(.*?)^}',(source/file).read_text(),re.S|re.M).group(1)
    for field in fields:assert re.search(r'pub\s+'+field+r'\s*:',body),(method,field)
    row=next(x for x in rows if x['method']==method);assert '已接入'in row['status'],method
    for path in row['entry'].split(';'):assert(r/path).is_file(),path
host=(r/'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
assert 'message.method == "currentTime/read"'in host and 'System.currentTimeMillis()/1000L'in host
for text in ['commands.processOutput(params)','commands.processExited(params)','search.notification(method,params)','realtime.notification(method,params)']:assert text in host,text
ui=(r/'app/src/main/java/cn/codex/android/OriginalCoreScreen.kt').read_text()
assert 'OriginalRealtimeScreen(host)'in ui and 'OriginalSearchScreen(host,'in ui
permission=(r/'app/src/main/AndroidManifest.xml').read_text()
assert 'android.permission.RECORD_AUDIO'in permission
assert 'RequestPermission()'in(r/'app/src/main/java/cn/codex/android/OriginalExperimentalScreens.kt').read_text()
for path in ['更新实验协议进度.py','核对实验接口.py']:ast.parse((r/'工具'/path).read_text())
if a.parser_path:
    sys.path.insert(0,a.parser_path)
    from tree_sitter import Language,Parser
    import tree_sitter_kotlin
    parser=Parser(Language(tree_sitter_kotlin.language()))
    names=['OriginalExperimentalFlowsTest.kt','OriginalRealtimeController.kt','OriginalSearchController.kt','OriginalCommandController.kt','OriginalRealtimeMicrophone.kt','OriginalExperimentalScreens.kt','OriginalCoreScreen.kt','OriginalEventsScreen.kt','OriginalCoreHost.kt','OriginalCoreHostTest.kt','OriginalCodexClient.kt','CapabilitiesScreen.kt','OriginalCommandScreen.kt','OriginalSessionScreen.kt']
    for name in names:
        path=next(r.rglob(name));assert not parser.parse(path.read_bytes()).root_node.has_error,path
    print('14份本批Kotlin语法树核对通过。')
print('11项实验调用及时间回调、Rust参数字段、通知/页面/权限接线、71项实验清单核对通过；未编译/未运行测试。')
