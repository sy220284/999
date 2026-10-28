#!/usr/bin/env python3
"""源码级核对；不编译也不替代行为测试。"""
from pathlib import Path
import argparse,csv,re,sys
p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--parser-path',required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1]
files=['core/src/main/kotlin/cn/codex/core/original/OriginalCompletionData.kt','core/src/main/kotlin/cn/codex/core/original/OriginalProjectsController.kt','core/src/main/kotlin/cn/codex/core/original/OriginalHistoryController.kt','core/src/main/kotlin/cn/codex/core/original/OriginalAdvancedController.kt','core/src/main/kotlin/cn/codex/core/original/OriginalConnectionsController.kt','app/src/main/java/cn/codex/android/OriginalCompletionScreens.kt']
for f in files:assert (r/f).exists(),f+' 尚未实现'
sys.path.insert(0,a.parser_path)
from tree_sitter import Language,Parser
import tree_sitter_kotlin
parser=Parser(Language(tree_sitter_kotlin.language()))
for f in files+['core/src/test/kotlin/cn/codex/core/original/OriginalCompletionTest.kt','app/src/main/java/cn/codex/android/ProjectCreationRequestStore.kt','app/src/main/java/cn/codex/android/CapabilitiesScreen.kt','core/src/main/kotlin/cn/codex/core/original/AppServerConnection.kt','app/src/androidTest/java/cn/codex/android/ProjectCreationRequestStoreTest.kt']:
 t=parser.parse((r/f).read_bytes());assert not t.root_node.has_error,f+' Kotlin语法错误'
print('新增业务/中文界面与测试源码Kotlin语法通过；尚未编译或运行。')
rows=list(csv.DictReader((r/'文档/实验协议执行清单.csv').open()))
source=Path(a.source)/'codex-rs'
client=(r/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt').read_text()
controllers='\n'.join((r/f).read_text()for f in files if 'Controller.kt'in f)
ui=(r/'app/src/main/java/cn/codex/android/OriginalCompletionScreens.kt').read_text()
ui+=(r/'app/src/main/java/cn/codex/android/OriginalExperimentalScreens.kt').read_text()
controllers+=(r/'core/src/main/kotlin/cn/codex/core/original/OriginalRealtimeController.kt').read_text()
previous={'thread/queue/','process/','fuzzyFileSearch/session'}
new=[x for x in rows if x['kind']=='客户端请求' and x['method'] not in {'mock/experimentalMethod','thread/prediction/request'} and not any(x['method'].startswith(prefix)for prefix in previous) and (not x['method'].startswith('thread/realtime/')or x['method']in {'thread/realtime/appendSpeech','thread/realtime/listVoices'})]
assert len(new)==51,len(new)
protocol='\n'.join(p.read_text()for p in(source/'app-server-protocol/src/protocol/v2').glob('*.rs'))
def rust_type(name):
    name=name.removeprefix('v2::')
    m=re.search(r'pub (?:struct|enum) '+name+r'\s*\{',protocol)
    if not m:
        target=re.search(r'pub type '+name+r' = Option<(\w+)>;',protocol)
        assert target,name
        return rust_type(target[1])
    i=m.end();depth=1
    while depth:
        depth+=(protocol[i]=='{')-(protocol[i]=='}');i+=1
    return protocol[m.end():i-1]
def camel(value):
    return re.sub(r'_([a-z])',lambda m:m[1].upper(),value)
for row in new:
    method=row['method']
    line=next(line for line in client.splitlines()if f'"{method}"'in line and 'suspend fun 'in line)
    function=re.search(r'suspend fun (\w+)',line)[1]
    assert re.search(r'\.'+function+r'\s*[({]',controllers),(method,'无业务调用')
    typename=row['params']
    if typename!='Option<()>':
        body=rust_type(typename)
        fields={camel(x)for x in re.findall(r'pub\s+(\w+)\s*:',body)}
        if typename.endswith('BedrockSetupParams'):fields={'type','profile','region'}
        direct=set(re.findall(r'put\("([^"]+)"',line))
        assert direct<=fields,(method,direct-fields)
    for path in row['entry'].split(';'):assert(r/path).is_file(),path
# 不只核对方法字符串：这些源行为直接影响实际入口和收尾。
host=(r/'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
for name in ['projects','history','advanced','connections']:
    assert f'{name}.bind(api)'in host and f'{name}.bind(null)'in host,name
for page in ['项目管理','搜索与会话记录','会话设置与记忆','连接、订阅与认证']:
    assert page in(r/'app/src/main/java/cn/codex/android/OriginalCoreScreen.kt').read_text(),page
assert '"mcpServer/event/stream/notification"'in host and 'connections.streamEvent(params)'in host
assert '"remoteControl/status/changed"'in host and 'connections.remoteChanged(params)'in host
assert 'projects.changed()'in host
history=(r/files[2]).read_text()
assert 'response["readState"]'in history and 'expectedRevision'in(r/files[0]).read_text()
assert '"ready"'in(r/files[4]).read_text()
assert 'cfg!(target_os = "macos")'in(source/'user-verification/src/lib.rs').read_text()
assert sum(x['phase']=='提供者缺口'for x in rows)==5
assert not any('待接入'in x['status']for x in rows)
assert len({(x['kind'],x['method'])for x in rows})==71
print('51项固定Rust方法、直接字段、业务调用、中文页面、通知/连接绑定核对通过；5项用户验证仍是提供者缺口，不计完整实现。')
