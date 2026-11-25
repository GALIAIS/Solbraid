#!/bin/bash
set -euo pipefail

project=$(cd "$(dirname "$0")/.." && pwd)
for architecture in arm64 amd64; do
    case "$architecture" in arm64) abi=arm64-v8a ;; amd64) abi=x86_64 ;; esac
    work=$(mktemp -d "$project/.agent-notes/installer-tools-$architecture.XXXXXX")
    mkdir -p "$work/state/lists/partial" "$work/cache/archives/partial" "$work/log" "$work/payload" "$work/sourceparts"
    tar -xOf "$project/downloads/offline/$abi/libpayload_rootfs.so" ./var/lib/dpkg/status > "$work/status"
    # 使用目标根系统的包状态及签名源，只下载依赖，不修改构建主机的系统包。
    apt_options=(
        -o "APT::Architecture=$architecture"
        -o "APT::Architectures::=$architecture"
        -o "Dir::Etc::sourcelist=$project/tools/installer-tools.sources"
        -o "Dir::Etc::sourceparts=$work/sourceparts"
        -o "Dir::State=$work/state"
        -o "Dir::State::status=$work/status"
        -o "Dir::Cache=$work/cache"
        -o "Dir::Log=$work/log"
        -o 'Acquire::Languages=none'
        -o 'Acquire::Retries=2'
        -o 'Acquire::https::Timeout=30'
    )
    APT_CONFIG="$project/tools/installer-tools.conf" apt-get "${apt_options[@]}" update
    APT_CONFIG="$project/tools/installer-tools.conf" apt-get "${apt_options[@]}" --download-only --no-install-recommends --reinstall --assume-yes install curl libcurl4t64 bzip2 libbz2-1.0
    packages=("$work/cache/archives/"*.deb)
    test -f "${packages[0]}"
    for package in "${packages[@]}"; do
        package_arch=$(dpkg-deb -f "$package" Architecture)
        test "$package_arch" = "$architecture" || test "$package_arch" = all
        dpkg-deb -x "$package" "$work/payload"
        dpkg-deb -W --showformat='${Package}\t${Version}\t${Architecture}\n' "$package" >> "$work/payload/packages.tsv"
    done
    test -x "$work/payload/usr/bin/curl"
    test -x "$work/payload/bin/bzip2" || test -x "$work/payload/usr/bin/bzip2"
    if test -x "$work/payload/bin/bzip2"; then
        mkdir -p "$work/payload/usr/bin"
        cp "$work/payload/bin/bzip2" "$work/payload/usr/bin/bzip2"
    fi
    (
        cd "$work/payload"
        find . -type f ! -name files.sha256 -print0 | sort -z | xargs -0 sha256sum > files.sha256
        tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -czf "$work/libpayload_tools.so" .
    )
    cp "$work/libpayload_tools.so" "$project/downloads/offline/$abi/libpayload_tools.so"
    sha256sum "$project/downloads/offline/$abi/libpayload_tools.so"
    cat "$work/payload/packages.tsv"
done
