#!/usr/bin/env python3
"""锁定来源的准备/打包工具。仅显式调用fetch才下载，不执行目标程序或构建。"""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,os,posixpath,shutil,tarfile,tempfile,urllib.request,zipfile
ROOT=Path(__file__).resolve().parent
LIMIT=2*1024*1024*1024

def digest(path):
    with Path(path).open('rb')as f:return hashlib.file_digest(f,'sha256').hexdigest()

def safe_name(value):
    p=PurePosixPath(value)
    if not value or '\\'in value or '\0'in value or p.is_absolute() or '..'in p.parts:raise ValueError('归档路径不安全')
    return p

def unpack(package,dest):
    dest=Path(dest)
    if dest.exists():raise ValueError('解包目标必须不存在；不会覆盖旧目录')
    dest.mkdir(parents=True)
    try:
        with tarfile.open(package)as archive:
            members=archive.getmembers()
            if len(members)>100000 or sum(m.size for m in members)>LIMIT:raise ValueError('归档超预算')
            seen=set();links=[]
            for member in members:
                name=safe_name(member.name)
                if str(name)in seen:raise ValueError('归档包含重复路径')
                seen.add(str(name));target=dest/name
                if member.issym() or member.islnk():links.append((member,name));continue
                if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
                if not member.isfile():raise ValueError('归档包含特殊文件')
                target.parent.mkdir(parents=True,exist_ok=True)
                with archive.extractfile(member)as src,target.open('xb')as out:shutil.copyfileobj(src,out)
                target.chmod(member.mode&0o755)
            link_names={str(name)for _,name in links}
            for member,name in links:
                if any(str(parent)in link_names for parent in name.parents):raise ValueError('链接不能充当文件祖先')
                raw=member.linkname
                logical=posixpath.normpath(posixpath.join(str(name.parent),raw)if member.issym()else raw)
                safe_name(logical)
                target=dest/name;target.parent.mkdir(parents=True,exist_ok=True)
                if member.issym():os.symlink(raw,target)
            for member,name in links:
                if member.issym() and not (dest/name).resolve().is_relative_to(dest.resolve()):raise ValueError('符号链接链逃出目标')
            for member,name in links:
                if member.islnk():
                    original=dest/posixpath.normpath(member.linkname)
                    if not original.resolve().is_relative_to(dest.resolve()) or not original.is_file() or original.is_symlink():raise ValueError('硬链接目标不安全')
                    os.link(original,dest/name)
        return dest
    except BaseException:
        shutil.rmtree(dest);raise

def fetch(name,work):
    data=json.loads((ROOT/'开发环境来源.json').read_text())['sources'][name]
    pin=data['sha256'];work=Path(work);work.mkdir(parents=True,exist_ok=True)
    package=work/(pin+'.tar')
    if not package.exists():
        if not data['url'].startswith('https://'):raise ValueError('来源必须HTTPS')
        temporary=package.with_suffix('.partial')
        try:
            with urllib.request.urlopen(data['url'],timeout=60)as response,temporary.open('wb')as out:
                total=0
                while chunk:=response.read(1024*1024):
                    total+=len(chunk)
                    if total>512*1024*1024:raise ValueError('下载超预算')
                    out.write(chunk)
            if digest(temporary)!=pin:raise ValueError('来源SHA256不匹配')
            temporary.replace(package)
        finally:temporary.unlink(missing_ok=True)
    if digest(package)!=pin:raise ValueError('缓存SHA256不匹配')
    destination=work/(name+'-'+pin[:12]);stamp=destination/'.codex-source-sha256'
    if destination.exists():
        if not stamp.is_file() or stamp.read_text()!=pin:raise ValueError('源码目录不属于本任务')
    else:unpack(package,destination);stamp.write_text(pin)
    roots=[p for p in destination.iterdir()if p.name!='.codex-source-sha256']
    return roots[0]if len(roots)==1 and roots[0].is_dir()else destination

def bundle(source,output):
    source=Path(source).resolve();output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    files={};total=0
    if output.resolve().is_relative_to(source):raise ValueError('归档不能写入源目录')
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6)as archive:
        for file in sorted(source.rglob('*')):
            if not file.is_file() or '__pycache__'in file.parts:continue
            if not file.resolve().is_relative_to(source):raise ValueError('打包链接逃出源目录')
            name=file.relative_to(source).as_posix();safe_name(name);size=file.stat().st_size;total+=size
            if total>512*1024*1024 or len(files)>=50000:raise ValueError('运行环境超预算')
            files[name]={'sha256':digest(file),'size':size};archive.write(file,name)
    return {'archive':output.name,'sha256':digest(output),'files':files}

def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['fetch','bundle']);p.add_argument('name');p.add_argument('output');args=p.parse_args()
    if args.mode=='fetch':print(fetch(args.name,args.output))
    else:print(json.dumps(bundle(args.name,args.output),ensure_ascii=False))
if __name__=='__main__':main()
