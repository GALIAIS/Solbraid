#!/system/bin/sh
# 免 Root 模式下以独立会话启动虚拟机守护进程（vm-host.cjs --daemon），受 Android 前台服务保活。
set -eu
base="${GALIAIS_APP_FILES:-/data/user/0/com.galiais.agent/files}"
root="$base/linux-proot"
source="$base/vm-runtime"
persist="${1:-1}"
case "$persist" in 0|1) ;; *) exit 64 ;; esac
test -f "$root/.galiais-ready"
test -f "$root/home/agent/.galiais/vm/.provisioned"
test -f "$root/home/agent/.galiais/start-vm-daemon.sh"
sock="$root/home/agent/.galiais/vm/daemon.sock"
err="$base/vm-proot-daemon.err"
rm -f "$err"
setsid -d /system/bin/sh "$source/run-proot.sh" /bin/sh /home/agent/.galiais/start-vm-daemon.sh "$persist" </dev/null >/dev/null 2>"$err" &
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
        echo "PRoot 虚拟机守护进程启动后退出（$code）" >&2
        exit 1
    fi
    sleep 0.2
    i=$((i + 1))
done
if test -s "$err"; then tail -c 4000 "$err" >&2; fi
echo "等待 PRoot 虚拟机守护进程就绪超时" >&2
exit 1
