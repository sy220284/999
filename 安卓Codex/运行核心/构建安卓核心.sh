#!/usr/bin/env bash
set -euo pipefail
# 产物只来自上传源码对应的精确上游提交；不下载第三方预编译引擎。
upstream_commit=970111f6cb62cc02fe36a82ea7d71b37a2889c97
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
v8_mode="${CODEX_ANDROID_V8_MODE:-source}"
: "${ANDROID_NDK_ROOT:?请设置 ANDROID_NDK_ROOT，使用 NDK 28.2.13676358}"
[[ -f "$ANDROID_NDK_ROOT/source.properties" ]] && grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties" || { echo '需要 NDK 28.2.13676358' >&2; exit 2; }
abi="${1:-arm64-v8a}"
component="${2:-engine}"
case "$component" in
  engine) package=codex-app-server; program=codex-app-server; library=libcodex_engine.so ;;
  code-mode) package=codex-code-mode-host; program=codex-code-mode-host; library=libcodex_code_mode_host.so ;;
  *) echo '第二参数支持 engine 或 code-mode；code-mode 需要真实 Android V8 工具链，不能用空库替代。' >&2; exit 2 ;;
esac
case "$abi" in
  arm64-v8a) target=aarch64-linux-android; cc=aarch64-linux-android35-clang ;;
  x86_64) target=x86_64-linux-android; cc=x86_64-linux-android35-clang ;;
  *) echo '支持的 ABI：arm64-v8a、x86_64' >&2; exit 2 ;;
esac
# 不同 ABI 使用独立 Cargo/GN 输出，避免切换目标时复用错误的 V8 归档或绑定。
work_root="${CODEX_ANDROID_BUILD_DIR:-$app_root/build/original-engine-$v8_mode-$abi}"
source_root="$work_root/source"
# 默认从固定源码构建 V8；supply 模式用于复用已核对的同批 Android 库和绑定。
unset V8_FORCE_DEBUG V8_FROM_SOURCE RUSTY_V8_SKIP_DOWNLOAD DOCS_RS DENO_TRYBUILD RUSTY_V8_SRC_BINDING_URL RUSTY_V8_ARCHIVE RUSTY_V8_ARCHIVE_SHA256 RUSTY_V8_SRC_BINDING_PATH
case "$v8_mode" in
  source)
    v8_work="$work_root/v8-source"
    python3 "$script_dir/准备V8源码.py" --work "$v8_work" --ndk "$ANDROID_NDK_ROOT" --target "$target"
    source "$v8_work/environment.sh"
    cargo_v8=(--config "$v8_work/cargo-v8.toml")
    ;;
  supply)
    : "${CODEX_ANDROID_V8_MANIFEST:?请提供 V8 152.2.0 Android 供应清单}"
    v8_output="$work_root/v8/$target"
    python3 "$script_dir/核对V8供应.py" --manifest "$CODEX_ANDROID_V8_MANIFEST" --target "$target" --ndk "$ANDROID_NDK_ROOT" --output "$v8_output"
    export RUSTY_V8_ARCHIVE="$v8_output/librusty_v8.a"
    export RUSTY_V8_SRC_BINDING_PATH="$v8_output/src_binding.rs"
    export RUSTY_V8_ARCHIVE_SHA256="$(sha256sum "$RUSTY_V8_ARCHIVE" | cut -d ' ' -f 1)"
    cargo_v8=()
    ;;
  *) echo 'V8 模式支持 source / supply' >&2; exit 2 ;;
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
if [[ -f "$source_root/codex-rs/keyring-store/src/android.rs" ]] && ! cmp -s "$script_dir/补充源码/android_keyring.rs" "$source_root/codex-rs/keyring-store/src/android.rs"; then
  echo '构建副本的 Android 凭证后端与当前版本不同，请指定新的 CODEX_ANDROID_BUILD_DIR；不会覆盖本地修改。' >&2
  exit 2
fi
install -m 644 "$script_dir/补充源码/android_keyring.rs" "$source_root/codex-rs/keyring-store/src/android.rs"
# Android验证提供者单独补丁；只改构建副本，绝不修改固定原始源码。
if git -C "$source_root" apply --check "$script_dir/安卓用户验证.patch"; then
  git -C "$source_root" apply "$script_dir/安卓用户验证.patch"
else
  git -C "$source_root" apply --reverse --check "$script_dir/安卓用户验证.patch"
fi
if [[ -f "$source_root/codex-rs/user-verification/src/platform_android.rs" ]] && ! cmp -s "$script_dir/补充源码/android_user_verification.rs" "$source_root/codex-rs/user-verification/src/platform_android.rs"; then
  echo '构建副本的Android验证模块不同，请另选构建目录；不会覆盖修改。' >&2
  exit 2
fi
install -m 644 "$script_dir/补充源码/android_user_verification.rs" "$source_root/codex-rs/user-verification/src/platform_android.rs"
if [[ "$v8_mode" == source ]]; then
  # local patch 只改变 v8 来源；其他包版本和校验和沿用固定上游锁文件。
  python3 - "$source_root/codex-rs/Cargo.lock" <<'PYLOCK'
import re, sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text()
blocks = text.split('[[package]]')
for index, block in enumerate(blocks):
    if re.search(r'^name = "v8"$', block, re.M):
        if not re.search(r'^version = "152\.2\.0"$', block, re.M):
            raise ValueError('V8 锁定版本不匹配')
        blocks[index] = re.sub(r'^(source|checksum) = .*\n', '', block, flags=re.M)
path.write_text('[[package]]'.join(blocks))
PYLOCK
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
cargo "${cargo_v8[@]}" build --manifest-path "$source_root/codex-rs/Cargo.toml" -p "$package" \
  --bin "$program" --target "$target" --profile dev-small --locked
binary="$CARGO_TARGET_DIR/$target/dev-small/$program"
"$ndk_bin/llvm-readelf" -l "$binary"
"$ndk_bin/llvm-readelf" -d "$binary"
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
# Android 只允许从安装时提取的 nativeLibraryDir 执行随包原生程序。
install -m 755 "$binary" "$output/$library"
sha256sum "$output/$library"
echo '仅完成构建；仍须在对应 ABI 的 Android 17 设备上验证启动、协议、工具与取消。'
