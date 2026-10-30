#!/usr/bin/env bash
set -euo pipefail
# 默认本地模式；CODEX_ANDROID_GIT_NETWORK=1增加固定OpenSSL/libcurl及独立OpenSSH。
# 当前阶段禁止执行此脚本；后续恢复构建后再核验 ELF/设备行为。
git_commit=a018953688f1b10bddf91bff8747068f5f4746a4 # 官方 v2.56.0
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
work_root="${CODEX_ANDROID_GIT_BUILD_DIR:-$app_root/build/android-git}"
: "${ANDROID_NDK_ROOT:?请设置 ANDROID_NDK_ROOT}"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties" || { echo '需要 NDK 28.2.13676358' >&2; exit 2; }
abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a) target=aarch64-linux-android; machine=aarch64 ;;
  x86_64) target=x86_64-linux-android; machine=x86_64 ;;
  *) echo '支持 arm64-v8a、x86_64' >&2; exit 2 ;;
esac
ndk_bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
compiler="$ndk_bin/${target}35-clang"
[[ -x "$compiler" ]] || { echo '需要 Linux x86_64 构建机' >&2; exit 2; }
# 每个 ABI 独立源码树，避免交叉复用 object；不覆盖已有本地修改。
source_root="$work_root/$abi/source"
mkdir -p "$(dirname -- "$source_root")"
if [[ ! -d "$source_root/.git" ]]; then
  git init "$source_root"
  git -C "$source_root" remote add origin https://github.com/git/git.git
fi
[[ -z "$(git -C "$source_root" status --porcelain)" ]] || { echo '源码有修改，请使用新的 CODEX_ANDROID_GIT_BUILD_DIR' >&2; exit 2; }
git -C "$source_root" fetch --depth 1 origin "$git_commit"
git -C "$source_root" checkout --detach FETCH_HEAD
[[ "$(git -C "$source_root" rev-parse HEAD)" == "$git_commit" ]]
network="${CODEX_ANDROID_GIT_NETWORK:-0}"
network_args=(NO_CURL=YesPlease)
targets=(git)
if [[ "$network" == 1 ]]; then
  "$script_dir/构建安卓网络工具.sh" "$abi"
  network_prefix="${CODEX_ANDROID_NETWORK_BUILD_DIR:-$app_root/build/network}/$abi/prefix"
  network_args=("CURL_CONFIG=$network_prefix/bin/curl-config" "CURL_CFLAGS=-I$network_prefix/include" "CURL_LDFLAGS=$network_prefix/lib/libcurl.a $network_prefix/lib/libssl.a $network_prefix/lib/libcrypto.a -lz -ldl -lm")
  targets+=(git-remote-http)
fi
# 不执行目标程序。libz是NDK公开系统依赖。
# SHA-1 保留上游默认碰撞检测实现，SHA-256 使用上游内置实现；不放入假 OpenSSL。
make -C "$source_root" -j "${CODEX_ANDROID_JOBS:-4}" "${targets[@]}" "${network_args[@]}" \
  CC="$compiler" AR="$ndk_bin/llvm-ar" \
  uname_S=Linux uname_R=android uname_M="$machine" HOST_CPU="$machine" \
  CFLAGS='-O2 -fPIE' LDFLAGS='-pie -Wl,-z,max-page-size=16384' \
  prefix=/codex-unavailable bindir=/codex-unavailable/bin \
  gitexecdir=/codex-unavailable/libexec/git-core \
  SHELL_PATH=/system/bin/sh DEFAULT_PAGER=cat DEFAULT_EDITOR=vi \
  NO_EXPAT=YesPlease NO_OPENSSL=YesPlease \
  NO_GETTEXT=YesPlease NO_ICONV=YesPlease NO_RUST=YesPlease \
  NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease \
  NO_NSEC=YesPlease NO_INSTALL_HARDLINKS=YesPlease \
  NO_REGEX=NeedsStartEnd PTHREAD_LIBS= \
  CSPRNG_METHOD=getrandom
binary="$source_root/git"
"$ndk_bin/llvm-readelf" -h -l -d "$binary"
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
install -m 755 "$binary" "$output/libcodex_git.so"
if [[ "$network" == 1 ]]; then
  install -m 755 "$source_root/git-remote-http" "$output/libcodex_git_remote_http.so"
  "$ndk_bin/llvm-readelf" -h -l -d "$output/libcodex_git_remote_http.so"
fi
licenses="$app_root/app/src/main/assets/licenses/git"
mkdir -p "$licenses"
install -m 644 "$source_root/COPYING" "$licenses/COPYING"
install -m 644 "$source_root/sha1dc/LICENSE.txt" "$licenses/sha1collisiondetection-LICENSE.txt"
# GPL 源码的固定获取位置、版本、准确构建入口随应用保存；发布前另核对完整对应源码交付。
python3 - "$git_commit" "$licenses/来源.json" "$network" <<'MANIFEST'
import json,sys
from pathlib import Path
Path(sys.argv[2]).write_text(json.dumps({"version":"2.56.0","commit":sys.argv[1],"repository":"https://github.com/git/git.git","build":"运行核心/构建安卓Git.sh","capability":"local-builtins-and-network"if sys.argv[3]=="1"else"local-builtins-only","network":sys.argv[3]=="1"}, ensure_ascii=False, indent=2)+"\n")
MANIFEST
sha256sum "$output/libcodex_git.so"
echo '已生成所选Git产物；尚须验收仓库、worktree、取消、16KB、TLS/SSH主机校验及认证。'
