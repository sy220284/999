#!/usr/bin/env python3
"""核对固定31项源码入口、中文页面路由和协议清单；不执行 Android/Kotlin 构建。"""
import argparse
import ast
import csv
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--source', help='固定原版源码目录，用于请求参数字段核对')
parser.add_argument('--parser-path', help='已安装 tree-sitter 与 tree-sitter-kotlin 的目录，仅语法解析')
args = parser.parse_args()
rows = json.loads((root/'文档/剩余31项闭环映射.json').read_text())
assert len(rows) == len({r['method'] for r in rows}) == 31
client = (root/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt').read_text()
known = set(re.findall(r'"method": "([^"]+)"', (root/'源码对照/ClientRequest.ts').read_text()))
paths = set()
for row in rows:
    assert row['method'] in known, row['method']
    declaration = next((line for line in client.splitlines() if f"suspend fun {row['client']}(" in line), '')
    assert f'call("{row["method"]}"' in declaration, row['method']
    if args.source:
        match = re.search(r'"method": "' + re.escape(row['method']) + r'".*?params\??:\s*(\w+)', (root/'源码对照/ClientRequest.ts').read_text())
        assert match, row['method']
        typename = match.group(1)
        fields = set(re.findall(r'put\("([^"\n]+)"', declaration))
        if row['client'] in {'uploadFeedback','saveFeedback'}:
            helper = next(line for line in client.splitlines() if 'private fun feedbackParams(' in line)
            fields.update(re.findall(r'put\("([^"\n]+)"',helper))
            if row['client']=='saveFeedback': fields.add('savePath')
        if typename=='undefined':
            assert not fields, row['method']
        else:
            sources = list((Path(args.source)/'codex-rs/app-server-protocol/schema/json').rglob(typename+'.json'))
            if not sources:
                legacy = list((Path(args.source)/'codex-rs/app-server-protocol/schema/typescript').rglob(typename+'.ts'))
                assert legacy, typename
                legacy_fields = set(re.findall(r'(?:[,{]\s*)([A-Za-z_]\w*)\??\s*:',legacy[0].read_text()))
                assert fields <= legacy_fields, (row['method'], fields - legacy_fields)
                schema = {'properties': dict.fromkeys(legacy_fields)}
            else:
                schema = json.loads(sources[0].read_text())
            def properties(node):
                keys = set(node.get('properties',{}))
                for group in ('anyOf','oneOf','allOf'):
                    for child in node.get(group,[]): keys.update(properties(child))
                return keys
            assert fields <= properties(schema), (row['method'], fields - properties(schema))
    consumer = (root/row['controller']).read_text()
    assert f"api.{row['client']}(" in consumer, row['method']
    ui = (root/row['ui']).read_text()
    assert any(x in ui for x in [f"controller.{row['action']}", f"controller::{row['action']}", f"host.{row['action']}"]), row['method']
    paths.update([root/row['controller'],root/row['ui']])
main = (root/'app/src/main/java/cn/codex/android/OriginalCoreScreen.kt').read_text()
for page in ['OriginalLibraryScreen','OriginalServicesScreen','OriginalMigrationScreen']:
    assert page+'(' in main, page
assert 'OriginalThreadAdvanced(' in (root/'app/src/main/java/cn/codex/android/OriginalSessionScreen.kt').read_text()
paths.add(root/'core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt')
if args.parser_path:
    sys.path.insert(0,args.parser_path)
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin
    syntax = Parser(Language(tree_sitter_kotlin.language()))
    for path in paths:
        assert not syntax.parse(path.read_bytes()).root_node.has_error, str(path)
asset = json.loads((root/'app/src/main/assets/原版协议进度.json').read_text())
ledger = list(csv.DictReader((root/'文档/原版协议执行清单.csv').open()))
assert len(asset) == len(ledger) == 206
assert [(x['类型'],x['方法'],x['代码状态']) for x in ledger] == [(x['kind'],x['method'],x['status']) for x in asset]
for row in rows:
    item = next(x for x in ledger if x['方法'] == row['method'] and x['类型'] == '客户端请求')
    assert '已接入' in item['代码状态'], row['method']
print('31 项协议封装、业务消费者、页面入口与 206 项清单核对通过。')
if args.parser_path:
    print(f'{len(paths)} 份入口源码通过 Kotlin 语法树解析；未编译、未运行测试。')
