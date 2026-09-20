#!/usr/bin/env bash
# Lover 时代专项优化的截图迭代：把 loverpreview 包拉到指定时刻、定格画面、抓一张 PNG 回本地。
#
# 与 folklore 那套 scripts/egg-shot.sh 是**兄弟脚本而不是一份**：两边在各自工作树里并行改，
# 装机身份也必须分开（这个脚本用 com.tracktosearch.loverpreview），合并到 master 时
# 两份脚本各自服务自己的预览包，互不覆盖。
#
# 前置：先装预览包。这个脚本刻意不自动跑构建（几十秒且要吃 Gradle daemon）：
#   ./gradlew :app:assembleLoverpreview && \
#     adb install -r app/build/outputs/apk/loverpreview/app-loverpreview.apk
#   （:app:installLoverpreview 在本机会报 Failed to install on any devices，
#     打包本身是成功的，直接顶上即可 —— 同 eggpreview 的教训。）
#   包只含 arm64-v8a（sherpa-onnx 的取舍），装不进 x86_64 模拟器，只能用 arm64 真机；
#   设备要处于解锁状态，锁屏挡在前面时抓回来的就是锁屏。
#
# 用法: scripts/lover-shot.sh <ms|eraN|globeN|loverN|midnightN> [输出文件]
#   ms       直接给毫秒                scripts/lover-shot.sh 53155
#   eraN     第 N 张专辑卡片（1..12）  scripts/lover-shot.sh era7
#   globeN   雪景球的第 N 拍（1..6）   scripts/lover-shot.sh globe3
#   loverN   Lover 那一箭的四个瞬间：  scripts/lover-shot.sh lover2
#     lover1  撒放后 200ms —— 箭刚离弦、还压在弓与卡片上（飞行进度 t≈0.22）
#     lover2  飞行中点 —— 箭悬在卡片与背景心之间（t≈0.5）
#     lover3  命中后 200ms —— 箭已穿心、心在回弹摆里（迸光与箭影都在）
#     lover4  回弹收住 —— 插住不动的稳态（t=1）
#   midnightN  Midnights 面钟上弦的四个瞬间：  scripts/lover-shot.sh midnight3
#     midnight1  起转后 150ms —— 两针刚离 2:00（分针转了几度）
#     midnight2  走针过半 —— 分针转过半圈、时针推到 2:30 一带
#     midnight3  回吸中 —— 两针已到 3:00 又往回退了一点（落位的那一「顿」）
#     midnight4  完全落位 —— 严格 3:00、之后不再动
#
# loverN 的毫秒**从 SwiftieTimeline 与 SwiftieLoverArcher 现算**：卡片时长按曲目数派生、
# 锚点卡片（索引 0 与 6）各多停 600ms、Lover 索引又由 SwiftieErasData 钉死 ——
# 抄一份常量表进 shell 迟早与账本对不上（egg-shot.sh 对 eraN 就是这么处理的）。
#
# 环境变量：
#   ANDROID_SERIAL   多设备时指定目标（adb 自己认这个变量）
#   EGG_SHOT_DELAY   截图前等待秒数，默认 2；冷启动慢的机子可以加大
#   EGG_SHOT_HUD=1   叠一行毫秒/专辑索引的调试文字（会一起进截图，默认关）
set -euo pipefail

PKG="com.tracktosearch.loverpreview"
ACTIVITY="$PKG/com.tracktosearch.loverpreview.SwiftieEggPreviewActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DELAY="${EGG_SHOT_DELAY:-2}"

die() { echo "lover-shot: $*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
用法: scripts/lover-shot.sh <ms|eraN|globeN|loverN|midnightN> [输出文件]

  ms        直接给毫秒                scripts/lover-shot.sh 53155
  eraN      第 N 张专辑卡片（1..12）  scripts/lover-shot.sh era7
  globeN    雪景球的第 N 拍（1..6）   scripts/lover-shot.sh globe3
  loverN    Lover 那一箭的四个瞬间 1..4  scripts/lover-shot.sh lover2
  midnightN Midnights 面钟上弦的四个瞬间 1..4  scripts/lover-shot.sh midnight3

装包: ./gradlew :app:assembleLoverpreview && adb install -r app/build/outputs/apk/loverpreview/app-loverpreview.apk （只支持 arm64 真机）
EOF
  exit 1
}

# 雪景球六拍 → 毫秒，与 egg-shot.sh 同一张对照表（改终局分镜要同步改两处）
globe_ms() {
  case "$1" in
    1) echo 118600 ;;
    2) echo 120000 ;;
    3) echo 120900 ;;
    4) echo 121700 ;;
    5) echo 122600 ;;
    6) echo 124000 ;;
    *) die "globeN 的 N 只能是 1..6：globe$1" ;;
  esac
}

