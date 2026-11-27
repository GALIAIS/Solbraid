#!/usr/bin/env bash
# Solbraid 本机纯本地 ARM64 APK 自迭代构建流水线
# 能力：
#   1. status    : 检测本机 ARM64 JDK 17 / Android SDK (android-36) / 原生 aapt2 / 签名密钥 / 源码目录就绪度
#   2. bootstrap : 配置 ARM64 依赖并复用离线组件；签名密钥必须由用户提供原版本密钥
#   3. build     : 运行单测 + 单 ABI (arm64-v8a) Gradle 编译 + 运行时完整性校验，输出可直接安装的 APK
#   4. apply     : 经明确确认（--yes）后移交独立 Root 启动看门狗安装并验证首帧心跳（异常自动回滚）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOME_DIR="${HOME:-/home/agent}"
SIGNING_PROPERTIES="${SOLBRAID_SIGNING_PROPERTIES:-$HOME_DIR/.galiais/self-build-signing.properties}"
HEARTBEAT_FILE="/data/user/0/com.galiais.agent/files/self-update-healthy.json"
WATCHDOG_STATUS_FILE="/data/local/tmp/solbraid-self-update/status.json"

detect_arch() {
    uname -m 2>/dev/null || echo "unknown"
}

detect_java_home() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME"
        return
    fi
    for d in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/default-java "$HOME_DIR/jdk-17"; do
        if [ -x "$d/bin/java" ]; then
            echo "$d"
            return
        fi
    done
    if command -v java >/dev/null 2>&1; then
        local jbin
        jbin="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
        dirname "$(dirname "$jbin")"
    fi
}

detect_android_home() {
    for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME_DIR/android-sdk" "/opt/android-sdk"; do
        if [ -n "$d" ] && [ -d "$d" ]; then
            echo "$d"
            return
        fi
    done
    echo "$HOME_DIR/android-sdk"
}

