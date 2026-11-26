#!/system/bin/sh
# 常驻守护进程热重连（reattached）时原子同步最新平台脚本。
set -eu
script_dir=$(cd "$(dirname "$0")" && pwd)
base=$(dirname "$script_dir")
case "$base" in
    /data/user/*/com.galiais.agent/files|/data/data/com.galiais.agent/files) ;;
    *) base=/data/user/0/com.galiais.agent/files ;;
esac
root="$base/linux"
source="$base/vm-runtime"
test "$(id -u)" = 0
mkdir -p "$root/opt/galiais-codex/android"
ln -sf /opt/galiais-native/libbin_antigravity.so "$root/opt/galiais-codex/usr/lib/chatgpt/resources/agy"
for name in components.json claude-host.mjs delegate-mcp.mjs provider-gateway.mjs provider-translators.mjs usage-ledger.mjs agent-hub.mjs safe-file-io.mjs acp-driver.mjs cli-bridge-driver.mjs dev-tools-hub.mjs terminal-hub.mjs cli-components.mjs terminal-headless.cjs terminal-serialize.cjs extension-sync.mjs plugin-kernel.mjs plugin-builtins.mjs plugin-mcp.mjs plugin-worker-host.mjs ai-reviewer.mjs community-registry.mjs download-network.mjs env-ssh-hub.mjs worktree-manager.mjs guest-mux.mjs self-build-apk.sh self-update-watchdog.sh; do
    cp "$source/$name" "$root/opt/galiais-codex/android/$name.next"
    case "$name" in *.sh) chmod 755 "$root/opt/galiais-codex/android/$name.next" ;; *) chmod 644 "$root/opt/galiais-codex/android/$name.next" ;; esac
    mv "$root/opt/galiais-codex/android/$name.next" "$root/opt/galiais-codex/android/$name"
done
