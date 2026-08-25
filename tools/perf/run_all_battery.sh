#!/usr/bin/env bash
# 按场景批量采集耗电（batterystats 每 UID 估算 mAh）。窗口默认 60s 以提升分辨率。
# 用法: run_all_battery.sh [场景名...]
set -uo pipefail
export SERIAL="${SERIAL:-eeb30d23}"
export PKG="${PKG:-com.tracktosearch}"
export OUT="${OUT:-perf-artifacts/cpu-battery}"
export APP_UID="${APP_UID:-u0a511}"
DUR="${DUR:-60}"
mkdir -p "$OUT"

A() { adb -s "$SERIAL" shell "$@" >/dev/null 2>&1; }
tap() { A "input tap $1 $2"; }
probe() { bash tools/perf/scenario_battery.sh "$1" "$2" "${3:-}"; }

TAB_SEARCH="247 3045"; TAB_DISCOVER="563 3045"; TAB_ME="879 3045"; TAB_SETTINGS="1195 3045"
export TAB_SEARCH TAB_DISCOVER TAB_ME TAB_SETTINGS

fling() {
  local end=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$end" ]; do
    A "input swipe 720 2300 720 1000 220"; A "input swipe 720 1000 720 2300 220"
  done
}
cycle_tabs() {
  local end=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$end" ]; do
    tap $TAB_SEARCH; sleep 0.6; tap $TAB_DISCOVER; sleep 0.6
    tap $TAB_ME; sleep 0.6; tap $TAB_SETTINGS; sleep 0.6; tap $TAB_DISCOVER; sleep 0.6
  done
}
export -f fling cycle_tabs A tap

goto_tab() { tap $1; sleep 2.5; }

run_scn() {
  case "$1" in
    discover_idle)   goto_tab "$TAB_DISCOVER"; probe discover_idle "$DUR" ;;
    discover_scroll) goto_tab "$TAB_DISCOVER"; probe discover_scroll "$DUR" "fling $DUR" ;;
    me_idle)         goto_tab "$TAB_ME"; probe me_idle "$DUR" ;;
    me_scroll)       goto_tab "$TAB_ME"; probe me_scroll "$DUR" "fling $DUR" ;;
    settings_idle)   goto_tab "$TAB_SETTINGS"; probe settings_idle "$DUR" ;;
    tab_switch)      goto_tab "$TAB_DISCOVER"; probe tab_switch "$DUR" "cycle_tabs $DUR" ;;
    detail_scroll)   probe detail_scroll "$DUR" "fling $DUR" ;;
    bg_screen_off)   A "input keyevent 26"; sleep 2; probe bg_screen_off "$DUR"; A "input keyevent 26"; sleep 1; A "input keyevent 82" ;;
    *) echo "未知场景: $1" >&2 ;;
  esac
  sleep 1.5
}

LIST="${*:-discover_idle discover_scroll me_scroll settings_idle tab_switch bg_screen_off}"
for s in $LIST; do echo "=== $s ==="; run_scn "$s"; done
