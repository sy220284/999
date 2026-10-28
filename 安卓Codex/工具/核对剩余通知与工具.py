#!/usr/bin/env python3
"""静态核对33类通知和动态工具，保留实验发起/外部提供者缺口；不运行项目。"""
import argparse
import ast
import csv
import json
import re
import sys
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument('--source', required=True)
p.add_argument('--parser-path')
a = p.parse_args()
r = Path(__file__).resolve().parents[1]
source = Path(a.source) / 'codex-rs/app-server-protocol'
protocol = (r/'源码对照/ServerNotification.ts').read_text()
known = set(re.findall(r'"method": "([^"]+)"',protocol))
events = (r/'core/src/main/kotlin/cn/codex/core/original/OriginalRemainingEvents.kt').read_text()
methods = known & set(re.findall(r'"([^"\n]+)"',events))
assert len(methods) == 33, len(methods)
rows = list(csv.DictReader((r/'文档/原版协议执行清单.csv').open()))
asset = json.loads((r/'app/src/main/assets/原版协议进度.json').read_text())
assert len(rows) == len(asset) == 206
assert [(x['类型'],x['方法'],x['代码状态']) for x in rows] == [(x['kind'],x['method'],x['status']) for x in asset]
for method in methods:
    typename = re.search(r'"method": "'+re.escape(method)+r'", "params": (\w+)',protocol).group(1)
    declarations = list((source/'schema/typescript').rglob(typename+'.ts'))
    assert len(declarations) == 1, (method,typename)
    assert next(x for x in rows if x['类型']=='服务端通知' and x['方法']==method)['代码状态'] != '待接入',method
host = (r/'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
client = (r/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt').read_text()
ui = (r/'app/src/main/java/cn/codex/android/OriginalEventsScreen.kt').read_text()
assert 'method in OriginalRemainingEvents.methods' in host
assert 'OriginalEventsScreen(host' in (r/'app/src/main/java/cn/codex/android/OriginalCoreScreen.kt').read_text()
assert 'executeAndroidTool(request)' in ui and 'androidTools.execute(params)' in host
assert 'put("dynamicTools", OriginalAndroidTools.definitions())' in client
# 默认TS生成物剔除了experimental字段，此处直接核对固定Rust原声明。
assert 'pub dynamic_tools: Option<Vec<DynamicToolSpec>>' in (source/'src/protocol/v2/thread.rs').read_text()
assert '#[experimental("thread/start.dynamicTools")]' in (source/'src/protocol/v2/thread.rs').read_text()
assert 'requestAttestation: boolean' in (source/'schema/typescript/InitializeCapabilities.ts').read_text()
assert 'put("requestAttestation", false)' in client
for method in ['account/chatgptAuthTokens/refresh','attestation/generate']:
    assert '外部提供者' in next(x for x in rows if x['类型']=='服务端请求' and x['方法']==method)['代码状态']
for name in ['更新原版协议进度.py','核对剩余通知与工具.py']:
    ast.parse((r/'工具'/name).read_text())
if a.parser_path:
    sys.path.insert(0,a.parser_path)
    from tree_sitter import Language,Parser
    import tree_sitter_kotlin
    parser = Parser(Language(tree_sitter_kotlin.language()))
    for name in ['OriginalAndroidTools.kt','OriginalRemainingEvents.kt','OriginalRemainingEventsTest.kt','OriginalRealtimeAudio.kt','OriginalEventsScreen.kt','OriginalCoreHost.kt','OriginalCoreHostTest.kt','OriginalCoreScreen.kt','OriginalCodexClient.kt']:
        path=next(r.rglob(name));assert not parser.parse(path.read_bytes()).root_node.has_error,path
    print('9份本批Kotlin语法通过。')
print('33类通知、实际工具执行/页面/注册、固定Rust实验字段和206项清单核对通过；外部提供者仍列缺口，WebRTC源码接线另由原生核查检查；运行均未验证。')
