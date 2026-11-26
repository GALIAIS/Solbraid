#!/system/bin/sh
set -eu
script_dir=$(cd "$(dirname "$0")" && pwd)
base="${GALIAIS_APP_FILES:-$(dirname "$script_dir")}"
exec /system/bin/sh "$base/vm-runtime/run-linux.sh" /opt/galiais-codex/usr/lib/chatgpt/resources/node /opt/galiais-codex/android/guest-mux.mjs gateway
