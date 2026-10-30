#!/usr/bin/env python3
"""固定源码CLI补丁：逐文件匹配前后哈希，拒绝零上下文补丁落入已修改文件。"""
from pathlib import Path
import argparse,hashlib,json,subprocess,re
ROOT=Path(__file__).resolve().parent

def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def package_block(text,name):
    match=re.search(r'(?ms)^\[\[package\]\]\nname = "'+re.escape(name)+r'"\n.*?(?=^\[\[package\]\]|\Z)',text)
    if not match:raise ValueError('锁文件缺少包：'+name)
    return match.group(0)
def add_dependency(block,name):
    marker='dependencies = [\n';start=block.index(marker)+len(marker);end=block.index(']\n',start)
    lines=block[start:end].splitlines();line=' "'+name+'",'
    if line not in lines:lines.append(line)
    return block[:start]+'\n'.join(sorted(lines))+'\n'+block[end:]
def apply(source,patch=None,manifest_path=None):
    source=Path(source).resolve();patch=Path(patch)if patch is not None else ROOT/'安卓CLI差异确认.patch'
    manifest=json.loads((Path(manifest_path)if manifest_path is not None else ROOT/'安卓CLI补丁来源.json').read_text())
    if digest(patch)!=manifest['patch_sha256']:raise ValueError('CLI补丁内容与固定来源记录不一致')
    states=[]
    for item in manifest['files']:
        path=source/item['path'];actual=digest(path)
        if actual==item['before']:states.append('before')
        elif actual==item['after']:states.append('after')
        else:raise ValueError('CLI构建副本含未识别修改，请使用新的构建目录：'+item['path'])
    cargo=[]
    for item in manifest.get('cargo_dependencies',[]):
        path=source/item['path'];text=path.read_text();block=package_block(text,item['package']);actual=hashlib.sha256(block.encode()).hexdigest()
        if actual==item['before']:states.append('before')
        elif actual==item['after']:states.append('after')
        else:raise ValueError('锁文件的补丁目标包有未知修改：'+item['package'])
        cargo.append((item,path,block))
        for ref in item.get('locked_packages',[]):
            if hashlib.sha256(package_block(text,ref['package']).encode()).hexdigest()!=ref['sha256']:raise ValueError('已有依赖版本或来源改变：'+ref['package'])
    if all(x=='after'for x in states):return 'already_applied'
    if not states or any(x!='before'for x in states):raise ValueError('CLI副本仅部分应用，拒绝覆盖，请使用新的构建目录')
    subprocess.run(['git','apply','--unidiff-zero','--check',str(patch.resolve())],cwd=source,check=True)
    subprocess.run(['git','apply','--unidiff-zero',str(patch.resolve())],cwd=source,check=True)
    for item,path,old in cargo:
        text=path.read_text();current=package_block(text,item['package'])
        if current!=old:raise ValueError('锁文件目标包在应用期间变化')
        updated=add_dependency(current,item['add'])
        if hashlib.sha256(updated.encode()).hexdigest()!=item['after']:raise ValueError('依赖增量不符')
        path.write_text(text.replace(current,updated,1))
    for item in manifest['files']:
        if digest(source/item['path'])!=item['after']:raise ValueError('CLI应用后哈希不符，停止构建')
    return 'applied'

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);parser.add_argument('--patch',type=Path);parser.add_argument('--manifest',type=Path);args=parser.parse_args()
    print(apply(args.source,args.patch,args.manifest))
