#!/system/bin/sh
# Root 原生直跑执行器：在隔离的 Ubuntu 24.04 chroot 内直接以应用自身非特权 UID 运行原生 CLI，零 QEMU 开销。
set -eu
script_dir=$(cd "$(dirname "$0")" && pwd)
base=$(dirname "$script_dir")
case "$base" in
    /data/user/*/com.galiais.agent/files|/data/data/com.galiais.agent/files) ;;
    *) base=/data/user/0/com.galiais.agent/files ;;
esac
root="$base/linux"
test "$(id -u)" = 0
test "$#" -gt 0
test -f "$root/.galiais-ready"
test -f "$base/native-lib.path"
IFS= read -r nlib < "$base/native-lib.path"
test -d "$nlib" && test -f "$nlib/libbin_codex.so"
test -f "$nlib/libbin_codex_code_mode_host.so"

uid=$(stat -c %u "$base")
# 允许主用户及工作资料/多用户（appId = uid % 100000 位于 10000..99999）
app_id=$((uid % 100000))
test "$app_id" -ge 10000 && test "$app_id" -lt 100000

is_mounted() {
    local sub="$1"
    grep -qE " (/data/user/[0-9]+|/data/data)/com\.galiais\.agent/files/linux${sub} " /proc/mounts
}

setup_mounts() {
    mkdir -p "$root/proc" "$root/dev" "$root/tmp" "$root/opt/galiais-native" "$root/home/agent/workspace"
    chmod 1777 "$root/tmp"
    if ! is_mounted "/proc"; then
        mount -t proc -o nosuid,nodev,noexec,hidepid=2 proc "$root/proc" 2>/dev/null \
            || mount -t proc -o nosuid,nodev,noexec proc "$root/proc"
    fi
    if ! is_mounted "/dev"; then
        mount -t tmpfs -o mode=755,size=1m,nosuid,noexec tmpfs "$root/dev"
        for device in null:1:3 zero:1:5 random:1:8 urandom:1:9 tty:5:0; do
            name=${device%%:*}
            rest=${device#*:}
            major=${rest%:*}
            minor=${rest#*:}
            test -e "$root/dev/$name" || mknod "$root/dev/$name" c "$major" "$minor"
            chmod 666 "$root/dev/$name"
        done
        ln -sf /proc/self/fd "$root/dev/fd"
    fi
    mkdir -p "$root/dev/shm" "$root/dev/pts"
    if ! is_mounted "/dev/shm"; then
        mount -t tmpfs -o mode=1777,size=64m,nosuid,nodev,noexec tmpfs "$root/dev/shm" 2>/dev/null || chmod 1777 "$root/dev/shm"
    fi
    if ! is_mounted "/dev/pts"; then
        mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "$root/dev/pts"
    fi
    ln -sf pts/ptmx "$root/dev/ptmx"

    # Codex 按 /proc/self/exe 查找同目录的 Code Mode Host，文件挂载可保留入口路径。
    resources="$root/opt/galiais-codex/usr/lib/chatgpt/resources"
    codex_entry="$resources/codex"
    mkdir -p "$resources"
    if test -L "$codex_entry"; then rm -f "$codex_entry"; fi
    if ! test -e "$codex_entry"; then : > "$codex_entry"; fi
    test -f "$codex_entry"
    ln -sf /opt/galiais-native/libbin_codex_code_mode_host.so "$resources/codex-code-mode-host"

    prev_nlib=""
    if test -f "$root/opt/.galiais-nlib"; then
        IFS= read -r prev_nlib < "$root/opt/.galiais-nlib" || true
    fi
    if test "$prev_nlib" != "$nlib" || ! test "$root/opt/galiais-native/libbin_codex.so" -ef "$nlib/libbin_codex.so"; then
        if is_mounted "/opt/galiais-codex/usr/lib/chatgpt/resources/codex"; then
            umount "$codex_entry" 2>/dev/null || umount -l "$codex_entry" || true
        fi
        umount "$root/opt/galiais-native" 2>/dev/null || umount -l "$root/opt/galiais-native" 2>/dev/null || true
        mount -o bind "$nlib" "$root/opt/galiais-native"
        printf '%s\n' "$nlib" > "$root/opt/.galiais-nlib"
    fi
    if ! test "$codex_entry" -ef "$nlib/libbin_codex.so"; then
        if is_mounted "/opt/galiais-codex/usr/lib/chatgpt/resources/codex"; then
            umount "$codex_entry" 2>/dev/null || umount -l "$codex_entry" || true
        fi
        mount -o bind "$nlib/libbin_codex.so" "$codex_entry"
    fi
    if ! test "$codex_entry" -ef "$nlib/libbin_codex.so" || ! test -x "$codex_entry"; then
        echo 'Codex 启动组件加载失败' >&2
        exit 1
    fi
}

# 用原子目录锁串行化并发挂载，消除多通道同时启动时的 mount/mknod 竞态
lock_dir="$base/.linux-mount.lock"
lock_acquired=0
for _try in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if mkdir "$lock_dir" 2>/dev/null; then
        lock_acquired=1
        break
    fi
    sleep 0.1 2>/dev/null || sleep 1
done
if test "$lock_acquired" = 1; then
    trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT
    setup_mounts
    rmdir "$lock_dir" 2>/dev/null || true
    trap - EXIT
else
    setup_mounts
fi

exec chroot "$root" /usr/bin/setpriv --reuid="$uid" --regid="$uid" --groups=3003,"$uid" \
    /usr/bin/env -i \
    HOME=/home/agent \
    USER=agent \
    LOGNAME=agent \
    LANG=C.UTF-8 \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    PATH=/opt/galiais-codex/usr/lib/chatgpt/resources:/home/agent/.local/bin:/home/agent/.galiais/npm-global/bin:/usr/local/bin:/usr/bin:/bin \
    GALIAIS_RUNTIME_MODE=root \
    GALIAIS_LAUNCH_ID="${GALIAIS_LAUNCH_ID:-}" \
    GALIAIS_RUNTIME_GENERATION="$nlib" \
    /bin/sh -c 'cd /home/agent/workspace && exec "$@"' -- "$@"
