#!/usr/bin/env python3
"""纯文件夹/归档测试：不下载、不构建、不启动解释器或目标程序。"""
from pathlib import Path
import importlib.util,io,tarfile,tempfile,unittest,zipfile
spec=importlib.util.spec_from_file_location('prepare',Path(__file__).with_name('准备开发环境.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ArchiveTests(unittest.TestCase):
    def archive(self,root,entries):
        path=root/'source.tar'
        with tarfile.open(path,'w')as archive:
            for name,kind,value in entries:
                entry=tarfile.TarInfo(name)
                if kind=='file':entry.size=len(value);archive.addfile(entry,io.BytesIO(value))
                else:entry.type=tarfile.SYMTYPE;entry.linkname=value;archive.addfile(entry)
        return path
    def test_relative_file_and_internal_link_are_preserved(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);source=self.archive(root,[('prefix/a','file',b'data'),('prefix/b','link','a')])
            m.unpack(source,root/'out');self.assertEqual((root/'out/prefix/b').read_bytes(),b'data')
    def test_parent_escape_and_special_files_are_rejected(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);source=self.archive(root,[('../escape','file',b'bad')])
            with self.assertRaises(ValueError):m.unpack(source,root/'out')
            self.assertFalse((root/'out').exists())
    def test_link_chain_cannot_escape_after_normalization(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);source=self.archive(root,[('dir/x/file','file',b'a'),('dir/up','link','..'),('escape','link','dir/up/../../outside')])
            with self.assertRaises(ValueError):m.unpack(source,root/'out')
    def test_duplicate_paths_cannot_overwrite_prior_files(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);source=self.archive(root,[('same','file',b'a'),('same','file',b'b')])
            with self.assertRaises(ValueError):m.unpack(source,root/'out')
    def test_bundle_receipt_matches_the_actual_data_and_archive(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);source=root/'data';source.mkdir();(source/'item').write_bytes(b'value')
            result=m.bundle(source,root/'bundle.zip')
            self.assertEqual(result['sha256'],m.digest(root/'bundle.zip'));self.assertEqual(result['files']['item']['sha256'],m.digest(source/'item'))
            with zipfile.ZipFile(root/'bundle.zip')as archive:self.assertEqual(archive.read('item'),b'value')
    def test_source_directory_is_never_used_as_the_archive_destination(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp)
            with self.assertRaises(ValueError):m.bundle(root,root/'bad.zip')
if __name__=='__main__':unittest.main()
