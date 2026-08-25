#!/usr/bin/env bash
# 单场景耗电采样：batterystats 复位 -> 执行动作 -> 读取本 UID 估算电量（mAh）与 CPU 时间。
# 依赖 dumpsys battery unplug 已生效（模拟放电），否则 batterystats 不累计。
# 用法: scenario_battery.sh <标签> <持续秒> [动作命令]
set -uo pipefail

SERIAL="${SERIAL:-eeb30d23}"
PKG="${PKG:-com.tracktosearch}"
OUT="${OUT:-perf-artifacts/cpu-battery}"
APP_UID="${APP_UID:-u0a511}"
LABEL="${1:?need label}"
DURATION="${2:?need duration seconds}"
ACTION="${3:-}"

mkdir -p "$OUT"
adb_sh() { adb -s "$SERIAL" shell "$@"; }

adb_sh "dumpsys battery unplug" >/dev/null 2>&1
adb_sh "dumpsys batterystats --reset" >/dev/null 2>&1
sleep 1

if [ -n "$ACTION" ]; then
  eval "$ACTION" &
  ACT=$!
  sleep "$DURATION"
  wait "$ACT" 2>/dev/null
else
  sleep "$DURATION"
fi

DUMP="$OUT/batt_$LABEL.txt"
adb_sh "dumpsys batterystats" | tr -d '\r' > "$DUMP"

PYTHONIOENCODING=utf-8 python tools/perf/parse_batt.py "$DUMP" "$LABEL" "$APP_UID" "$DURATION" "$OUT"
