#!/usr/bin/env python3
"""核对真实 Android 静态库和同批绑定，复制到固定路径；不执行/构建这些产物。
清单是供应方声明，哈希与 ELF 架构核对不证明 ABI、功能或 V8 sandbox 正确。
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


def prepare(manifest_path, target, ndk, output):
    manifest_path = Path(manifest_path).resolve(strict=True)
    data = json.loads(manifest_path.read_text())
    required = {
        'version': '152.2.0',
        'source_commit': '2768994f664e8a6e3aba27503606c58339136e2a',
        'target': target,
        'ndk': '28.2.13676358',
        'profile': 'release',
    }
    for key, value in required.items():
        if data.get(key) != value:
            raise ValueError(f'V8 供应清单 {key} 不匹配，需要 {value}')
    expected_features = {'use_custom_libcxx', 'v8_enable_pointer_compression', 'v8_enable_sandbox'}
    if set(data.get('features', [])) != expected_features:
        raise ValueError('V8 features 必须匹配当前 Cargo 默认 libc++、pointer compression 与 sandbox')
    if not data.get('build_provenance'):
        raise ValueError('缺少构建来源记录')
    expected_gn = {
        'target_os': 'android',
        'target_cpu': {'aarch64-linux-android': 'arm64', 'x86_64-linux-android': 'x64'}[target],
        'use_custom_libcxx': True,
        'v8_enable_pointer_compression': True,
        'v8_enable_sandbox': True,
        'is_debug': False,
    }
    gn = data.get('gn_args', {})
    if not isinstance(gn, dict) or any(gn.get(key) != value for key, value in expected_gn.items()):
        raise ValueError('GN 声明必须匹配 Android 架构、release、libc++、pointer compression 和 sandbox')
    artifacts = {}
    for key in ('archive', 'bindings'):
        record = data[key]
        pin = record['sha256']
        if not re.fullmatch('[0-9a-f]{64}', pin):
            raise ValueError(f'{key} 缺少合法 SHA-256')
        path = (manifest_path.parent / record['path']).resolve(strict=True)
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f'{key} 不是有效文件')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != pin:
            raise ValueError(f'{key} 校验和不匹配')
        artifacts[key] = path
    archive = artifacts['archive']
    with archive.open('rb') as stream:
        if stream.read(8) != b'!<arch>\n':
            raise ValueError('需要完整的非 thin 未压缩 .a 归档，不能使用占位库或 Linux .so')
    readelf = Path(ndk) / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    result = subprocess.run([str(readelf), '-h', str(archive)], capture_output=True, text=True, check=True)
    machines = re.findall(r'^\s*Machine:\s*(.+)$', result.stdout, re.MULTILINE)
    expected_machine = {'aarch64-linux-android': 'AArch64', 'x86_64-linux-android': 'Advanced Micro Devices X86-64'}[target]
    if not machines or any(machine.strip() != expected_machine for machine in machines):
        raise ValueError('V8 归档架构不匹配；声明 Android target 不能替代 Bionic/ABI 设备验证')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    for key, filename in [('archive', 'librusty_v8.a'), ('bindings', 'src_binding.rs')]:
        staged = output / filename
        shutil.copyfile(artifacts[key], staged)
        with staged.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != data[key]['sha256']:
                staged.unlink()
                raise ValueError(f'{key} 复制后校验失败，请检查供应文件是否被并发修改')
    shutil.copyfile(manifest_path, output / 'supply.json')
    print('V8 来源声明、双文件 SHA-256 和 ELF 架构已核对；ABI/功能仍未验证。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--target', choices=['aarch64-linux-android', 'x86_64-linux-android'], required=True)
    parser.add_argument('--ndk', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    prepare(args.manifest, args.target, args.ndk, args.output)
