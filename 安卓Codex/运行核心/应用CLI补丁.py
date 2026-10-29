#!/usr/bin/env python3
"""固定源码CLI补丁：逐文件匹配前后哈希，拒绝零上下文补丁落入已修改文件。"""
from pathlib import Path
import argparse,hashlib,json,subprocess
ROOT=Path(__file__).resolve().parent

def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def apply(source,patch=None):
    source=Path(source).resolve();patch=Path(patch)if patch is not None else ROOT/'安卓CLI差异确认.patch'
    manifest=json.loads((ROOT/'安卓CLI补丁来源.json').read_text())
    if digest(patch)!=manifest['patch_sha256']:raise ValueError('CLI补丁内容与固定来源记录不一致')
    states=[]
    for item in manifest['files']:
        path=source/item['path'];actual=digest(path)
        if actual==item['before']:states.append('before')
        elif actual==item['after']:states.append('after')
        else:raise ValueError('CLI构建副本含未识别修改，请使用新的构建目录：'+item['path'])
    if all(x=='after'for x in states):return 'already_applied'
    if not states or any(x!='before'for x in states):raise ValueError('CLI副本仅部分应用，拒绝覆盖，请使用新的构建目录')
    subprocess.run(['git','apply','--unidiff-zero','--check',str(patch.resolve())],cwd=source,check=True)
    subprocess.run(['git','apply','--unidiff-zero',str(patch.resolve())],cwd=source,check=True)
    for item in manifest['files']:
        if digest(source/item['path'])!=item['after']:raise ValueError('CLI应用后哈希不符，停止构建')
    return 'applied'

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);args=parser.parse_args()
    print(apply(args.source))
