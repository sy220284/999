#!/usr/bin/env python3
"""临时副本验证服务补丁和锁增量；不编译或执行目标代码。"""
from pathlib import Path
import argparse,tempfile,unittest,json,hashlib,importlib.util
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);a=p.parse_args();r=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('patch',r/'应用CLI补丁.py');helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
manifest=json.loads((r/'安卓服务补丁来源.json').read_text())
class ServicesPatchTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        for name in [x['path']for x in manifest['files']]+['codex-rs/Cargo.lock']:
            file=self.root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes((a.source/name).read_bytes())
    def tearDown(self):self.temp.cleanup()
    def apply(self):return helper.apply(self.root,r/'安卓本机服务.patch',r/'安卓服务补丁来源.json')
    def testAppliesAndPreservesUnrelatedLockContent(self):
        lock=self.root/'codex-rs/Cargo.lock';text=lock.read_text()+'\n# unrelated fixture\n';lock.write_text(text)
        self.assertEqual(self.apply(),'applied');self.assertEqual(self.apply(),'already_applied')
        old=helper.package_block(text,'codex-responses-api-proxy');new=helper.package_block(lock.read_text(),'codex-responses-api-proxy')
        self.assertEqual(lock.read_text(),text.replace(old,new,1));self.assertIn(' "subtle",',new)
    def testModifiedTargetCannotBeOverwritten(self):
        file=self.root/manifest['files'][0]['path'];file.write_text(file.read_text()+'\n// local\n');before=file.read_bytes()
        with self.assertRaises(ValueError):self.apply()
        self.assertEqual(before,file.read_bytes())
    def testChangedExistingDependencyIsRejectedBeforePatching(self):
        lock=self.root/'codex-rs/Cargo.lock';text=lock.read_text();block=helper.package_block(text,'subtle');lock.write_text(text.replace(block,block.replace('2.6.1','9.9.9'),1))
        target=self.root/manifest['files'][0]['path'];before=target.read_bytes()
        with self.assertRaises(ValueError):self.apply()
        self.assertEqual(before,target.read_bytes())
unittest.main(argv=[__file__])
