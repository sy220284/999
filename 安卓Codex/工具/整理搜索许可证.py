#!/usr/bin/env python3
"""构建后从 Cargo metadata 定位锁定依赖，保留真实许可证及 PCRE2/SLJIT 源文件声明。"""
import argparse
import json
import re
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--metadata', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
packages = json.loads(args.metadata.read_text())['packages']
for name, version in [('pcre2', '0.2.11'), ('pcre2-sys', '0.2.10')]:
    package, = [item for item in packages if item['name'] == name and item['version'] == version]
    source = Path(package['manifest_path']).parent
    output = args.output / name
    output.mkdir(parents=True, exist_ok=True)
    for filename in ['COPYING', 'LICENSE-MIT', 'UNLICENSE']:
        shutil.copyfile(source / filename, output / filename)
    if name == 'pcre2-sys':
        notices = {}
        # PCRE2 与内置 SLJIT 的完整版权/许可声明在源文件开头的连续注释中。
        pattern = re.compile(r'(?:\s+|/\*[\s\S]*?\*/|//[^\n]*(?:\n|$))+')
        for path in sorted((source / 'upstream').rglob('*')):
            if path.suffix not in {'.c', '.h'}:
                continue
            text = path.read_text(errors='strict')
            prefix = pattern.match(text)
            if prefix:
                notices.setdefault(prefix.group().strip(), []).append(str(path.relative_to(source)))
        if not notices:
            raise ValueError('锁定依赖中缺少上游源码声明，不能完成许可证整理')
        (output / 'PCRE2-SLJIT-source-notices.txt').write_text('\n\n'.join('\n'.join(paths) + '\n' + notice for notice, paths in notices.items()) + '\n')
print('已保留 pcre2 / pcre2-sys 许可证及 PCRE2 / SLJIT 源码声明。')
