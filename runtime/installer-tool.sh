#!/bin/sh
set -eu
tool=${0##*/}
case "$tool" in curl|bzip2) ;; *) echo '未知安装工具' >&2; exit 64 ;; esac
case "$(uname -m)" in
    aarch64|arm64) triplet=aarch64-linux-gnu ;;
    x86_64) triplet=x86_64-linux-gnu ;;
    *) echo '无法识别安装工具架构' >&2; exit 1 ;;
esac
tools=/opt/galiais-install-tools/current
# 依赖仅对当前工具生效，不改动 Agent 或系统库的搜索路径。
LD_LIBRARY_PATH="$tools/usr/lib/$triplet:$tools/lib/$triplet${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" exec "$tools/usr/bin/$tool" "$@"
