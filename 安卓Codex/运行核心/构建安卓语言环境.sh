#!/usr/bin/env bash
set -euo pipefail
# 当前阶段只提交源码，不执行。所有程序从安装目录执行；数据文件以校验ZIP随包。
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
: "${ANDROID_NDK_ROOT:?需要NDK 28.2.13676358}"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties"
abi="${1:-arm64-v8a}"
case "$abi" in
 arm64-v8a) target=aarch64-linux-android; arch=arm64 ;;
 x86_64) target=x86_64-linux-android; arch=x86_64 ;;
 *) echo '支持arm64-v8a或x86_64' >&2; exit 2 ;;
esac
work="${CODEX_ANDROID_LANGUAGE_BUILD_DIR:-$app_root/build/languages}/$abi"
ndk_bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$ndk_bin/${target}35-clang" CXX="$ndk_bin/${target}35-clang++"
export AR="$ndk_bin/llvm-ar" RANLIB="$ndk_bin/llvm-ranlib"
export PATH="$ndk_bin:$PATH"
export LDFLAGS='-Wl,-z,max-page-size=16384'
export CFLAGS='-O2 -fPIC' CXXFLAGS='-O2 -fPIC'
mkdir -p "$work"
node_source="$(python3 "$script_dir/准备开发环境.py" fetch node "$work/sources")"
python_source="$(python3 "$script_dir/准备开发环境.py" fetch "python-$abi" "$work/sources")"
(
 cd "$node_source"
 python3 android_configure.py "$ANDROID_NDK_ROOT" 35 "$arch"
 [[ -f config.gypi ]] || { echo 'Node配置未成功' >&2; exit 2; }
 make -j "${CODEX_ANDROID_JOBS:-4}" node
)
output="$app_root/app/src/main/jniLibs/$abi"
assets="$app_root/app/src/main/assets/runtimes"
mkdir -p "$output" "$assets"
install -m 755 "$node_source/out/Release/node" "$output/libcodex_node.so"
# 版本化官方Android嵌入包的prefix，不能使用桌面Linux Python。
prefix="$python_source/prefix"
[[ -d "$prefix/include/python3.14" && -f "$prefix/lib/libpython3.14.so" ]]
find -L "$prefix/lib" -maxdepth 1 -type f \( -name 'libpython*.so' -o -name 'lib*_python.so' \) -exec install -m 755 '{}' "$output/" \;
"$CC" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 '-Wl,-rpath,$ORIGIN' \
 -I"$prefix/include/python3.14" "$script_dir/补充源码/python_main.c" -L"$prefix/lib" -lpython3.14 -ldl -lm -llog -o "$output/libcodex_python.so"
"$CC" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 "$script_dir/补充源码/npm_main.c" -o "$output/libcodex_npm.so"
# Node可能链接NDK C++共享运行库，使用同批NDK版本，不引入桌面库。
install -m 755 "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$target/libc++_shared.so" "$output/libc++_shared.so"
python3 - "$node_source" "$prefix" "$assets" "$abi" "$script_dir" <<'PYBUNDLE'
from pathlib import Path
import importlib.util,json,sys
spec=importlib.util.spec_from_file_location('source',Path(sys.argv[5])/'准备开发环境.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
node,prefix,assets=map(Path,sys.argv[1:4]);abi=sys.argv[4]
node_data=module.bundle(node/'deps/npm',assets/f'node-{abi}.zip');node_data.update(version='24.22.1',kind='node')
python_data=module.bundle(prefix,assets/f'python-{abi}.zip');python_data.update(version='3.14.10',kind='python')
(assets/f'manifest-{abi}.json').write_text(json.dumps({'abi':abi,'bundles':[node_data,python_data]},ensure_ascii=False,indent=2)+'\n')
PYBUNDLE
licenses="$app_root/app/src/main/assets/licenses/languages"
mkdir -p "$licenses"
install -m 644 "$node_source/LICENSE" "$licenses/Node-LICENSE"
python3 - "$python_source" "$licenses/Python" <<'PYLICENSE'
from pathlib import Path
import shutil,sys
source,target=map(Path,sys.argv[1:]);count=0
for entry in source.rglob('*'):
 if entry.is_file() and any(word in entry.name.lower() for word in ('license','copying','notice')):
  entry.resolve().relative_to(source.resolve())
  destination=target/entry.relative_to(source);destination.parent.mkdir(parents=True,exist_ok=True)
  shutil.copyfile(entry,destination);count+=1
if not count:raise ValueError('官方Android包没有找到许可文件，不能继续交付')
PYLICENSE
cp "$script_dir/开发环境来源.json" "$licenses/来源.json"
for name in libcodex_node.so libcodex_python.so libcodex_npm.so libpython3.14.so; do
 "$ndk_bin/llvm-readelf" -h -l -d "$output/$name"
done
echo '已写入语言环境产物和资产；仍需双ABI、16KB、stdio、TLS、npm脚本及原生扩展设备验收。'
