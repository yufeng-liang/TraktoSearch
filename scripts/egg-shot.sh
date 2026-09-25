#!/usr/bin/env bash
# 霉粉彩蛋截图迭代：把预览包拉到指定时刻、定格画面、抓一张 PNG 回本地。
#
# 前置：先装预览包 —— ./gradlew installEggpreview
#   脚本刻意不自动跑它：一次构建要几十秒且要独占 Gradle daemon，什么时候重装由人决定。
#   app/build.gradle.kts 的 defaultConfig.ndk.abiFilters 只留了 arm64-v8a
#   （sherpa-onnx 全 ABI 太重），所以这个包**装不进 x86_64 模拟器**，只能用 arm64 真机。
#   预览包与正式包是两个 applicationId，装上去不会顶掉设备里那份 3.6.0 的数据。
#   设备还要处于解锁状态：锁屏挡在前面时抓回来的就是锁屏。
#
#   install 任务在某些机器上会以「Failed to install on any devices」失败（2026-09-11
#   在 23116PN5BC 上遇到），打包本身是成功的，直接顶上：
#     ./gradlew :app:assembleEggpreview && \
#       adb install -r app/build/outputs/apk/eggpreview/app-eggpreview.apk
#
# 用法: scripts/egg-shot.sh <ms|eraN|globeN|encoreN> [输出文件]
#   ms      直接给毫秒                scripts/egg-shot.sh 120400
#   eraN    第 N 张专辑卡片（1..12）  scripts/egg-shot.sh era7
#   globeN  雪景球的第 N 拍（1..6）   scripts/egg-shot.sh globe3
#   encoreN Showgirl 加曲续章第 N 拍（1..4）  scripts/egg-shot.sh encore2
#
# eraN 不在这里换算成毫秒，只把 0 起的索引传给 app，由 SwiftieTimeline.eraStartMs()
# 现算：卡片时长按曲目数派生，抄一份常量表进 shell 迟早与账本对不上。
#
# globeN 的毫秒对照表。这几个值落在最后一张卡片**内部**，账本里没有对应常量可取，
# 来源是 SwiftieTimeline 的段落常量加终局分镜，**改账本要同步改这里**：
#   globe1  118600  曲目卷收中
#   globe2  120000  转正 + 球成型
#   globe3  120900  雪开洒 + 房子淡入
#   globe4  121700  心升起点亮
#   globe5  122600  7·3 印落底座 + 球自转
#   globe6  124000  淡出上浮
#
# 环境变量：
#   ANDROID_SERIAL   多设备时指定目标（adb 自己认这个变量）
#   EGG_SHOT_DELAY   截图前等待秒数，默认 2；冷启动慢的机子可以加大
#   EGG_SHOT_HUD=1   叠一行毫秒/专辑索引的调试文字（会一起进截图，默认关）
set -euo pipefail

PKG="com.tracktosearch.eggpreview"
ACTIVITY="$PKG/com.tracktosearch.eggpreview.SwiftieEggPreviewActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DELAY="${EGG_SHOT_DELAY:-2}"

die() { echo "egg-shot: $*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
用法: scripts/egg-shot.sh <ms|eraN|globeN|encoreN> [输出文件]

  ms      直接给毫秒                scripts/egg-shot.sh 120400
  eraN    第 N 张专辑卡片（1..12）  scripts/egg-shot.sh era7
  globeN  雪景球的第 N 拍（1..6）   scripts/egg-shot.sh globe3
  encoreN Showgirl 加曲续章第 N 拍（1..4）  scripts/egg-shot.sh encore2

装包: ./gradlew installEggpreview   （只支持 arm64 真机）
EOF
  exit 1
}

# 雪景球六拍 → 毫秒，见文件头的对照表
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

