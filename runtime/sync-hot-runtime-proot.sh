#!/system/bin/sh
# 免 Root PRoot 模式下常驻守护进程热重连时原子同步最新平台脚本。
set -eu
umask 022
base="${GALIAIS_APP_FILES:-/data/user/0/com.galiais.agent/files}"
root="$base/linux-proot"
source="$base/vm-runtime"
mkdir -p "$root/opt/galiais-codex/android"
ln -sf /opt/galiais-native/libbin_antigravity.so "$root/opt/galiais-codex/usr/lib/chatgpt/resources/agy"
for name in components.json claude-host.mjs delegate-mcp.mjs provider-gateway.mjs provider-translators.mjs usage-ledger.mjs agent-hub.mjs acp-driver.mjs cli-bridge-driver.mjs dev-tools-hub.mjs terminal-hub.mjs cli-components.mjs terminal-headless.cjs terminal-serialize.cjs extension-sync.mjs plugin-kernel.mjs plugin-builtins.mjs plugin-mcp.mjs plugin-worker-host.mjs ai-reviewer.mjs community-registry.mjs download-network.mjs env-ssh-hub.mjs worktree-manager.mjs guest-mux.mjs; do
    cp "$source/$name" "$root/opt/galiais-codex/android/$name.next"
    chmod 644 "$root/opt/galiais-codex/android/$name.next"
    mv "$root/opt/galiais-codex/android/$name.next" "$root/opt/galiais-codex/android/$name"
done
chmod -R a+rX "$root/opt/galiais-codex/android"
