#!/usr/bin/env python3
"""对照未修改的固定原版源码核对证据；只读文件，不执行任何程序或构建。"""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True, help='上传版本的未修改源码根目录')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / '运行核心/核心宿主清单.json').read_text())
verified = {}
for item in manifest['entries']:
    for ref in item['refs']:
        relative = Path(ref['path'])
        assert not relative.is_absolute() and '..' not in relative.parts
        data = (args.source / relative).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        assert digest == ref['sha256'], f'源码证据版本不匹配：{relative}'
        verified[str(relative)] = digest
print(f"已核对 {len(manifest['entries'])} 个宿主/工具条目、{len(verified)} 份源码证据；不代表可执行程序已构建或运行。")
