#!/usr/bin/env bash
# 单场景 CPU / 帧率 / GPU / CPU 频率采样。采样窗口由设备侧计时，避免 adb 往返污染。
# 用法: scenario_cpu.sh <标签> <持续秒> [动作命令]
set -uo pipefail

SERIAL="${SERIAL:-eeb30d23}"
PKG="${PKG:-com.tracktosearch}"
OUT="${OUT:-perf-artifacts/cpu-battery}"
LABEL="${1:?need label}"
DURATION="${2:?need duration seconds}"
ACTION="${3:-}"
POLICIES="0 2 5 7"

mkdir -p "$OUT"
adb_sh() { adb -s "$SERIAL" shell "$@"; }

PID="$(adb_sh pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
if [ -z "$PID" ]; then echo "[$LABEL] 进程未运行" >&2; exit 1; fi

RAW="$OUT/.snap_$LABEL"
read -r -d '' SAMPLER <<EOF || true
S() {
  echo NS=\$(date +%s%N)
  head -1 /proc/stat
  echo '#P'; cat /proc/$PID/stat
  echo '#TH'; cat /proc/$PID/task/*/stat 2>/dev/null
  for p in $POLICIES; do echo "#FREQ \$p"; cat /sys/devices/system/cpu/cpufreq/policy\$p/stats/time_in_state 2>/dev/null; done
}
cat /sys/class/kgsl/kgsl-3d0/gpubusy >/dev/null 2>&1
S
sleep $DURATION
S
echo "#GPU \$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)"
EOF

adb_sh "dumpsys gfxinfo $PKG reset" >/dev/null 2>&1
adb_sh "$SAMPLER" | tr -d '\r' > "$RAW" &
SAMPLER_JOB=$!
sleep 0.4
[ -n "$ACTION" ] && eval "$ACTION"
wait "$SAMPLER_JOB"
adb_sh "dumpsys gfxinfo $PKG" > "$OUT/gfx_$LABEL.txt" 2>&1
adb -s "$SERIAL" exec-out screencap -p > "$OUT/shot_$LABEL.png" 2>/dev/null
bash tools/perf/_report_cpu.awk.sh "$LABEL" "$OUT" "$RAW"

FRAMES="$(grep -E 'Total frames rendered' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
JANK="$(grep -E 'Janky frames:' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
P50="$(grep -E '50th percentile' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
P90="$(grep -E '^ *90th percentile' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
P99="$(grep -E '^ *99th percentile' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
MISS="$(grep -E 'Number Frame deadline missed:' "$OUT/gfx_$LABEL.txt" | head -1 | sed 's/^ *//')"
echo "[$LABEL] $FRAMES | $JANK | $P50 | $P90 | $P99 | $MISS"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$LABEL" "$FRAMES" "$JANK" "$P50" "$P90" "$P99" "$MISS" >> "$OUT/gfx_summary.tsv"
rm -f "$RAW"
