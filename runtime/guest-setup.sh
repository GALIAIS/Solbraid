#!/bin/sh
set -eu
id=$1
test "$(id -u)" = 0
phase=packages
trap 'code=$?; if test "$code" != 0; then printf "GALIAIS_VM_SETUP_FAILED %s %s\n" "$id" "$phase"; fi' EXIT
test -x /usr/bin/bwrap
test -x /usr/bin/socat
phase=engine-files
test -x /opt/galiais-codex/usr/lib/chatgpt/resources/codex
phase=apparmor
if test -r /sys/module/apparmor/parameters/enabled && grep -q Y /sys/module/apparmor/parameters/enabled; then
    test -f /usr/share/apparmor/extra-profiles/bwrap-userns-restrict
    install -m 644 /usr/share/apparmor/extra-profiles/bwrap-userns-restrict /etc/apparmor.d/bwrap-userns-restrict
    apparmor_parser -r /etc/apparmor.d/bwrap-userns-restrict
fi
phase=account
install -d -o root -g root -m 755 /var/lib/galiais
if ! test -f /var/lib/galiais/bootstrap-id || ! grep -qFx "$id" /var/lib/galiais/bootstrap-id; then
    usermod -s /bin/sh agent
    printf '%s\n' "$id" > /var/lib/galiais/bootstrap-id
fi
install -d -o agent -g agent -m 700 /home/agent/workspace
systemctl reload ssh
printf 'GALIAIS_VM_TRUST %s ' "$id"
cut -d ' ' -f 1,2 /etc/ssh/ssh_host_ed25519_key.pub
printf 'GALIAIS_VM_SETUP_READY %s\n' "$id"
