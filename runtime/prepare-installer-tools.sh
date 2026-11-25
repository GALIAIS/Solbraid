#!/system/bin/sh
set -eu
root=${1:?missing runtime root}
nlib=${2:?missing native library directory}
source=${3:?missing runtime assets}
case "$(uname -m)" in
    aarch64|arm64) abi=arm64-v8a ;;
    x86_64) abi=x86_64 ;;
    *) echo '无法识别安装工具架构'; exit 1 ;;
esac
checksum="$source/installer-tools-$abi.sha256"
(cd "$nlib" && sha256sum -c "$checksum")
digest=$(cut -d ' ' -f 1 "$checksum")
target="$root/opt/galiais-install-tools/$digest"
mkdir -p "$target"
# 免 Root (PRoot) 模式下由宿主无硬链接解包器预先解压为相对符号链接，校验通过则跳过 /system/bin/tar 避免 SELinux link(2) 拒绝
if ! (cd "$target" && sha256sum -c files.sha256 >/dev/null 2>&1); then
    rm -rf "$target"
    mkdir -p "$target"
    tar -xzf "$nlib/libpayload_tools.so" -C "$target"
fi
(cd "$target" && sha256sum -c files.sha256 >/dev/null)
find "$target" ! -type l -exec chmod a+rX {} + 2>/dev/null || chmod -R a+rX "$target" 2>/dev/null || true
ln -sfn "$digest" "$root/opt/galiais-install-tools/current"
resources="$root/opt/galiais-codex/usr/lib/chatgpt/resources"
mkdir -p "$resources"
for name in curl bzip2; do
    cp "$source/installer-tool.sh" "$resources/$name.next"
    chmod 755 "$resources/$name.next"
    mv "$resources/$name.next" "$resources/$name"
done
