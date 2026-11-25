#!/system/bin/sh
# 非 Root PRoot 原生直跑入口：将 APK nativeLibraryDir 挂载为 /opt/galiais-native，零拷贝直跑原生 CLI。
set -eu
base="${GALIAIS_APP_FILES:-/data/user/0/com.galiais.agent/files}"
cache="${GALIAIS_APP_CACHE:-/data/user/0/com.galiais.agent/cache}"
nlib="${GALIAIS_NATIVE_LIB:?missing GALIAIS_NATIVE_LIB}"
root="$base/linux-proot"
mode_root=0
if test "${1:-}" = --root; then
    mode_root=1
    shift
fi
test "$#" -gt 0
test -x "$nlib/libproot.so"
test -x "$nlib/libproot-loader.so"
test -f "$nlib/libbin_codex.so" && test -f "$nlib/libbin_codex_code_mode_host.so"
test -f "$root/.galiais-ready"
mkdir -p "$cache/proot-tmp" "$root/opt/galiais-native" "$root/home/agent/workspace"
# 文件映射保留 Codex 的入口目录，使其能找到同目录的 Code Mode Host。
codex_path=/opt/galiais-codex/usr/lib/chatgpt/resources/codex
resources="$root/opt/galiais-codex/usr/lib/chatgpt/resources"
mkdir -p "$resources"
if test -L "$root$codex_path"; then rm -f "$root$codex_path"; fi
if ! test -e "$root$codex_path"; then : > "$root$codex_path"; fi
test -f "$root$codex_path"
ln -sf /opt/galiais-native/libbin_codex_code_mode_host.so "$resources/codex-code-mode-host"
export LD_LIBRARY_PATH="$nlib"
export PROOT_LOADER="$nlib/libproot-loader.so"
export PROOT_LOADER_32="$nlib/libproot-loader32.so"
export PROOT_TMP_DIR="$cache/proot-tmp"
export TMPDIR="$cache/proot-tmp"
if test "$mode_root" = 1; then
    exec "$nlib/libproot.so" --link2symlink -0 -r "$root" -b /dev -b /proc -b /sys -b "$nlib:/opt/galiais-native" -b "$nlib/libbin_codex.so:$codex_path" -w / \
        /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt GALIAIS_RUNTIME_MODE=proot GALIAIS_RUNTIME_GENERATION="$nlib" "$@"
else
    exec "$nlib/libproot.so" --link2symlink -i 60000:60000 -r "$root" -b /dev -b /proc -b /sys -b "$nlib:/opt/galiais-native" -b "$nlib/libbin_codex.so:$codex_path" -w /home/agent/workspace \
        /usr/bin/env -i HOME=/home/agent USER=agent LOGNAME=agent PATH=/opt/galiais-codex/usr/lib/chatgpt/resources:/home/agent/.local/bin:/home/agent/.galiais/npm-global/bin:/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt GALIAIS_RUNTIME_MODE=proot GALIAIS_RUNTIME_GENERATION="$nlib" "$@"
fi
