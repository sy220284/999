#!/usr/bin/env python3
"""四份追加补丁的临时文件应用测试，不构建或运行目标代码。"""
import argparse,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('patch',root/'应用CLI补丁.py');helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
pairs=[('安卓诊断连接.patch','安卓诊断补丁来源.json'),('安卓远程执行状态.patch','安卓远程执行补丁来源.json'),('安卓Unix共享核心.patch','安卓Unix补丁来源.json'),('安卓执行链补齐.patch','安卓执行链补丁来源.json')]
class ExtraPatchTests(unittest.TestCase):
    def prepare(self,directory,record):
        for item in record['files']:
            path=directory/item['path'];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((a.source/item['path']).read_bytes())
    def testEachPatchIsIdempotentAndMatchesEveryRecordedHash(self):
        for diff,metadata in pairs:
            with self.subTest(patch=diff),tempfile.TemporaryDirectory()as temporary:
                directory=Path(temporary);record=json.loads((root/metadata).read_text());self.prepare(directory,record)
                self.assertEqual(helper.apply(directory,root/diff,root/metadata),'applied');self.assertEqual(helper.apply(directory,root/diff,root/metadata),'already_applied')
                for item in record['files']:self.assertEqual(hashlib.sha256((directory/item['path']).read_bytes()).hexdigest(),item['after'])
    def testUnknownSourceChangesAreNeverOverwritten(self):
        for diff,metadata in pairs:
            with self.subTest(patch=diff),tempfile.TemporaryDirectory()as temporary:
                directory=Path(temporary);record=json.loads((root/metadata).read_text());self.prepare(directory,record);target=directory/record['files'][0]['path'];target.write_text(target.read_text()+'\n// user change\n');before=target.read_bytes()
                with self.assertRaises(ValueError):helper.apply(directory,root/diff,root/metadata)
                self.assertEqual(target.read_bytes(),before)
    def testTamperedPatchCannotTouchTheSource(self):
        for diff,metadata in pairs:
            with self.subTest(patch=diff),tempfile.TemporaryDirectory()as temporary:
                directory=Path(temporary);record=json.loads((root/metadata).read_text());self.prepare(directory,record);changed=directory/'tampered.patch';changed.write_text((root/diff).read_text()+'\n# changed\n');target=directory/record['files'][0]['path'];before=target.read_bytes()
                with self.assertRaises(ValueError):helper.apply(directory,changed,root/metadata)
                self.assertEqual(target.read_bytes(),before)
unittest.main(argv=[__file__])
