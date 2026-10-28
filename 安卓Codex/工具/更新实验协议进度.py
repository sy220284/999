#!/usr/bin/env python3
"""从固定Rust协议宏补出默认TS剔除项；与原206项分开追踪，不执行构建。"""
import argparse
import csv
import json
import re
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--source',required=True)
a=p.parse_args()
r=Path(__file__).resolve().parents[1]
s=(Path(a.source)/'codex-rs/app-server-protocol/src/protocol/common.rs').read_text()
def block(macro):
    value=s.split(macro+'! {',1)[1]
    return value[:re.search(r'(?m)^}',value).start()]
def known(name):return set(re.findall(r'"method": "([^"]+)"',(r/'源码对照'/name).read_text()))
clients=re.findall(r'(?m)^\s*(\w+)\s*=>\s*"([^"]+)"\s*\{(.*?)^\s*\},',block('client_request_definitions'),re.S)
server_entries=re.findall(r'(?m)^\s*(\w+)(?:\s*=>\s*"([^"]+)")?\s*\{(.*?)^\s*\},',block('server_request_definitions'),re.S)
servers=[(v,wire or v[0].lower()+v[1:],body)for v,wire,body in server_entries]
notifs=set(re.findall(r'=>\s*"([^"]+)"',block('server_notification_definitions')))|set(re.findall(r'#\[serde\(rename = "([^"]+)"\)\]',block('server_notification_definitions')))
assert known('ClientRequest.ts') <= {x[1]for x in clients}
assert known('ServerRequest.ts') <= {x[1]for x in servers}
assert known('ServerNotification.ts') == notifs
client_path='core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt'
controllers={
 'process/':'core/src/main/kotlin/cn/codex/core/original/OriginalCommandController.kt',
 'fuzzyFileSearch/session':'core/src/main/kotlin/cn/codex/core/original/OriginalSearchController.kt',
 'thread/realtime/':'core/src/main/kotlin/cn/codex/core/original/OriginalRealtimeController.kt'}
ui={'process/':'app/src/main/java/cn/codex/android/OriginalCommandScreen.kt','fuzzyFileSearch/session':'app/src/main/java/cn/codex/android/OriginalExperimentalScreens.kt','thread/realtime/':'app/src/main/java/cn/codex/android/OriginalExperimentalScreens.kt'}
client=(r/client_path).read_text()
rows=[]
for kind,entries,stable in [('客户端请求',clients,known('ClientRequest.ts')),('服务端请求',servers,known('ServerRequest.ts'))]:
    for variant,method,body in entries:
        if method in stable:continue
        clean=re.sub(r'#\[[^\]]*\]','',body)
        params=re.search(r'params:\s*([^,\n]+)',clean).group(1).strip()
        response=re.search(r'response:\s*([^,\n]+)',clean).group(1).strip()
        status,entry,phase='待接入（Rust实验接口）','','后续实验单元'
        if method=='mock/experimentalMethod':status,phase='开发测试专用，产品不提供','开发专用'
        elif kind=='服务端请求' and method=='currentTime/read':
            entry='app/src/main/java/cn/codex/android/OriginalCoreHost.kt';assert 'message.method == "currentTime/read"'in(r/entry).read_text()
            status,phase='本机秒级时间回调代码已接入，未运行验证','运行验收'
        elif f'call("{method}"'in client:
            group=next(k for k in controllers if method.startswith(k))
            entry=client_path+';'+controllers[group]+';'+ui[group]
            status,phase='实验调用/通知/中文操作代码已接入，未运行验证','运行验收'
        rows.append(dict(kind=kind,method=method,status=status,entry=entry,phase=phase,validation='未运行验证',scope='Rust实验/默认TS省略',params=params,response=response))
with(r/'文档/实验协议执行清单.csv').open('w',newline='')as f:
    writer=csv.DictWriter(f,fieldnames=list(rows[0]),lineterminator='\n');writer.writeheader();writer.writerows(rows)
(r/'app/src/main/assets/实验协议进度.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
print(f'Rust客户端{len(clients)}、服务端{len(servers)}、通知{len(notifs)}；默认清单外{len(rows)}项，合计{len(clients)+len(servers)+len(notifs)}项。')
print('实验已接入',sum('已接入'in x['status']for x in rows),'待接入',sum('待接入'in x['status']for x in rows),'开发专用',sum('开发专用'==x['phase']for x in rows))
