#!/usr/bin/env bash
set -euo pipefail
# 恢复构建授权后执行：静态OpenSSL/libcurl与OpenSSH客户端。当前仅提交脚本。
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/.." && pwd)"
: "${ANDROID_NDK_ROOT:?需要NDK 28.2.13676358}"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ANDROID_NDK_ROOT/source.properties"
abi="${1:-arm64-v8a}"
case "$abi" in
 arm64-v8a) target=aarch64-linux-android; ssl_target=android-arm64 ;;
 x86_64) target=x86_64-linux-android; ssl_target=android-x86_64 ;;
 *) echo '只支持arm64-v8a或x86_64' >&2; exit 2 ;;
esac
work="${CODEX_ANDROID_NETWORK_BUILD_DIR:-$app_root/build/network}/$abi"
prefix="$work/prefix";ndk_bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$ndk_bin:$PATH"
export CC="$ndk_bin/${target}35-clang" CXX="$ndk_bin/${target}35-clang++" AR="$ndk_bin/llvm-ar" RANLIB="$ndk_bin/llvm-ranlib"
export CFLAGS='-O2 -fPIC' LDFLAGS='-Wl,-z,max-page-size=16384'
mkdir -p "$work" "$prefix"
openssl_source="$(python3 "$script_dir/准备开发环境.py" fetch openssl "$work/sources")"
curl_source="$(python3 "$script_dir/准备开发环境.py" fetch curl "$work/sources")"
ssh_source="$(python3 "$script_dir/准备开发环境.py" fetch openssh "$work/sources")"
(
 cd "$openssl_source"
 ./Configure "$ssl_target" -D__ANDROID_API__=35 no-shared no-tests no-module --prefix="$prefix" --libdir=lib
 make -j "${CODEX_ANDROID_JOBS:-4}"
 make install_sw
)
cmake -S "$curl_source" -B "$work/curl-build" \
 -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
 -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-35 -DCMAKE_BUILD_TYPE=Release \
 -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
 -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
 -DCURL_USE_OPENSSL=ON -DOPENSSL_USE_STATIC_LIBS=TRUE -DOPENSSL_ROOT_DIR="$prefix" \
 -DCURL_USE_LIBPSL=OFF -DCURL_USE_LIBSSH2=OFF -DCURL_USE_LIBSSH=OFF -DCURL_USE_GSSAPI=OFF \
 -DCURL_DISABLE_LDAP=ON -DCURL_DISABLE_LDAPS=ON -DUSE_LIBIDN2=OFF \
 -DCMAKE_EXE_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$work/curl-build" --parallel "${CODEX_ANDROID_JOBS:-4}"
cmake --install "$work/curl-build"
(
 cd "$ssh_source"
 ./configure --host="$target" --prefix=/codex-unavailable --sysconfdir=/codex-unavailable/etc \
  --with-ssl-dir="$prefix" --without-pam --without-selinux --without-kerberos5 \
  --without-libedit --without-zlib --with-sandbox=no --with-privsep-path=/codex-unavailable \
  --with-default-path=/system/bin --disable-strip \
  CPPFLAGS="-I$prefix/include" LDFLAGS="-L$prefix/lib -pie -Wl,-z,max-page-size=16384" \
  LIBS='-ldl -lm' ac_cv_func_getresuid=no ac_cv_func_getresgid=no
 make -j "${CODEX_ANDROID_JOBS:-4}" ssh ssh-keygen
)
output="$app_root/app/src/main/jniLibs/$abi"
mkdir -p "$output"
install -m 755 "$ssh_source/ssh" "$output/libcodex_ssh.so"
install -m 755 "$ssh_source/ssh-keygen" "$output/libcodex_ssh_keygen.so"
"$ndk_bin/llvm-readelf" -h -l -d "$output/libcodex_ssh.so"
licenses="$app_root/app/src/main/assets/licenses/network"
mkdir -p "$licenses"
install -m 644 "$openssl_source/LICENSE.txt" "$licenses/OpenSSL-LICENSE.txt"
install -m 644 "$curl_source/COPYING" "$licenses/Curl-COPYING"
install -m 644 "$ssh_source/LICENCE" "$licenses/OpenSSH-LICENCE"
cp "$script_dir/开发环境来源.json" "$licenses/来源.json"
echo '网络依赖已准备。Git网络构建请使用CODEX_ANDROID_GIT_NETWORK=1，设备/TLS/SSH尚需验收。'
