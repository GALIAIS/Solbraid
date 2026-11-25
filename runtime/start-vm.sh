#!/bin/sh
set -eu
base=/home/agent/.galiais/vm
test "$(id -u)" = 60000
test -f "$base/.provisioned"
exec /usr/bin/flock -n -E 75 "$base/vm.lock" /usr/bin/node /home/agent/.galiais/vm-host.cjs