# Showgirl 加曲续章四拍 → 毫秒。**改账本要同步改这里**。
#
# 起点 = eraStartMs(11) 103613 + SHOWGIRL_ENCORE_AT 1900 = 105513。eraStartMs(11)
# 依赖它前面 11 张的时长（含 TTPD 的 4700 前摇与四张卡的时间挪移），
# 所以这些数不能靠猜 —— 账本一动就要按 `SwiftieTimelineTest` 的断言重算一遍。
encore_ms() {
  case "$1" in
    1) echo 105963 ;;   # 尘埃聚字中
    2) echo 106413 ;;   # 尘埃聚成、真字开始淡入
    3) echo 106813 ;;   # 日期已翻成 2026-09-25
    4) echo 107913 ;;   # 四首加曲全部落墨
    *) die "encoreN 的 N 只能是 1..4：encore$1" ;;
  esac
}

TARGET="${1:-}"
OUT="${2:-}"
[ -n "$TARGET" ] || usage

command -v adb >/dev/null 2>&1 || die "PATH 里找不到 adb，先把 platform-tools 加进去"

# 只数状态为 device 的行：unauthorized / offline 的设备接了也用不了，
# 让它们参与计数只会把「多设备」的提示报得莫名其妙
DEVICES="$(adb devices | tr -d '\r' | awk '$2 == "device" { print $1 }')"
COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
[ "$COUNT" -gt 0 ] || die "没有可用设备（adb devices 里状态为 device 的一台都没有）"
if [ "$COUNT" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  die "接了 $COUNT 台设备，用 ANDROID_SERIAL=<serial> 指定一台：
$DEVICES"
fi

# 用 `pm path` 而不是 `pm list packages`：HyperOS 上 `pm list packages` 会返回空列表
# （2026-09-11 在 23116PN5BC 上实测，包明明装着、`pm path` 也有输出），
# 拿它做检查会一直报「设备上没装」。`pm path` 装了退出码 0、没装非 0。
adb shell pm path "$PKG" >/dev/null 2>&1 \
  || die "设备上没装 $PKG，先跑 ./gradlew installEggpreview（arm64 真机）"

# 开发机常把动画时长缩放调成 0 图个跳转快，而彩蛋会因此走「减少动效」的静态终态，
# 126s 序列一帧都不播（见 SwiftieReducedMotion.kt；另一个触发条件是无障碍里的
# accessibility_display_animation_disabled）。不先探一下，会对着一张永远不变的
# 静态图改半小时。
SCALE="$(adb shell settings get global animator_duration_scale 2>/dev/null | tr -d '\r')"
case "$SCALE" in
  0|0.0|0.00) die "设备的 animator_duration_scale = 0，彩蛋会走静态终态、序列不播。
先跑: adb shell settings put global animator_duration_scale 1" ;;
esac

# app 侧的 era 是 0 起（与 SwiftieTimeline 的索引同一套编号），脚本的 eraN 是 1 起
EXTRAS=()
case "$TARGET" in
  era[1-9]|era1[0-2])
    EXTRAS=(--ei era "$(( ${TARGET#era} - 1 ))")
    ;;
  globe[1-6])
    EXTRAS=(--el ms "$(globe_ms "${TARGET#globe}")")
    ;;
  encore[1-4])
    EXTRAS=(--el ms "$(encore_ms "${TARGET#encore}")")
    ;;
  *[!0-9]*|'')
    die "看不懂的参数「$TARGET」：要么是纯毫秒，要么是 era1..era12 / globe1..globe6 / encore1..encore4"
    ;;
  *)
    EXTRAS=(--el ms "$TARGET")
    ;;
esac
[ "${EGG_SHOT_HUD:-0}" = "0" ] || EXTRAS+=(--ez hud true)

[ -n "$OUT" ] || OUT="$ROOT/build/egg-shots/$TARGET-$(date +%Y%m%d-%H%M%S).png"
mkdir -p "$(dirname "$OUT")"

# 息屏时 screencap 抓回来是纯黑，先唤醒一次。失败不致命（有些 ROM 不响应这个键值）
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

# -S 先把进程停掉再起：每次都是干净的一帧，不会捡到上一次留在栈里的 Activity。
# -W 等启动真的完成再返回，后面那个 sleep 才是纯留给首帧渲染（含 AGSL 编译）的，
# 不用把冷启动那几百毫秒也算进去。
# paused 恒为 true —— 这个脚本只干截图这一件事，要看动的画面直接从桌面点图标进去
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
