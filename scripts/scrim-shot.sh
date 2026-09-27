#!/usr/bin/env bash
# 背景光晕压制量对照网格截图：把某一屏的四档候选拉起来、各抓一张 PNG 回本地。
#
# 前置：先装预览包 —— ./gradlew :app:assembleScrimpreview && adb install -r \
#           app/build/outputs/apk/scrimpreview/app-scrimpreview.apk
#   刻意不在这里自动构建：一次要几十秒且独占 Gradle daemon，什么时候重装由人决定。
#   设备上那份已装的正式包是 release 签名，同包名覆盖安装会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
#   所以这个包走独立 applicationId（com.tracktosearch.scrimpreview），装上去是共存不是顶掉。
#   abiFilters 只留 arm64-v8a，装不进 x86_64 模拟器 —— 只能 arm64 真机。
#
# 用法: scripts/scrim-shot.sh <1..6|all> [输出目录]
#   1  主题色系 × 雷诺阿粉（弥散绽放/极光/熔岩灯共用这一档 mix）
#   2  主题色系 × 星夜蓝
#   3  主题色系 × 复古票根
#   4  星云 NEBULA
#   5  水墨 INK
#   6  海滩 BEACH
#   all  1..6 连拍
#
# 屏号在脚本里是 1 起（好念），app 侧 --ei screen 是 0 起，差 1，别混。
# 每屏四档都画在同一张图上（竖排四格，每格自带 mix/scrim 数值标签与渲后色卡），
# 所以标签默认就在画面里 —— 不留标签的 16 张图无法回填。
#
# 环境变量：
#   ANDROID_SERIAL    多设备时指定目标
#   SCRIM_SHOT_DELAY  截图前等待秒数，默认 2（首帧要编译 AGSL，冷启别压太短）
set -euo pipefail

PKG="com.tracktosearch.scrimpreview"
ACTIVITY="$PKG/com.tracktosearch.scrimpreview.ScrimPreviewActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DELAY="${SCRIM_SHOT_DELAY:-2}"
SCREEN_COUNT=6

die() { echo "scrim-shot: $*" >&2; exit 1; }

usage() {
  cat >&2 <<EOF
用法: scripts/scrim-shot.sh <1..$SCREEN_COUNT|all> [输出目录]
EOF
  exit 1
}

TARGET="${1:-}"
OUTDIR="${2:-$ROOT/build/scrim-shots}"
[ -n "$TARGET" ] || usage

command -v adb >/dev/null 2>&1 || die "PATH 里找不到 adb，先把 platform-tools 加进去"

DEVICES="$(adb devices | tr -d '\r' | awk '$2 == "device" { print $1 }')"
COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
[ "$COUNT" -gt 0 ] || die "没有可用设备（adb devices 里状态为 device 的一台都没有）"
if [ "$COUNT" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  die "接了 $COUNT 台设备，用 ANDROID_SERIAL=<serial> 指定一台：
$DEVICES"
fi

# 用 `pm path` 不用 `pm list packages`：HyperOS 上后者会静默返回空列表，
# 拿它做检查会一直报「设备上没装」。
adb shell pm path "$PKG" >/dev/null 2>&1 \
  || die "设备上没装 $PKG，先按文件头那两行构建并安装"

case "$TARGET" in
  all) SCREENS=$(seq 1 "$SCREEN_COUNT") ;;
  ''|*[!0-9]*) die "看不懂的参数「$TARGET」：给 1..$SCREEN_COUNT 或 all" ;;
  *)
    [ "$TARGET" -ge 1 ] && [ "$TARGET" -le "$SCREEN_COUNT" ] \
      || die "屏号只能是 1..$SCREEN_COUNT：$TARGET"
    SCREENS="$TARGET"
    ;;
esac

mkdir -p "$OUTDIR"
# 息屏时 screencap 抓回来是纯黑，先唤醒一次。失败不致命（有些 ROM 不响应这个键值）
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

for SCREEN in $SCREENS; do
  # -S 每次先把进程停掉再起：四档是按相位对齐才横比的，捡到上一次留在栈里的 Activity
  # 就会拿到一份混合相位的图。-W 等启动真完成再返回，后面的 sleep 才是纯留给首帧渲染的。
  START_OUT="$(adb shell am start -S -W -n "$ACTIVITY" --ei screen "$((SCREEN - 1))" 2>&1 | tr -d '\r')"
  case "$START_OUT" in
    *Error*) die "拉起屏 $SCREEN 失败：
$START_OUT" ;;
  esac

  sleep "$DELAY"
  OUT="$OUTDIR/screen$SCREEN.png"
  adb exec-out screencap -p > "$OUT"
  # 空文件 = adb 断了或设备睡了，不能当成「拍到了但都一样」
  [ -s "$OUT" ] || { rm -f "$OUT"; die "屏 $SCREEN 截图是空文件，adb 连接可能断了"; }
  echo "$OUT"
done
