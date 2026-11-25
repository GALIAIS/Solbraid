#!/system/bin/sh
# 以独立会话启动常驻的 Linux 虚拟机守护进程（vm-host.cjs --daemon）。
# 守护进程脱离 GALIAIS 进程组与应用 cgroup，因此划掉/被系统回收应用进程后虚拟机仍可保持运行，
# 下次打开应用时通过 vm-client.cjs 经 daemon.sock 重新挂接。
set -eu
root=/data/user/0/com.galiais.agent/files/linux
base=/data/user/0/com.galiais.agent/files
test "$(id -u)" = 0
persist="${1:-1}"
case "$persist" in 0|1) ;; *) exit 64 ;; esac
if test "${2:-}" = --inside-namespace; then
    # KernelSU 通常已将 Root 进程放在根 cgroup；若仍位于应用 uid_*/pid_* 组，则移出以免随应用进程组被回收或冻结。
    if test -w /sys/fs/cgroup/cgroup.procs; then
        echo "$$" > /sys/fs/cgroup/cgroup.procs 2>/dev/null || true
    fi
    read -r network_name < "$base/vm-network.name"
    case "$network_name" in galiais.network.*) ;; *) exit 64 ;; esac
    case "$network_name" in *[!a-zA-Z0-9.-]*) exit 64 ;; esac
    uid=$(stat -c %u "$base")
    test "$uid" -ge 10000 && test "$uid" -lt 100000
    if test "${GALIAIS_SHARED_MNT:-0}" != 1; then
        mount -o rprivate none /
    fi
    test -f "$root/.galiais-ready"
    test ! -L "$root/proc"
    if ! grep -q " $root/proc " /proc/mounts; then
        mount -t proc -o nosuid,nodev,noexec,hidepid=2 proc "$root/proc"
    fi
    if ! grep -q " $root/dev " /proc/mounts; then
        mount -t tmpfs -o mode=755,size=64k,nosuid,noexec tmpfs "$root/dev"
        for device in null:3 zero:5 random:8 urandom:9; do
            name=${device%:*}
            minor=${device#*:}
            mknod "$root/dev/$name" c 1 "$minor"
            chmod 666 "$root/dev/$name"
        done
        ln -sf /proc/self/fd "$root/dev/fd"
    fi
    test -f "$root/usr/local/lib/galiais/android-network.so"
    exec chroot "$root" /usr/bin/env -i HOME=/home/agent PATH=/opt/galiais-codex/usr/lib/chatgpt/resources:/usr/bin:/bin LANG=C.UTF-8 /bin/su -s /bin/sh agent -c 'network=$1; uid=$2; persist=$3; cd /home/agent/workspace; exec /usr/bin/env LD_PRELOAD=/usr/local/lib/galiais/android-network.so GALIAIS_NETWORK_SOCKET="$network" GALIAIS_APP_UID="$uid" /bin/sh /home/agent/.galiais/start-vm-daemon.sh "$persist"' -- runtime "$network_name" "$uid" "$persist"
fi
test -f "$root/.galiais-ready"
test -f "$root/home/agent/.galiais/vm/.provisioned"
test -f "$root/home/agent/.galiais/start-vm-daemon.sh"
sock="$root/home/agent/.galiais/vm/daemon.sock"
err="$base/vm-daemon.err"
rm -f "$err"
if unshare -m /system/bin/true 2>/dev/null; then
    setsid -d /system/bin/env -i PATH=/system/bin:/system/xbin /system/bin/unshare -m /system/bin/sh "$0" "$persist" --inside-namespace </dev/null >/dev/null 2>"$err" &
else
    setsid -d /system/bin/env -i PATH=/system/bin:/system/xbin GALIAIS_SHARED_MNT=1 /system/bin/sh "$0" "$persist" --inside-namespace </dev/null >/dev/null 2>"$err" &
fi
child=$!
i=0
while test "$i" -lt 75; do
    if test -S "$sock"; then
        test -s "$err" || rm -f "$err"
        echo "{\"kind\":\"daemon-started\",\"pid\":$child}"
        exit 0
    fi
    state=$(sed 's/.*) //' "/proc/$child/stat" 2>/dev/null | cut -c1 || true)
    if test -z "$state" || test "$state" = Z; then
        wait "$child" 2>/dev/null && code=0 || code=$?
        if test -s "$err"; then tail -c 4000 "$err" >&2; fi
        if test "$code" = 75; then echo "已有虚拟机实例持有锁" >&2; exit 75; fi
        echo "虚拟机守护进程启动后退出（$code）" >&2
        exit 1
    fi
    sleep 0.2
    i=$((i + 1))
done
if test -s "$err"; then tail -c 4000 "$err" >&2; fi
echo "等待虚拟机守护进程就绪超时" >&2
exit 1
