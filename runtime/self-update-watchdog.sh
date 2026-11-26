#!/system/bin/sh
# Solbraid 独立 Root 启动看门狗与原子自更新回滚脚本
# 脱离 com.galiais.agent 进程树运行：备份 last-known-good.apk → pm install -r → 启动 MainActivity →
# 20 秒内检测首帧健康心跳（self-update-healthy.json）；若崩溃或未就绪则自动回滚旧版 APK 并拉起。
set -eu

PKG="com.galiais.agent"
ACTIVITY="com.galiais.agent/.MainActivity"
WORK_DIR="/data/local/tmp/solbraid-self-update"
BACKUP_APK="$WORK_DIR/last-known-good.apk"
CANDIDATE_APK="$WORK_DIR/candidate.apk"
STATUS_FILE="$WORK_DIR/status.json"
LOG_FILE="$WORK_DIR/watchdog.log"
HEARTBEAT_FILE="${SOLBRAID_APP_FILES:-/data/user/0/$PKG/files}/self-update-healthy.json"
LOCK_DIR="$WORK_DIR/update.lock"
TIMEOUT_SEC="${SOLBRAID_WATCHDOG_TIMEOUT:-20}"

mkdir -p "$WORK_DIR"
chmod 755 "$WORK_DIR" 2>/dev/null || true

acquire_update_lock() {
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        owner=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
        case "$owner" in ''|*[!0-9]*) echo 'ERROR: 自更新锁未就绪，请稍后重试' >&2; return 1 ;; esac
        if kill -0 "$owner" 2>/dev/null; then echo 'ERROR: 已有安装或回滚正在进行' >&2; return 1; fi
        rm -f "$LOCK_DIR/pid"
        rmdir "$LOCK_DIR" || return 1
        mkdir "$LOCK_DIR" || return 1
    fi
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
}

release_update_lock() {
    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

now_ms() {
    s=$(date +%s)
    printf '%s000' "$s"
}

write_status() {
    phase="$1"
    ok="$2"
    rolled_back="$3"
    detail="$4"
    ts=$(now_ms)
    escaped=$(printf '%s' "$detail" | tr '\r\n' '  ' | sed 's/\\/\\\\/g; s/"/\\"/g')
    cat > "$STATUS_FILE.tmp" <<EOF
{"phase":"$phase","ok":$ok,"rolledBack":$rolled_back,"detail":"$escaped","ts":$ts,"pid":$$}
EOF
    chmod 644 "$STATUS_FILE.tmp" 2>/dev/null || true
    mv -f "$STATUS_FILE.tmp" "$STATUS_FILE"
}

log_line() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1"
}

backup_current_apk() {
    cur_path=$(pm path "$PKG" 2>/dev/null | head -n 1 | sed 's/^package://')
    if [ -n "$cur_path" ] && [ -f "$cur_path" ]; then
        cp -f "$cur_path" "$BACKUP_APK.tmp" || return 1
        chmod 644 "$BACKUP_APK.tmp" 2>/dev/null || true
        mv -f "$BACKUP_APK.tmp" "$BACKUP_APK" || return 1
        log_line "Backed up last-known-good APK from $cur_path"
        return 0
    fi
    log_line "No existing installed APK found to backup"
    return 1
}

