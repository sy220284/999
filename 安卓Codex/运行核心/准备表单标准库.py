#!/usr/bin/env python3
"""只获取固定SRI的纯JS数据，打包为签名APK资产；不安装包、不执行脚本或编译目标。"""
import argparse,base64,hashlib,hmac,io,json,re,tarfile,urllib.request
from pathlib import Path,PurePosixPath

def read_package(data,record):
    algorithm,encoded=record['integrity'].split('-',1)
    if algorithm!='sha512' or not hmac.compare_digest(hashlib.sha512(data).digest(),base64.b64decode(encoded,validate=True)):raise ValueError('包SRI不匹配')
    if len(data)>16*1024*1024:raise ValueError('归档超出预算')
    files={};total=0
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz')as archive:
        for member in archive:
            p=PurePosixPath(member.name)
            if p.is_absolute() or '..'in p.parts or not p.parts or p.parts[0]!='package' or '\\'in member.name:raise ValueError('非法归档路径')
            if member.isdir():continue
            if not member.isfile() or member.size>8*1024*1024:raise ValueError('归档包含链接、设备或超大文件')
            relative=str(PurePosixPath(*p.parts[1:]));total+=member.size
            if relative in files or len(files)>=20000 or total>48*1024*1024:raise ValueError('包文件重复或超出预算')
            stream=archive.extractfile(member);blob=stream.read(member.size+1)
            if len(blob)!=member.size or blob.startswith(b'\x7fELF'):raise ValueError('包不是纯数据或文件尺寸不符')
            files[relative]=blob
    package=json.loads(files['package.json']);
    if package['name']!=record['name'] or package['version']!=record['version']:raise ValueError('包身份不符')
    return files

def prepare(records,cache,output,licenses):
    modules={};omitted={'smtp-address-parser':{'typescript'}};names={x['name']for x in records}
    for record in records:
        if not re.fullmatch(r'[a-z0-9][a-z0-9._-]*',record['name']):raise ValueError('不支持的包名')
        expected=f"https://registry.npmjs.org/{record['name']}/-/{record['name']}-{record['version']}.tgz"
        if record['url']!=expected:raise ValueError('供应来源不是固定官方版本')
        key=hashlib.sha256(record['integrity'].encode()).hexdigest();path=cache/(key+'.tgz');cache.mkdir(parents=True,exist_ok=True)
        if not path.exists():
            with urllib.request.urlopen(record['url'],timeout=30)as response:
                if response.geturl()!=expected:raise ValueError('归档来源发生重定向')
                data=response.read(16*1024*1024+1)
            # 先校验再缓存，不把首次下载摘要当可信根。
            read_package(data,record);path.write_bytes(data)
        files=read_package(path.read_bytes(),record)
        package=json.loads(files['package.json'])
        if any(dep not in names and dep not in omitted.get(record['name'],set())for dep in package.get('dependencies',{})):raise ValueError('依赖未固定')
        count=0
        for name,blob in files.items():
            if 'licen' in PurePosixPath(name).name.lower() or 'copying'in PurePosixPath(name).name.lower() or 'notice'in PurePosixPath(name).name.lower():
                target=licenses/record['name']/name;target.parent.mkdir(parents=True,exist_ok=True)
                if target.exists() and target.read_bytes()!=blob:raise ValueError('已有许可文件不同，拒绝覆盖')
                target.write_bytes(blob);count+=1
            if name.endswith(('.js','.json')):
                text=blob.decode('utf-8')
                if record['name']=='smtp-address-parser' and re.search(r'''require\s*\(\s*['"]typescript['"]''',text):raise ValueError('TypeScript并非仅构建依赖，拒绝漏装')
                modules[record['name']+'/'+name]=text
        if not count:
            for name,blob in files.items():
                if 'readme' in PurePosixPath(name).name.lower() and ((b'Permission is hereby granted' in blob and b'Copyright' in blob) or (package.get('license')=='CC0-1.0' and b'License' in blob and b'creativecommons.org/publicdomain/zero/1.0/' in blob)):
                    target=licenses/record['name']/'NOTICE-from-README.md';target.parent.mkdir(parents=True,exist_ok=True)
                    if target.exists() and target.read_bytes()!=blob:raise ValueError('已有README许可不同，拒绝覆盖')
                    target.write_bytes(blob);count+=1
            if not count:raise ValueError('包缺少许可文件：'+record['name'])
    encoded=json.dumps({'version':1,'modules':modules},ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
    if len(encoded)>8*1024*1024:raise ValueError('标准校验库资产超出8MiB预算')
    output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists() and output.read_bytes()!=encoded:raise ValueError('已有标准库资产不同，请使用干净构建输出')
    output.write_bytes(encoded)
    return len(modules)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--licenses',type=Path,required=True);a=p.parse_args()
    records=json.loads(Path(__file__).with_name('表单标准库来源.json').read_text())['packages']
    print('已准备标准校验数据模块：',prepare(records,a.cache,a.output,a.licenses),'；没有运行目标校验器或构建。')
