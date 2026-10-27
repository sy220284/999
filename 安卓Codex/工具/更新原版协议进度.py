#!/usr/bin/env python3
"""静态生成调用入口清单；不将方法存在性视为语义完成或测试通过。"""
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
client_path = "core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt"
host_path = "app/src/main/java/cn/codex/android/OriginalCoreHost.kt"
extension_path = "core/src/main/kotlin/cn/codex/core/original/OriginalExtensionsController.kt"
settings_path = "core/src/main/kotlin/cn/codex/core/original/OriginalSettingsController.kt"
mcp_path = "core/src/main/kotlin/cn/codex/core/original/OriginalMcpController.kt"
client = (ROOT / client_path).read_text()
host = (ROOT / host_path).read_text()
facade = set(re.findall(r'call\("([^"]+)"', client)) | {"thread/archive", "thread/unarchive"}
notifications = set(re.findall(r'"method": "([^"]+)"', (ROOT / "源码对照/ServerNotification.ts").read_text()))
handled = {m for m in notifications if f'"{m}" ->' in host or f'method == "{m}"' in host}
queued = {"item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request", "item/permissions/requestApproval"}
rows = []
for kind, file in [("客户端请求", "ClientRequest.ts"), ("服务端请求", "ServerRequest.ts"), ("服务端通知", "ServerNotification.ts")]:
    methods = list(dict.fromkeys(re.findall(r'"method": "([^"]+)"', (ROOT / "源码对照" / file).read_text())))
    for method in methods:
        status, entry, phase = "待接入", "", "P4"
        if kind == "客户端请求":
            if method.startswith("windowsSandbox/"):
                status, phase = "Windows 专用，Android 平台受限", "平台限制"
            elif method in facade:
                entry = client_path
                status = "调用及界面代码已接入，未运行验证"
                if method.startswith("plugin/") or method in {"hooks/list", "config/read", "config/batchWrite"}:
                    entry += ";" + extension_path
                if method in {"mcpServerStatus/list", "config/mcpServer/reload", "config/read", "config/batchWrite"}:
                    entry += ";" + mcp_path
                if method in {"account/usage/read", "account/workspaceMessages/read", "modelProvider/capabilities/read", "configRequirements/read", "permissionProfile/list", "experimentalFeature/list", "experimentalFeature/enablement/set"}:
                    entry += ";" + settings_path
                phase = "待运行验证/继续语义审阅"
            elif method.startswith(("plugin/", "skills/", "mcpServer/", "config/mcpServer")):
                phase = "P2"
            elif method.startswith(("thread/", "threadSection/", "turn/", "review/")):
                phase = "P3"
        elif kind == "服务端请求":
            if method in queued:
                status, entry, phase = "中文交互已接入，未运行验证", host_path, "待验证"
                if method == "mcpServer/elicitation/request":
                    status, phase = "表单/URL 已接入；有限复杂 schema，未运行验证", "P2/待验证"
            else:
                status, entry = "未接入，明确返回协议错误", host_path
        elif method.startswith("windows"):
            status, phase = "Windows 专用，Android 平台受限", "平台限制"
        elif method in handled:
            status, entry, phase = "通知分支已接入，未运行验证", host_path, "待验证/继续语义审阅"
        elif method.startswith(("hook/", "mcpServer/")):
            phase = "P2"
        elif "Agent" in method or method.startswith(("thread/", "turn/")):
            phase = "P3"
        rows.append([kind, method, status, entry, phase, "未运行验证"])
path = ROOT / "文档/原版协议执行清单.csv"
with path.open("w", newline="", encoding="utf-8") as stream:
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(["类型", "方法", "代码状态", "代码入口", "后续单元", "验证状态"])
    writer.writerows(rows)
print("写入", len(rows), "条协议项；此清单不代表完整功能完成率。")

# 应用展示与仓库台账使用同一源，保留请求/通知种类，避免旧版功能页继续误报。
asset = ROOT / "app/src/main/assets/原版协议进度.json"
asset.write_text(json.dumps([dict(zip(["kind", "method", "status", "entry", "phase", "validation"], row)) for row in rows], ensure_ascii=False, indent=2) + "\n")
