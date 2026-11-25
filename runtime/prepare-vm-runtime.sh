#!/system/bin/sh
# 全离线 Root 环境极速装配脚本：直接关联内置原生二进制，零网络下载、零 apt-get。
set -eu
script_dir=$(cd "$(dirname "$0")" && pwd)
base=$(dirname "$script_dir")
case "$base" in
    /data/user/*/com.galiais.agent/files|/data/data/com.galiais.agent/files) ;;
    *) base=/data/user/0/com.galiais.agent/files ;;
esac
root="$base/linux"
source="$base/vm-runtime"
. "$source/engine-versions.sh"
test "$(id -u)" = 0
test -f "$root/.galiais-ready"
test -f "$base/native-lib.path"
IFS= read -r nlib < "$base/native-lib.path"
test -f "$nlib/libbin_codex.so" && test -f "$nlib/libbin_claude.so" && test -f "$nlib/libbin_node.so" && test -f "$nlib/libpayload_npm.so"
test -f "$nlib/libbin_codex_code_mode_host.so"
test -f "$nlib/libbin_antigravity.so"

uid=$(stat -c %u "$base")
mkdir -p "$root/home/agent/.galiais/vm" "$root/home/agent/workspace" "$root/opt/galiais-native" "$root/tmp"
chmod 1777 "$root/tmp"
if grep -q '^agent:' "$root/etc/passwd"; then
    sed -i "s|^agent:x:[0-9]*:[0-9]*:|agent:x:$uid:$uid:|" "$root/etc/passwd"
else
    printf 'agent:x:%s:%s:Agent:/home/agent:/bin/bash\n' "$uid" "$uid" >> "$root/etc/passwd"
fi
grep -q "^appdata:x:$uid:" "$root/etc/group" || printf 'appdata:x:%s:\n' "$uid" >> "$root/etc/group"
grep -q '^inet:' "$root/etc/group" || printf 'inet:x:3003:agent\n' >> "$root/etc/group"
rm -f "$root/etc/resolv.conf"
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$root/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$root/etc/hosts"

echo "GALIAIS_PROGRESS 30 正在配置 Codex 和 Node.js"
resources="$root/opt/galiais-codex/usr/lib/chatgpt/resources"
mkdir -p "$resources"
ln -sf /opt/galiais-native/libbin_node.so "$resources/node"
ln -sf /opt/galiais-native/libbin_rg.so "$resources/rg"
ln -sf /opt/galiais-native/libbin_antigravity.so "$resources/agy"
if ! test -f "$resources/lib/node_modules/npm/bin/npm-cli.js"; then
    tar -xzf "$nlib/libpayload_npm.so" -C "$resources"
fi
cat > "$resources/npm" << 'EOF'
#!/bin/sh
exec /opt/galiais-codex/usr/lib/chatgpt/resources/node /opt/galiais-codex/usr/lib/chatgpt/resources/lib/node_modules/npm/bin/npm-cli.js "$@"
EOF
chmod 755 "$resources/npm"
/system/bin/sh "$source/prepare-installer-tools.sh" "$root" "$nlib" "$source"

echo "GALIAIS_PROGRESS 60 正在配置 Claude Code"
target="$root/opt/galiais-codex/claude/$CLAUDE_VERSION"
mkdir -p "$target"
ln -sf /opt/galiais-native/libbin_claude.so "$target/claude"
ln -sfn "$CLAUDE_VERSION" "$root/opt/galiais-codex/claude/current"

sdk="$root/opt/galiais-codex/claude-sdk-$CLAUDE_SDK_VERSION"
# SDK 的分块文件也必须完整，不能只依据安装标记跳过修复。
if ! test -f "$sdk/.galiais-sdk-ready" || ! (cd "$sdk" && sha256sum -c "$source/claude-sdk-files.sha256" >/dev/null 2>&1); then
    rm -rf "$sdk"
    mkdir -p "$sdk"
    tar -xzf "$source/$CLAUDE_SDK_ARCHIVE" -C "$sdk" --strip-components=1
    (cd "$sdk" && sha256sum -c "$source/claude-sdk-files.sha256" >/dev/null)
    printf '%s\n' "$CLAUDE_SDK_VERSION" > "$sdk/.galiais-sdk-ready"
fi

echo "GALIAIS_PROGRESS 85 正在配置本地运行服务"
plan="$root/opt/galiais-codex/official-plan-$PLAN_VERSION"
if ! test -d "$plan" || ! (cd "$plan" && sha256sum -c "$source/official-plan-files.sha256" >/dev/null 2>&1); then
    mkdir -p "$plan"
    tar -xzf "$source/$PLAN_ARCHIVE" -C "$plan"
    (cd "$plan" && sha256sum -c "$source/official-plan-files.sha256" >/dev/null)
fi
skills="$root/home/agent/.agents/skills"
mkdir -p "$skills"
if test -e "$skills/openai-plan" || test -L "$skills/openai-plan"; then
    case "$(readlink "$skills/openai-plan")" in /opt/galiais-codex/official-plan-*/skills/plan) ;; *) echo '技能目录已存在：openai-plan'; exit 1 ;; esac
fi
ln -sfn "/opt/galiais-codex/official-plan-$PLAN_VERSION/skills/plan" "$skills/openai-plan"
mkdir -p "$root/opt/galiais-codex/android"
for name in components.json claude-host.mjs delegate-mcp.mjs provider-gateway.mjs provider-translators.mjs usage-ledger.mjs agent-hub.mjs acp-driver.mjs cli-bridge-driver.mjs dev-tools-hub.mjs terminal-hub.mjs cli-components.mjs terminal-headless.cjs terminal-serialize.cjs extension-sync.mjs plugin-kernel.mjs plugin-builtins.mjs ai-reviewer.mjs community-registry.mjs download-network.mjs env-ssh-hub.mjs worktree-manager.mjs guest-mux.mjs; do
    cp "$source/$name" "$root/opt/galiais-codex/android/$name.next"
    chmod 644 "$root/opt/galiais-codex/android/$name.next"
    mv "$root/opt/galiais-codex/android/$name.next" "$root/opt/galiais-codex/android/$name"
done
chmod -R a+rX "$root/opt/galiais-codex"

printf 'native-direct-v1\n' > "$root/home/agent/.galiais/vm/.provisioned"
chown -R "$uid:$uid" "$root/home/agent"
echo "GALIAIS_PROGRESS 100 运行环境配置完成"