# ── Lover 那一箭的四个瞬间，全部现算 ──────────────────────────────
#
# 结构：Lover 卡片起点 = ERAS_CARDS_START + Σ(cardDurationMs(0..5))，
# 卡片索引 6 在 ANCHOR_INDICES 里（+600ms 停留）。三个结构常量从源码里 grep，
# 剩下两个数值直接取自 SwiftieLoverArcher 的 LOVER_SHOT_MS / LOVER_FLIGHT_MS。
TIMELINE="app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieTimeline.kt"
ARCHER="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieLoverArcher.kt"
LOVER_INDEX="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieErasData.kt"
BACKDROP="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieEraBackdrop.kt"

# 取 `const val X: Long = 12_345L` 里的 12_345。刻意不从整行里抓第一串数字：
# 常量名 `CARD_BASE_MS` 本身带下划线，`[0-9_]+` 会先命中名字里的 `_`。
# 所以先定位等号（允许等号前后有空格），再取下划线分隔的数字串。
num() { grep -m1 -E "$2" "$1" | grep -oE '= *[0-9][0-9_]*' | grep -oE '[0-9_]+' | tr -d '_'; }

# 第 N 张（0 起）卡片的起点毫秒。**任意一张都能算**，不再只认 Lover：
# 卡片时长按曲目数派生、锚点卡片（ANCHOR_INDICES 里那几个）各多停 600ms。
era_start() {
  local want="$1" base per anchor_bonus anchor_idx idx total i
  base="$(grep -m1 'const val CARD_BASE_MS' "$TIMELINE" | grep -oE '= *[0-9_]+' | grep -oE '[0-9_]+' | tr -d '_')"
  per="$(num "$TIMELINE" 'const val CARD_PER_TRACK_MS')"
  anchor_bonus="$(num "$TIMELINE" 'const val CARD_ANCHOR_BONUS_MS')"
  # ERAS_CARDS_START 是 val 且由两个常量相加派生，直接取它前一行的算式不方便；
  # 用 SOLVE_MS + DIFFUSION_MS + ERAS_INTRO_MS 三个 const 拼
  local solve diff intro
  solve="$(num "$TIMELINE" 'const val SOLVE_MS')"
  diff="$(num "$TIMELINE" 'const val DIFFUSION_MS')"
  intro="$(num "$TIMELINE" 'const val ERAS_INTRO_MS')"
  # 额外停留索引：CARD_ANCHOR_BONUS_INDICES 那行的 setOf(6)，**不是**触感上的
  # ANCHOR_INDICES（那个是 {0, 6}，TS1 已经不再多停 600ms）
  anchor_idx="$(grep -m1 'CARD_ANCHOR_BONUS_INDICES' "$TIMELINE" | grep -oE 'setOf\([0-9, ]+\)' | grep -oE '[0-9]+' | paste -sd' ' -)"
  # 12 张卡片的曲目数，与 timeline 里的列表同一顺序（补齐四张 TV 独有曲目后）
  counts=(11 26 22 30 21 15 18 17 17 24 31 12)
  total=$(( solve + diff + intro ))
  for ((i = 0; i < want; i++)); do
    total=$(( total + base + per * counts[i] ))
    case " $anchor_idx " in *" $i "*) total=$(( total + anchor_bonus )) ;; esac
  done
  echo "$total"
}

# Lover 的索引从 ErasData 取，不抄字面量
lover_index() { num "$LOVER_INDEX" 'const val LOVER_INDEX'; }

lover_ms() {
  local start draw shot flight
  start="$(era_start "$(lover_index)")"
  draw="$(num "$ARCHER" 'const val LOVER_DRAW_START_MS')"
  # LOVER_SHOT_MS 在源码里写成 "LOVER_DRAW_START_MS + 620L" 的算式，取不到纯数字，
  # 按同一式子现算：拉弓 620ms 到满后撒放
  shot="$(( draw + 620 ))"
  flight="$(num "$ARCHER" 'const val LOVER_FLIGHT_MS')"
  case "$1" in
    1) echo $(( start + shot + 200 )) ;;
    2) echo $(( start + shot + flight / 2 )) ;;
    3) echo $(( start + shot + flight + 200 )) ;;
    4) echo $(( start + shot + flight + 420 )) ;;
    *) die "loverN 的 N 只能是 1..4：lover$1" ;;
  esac
}