detect_aapt2() {
    local sdk="$1"
    local cand=""
    # AGP 9 严格校验 android.aapt2FromMavenOverride 的文件名必须为 aapt2
    for cand in "${SOLBRAID_AAPT2_BIN:-}" "$sdk"/build-tools/*/aapt2 "$sdk/arm64-tools/aapt2" "/usr/bin/aapt2"; do
        if [ -n "$cand" ] && [ -x "$cand" ]; then
            if "$cand" version >/dev/null 2>&1; then
                echo "$cand"
                return
            fi
        fi
    done
}

detect_repo_dir() {
    local explicit="${1:-}"
    for d in "$explicit" "${SOLBRAID_REPO_DIR:-}" "$HOME_DIR/workspace/Solbraid" "$HOME_DIR/workspace/GALIAIS" "$PWD"; do
        if [ -n "$d" ] && [ -f "$d/gradlew" ] && [ -f "$d/app/build.gradle.kts" ]; then
            (cd "$d" && pwd)
            return
        fi
    done
}

hydrate_offline_assets() {
    local repo="$1"
    local abi="${2:-arm64-v8a}"
    local native_src=""
    for d in "/opt/galiais-native" "${SOLBRAID_NATIVE_LIB_DIR:-}"; do
        if [ -n "$d" ] && [ -f "$d/libbin_node.so" ]; then
            native_src="$d"
            break
        fi
    done
    if [ -n "$native_src" ] && [ "$abi" = "arm64-v8a" ]; then
        mkdir -p "$repo/downloads/offline/arm64-v8a"
        for so in libbin_antigravity.so libpayload_tools.so libbin_claude.so libbin_codex_code_mode_host.so libbin_codex.so libbin_node.so libbin_rg.so libpayload_npm.so libpayload_rootfs.so; do
            if [ ! -f "$repo/downloads/offline/arm64-v8a/$so" ] && [ -f "$native_src/$so" ]; then
                ln -sf "$native_src/$so" "$repo/downloads/offline/arm64-v8a/$so"
            fi
        done
    fi
    # 若宿主 runtime 缓存目录可读，顺带复用已落盘的 Claude SDK 与 Official Plan 压缩包
    local vm_rt="/data/user/0/com.galiais.agent/files/vm-runtime"
    if [ -d "$vm_rt" ]; then
        mkdir -p "$repo/downloads/runtime"
        for tgz in "$vm_rt"/claude-agent-sdk-*.tgz "$vm_rt"/official-plan-*.tgz; do
            [ -f "$tgz" ] || continue
            local base
            base="$(basename "$tgz")"
            [ -f "$repo/downloads/runtime/$base" ] || cp -f "$tgz" "$repo/downloads/runtime/$base"
        done
    fi
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\r\n' '  '
}

detect_signing_cert_sha256() {
    local props="$1"
    local java_home="$2"
    [ -r "$props" ] || return 0
    local kt=""
    if [ -n "$java_home" ] && [ -x "$java_home/bin/keytool" ]; then
        kt="$java_home/bin/keytool"
    elif command -v keytool >/dev/null 2>&1; then
        kt="$(command -v keytool)"
    fi
    [ -n "$kt" ] || return 0
    local store_file store_pass key_alias
    store_file="$(awk -F= '/^storeFile=/{sub(/^[^=]*=/,""); print; exit}' "$props" | tr -d '\r')"
    store_pass="$(awk -F= '/^storePassword=/{sub(/^[^=]*=/,""); print; exit}' "$props" | tr -d '\r')"
    key_alias="$(awk -F= '/^keyAlias=/{sub(/^[^=]*=/,""); print; exit}' "$props" | tr -d '\r')"
    [ -n "$store_file" ] && [ -r "$store_file" ] || return 0
    "$kt" -list -v -keystore "$store_file" -storepass "${store_pass:-android}" ${key_alias:+-alias "$key_alias"} 2>/dev/null \
        | awk '/SHA256:/{print $2; exit}' | tr -d '\r'
}

cmd_init_signing() {
    local ks="${HOME_DIR}/.galiais/solbraid-signing.keystore"
    local alias_name="androiddebugkey"
    local store_pass="android"
    local key_pass="android"
    while [ $# -gt 0 ]; do
        case "$1" in
            --keystore) ks="${2:-}"; shift 2 ;;
            --alias) alias_name="${2:-}"; shift 2 ;;
            --store-pass) store_pass="${2:-}"; shift 2 ;;
            --key-pass) key_pass="${2:-}"; shift 2 ;;
            *) shift ;;
        esac
    done
    if [ ! -f "$ks" ] && [ -f "$HOME_DIR/.android/debug.keystore" ]; then
        mkdir -p "$(dirname "$ks")"
        cp -f "$HOME_DIR/.android/debug.keystore" "$ks"
        chmod 600 "$ks"
    fi
    [ -f "$ks" ] || { echo "ERROR: 未找到可用 keystore 文件：$ks" >&2; exit 1; }
    mkdir -p "$(dirname "$SIGNING_PROPERTIES")"
    cat > "$SIGNING_PROPERTIES" <<EOF
storeFile=$ks
storePassword=$store_pass
keyAlias=$alias_name
keyPassword=$key_pass
EOF
    chmod 600 "$SIGNING_PROPERTIES"
    local fp
    fp="$(detect_signing_cert_sha256 "$SIGNING_PROPERTIES" "$(detect_java_home || true)" || true)"
    printf '{"ok":true,"signingPropertiesPath":"%s","storeFile":"%s","signingCertSha256":"%s"}\n' \
        "$(json_escape "$SIGNING_PROPERTIES")" "$(json_escape "$ks")" "$(json_escape "$fp")"
}

cmd_status() {
    local repo_arg="${1:-}"
    local arch java_home java_ver android_home aapt2_bin repo_dir
    arch="$(detect_arch)"
    java_home="$(detect_java_home || true)"
    java_ver=""
    local java_ok="false"
    if [ -n "$java_home" ] && [ -x "$java_home/bin/java" ]; then
        java_ver="$("$java_home/bin/java" -version 2>&1 | head -n 1 || true)"
        java_ok="true"
    fi
    android_home="$(detect_android_home)"
    local platform_ok="false"
    [ -f "$android_home/platforms/android-36/android.jar" ] && platform_ok="true"
    aapt2_bin="$(detect_aapt2 "$android_home" || true)"
    local aapt2_ok="false"
    [ -n "$aapt2_bin" ] && aapt2_ok="true"
    repo_dir="$(detect_repo_dir "$repo_arg" || true)"
    local repo_ok="false"
    [ -n "$repo_dir" ] && repo_ok="true"
    local keystore_ok="false"
    local signing_cert_sha256=""
    if [ -r "$SIGNING_PROPERTIES" ]; then
        local sf
        sf="$(awk -F= '/^storeFile=/{sub(/^[^=]*=/,""); print; exit}' "$SIGNING_PROPERTIES" | tr -d '\r')"
        if [ -n "$sf" ] && [ -r "$sf" ]; then
            keystore_ok="true"
            signing_cert_sha256="$(detect_signing_cert_sha256 "$SIGNING_PROPERTIES" "$java_home" || true)"
        fi
    fi
    local native_ready="false"
    [ -f "/opt/galiais-native/libbin_node.so" ] && native_ready="true"

    local missing_json=""
    add_missing() {
        local item="$1"
        if [ -z "$missing_json" ]; then
            missing_json="\"$(json_escape "$item")\""
        else
            missing_json="$missing_json,\"$(json_escape "$item")\""
        fi
    }
    [ "$java_ok" = "true" ] || add_missing "openjdk-17-jdk"
    [ "$platform_ok" = "true" ] || add_missing "android-sdk:platforms;android-36"
    if [ "$arch" = "aarch64" ] && [ "$aapt2_ok" != "true" ]; then
        add_missing "arm64-aapt2"
    fi
    [ "$repo_ok" = "true" ] || add_missing "solbraid-git-repo"
    [ "$keystore_ok" = "true" ] || add_missing "原版本签名配置"

    local ready="true"
    [ -n "$missing_json" ] && ready="false"

    local hb="{}"
    [ -r "$HEARTBEAT_FILE" ] && hb="$(cat "$HEARTBEAT_FILE" 2>/dev/null || echo '{}')"
    local wd="{}"
    [ -r "$WATCHDOG_STATUS_FILE" ] && wd="$(cat "$WATCHDOG_STATUS_FILE" 2>/dev/null || echo '{}')"
    local has_backup="false"
    [ -f "/data/local/tmp/solbraid-self-update/last-known-good.apk" ] && has_backup="true"

    printf '{"arch":"%s","javaHome":"%s","javaVersion":"%s","javaOk":%s,"androidHome":"%s","platform36Ok":%s,"aapt2Bin":"%s","aapt2Ok":%s,"repoDir":"%s","repoOk":%s,"keystoreOk":%s,"signingPropertiesPath":"%s","signingCertSha256":"%s","nativeOfflineHydratable":%s,"readyForLocalBuild":%s,"hasBackup":%s,"missing":[%s],"heartbeat":%s,"watchdogStatus":%s}\n' \
        "$(json_escape "$arch")" \
        "$(json_escape "$java_home")" \
        "$(json_escape "$java_ver")" \
        "$java_ok" \
        "$(json_escape "$android_home")" \
        "$platform_ok" \
        "$(json_escape "$aapt2_bin")" \
        "$aapt2_ok" \
        "$(json_escape "$repo_dir")" \
        "$repo_ok" \
        "$keystore_ok" \
        "$(json_escape "$SIGNING_PROPERTIES")" \
        "$(json_escape "$signing_cert_sha256")" \
        "$native_ready" \
        "$ready" \
        "$has_backup" \
        "$missing_json" \
        "$hb" \
        "$wd"
}

cmd_bootstrap() {
    local repo_arg="${1:-}"
    local android_home
    android_home="$(detect_android_home)"
    mkdir -p "$android_home" "$HOME_DIR/.android"

    if [ "$(id -u)" = "0" ] && command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get -o APT::Sandbox::User=root update -y
        apt-get -o APT::Sandbox::User=root install -y --no-install-recommends openjdk-17-jdk-headless unzip curl ca-certificates
    fi

    # ARM64 环境自动安装原生 aarch64-glibc aapt2（用于 AGP android.aapt2FromMavenOverride，文件名必须为 aapt2）
    if [ "$(detect_arch)" = "aarch64" ] && [ -z "$(detect_aapt2 "$android_home" || true)" ] && command -v curl >/dev/null 2>&1; then
        local aapt2_tag="${SOLBRAID_ARM64_BT_TAG:-platform-tools-36.1.0}"
        local aapt2_url="https://github.com/Commit451/android-arm-build-tools/releases/download/${aapt2_tag}/aapt2"
        mkdir -p "$android_home/arm64-tools"
        local tmp_aapt2="$android_home/arm64-tools/aapt2.$$.tmp"
        if curl -fsSL "$aapt2_url" -o "$tmp_aapt2"; then
            local expected_sha="${SOLBRAID_AAPT2_SHA256:-}"
            if [ -z "$expected_sha" ]; then
                expected_sha="$(curl -fsSL "${aapt2_url}.sha256" 2>/dev/null | awk '{print $1}' | tr -d '\r\n' || true)"
            fi
            if [ -n "$expected_sha" ]; then
                local actual_sha
                actual_sha="$(sha256sum "$tmp_aapt2" | awk '{print $1}')"
                if [ "$actual_sha" != "$expected_sha" ]; then
                    echo "ERROR: 下载的 aapt2 SHA-256 校验失败（预期 $expected_sha，实际 $actual_sha）" >&2
                    rm -f "$tmp_aapt2"
                    exit 1
                fi
            fi
            chmod 755 "$tmp_aapt2"
            if ! "$tmp_aapt2" version >/dev/null 2>&1; then
                echo "ERROR: 下载的 aapt2 冒烟校验失败（非可执行 ARM64 二进制）" >&2
                rm -f "$tmp_aapt2"
                exit 1
            fi
            mv -f "$tmp_aapt2" "$android_home/arm64-tools/aapt2"
        else
            rm -f "$tmp_aapt2"
        fi
    fi

    local repo_dir
    repo_dir="$(detect_repo_dir "$repo_arg" || true)"
    if [ -n "$repo_dir" ]; then
        hydrate_offline_assets "$repo_dir" "arm64-v8a"
        printf 'sdk.dir=%s\n' "$android_home" > "$repo_dir/local.properties"
    fi
    cmd_status "$repo_arg"
}

cmd_build() {
    local repo_arg=""
    local skip_tests="false"
    local variant="debug"
    local abis="arm64-v8a"
    while [ $# -gt 0 ]; do
        case "$1" in
            --repo) repo_arg="${2:-}"; shift 2 ;;
            --skip-tests) skip_tests="true"; shift ;;
            --variant) variant="${2:-debug}"; shift 2 ;;
            --abis) abis="${2:-arm64-v8a}"; shift 2 ;;
            *) shift ;;
        esac
    done

    local arch java_home android_home aapt2_bin repo_dir
    arch="$(detect_arch)"
    java_home="$(detect_java_home || true)"
    android_home="$(detect_android_home)"
    aapt2_bin="$(detect_aapt2 "$android_home" || true)"
    repo_dir="$(detect_repo_dir "$repo_arg" || true)"

    [ -n "$java_home" ] || { echo "ERROR: 未找到 JDK 17，请先运行 self-build-apk.sh bootstrap 安装 openjdk-17-jdk" >&2; exit 1; }
    [ -f "$android_home/platforms/android-36/android.jar" ] || { echo "ERROR: 未找到 Android SDK platforms/android-36 ($android_home)" >&2; exit 1; }
    [ -n "$repo_dir" ] || { echo "ERROR: 未找到 Solbraid 源码仓库（请传入 --repo <path>）" >&2; exit 1; }
    [ -r "$SIGNING_PROPERTIES" ] || { echo "ERROR: 请先配置已安装版本的原签名密钥" >&2; exit 1; }
    if [ "$arch" = "aarch64" ] && [ -z "$aapt2_bin" ]; then
        echo "ERROR: ARM64 环境缺少原生 aapt2，请安装 apt-get install -y aapt2 或设置 SOLBRAID_AAPT2_BIN" >&2
        exit 1
    fi

    hydrate_offline_assets "$repo_dir" "$abis"
    printf 'sdk.dir=%s\n' "$android_home" > "$repo_dir/local.properties"

    export JAVA_HOME="$java_home"
    export ANDROID_HOME="$android_home"
    export ANDROID_SDK_ROOT="$android_home"
    export PATH="$JAVA_HOME/bin:/opt/galiais-codex/usr/lib/chatgpt/resources:$PATH"

    local heap_mb="${SOLBRAID_GRADLE_HEAP_MB:-4096}"
    case "$heap_mb" in
        ''|*[!0-9]*) heap_mb=4096 ;;
    esac
    if [ "$heap_mb" -lt 1536 ]; then heap_mb=1536; fi
    if [ "$heap_mb" -gt 8192 ]; then heap_mb=8192; fi

    local gradle_args=(
        "--no-daemon"
        "-PsolbraidAbis=$abis"
        "-PsolbraidSigningProperties=$SIGNING_PROPERTIES"
        "-Dorg.gradle.jvmargs=-Xmx${heap_mb}m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
    )
    if [ -n "$aapt2_bin" ]; then
        gradle_args+=("-Pandroid.aapt2FromMavenOverride=$aapt2_bin")
    fi
    if [ -n "${SOLBRAID_EXTRA_GRADLE_ARGS:-}" ]; then
        local tok
        for tok in $SOLBRAID_EXTRA_GRADLE_ARGS; do
            case "$tok" in
                -[A-Za-z0-9._:=/-]*) gradle_args+=("$tok") ;;
                *) echo "ERROR: 不允许的附加 Gradle 参数：$tok" >&2; exit 1 ;;
            esac
        done
    fi

    local tasks=()
    if [ "$skip_tests" != "true" ]; then
        tasks+=(":app:testDebugUnitTest")
    fi
    if [ "$variant" = "release" ]; then
        tasks+=(":app:assembleRelease")
    else
        tasks+=(":app:assembleDebug")
    fi

    echo "[self-build] repo=$repo_dir arch=$arch abis=$abis heap=${heap_mb}m tasks=${tasks[*]}"
    (cd "$repo_dir" && chmod +x ./gradlew && ./gradlew "${tasks[@]}" "${gradle_args[@]}")

    local apk_dir="$repo_dir/app/build/outputs/apk/$variant"
    local out_apk="$apk_dir/app-arm64-v8a-$variant.apk"
    if [ -f "$apk_dir/output-metadata.json" ]; then
        local meta_file
        meta_file="$(awk -F'"' '/"outputFile"/{print $4; exit}' "$apk_dir/output-metadata.json" || true)"
        if [ -n "$meta_file" ] && [ -f "$apk_dir/$meta_file" ]; then
            out_apk="$apk_dir/$meta_file"
        fi
    fi
    if [ ! -f "$out_apk" ] && [ -f "$apk_dir/app-$variant.apk" ]; then
        out_apk="$apk_dir/app-$variant.apk"
    fi
    [ -f "$out_apk" ] || { echo "ERROR: 编译完成但未找到产物 APK：$out_apk" >&2; exit 1; }
    local size sha
    size="$(stat -c %s "$out_apk" 2>/dev/null || wc -c < "$out_apk")"
    [ "${size:-0}" -gt 1024 ] || { echo "ERROR: 产物 APK 大小异常（${size:-0} B）：$out_apk" >&2; exit 1; }
    sha="$(sha256sum "$out_apk" | awk '{print $1}')"
    printf 'SOLBRAID_SELF_BUILD_RESULT:{"ok":true,"apkPath":"%s","size":%s,"sha256":"%s","variant":"%s","abis":"%s"}\n' \
        "$(json_escape "$out_apk")" "$size" "$sha" "$variant" "$abis"
}

cmd_apply() {
    local apk=""
    local confirmed="false"
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes) confirmed="true"; shift ;;
            *) [ -z "$apk" ] && apk="$1"; shift ;;
        esac
    done
    [ "$confirmed" = "true" ] || { echo "ERROR: 安全护栏：安装并重启 App 必须显式传入 --yes" >&2; exit 1; }
    [ -n "$apk" ] && [ -f "$apk" ] || { echo "ERROR: 无效 APK 路径：$apk" >&2; exit 1; }

    node "$SCRIPT_DIR/plugin-mcp.mjs" --self-build-apply "$apk"
}

cmd_rollback() {
    local confirmed="false"
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes) confirmed="true"; shift ;;
            *) shift ;;
        esac
    done
    [ "$confirmed" = "true" ] || { echo "ERROR: 安全护栏：回滚并重启 App 必须显式传入 --yes" >&2; exit 1; }

    node "$SCRIPT_DIR/plugin-mcp.mjs" --self-build-rollback
}

sub="${1:-status}"
shift || true
case "$sub" in
    status) cmd_status "${1:-}" ;;
    init-signing) cmd_init_signing "$@" ;;
    bootstrap) cmd_bootstrap "${1:-}" ;;
    build) cmd_build "$@" ;;
    apply) cmd_apply "$@" ;;
    rollback) cmd_rollback "$@" ;;
    *)
        echo "Usage: $0 {status [repo] | init-signing [--keystore path] | bootstrap [repo] | build [--repo dir] [--skip-tests] [--variant debug|release] [--abis arm64-v8a] | apply <apk> --yes | rollback --yes}" >&2
        exit 1
        ;;
esac
