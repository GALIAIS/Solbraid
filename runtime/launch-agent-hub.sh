#!/system/bin/sh
set -eu
base=/data/user/0/com.galiais.agent/files
exec /system/bin/sh "$base/vm-runtime/run-linux.sh" /opt/galiais-codex/usr/lib/chatgpt/resources/node /opt/galiais-codex/android/guest-mux.mjs agent-hub