# ── Midnights 面钟上弦的四个瞬间，同样现算 ────────────────────────
#
# Midnights 是卡片顺序里的第 10 张（0 起索引 9）—— 与上面那张曲目数表同一套编号，
# 那 12 个数字本来就是这个脚本里唯一抄自 SwiftieTimeline 的一处。
# 起手姿态由 SwiftieEraBackdrop 的 MIDNIGHT_WIND_* 几个常量决定，那几个值也可能被改，
# 所以连起始偏移一起 grep，不写 800/1800 这种字面量进脚本。
midnight_ms() {
  local start wind wind_len settle
  start="$(era_start 9)"
  wind="$(num "$BACKDROP" 'const val MIDNIGHT_WIND_START_MS')"
  # MIDNIGHT_WIND_MS / MIDNIGHT_SETTLE_MS 是 Float（写成 1_800f），num() 的等号锚点认不了
  # ——它要求等号右边直接接数字，而这里的正则只看等号后有没有下划线分隔的数字串，够用
  wind_len="$(grep -m1 'const val MIDNIGHT_WIND_MS' "$BACKDROP" | grep -oE '= *[0-9][0-9_]*' | grep -oE '[0-9_]+' | tr -d '_')"
  settle="$(grep -m1 'const val MIDNIGHT_SETTLE_MS' "$BACKDROP" | grep -oE '= *[0-9][0-9_]*' | grep -oE '[0-9_]+' | tr -d '_')"
  case "$1" in
    1) echo $(( start + wind + 150 )) ;;
    2) echo $(( start + wind + wind_len / 2 )) ;;
    3) echo $(( start + wind + wind_len + settle / 2 )) ;;
    4) echo $(( start + wind + wind_len + settle + 200 )) ;;
    *) die "midnightN 的 N 只能是 1..4：midnight$1" ;;
  esac
}

TARGET="${1:-}"
OUT="${2:-}"
[ -n "$TARGET" ] || usage

command -v adb >/dev/null 2>&1 || die "PATH 里找不到 adb，先把 platform-tools 加进去"

# 只数状态为 device 的行：unauthorized / offline 的设备接了也用不了
DEVICES="$(adb devices | tr -d '\r' | awk '$2 == "device" { print $1 }')"
COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
[ "$COUNT" -gt 0 ] || die "没有可用设备（adb devices 里状态为 device 的一台都没有）"
if [ "$COUNT" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  die "接了 $COUNT 台设备，用 ANDROID_SERIAL=<serial> 指定一台：
$DEVICES"
fi

# 用 `pm path` 而不是 `pm list packages`：HyperOS 上 `pm list packages` 会返回空列表
# （2026-09-11 在 23116PN5BC 上实测），拿它做检查会一直报「设备上没装」。
adb shell pm path "$PKG" >/dev/null 2>&1 \
  || die "设备上没装 $PKG，先跑 ./gradlew :app:assembleLoverpreview 再 adb install -r（arm64 真机）"

# animator_duration_scale = 0 时彩蛋会走「减少动效」的静态终态，126s 序列一帧都不播
# （见 SwiftieReducedMotion.kt）。不先探一下，会对着一张永远不变的静态图改半小时。
SCALE="$(adb shell settings get global animator_duration_scale 2>/dev/null | tr -d '\r')"
case "$SCALE" in
  0|0.0|0.00) die "设备的 animator_duration_scale = 0，彩蛋会走静态终态、序列不播。
先跑: adb shell settings put global animator_duration_scale 1" ;;
esac

# app 侧的 era 是 0 起，脚本的 eraN 是 1 起
EXTRAS=()
case "$TARGET" in
  era[1-9]|era1[0-2])
    EXTRAS=(--ei era "$(( ${TARGET#era} - 1 ))")
    ;;
  globe[1-6])
    EXTRAS=(--el ms "$(globe_ms "${TARGET#globe}")")
    ;;
  lover[1-4])
    EXTRAS=(--el ms "$(lover_ms "${TARGET#lover}")")
    ;;
  midnight[1-4])
    EXTRAS=(--el ms "$(midnight_ms "${TARGET#midnight}")")
    ;;
  *[!0-9]*|'')
    die "看不懂的参数「$TARGET」：要么是纯毫秒，要么是 era1..era12 / globe1..globe6 / lover1..lover4 / midnight1..midnight4"
    ;;
  *)
    EXTRAS=(--el ms "$TARGET")
    ;;
esac
[ "${EGG_SHOT_HUD:-0}" = "0" ] || EXTRAS+=(--ez hud true)

[ -n "$OUT" ] || OUT="$ROOT/build/egg-shots/lover-$TARGET-$(date +%Y%m%d-%H%M%S).png"
mkdir -p "$(dirname "$OUT")"

# 息屏时 screencap 抓回来是纯黑，先唤醒一次。失败不致命（有些 ROM 不响应这个键值）
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

# -S 先把进程停掉再起：每次都是干净的一帧，不会捡到上一次留在栈里的 Activity。
# -W 等启动真的完成再返回，后面那个 sleep 是留给首帧渲染（含 AGSL 编译）的。
# paused 恒为 true —— 这个脚本只干截图这一件事，要看动的画面直接从桌面点图标进去。
START_OUT="$(adb shell am start -S -W -n "$ACTIVITY" --ez paused true "${EXTRAS[@]}" 2>&1 | tr -d '\r')"
case "$START_OUT" in
  *Error*) die "拉起失败：
$START_OUT" ;;
esac

sleep "$DELAY"
adb exec-out screencap -p > "$OUT"
if [ ! -s "$OUT" ]; then
  rm -f "$OUT"
  die "截图是空文件，adb 连接可能断了"
fi
echo "$OUT"
