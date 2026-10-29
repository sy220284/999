#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
: "${ANDROID_NDK_ROOT:?请设置Android NDK28.2.13676358}"
[[ -f "$ANDROID_NDK_ROOT/source.properties" ]] && grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties" || { echo 'NDK版本不符' >&2; exit 2; }
abi="${1:-arm64-v8a}"
case "$abi" in
 arm64-v8a) compiler=aarch64-linux-android35-clang ;;
 x86_64) compiler=x86_64-linux-android35-clang ;;
 *) echo '仅支持arm64-v8a/x86_64' >&2;exit 2 ;;
esac
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/$compiler" -std=c11 -O2 -fPIE -pie -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 "$script_dir/补充源码/android_project_sandbox.c" -o "$output/libcodex_sandbox.so"
sha256sum "$output/libcodex_sandbox.so"
echo '仅生成条件式受限执行程序；仍需目标内核/SELinux/seccomp与真实逃逸用例验收，不支持时拒绝执行。'
