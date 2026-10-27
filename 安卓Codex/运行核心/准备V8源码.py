#!/usr/bin/env python3
"""为原版 Cargo 构建准备固定的 Android V8 源码与工具，不生成占位库。
仅在明确运行构建入口后调用；本轮没有执行。构建机需要 Python 3.12+ 和 libclang。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import urllib.request

REVISION = '2768994f664e8a6e3aba27503606c58339136e2a'
EXTRA = {
    'third_party/android_platform': ('https://chromium.googlesource.com/chromium/src/third_party/android_platform.git', 'e3919359f2387399042d31401817db4a02d756ec'),
    'third_party/catapult': ('https://chromium.googlesource.com/catapult.git', '74513188f5cd39f9edb16236ea3a49ed5b3cb3c6'),
}
RUST_URL = 'https://storage.googleapis.com/chromium-browser-clang/Linux_x64/rust-toolchain-b998449636a48e2c4a362809085b600a0174e1f2-5-llvmorg-23-init-19482-g53d18800.tar.xz'
RUST_SHA = '1753392d6fe791a5ce743bfc70f45e151ee27bcccede516fb221b023f2c06a05'
CLANG_URL = 'https://storage.googleapis.com/chromium-browser-clang/Linux_x64/clang-llvmorg-23-init-19482-g53d18800-1.tar.xz'
CLANG_SHA = 'e22e06c05fe1657f48f988b15804b8226e691addb00abba5b984a5c99ac98c42'


def run(*args, cwd=None):
    subprocess.run(list(map(str, args)), cwd=cwd, check=True)


def checkout(url, revision, dest):
    dest.mkdir(parents=True, exist_ok=True)
    if not (dest / '.git').exists():
        run('git', 'init', dest)
    status = subprocess.check_output(['git', '-C', str(dest), 'status', '--porcelain']).strip()
    if status:
        raise ValueError(f'源码目录有本地修改，不覆盖：{dest}')
    run('git', '-C', dest, 'fetch', '--depth', '1', url, revision)
    run('git', '-C', dest, 'checkout', '--detach', 'FETCH_HEAD')
    actual = subprocess.check_output(['git', '-C', str(dest), 'rev-parse', 'HEAD']).decode().strip()
    if actual != revision:
        raise ValueError(f'源码提交不匹配：{dest}')


def archive(url, pin, cache, dest):
    package = cache / (pin + '.tar.xz')
    if not package.exists():
        with urllib.request.urlopen(url, timeout=60) as response, package.open('wb') as output:
            shutil.copyfileobj(response, output)
    with package.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != pin:
            raise ValueError(f'工具包校验失败：{package}')
    stamp = dest / '.android-v8-sha256'
    if not stamp.exists() or stamp.read_text().strip() != pin:
        dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(package) as data:
            data.extractall(dest, filter='data')
        stamp.write_text(pin)


def prepare(work, ndk, target):
    if not os.environ.get('LIBCLANG_PATH'):
        raise ValueError('请先设置构建机 LIBCLANG_PATH，供 bindgen 使用')
    source = work / 'rusty_v8'
    cache = work / 'downloads'
    cache.mkdir(parents=True, exist_ok=True)
    checkout('https://github.com/denoland/rusty_v8.git', REVISION, source)
    exclude = source / '.git/info/exclude'
    managed = '\n'.join('/' + path + '/' for path in [*EXTRA, 'third_party/android_ndk', 'third_party/rust-toolchain'])
    if managed not in exclude.read_text():
        with exclude.open('a') as stream:
            stream.write('\n' + managed + '\n')
    # Gitlink 固定各子模块的提交；不执行浮动分支更新。
    run('git', 'submodule', 'sync', '--recursive', cwd=source)
    run('git', 'submodule', 'update', '--init', '--recursive', '--depth', '1', cwd=source)
    for name, (url, revision) in EXTRA.items():
        checkout(url, revision, source / name)
    ndk_link = source / 'third_party/android_ndk'
    if ndk_link.is_symlink():
        ndk_link.unlink()
    if ndk_link.exists():
        raise ValueError('源码目录含未受本脚本管理的 NDK，请使用新的构建目录')
    ndk_link.symlink_to(ndk.resolve(), target_is_directory=True)
    archive(RUST_URL, RUST_SHA, cache, source / 'third_party/rust-toolchain')
    # 上游脚本检查原始 URL sentinel，以此跳过未校验的重复下载。
    (source / 'third_party/rust-toolchain/.rusty_v8_version').write_text(RUST_URL)
    clang = work / 'clang'
    archive(CLANG_URL, CLANG_SHA, cache, clang)
    # GN/Ninja 版本来自同一个 V8 DEPS：GN git 641ace93，Ninja 1.12.1.chromium.4。
    tools = work / 'gn-ninja'
    if not (tools / 'gn/gn').exists() or not (tools / 'ninja/ninja').exists():
        run('python3', source / 'tools/ninja_gn_binaries.py', '--dir', tools, cwd=source)
    libclang = os.environ.get('LIBCLANG_PATH')
    if not libclang:
        raise ValueError('请设置构建机 LIBCLANG_PATH，供 bindgen 使用；不能用 Android 目标库充当宿主 libclang')
    config = work / 'cargo-v8.toml'
    config.write_text('[patch.crates-io]\nv8 = { path = ' + json.dumps(str(source)) + ' }\n')
    exports = {
        'V8_FROM_SOURCE': '1', 'CLANG_BASE_PATH': str(clang), 'GN': str(tools / 'gn/gn'), 'NINJA': str(tools / 'ninja/ninja'),
        'LIBCLANG_PATH': libclang,
        'GN_ARGS': 'is_debug=false use_custom_libcxx=true v8_enable_sandbox=true v8_enable_pointer_compression=true treat_warnings_as_errors=false',
        'BINDGEN_EXTRA_CLANG_ARGS_' + target.replace('-', '_'): '--sysroot=' + str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot') + ' -D__ANDROID_API__=35',
    }
    (work / 'environment.sh').write_text('\n'.join('export ' + key + '=' + shlex.quote(value) for key, value in exports.items()) + '\n')
    tool_digests = {}
    for path in [tools / 'gn/gn', tools / 'ninja/ninja']:
        with path.open('rb') as stream:
            tool_digests[str(path)] = hashlib.file_digest(stream, 'sha256').hexdigest()
    modules = subprocess.check_output(['git', '-C', str(source), 'submodule', 'status', '--recursive']).decode()
    (work / 'source-provenance.json').write_text(json.dumps({'rusty_v8': REVISION, 'extra': EXTRA, 'submodules': modules, 'rust_sha256': RUST_SHA, 'clang_sha256': CLANG_SHA, 'target': target, 'ndk': '28.2.13676358', 'gn': '641ace93dd9560e75e7add0d08f77b446fbb3b78', 'ninja': '1.12.1.chromium.4', 'tool_binary_sha256': tool_digests}, ensure_ascii=False, indent=2) + '\n')
    print('已准备固定源码与工具；V8 尚须 Cargo/GN 编译和 Android 验证。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--target', choices=['aarch64-linux-android', 'x86_64-linux-android'], required=True)
    args = parser.parse_args()
    prepare(args.work.resolve(), args.ndk.resolve(), args.target)
