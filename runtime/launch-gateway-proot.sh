#!/system/bin/sh
set -eu
base="${GALIAIS_APP_FILES:-/data/user/0/com.galiais.agent/files}"
exec /system/bin/sh "$base/vm-runtime/run-proot.sh" /opt/galiais-codex/usr/lib/chatgpt/resources/node /opt/galiais-codex/android/guest-mux.mjs gateway
