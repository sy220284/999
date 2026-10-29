#!/usr/bin/env python3
"""纯Python归档供应链测试；不执行下载包代码或目标程序。"""
import base64,hashlib,importlib.util,io,json,tarfile,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('bundle',Path(__file__).with_name('准备表单标准库.py'));bundle=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundle)
def archive(extra=None):
    out=io.BytesIO()
    with tarfile.open(fileobj=out,mode='w:gz') as t:
        files={'package/package.json':json.dumps({'name':'fixture','version':'1.0.0','license':'MIT'}).encode(),'package/index.js':b'module.exports=1','package/LICENSE':b'Copyright fixture; Permission is hereby granted'}
        files.update(extra or {})
        for name,value in files.items():
            m=tarfile.TarInfo(name)
            if value is None:m.type=tarfile.SYMTYPE;m.linkname='/etc/passwd';t.addfile(m)
            else:m.size=len(value);t.addfile(m,io.BytesIO(value))
    return out.getvalue()
def record(data):return {'name':'fixture','version':'1.0.0','url':'https://registry.npmjs.org/fixture/-/fixture-1.0.0.tgz','integrity':'sha512-'+base64.b64encode(hashlib.sha512(data).digest()).decode()}
class PackageTests(unittest.TestCase):
    def test_verified_archive_and_identity(self):
        data=archive();r=record(data);self.assertIn('index.js',bundle.read_package(data,r));r['version']='2.0.0'
        with self.assertRaisesRegex(ValueError,'身份'):bundle.read_package(data,r)
    def test_rejects_sri_change(self):
        data=archive()
        with self.assertRaisesRegex(ValueError,'SRI'):bundle.read_package(data+b'changed',record(data))
    def test_rejects_traversal_link_and_native_code(self):
        for extra in ({'package/../escape':b'x'},{'package/link':None},{'package/native':b'\x7fELFbad'}):
            data=archive(extra)
            with self.assertRaises(ValueError):bundle.read_package(data,record(data))
    def test_cached_package_still_verified_and_no_scripts_execute(self):
        data=archive({'package/index.js':b'throw Error("must not execute")'})
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);cache=root/'cache';cache.mkdir();r=record(data);cached=cache/(hashlib.sha256(r['integrity'].encode()).hexdigest()+'.tgz');cached.write_bytes(data)
            self.assertEqual(bundle.prepare([r],cache,root/'modules.json',root/'licenses'),2)
            self.assertIn('must not execute',(root/'modules.json').read_text());self.assertTrue((root/'licenses/fixture/LICENSE').exists())
            cached.write_bytes(data+b'changed')
            with self.assertRaisesRegex(ValueError,'SRI'):bundle.prepare([r],cache,root/'modules.json',root/'licenses')
if __name__=='__main__':unittest.main()
