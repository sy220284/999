#!/usr/bin/env bash
set -euo pipefail
# 产物只来自上传源码对应的精确上游提交；不下载第三方预编译引擎。
upstream_commit=970111f6cb62cc02fe36a82ea7d71b37a2889c97
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
work_root="${CODEX_ANDROID_BUILD_DIR:-$app_root/build/original-engine}"
source_root="$work_root/source"
: "${ANDROID_NDK_ROOT:?请设置 ANDROID_NDK_ROOT，使用 NDK 28.2.13676358}"
abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a) target=aarch64-linux-android; cc=aarch64-linux-android35-clang ;;
  x86_64) target=x86_64-linux-android; cc=x86_64-linux-android35-clang ;;
  *) echo '支持的 ABI：arm64-v8a、x86_64' >&2; exit 2 ;;
esac
mkdir -p "$work_root"
if [[ ! -d "$source_root/.git" ]]; then
  git init "$source_root"
  git -C "$source_root" remote add origin https://github.com/openai/codex.git
fi
git -C "$source_root" fetch --depth 1 origin "$upstream_commit"
git -C "$source_root" checkout --detach FETCH_HEAD
[[ "$(git -C "$source_root" rev-parse HEAD)" == "$upstream_commit" ]]
# 不重置已有本地修改；重复执行时确认补丁已经完整应用。
if git -C "$source_root" apply --check "$script_dir/安卓依赖适配.patch"; then
  git -C "$source_root" apply "$script_dir/安卓依赖适配.patch"
else
  git -C "$source_root" apply --reverse --check "$script_dir/安卓依赖适配.patch"
fi
export RUSTUP_TOOLCHAIN=1.99.1
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
rustup target add --toolchain "$RUSTUP_TOOLCHAIN" "$target"
ndk_bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$ndk_bin/$cc" ]] || { echo '此脚本需要 Linux x86_64 构建机与指定 NDK' >&2; exit 2; }
export CARGO_TARGET_DIR="$work_root/target"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
cargo_target="${target^^}"
cargo_target="${cargo_target//-/_}"
export "CARGO_TARGET_${cargo_target}_LINKER=$ndk_bin/$cc"
export "CARGO_TARGET_${cargo_target}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
export "CC_${target//-/_}=$ndk_bin/$cc"
export "AR_${target//-/_}=$ndk_bin/llvm-ar"
export PATH="$ndk_bin:$PATH"
cargo build --manifest-path "$source_root/codex-rs/Cargo.toml" -p codex-app-server \
  --bin codex-app-server --target "$target" --profile dev-small --locked
binary="$CARGO_TARGET_DIR/$target/dev-small/codex-app-server"
"$ndk_bin/llvm-readelf" -l "$binary"
"$ndk_bin/llvm-readelf" -d "$binary"
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
# Android 只允许从安装时提取的 nativeLibraryDir 执行随包原生程序。
install -m 755 "$binary" "$output/libcodex_engine.so"
sha256sum "$output/libcodex_engine.so"
echo '仅完成构建；仍须在对应 ABI 的 Android 17 设备上验证启动、协议、工具与取消。'
