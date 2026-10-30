#!/usr/bin/env python3
"""仅操作临时源码副本，验证补丁边界；不构建、不执行目标程序。"""
import argparse,importlib.util,json,tempfile,unittest
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('patch',root/'应用CLI补丁.py');patch=importlib.util.module_from_spec(spec);spec.loader.exec_module(patch)
manifest=json.loads((root/'安卓CLI补丁来源.json').read_text())
class PatchTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.source=Path(self.temporary.name)
        for entry in manifest['files']:
            path=self.source/entry['path'];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((args.source/entry['path']).read_bytes())
    def tearDown(self):self.temporary.cleanup()
    def testAppliesOnceAndAcceptsOnlyTheIdenticalPatchedCopy(self):
        self.assertEqual(patch.apply(self.source),'applied');self.assertEqual(patch.apply(self.source),'already_applied')
        path=self.source/manifest['files'][0]['path'];path.write_text(path.read_text()+'\n// local change\n')
        with self.assertRaises(ValueError):patch.apply(self.source)
    def testMixedPartialStateIsRejectedWithoutChangingTheOtherFile(self):
        patch.apply(self.source)
        entry=manifest['files'][0];path=self.source/entry['path'];path.write_bytes((args.source/entry['path']).read_bytes());before=path.read_bytes()
        with self.assertRaises(ValueError):patch.apply(self.source)
        self.assertEqual(before,path.read_bytes())
    def testAlteredPatchIsRejectedBeforeAnySourceMutation(self):
        altered=self.source/'altered.patch';altered.write_bytes((root/'安卓CLI差异确认.patch').read_bytes()+b'\n')
        with self.assertRaises(ValueError):patch.apply(self.source,altered)
        for entry in manifest['files']:self.assertEqual((self.source/entry['path']).read_bytes(),(args.source/entry['path']).read_bytes())
unittest.main(argv=[__file__])
