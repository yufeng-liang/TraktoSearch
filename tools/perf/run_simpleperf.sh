#!/usr/bin/env bash
# Simpleperf 采样：录制指定场景动作期间的 CPU 采样。
# 用法: run_simpleperf.sh <标签> <秒> <动作命令>
set -uo pipefail
SERIAL="${SERIAL:-eeb30d23}"
PKG="${PKG:-com.tracktosearch}"
OUT="${OUT:-perf-artifacts/cpu-battery}"
LABEL="${1:?}"; DUR="${2:-20}"; ACTION="${3:-}"
mkdir -p "$OUT"

adb -s "$SERIAL" shell rm -f /data/local/tmp/perf_$LABEL.data
adb -s "$SERIAL" shell "simpleperf record --app $PKG -o /data/local/tmp/perf_$LABEL.data \
  -e cpu-clock -f 4000 -g --duration $DUR" > "$OUT/simpleperf_record_$LABEL.log" 2>&1 &
REC=$!
sleep 1.5
[ -n "$ACTION" ] && eval "$ACTION"
wait "$REC"
tail -3 "$OUT/simpleperf_record_$LABEL.log"

adb -s "$SERIAL" shell "simpleperf report -i /data/local/tmp/perf_$LABEL.data --sort dso,symbol -n --percent-limit 0.4" \
  > "$OUT/perf_self_$LABEL.txt" 2>&1
adb -s "$SERIAL" shell "simpleperf report -i /data/local/tmp/perf_$LABEL.data -g caller --sort symbol --percent-limit 1.5" \
  > "$OUT/perf_callers_$LABEL.txt" 2>&1
adb -s "$SERIAL" pull /data/local/tmp/perf_$LABEL.data "$OUT/perf_$LABEL.data" >/dev/null 2>&1
echo "报告: $OUT/perf_self_$LABEL.txt  $OUT/perf_callers_$LABEL.txt"
head -40 "$OUT/perf_self_$LABEL.txt"
