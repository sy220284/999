#!/usr/bin/env bash
set -euo pipefail
# 原版 scripts/codex_package/rg 固定 15.2.0；此入口仅构建 Android Bionic 版本。
rg_commit=e89fff89ac9af12e8d4ce9d5fd07beb408ca730f
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
work_root="${CODEX_ANDROID_RG_BUILD_DIR:-$app_root/build/android-rg}"
: "${ANDROID_NDK_ROOT:?请设置 ANDROID_NDK_ROOT}"
[[ -f "$ANDROID_NDK_ROOT/source.properties" ]] && grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties" || { echo '需要 NDK 28.2.13676358' >&2; exit 2; }
abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a) target=aarch64-linux-android; cc=aarch64-linux-android35-clang ;;
  x86_64) target=x86_64-linux-android; cc=x86_64-linux-android35-clang ;;
  *) echo '支持 arm64-v8a、x86_64' >&2; exit 2 ;;
esac
ndk_bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$ndk_bin/$cc" ]] || { echo '需要 Linux x86_64 构建机' >&2; exit 2; }
source_root="$work_root/source"
mkdir -p "$work_root"
if [[ ! -d "$source_root/.git" ]]; then
  git init "$source_root"
  git -C "$source_root" remote add origin https://github.com/BurntSushi/ripgrep.git
fi
# 不覆盖任何本地修改；错误的源目录也必须拒绝。
[[ -z "$(git -C "$source_root" status --porcelain)" ]] || { echo '源码目录有修改，请换用新的 CODEX_ANDROID_RG_BUILD_DIR' >&2; exit 2; }
git -C "$source_root" fetch --depth 1 origin "$rg_commit"
git -C "$source_root" checkout --detach FETCH_HEAD
[[ "$(git -C "$source_root" rev-parse HEAD)" == "$rg_commit" ]]
export RUSTUP_TOOLCHAIN=1.99.1
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
rustup target add --toolchain "$RUSTUP_TOOLCHAIN" "$target"
cargo_target="${target^^}"; cargo_target="${cargo_target//-/_}"
export "CARGO_TARGET_${cargo_target}_LINKER=$ndk_bin/$cc"
export "CARGO_TARGET_${cargo_target}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
export "CC_${target//-/_}=$ndk_bin/$cc"
export "AR_${target//-/_}=$ndk_bin/llvm-ar"
export CARGO_TARGET_DIR="$work_root/target"
# pcre2-sys 0.2.10 的受支持开关：跳过宿主 pkg-config，编译锁文件中的内置 PCRE2。
# 上游对 aarch64-linux-android 自动禁用 JIT；保留解释执行，不注入空库。
export PCRE2_SYS_STATIC=1
cargo build --manifest-path "$source_root/Cargo.toml" --locked --release --target "$target" --bin rg --features pcre2
binary="$CARGO_TARGET_DIR/$target/release/rg"
"$ndk_bin/llvm-readelf" -h -l -d "$binary"
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
install -m 755 "$binary" "$output/libcodex_rg.so"
# 原始许可证跟随最终应用资源，后续打包时保留。
license_output="$app_root/app/src/main/assets/licenses/ripgrep"
mkdir -p "$license_output"
for license in COPYING LICENSE-MIT UNLICENSE; do install -m 644 "$source_root/$license" "$license_output/$license"; done
cargo metadata --manifest-path "$source_root/Cargo.toml" --locked --format-version 1 --features pcre2 --filter-platform "$target" > "$work_root/metadata-$target.json"
python3 "$app_root/工具/整理搜索许可证.py" --metadata "$work_root/metadata-$target.json" --output "$app_root/app/src/main/assets/licenses"
sha256sum "$output/libcodex_rg.so"
echo '仅构建搜索程序；仍需设备验证搜索、取消、大目录及 16 KB 页兼容性。'