read_heartbeat_ts() {
    if [ ! -f "$HEARTBEAT_FILE" ]; then
        printf '0'
        return
    fi
    if ! grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$HEARTBEAT_FILE" 2>/dev/null; then
        printf '0'
        return
    fi
    val=$(sed -n 's/.*"ts"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$HEARTBEAT_FILE" | head -n 1)
    printf '%s' "${val:-0}"
}

do_rollback() {
    reason="$1"
    log_line "WATCHDOG TRIGGERED ROLLBACK: $reason"
    write_status "rolling_back" "false" "false" "$reason"
    if [ -f "$BACKUP_APK" ]; then
        if pm install -r -d -t "$BACKUP_APK" >>"$LOG_FILE" 2>&1; then
            am start -n "$ACTIVITY" >>"$LOG_FILE" 2>&1 || true
            write_status "rolled_back" "false" "true" "新版本启动异常（$reason），已自动回滚至上一稳定版本"
            log_line "Rollback succeeded and previous APK relaunched"
            exit 2
        else
            write_status "rollback_failed" "false" "false" "自动回滚安装失败（原原因：$reason）"
            log_line "CRITICAL: Rollback pm install failed"
            exit 3
        fi
    else
        write_status "rollback_unavailable" "false" "false" "无可用旧版备份，无法回滚（$reason）"
        exit 3
    fi
}

do_apply() {
    src_apk="$1"
    if [ "$(id -u)" != "0" ]; then
        write_status "error" "false" "false" "需要 Root 权限执行自更新看门狗"
        echo "ERROR: root required" >&2
        exit 1
    fi
    if [ ! -f "$src_apk" ]; then
        write_status "error" "false" "false" "候选 APK 不存在：$src_apk"
        echo "ERROR: APK not found: $src_apk" >&2
        exit 1
    fi

    log_line "=== Self-update apply start: $src_apk ==="
    write_status "preparing" "false" "false" "正在备份当前稳定版本并校验候选 APK"
    backup_current_apk || { write_status "backup_failed" "false" "false" "旧版本备份失败，已取消安装"; exit 1; }

    cp -f "$src_apk" "$CANDIDATE_APK.tmp"
    chmod 644 "$CANDIDATE_APK.tmp" 2>/dev/null || true
    mv -f "$CANDIDATE_APK.tmp" "$CANDIDATE_APK"

    marker_ts=$(now_ms)
    rm -f "$HEARTBEAT_FILE" 2>/dev/null || true
    write_status "installing" "false" "false" "正在安装新版本 APK（markerTs=$marker_ts）"

    install_out=$(pm install -r -t "$CANDIDATE_APK" 2>&1) || {
        log_line "pm install failed: $install_out"
        write_status "install_failed" "false" "false" "pm install 失败：$install_out"
        am start -n "$ACTIVITY" >>"$LOG_FILE" 2>&1 || true
        exit 1
    }
    log_line "pm install output: $install_out"

    write_status "verifying" "false" "false" "已安装，等待新版本首帧健康心跳（超时 ${TIMEOUT_SEC}s）"
    am start -W -n "$ACTIVITY" >>"$LOG_FILE" 2>&1 || true

    elapsed=0
    while [ "$elapsed" -lt "$TIMEOUT_SEC" ]; do
        hb_ts=$(read_heartbeat_ts)
        app_pid=$(pidof "$PKG" 2>/dev/null || true)
        if [ -n "$app_pid" ] && [ "$hb_ts" -ge "$marker_ts" ]; then
            log_line "Healthy heartbeat verified! pid=$app_pid hb_ts=$hb_ts marker_ts=$marker_ts elapsed=${elapsed}s"
            write_status "healthy" "true" "false" "新版本启动健康检查通过（pid=$app_pid，耗时 ${elapsed}s）"
            exit 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    do_rollback "超过 ${TIMEOUT_SEC}s 未检测到新版本首帧健康心跳"
}

do_install_direct() {
    src_apk="$1"
    if [ "$(id -u)" != "0" ]; then
        write_status "error" "false" "false" "需要 Root 权限执行直接安装"
        echo "ERROR: root required" >&2
        exit 1
    fi
    if [ ! -f "$src_apk" ]; then
        write_status "error" "false" "false" "候选 APK 不存在：$src_apk"
        echo "ERROR: APK not found: $src_apk" >&2
        exit 1
    fi
    log_line "=== Self-update direct install (watchdog disabled): $src_apk ==="
    write_status "preparing" "false" "false" "正在备份当前稳定版本并准备直接安装（看门狗已关闭）"
    backup_current_apk || { write_status "backup_failed" "false" "false" "旧版本备份失败，已取消安装"; exit 1; }
    cp -f "$src_apk" "$CANDIDATE_APK.tmp"
    chmod 644 "$CANDIDATE_APK.tmp" 2>/dev/null || true
    mv -f "$CANDIDATE_APK.tmp" "$CANDIDATE_APK"
    write_status "installing" "false" "false" "正在直接安装新版本 APK（未启用自动回滚看门狗）"
    install_out=$(pm install -r -t "$CANDIDATE_APK" 2>&1) || {
        log_line "pm install failed: $install_out"
        write_status "install_failed" "false" "false" "pm install 失败：$install_out"
        am start -n "$ACTIVITY" >>"$LOG_FILE" 2>&1 || true
        exit 1
    }
    log_line "pm install direct output: $install_out"
    am start -W -n "$ACTIVITY" >>"$LOG_FILE" 2>&1 || true
    write_status "installed_no_watchdog" "true" "false" "已完成直接安装并拉起应用（看门狗心跳检测已关闭）"
    exit 0
}

cmd="${1:-}"
case "$cmd" in
    --spawn)
        apk="${2:-}"
        if [ -z "$apk" ] || [ ! -f "$apk" ]; then
            echo '{"ok":false,"error":"缺少有效的候选 APK 路径"}'
            exit 1
        fi
        acquire_update_lock || exit 1
        trap release_update_lock EXIT
        export SOLBRAID_UPDATE_LOCK_HELD=1
        sub_flag="--apply"
        if [ "${SOLBRAID_WATCHDOG_ENABLED:-true}" = "false" ]; then
            sub_flag="--install-direct"
        fi
        if command -v setsid >/dev/null 2>&1; then
            SOLBRAID_WATCHDOG_TIMEOUT="$TIMEOUT_SEC" setsid /system/bin/sh "$0" "$sub_flag" "$apk" </dev/null >>"$LOG_FILE" 2>&1 &
        else
            SOLBRAID_WATCHDOG_TIMEOUT="$TIMEOUT_SEC" nohup /system/bin/sh "$0" "$sub_flag" "$apk" </dev/null >>"$LOG_FILE" 2>&1 &
        fi
        worker_pid=$!
        [ ! -d "$LOCK_DIR" ] || printf '%s\n' "$worker_pid" > "$LOCK_DIR/pid"
        trap - EXIT
        printf '{"ok":true,"spawned":true,"watchdogEnabled":%s,"timeoutSec":%s,"apk":"%s","statusFile":"%s","logFile":"%s"}\n' \
            "$([ "$sub_flag" = "--apply" ] && echo true || echo false)" "$TIMEOUT_SEC" "$apk" "$STATUS_FILE" "$LOG_FILE"
        ;;
    --apply)
        [ "${SOLBRAID_UPDATE_LOCK_HELD:-}" = 1 ] || acquire_update_lock || exit 1
        trap release_update_lock EXIT
        do_apply "${2:-}"
        ;;
    --install-direct)
        [ "${SOLBRAID_UPDATE_LOCK_HELD:-}" = 1 ] || acquire_update_lock || exit 1
        trap release_update_lock EXIT
        do_install_direct "${2:-}"
        ;;
    --spawn-rollback)
        if [ ! -f "$BACKUP_APK" ]; then
            echo '{"ok":false,"error":"暂无可回滚的上一稳定版本备份"}'
            exit 1
        fi
        acquire_update_lock || exit 1
        trap release_update_lock EXIT
        export SOLBRAID_UPDATE_LOCK_HELD=1
        if command -v setsid >/dev/null 2>&1; then
            setsid /system/bin/sh "$0" --rollback </dev/null >>"$LOG_FILE" 2>&1 &
        else
            nohup /system/bin/sh "$0" --rollback </dev/null >>"$LOG_FILE" 2>&1 &
        fi
        worker_pid=$!
        [ ! -d "$LOCK_DIR" ] || printf '%s\n' "$worker_pid" > "$LOCK_DIR/pid"
        trap - EXIT
        printf '{"ok":true,"spawned":true,"rollback":true,"backupApk":"%s","statusFile":"%s","logFile":"%s"}\n' "$BACKUP_APK" "$STATUS_FILE" "$LOG_FILE"
        ;;
    --rollback)
        [ "${SOLBRAID_UPDATE_LOCK_HELD:-}" = 1 ] || acquire_update_lock || exit 1
        trap release_update_lock EXIT
        do_rollback "用户手动触发回滚"
        ;;
    --status)
        st="{}"
        hb="{}"
        [ -f "$STATUS_FILE" ] && st=$(cat "$STATUS_FILE" 2>/dev/null || printf '{}')
        [ -f "$HEARTBEAT_FILE" ] && hb=$(cat "$HEARTBEAT_FILE" 2>/dev/null || printf '{}')
        has_bak="false"
        [ -f "$BACKUP_APK" ] && has_bak="true"
        printf '{"status":%s,"heartbeat":%s,"hasBackup":%s,"backupApk":"%s","logFile":"%s"}\n' "$st" "$hb" "$has_bak" "$BACKUP_APK" "$LOG_FILE"
        ;;
    *)
        echo "Usage: $0 [--spawn <apk> | --apply <apk> | --install-direct <apk> | --spawn-rollback | --rollback | --status]" >&2
        exit 1
        ;;
esac
