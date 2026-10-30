#!/usr/bin/env python3
"""固定16类会话通知的协议/业务/界面静态核对；不编译、不执行 Android。"""
import argparse
import ast
import csv
import json
from pathlib import Path
import re
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True, help='固定原版源码目录')
parser.add_argument('--parser-path', help='tree-sitter 固定语法依赖目录')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
base = Path(args.source) / 'codex-rs/app-server-protocol/schema/typescript/v2'
methods = {
    'thread/started': ('ThreadStartedNotification', ['thread']),
    'thread/readState/changed': ('ThreadReadStateChangedNotification', ['threadId', 'readState']),
    'thread/archived': ('ThreadArchivedNotification', ['threadId']),
    'thread/deleted': ('ThreadDeletedNotification', ['threadId']),
    'thread/unarchived': ('ThreadUnarchivedNotification', ['threadId']),
    'thread/closed': ('ThreadClosedNotification', ['threadId']),
    'thread/reverted': ('ThreadRevertedNotification', ['threadId']),
    'thread/name/updated': ('ThreadNameUpdatedNotification', ['threadId', 'threadName']),
    'thread/attachment/updated': ('ThreadAttachmentUpdatedNotification', ['threadId', 'operation', 'attachmentId', 'attachmentType', 'identityKey']),
    'thread/prediction/updated': ('ThreadPredictionUpdatedNotification', ['threadId', 'sourceTurnId', 'result']),
    'thread/queue/changed': ('ThreadQueueChangedNotification', ['threadId']),
    'project/changed': ('ProjectChangedNotification', ['projectId', 'changeType']),
    'thread/project/updated': ('ThreadProjectUpdatedNotification', ['threadId', 'projectId']),
    'thread/environment/connected': ('EnvironmentConnectionNotification', ['threadId', 'environmentId']),
    'thread/environment/disconnected': ('EnvironmentConnectionNotification', ['threadId', 'environmentId']),
    'thread/settings/updated': ('ThreadSettingsUpdatedNotification', ['threadId', 'threadSettings']),
}
protocol = (root / '源码对照/ServerNotification.ts').read_text()
host = (root / 'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
reducer = (root / 'core/src/main/kotlin/cn/codex/core/original/OriginalThreadUpdates.kt').read_text()
rows = list(csv.DictReader((root / '文档/原版协议执行清单.csv').open()))
asset = json.loads((root / 'app/src/main/assets/原版协议进度.json').read_text())
assert len(rows) == len(asset) == 206
assert [(x['类型'], x['方法'], x['代码状态']) for x in rows] == [(x['kind'], x['method'], x['status']) for x in asset]
for method, (name, fields) in methods.items():
    assert f'"method": "{method}", "params": {name}' in protocol, method
    assert f'"{method}"' in host, method
    assert f'"{method}" ->' in reducer or f'method == "{method}"' in reducer, method
    for field in fields:
        assert re.search(r'\b' + field + r'\??\s*:', (base / (name + '.ts')).read_text()), (name, field)
    row = next(r for r in rows if r['类型'] == '服务端通知' and r['方法'] == method)
    assert '已接入' in row['代码状态'], method
for name in ['OriginalCoreScreen.kt', 'OriginalSessionScreen.kt']:
    assert 'OriginalThreadSyncPanel(' in next(root.rglob(name)).read_text(), name
ast.parse((root / '工具/更新原版协议进度.py').read_text())
if args.parser_path:
    sys.path.insert(0, args.parser_path)
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin
    syntax = Parser(Language(tree_sitter_kotlin.language()))
    for name in ['OriginalThreadUpdates.kt', 'OriginalThreadUpdatesTest.kt', 'OriginalThreadSyncPanel.kt', 'OriginalCoreHost.kt', 'OriginalCoreHostTest.kt', 'OriginalCoreScreen.kt', 'OriginalSessionScreen.kt', 'OriginalRuntimeLifecycle.kt']:
        path = next(root.rglob(name))
        assert not syntax.parse(path.read_bytes()).root_node.has_error, path
    print('8 份本批 Kotlin 语法树通过。')
print('16 类通知、固定原版类型/字段、宿主/业务/界面入口和206项清单核对通过；未编译、未运行测试。')
