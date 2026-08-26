#!/usr/bin/env bash
# 按场景批量采集 CPU / 帧率 / GPU / 频率。
# 用法: run_all_cpu.sh [场景名...]  不带参数则跑全部
set -uo pipefail
export SERIAL="${SERIAL:-eeb30d23}"
export PKG="${PKG:-com.tracktosearch}"
export OUT="${OUT:-perf-artifacts/cpu-battery}"
DUR="${DUR:-20}"
mkdir -p "$OUT"

A() { adb -s "$SERIAL" shell "$@" >/dev/null 2>&1; }
tap() { A "input tap $1 $2"; }
probe() { bash tools/perf/scenario_cpu.sh "$1" "$2" "${3:-}"; }

TAB_SEARCH="247 3045"; TAB_DISCOVER="563 3045"; TAB_ME="879 3045"; TAB_SETTINGS="1195 3045"
export TAB_SEARCH TAB_DISCOVER TAB_ME TAB_SETTINGS

# 上下往复 fling，持续 $1 秒
fling() {
  local end=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$end" ]; do
    A "input swipe 720 2300 720 1000 220"
    A "input swipe 720 1000 720 2300 220"
  done
}
# 循环切 Tab，持续 $1 秒
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
    search_idle)     goto_tab "$TAB_SEARCH"; probe search_idle "$DUR" ;;
    me_idle)         goto_tab "$TAB_ME"; probe me_idle "$DUR" ;;
    me_scroll)       goto_tab "$TAB_ME"; probe me_scroll "$DUR" "fling $DUR" ;;
    settings_idle)   goto_tab "$TAB_SETTINGS"; probe settings_idle "$DUR" ;;
    settings_scroll) goto_tab "$TAB_SETTINGS"; probe settings_scroll "$DUR" "fling $DUR" ;;
    tab_switch)      goto_tab "$TAB_DISCOVER"; probe tab_switch "$DUR" "cycle_tabs $DUR" ;;
    detail_idle)     goto_tab "$TAB_ME"; for i in 1 2 3; do A "input swipe 720 1000 720 2600 150"; done; sleep 2; tap 240 940; sleep 6; probe detail_idle "$DUR" ;;
    detail_scroll)   probe detail_scroll "$DUR" "fling $DUR" ;;
    detail_back)     A "input keyevent 4"; sleep 2; probe after_back "$DUR" ;;
    bg_screen_on)    A "input keyevent 3"; sleep 2; probe bg_screen_on "$DUR" ;;
    bg_screen_off)   A "input keyevent 26"; sleep 2; probe bg_screen_off "$DUR"; A "input keyevent 26"; sleep 1; A "input keyevent 82" ;;
    fg_return)       A "monkey -p $PKG -c android.intent.category.LAUNCHER 1"; sleep 3; probe fg_return "$DUR" ;;
    *) echo "未知场景: $1" >&2 ;;
  esac
  sleep 1.5
}

ALL="discover_idle discover_scroll search_idle me_idle me_scroll settings_idle settings_scroll tab_switch detail_idle detail_scroll detail_back bg_screen_on bg_screen_off fg_return"
LIST="${*:-$ALL}"
for s in $LIST; do echo "=== $s ==="; run_scn "$s"; done
