#!/usr/bin/env bash
# TTPD 时代专项优化的截图迭代：把 ttp dpreview 包拨到打字机的指定一拍、定格、抓一张 PNG 回本地。
#
# 与 folklore 的 scripts/egg-shot.sh、Lover 的 scripts/lover-shot.sh 是**兄弟脚本而不是一份**：
# 三方在各自工作树里并行改舞台代码，装机身份也必须分开（这个脚本用
# com.tracktosearch.ttpdpreview），合并到 master 时三份脚本各自服务自己的预览包。
#
# 前置：先装预览包。这个脚本刻意不自动跑构建（几十秒且要吃 Gradle daemon）：
#   ./gradlew :app:assembleTtpdpreview && \
#     adb install -r app/build/outputs/apk/ttpdpreview/app-ttpdpreview.apk
#   （:app:installTtpdpreview 在本机会报 Failed to install on any devices，
#     打包本身是成功的，直接顶上即可 —— 同 eggpreview 的教训。）
#   包只含 arm64-v8a（sherpa-onnx 的取舍），装不进 x86_64 模拟器，只能用 arm64 真机；
#   设备要处于解锁状态，锁屏挡在前面时抓回来的就是锁屏。
#
# 用法: scripts/ttpd-shot.sh <ms|eraN|typeN|writeN> [输出文件]
#   ms       直接给毫秒                 scripts/ttpd-shot.sh 90000
#   eraN     第 N 张专辑卡片（1..12）   scripts/ttpd-shot.sh era11
#   typeN    打字机的四个瞬间（都在 TTPD 段头那段独奏里，卡片还没出来或刚出来）：
#     type1  独奏第一行的中段 —— 字锤正落在滚筒上（lift 峰值）、行画到六成
#     type2  回车横扫 —— 打字点飞到行末又往回扫，滑架痕在纸右端
#     type3  出纸半程 —— 卡片升到一半、滚筒暗影压在纸上（feedProgress ≈ 0.5）
#     type4  出纸落位 —— 整张纸坐定、机器在屏幕底下（与 era11 的时刻不同：
#           这一拍只等纸，不等 31 行曲目点完）
#   writeN   卡片右下角那行题词的写字（按**卡片自己的时钟**算，见 elapsedInCard）：
#     write1 写到一半 —— 墨铺到五六成、羽毛笔还在场
#     write2 写完抬笔 —— 整句都在、笔已淡走（墨留着不淡）
#     write3 第一行刚写完 —— **笔横向最伸出卡片的一瞬**（看有没有被裁）
#
# typeN 与 writeN 的毫秒**从源码现算**，绝不抄一份常量表进 shell：独奏时长由
# SwiftieTimeline.TTPD_PREROLL_MS 定、出纸时长由 SwiftieEraCard 的 CARD_FEED_MS 定、
# 写字与收笔时长由 SwiftieEraMotifs 的 LETTER_WRITE_WALL_MS / QUILL_RETIRE_MS 定，
# 几处任何一处被调，这里跟着走（egg-shot.sh 对 eraN 就是这么处理的）。
#
# 环境变量：
#   ANDROID_SERIAL   多设备时指定目标（adb 自己认这个变量）
#   EGG_SHOT_DELAY   截图前等待秒数，默认 2；冷启动慢的机子可以加大
#   EGG_SHOT_HUD=1   叠一行毫秒/专辑索引的调试文字（会一起进截图，默认关）
set -euo pipefail

PKG="com.tracktosearch.ttpdpreview"
ACTIVITY="$PKG/com.tracktosearch.ttpdpreview.SwiftieEggPreviewActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DELAY="${EGG_SHOT_DELAY:-2}"

die() { echo "ttpd-shot: $*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
用法: scripts/ttpd-shot.sh <ms|eraN|typeN|writeN> [输出文件]

  ms     直接给毫秒                scripts/ttpd-shot.sh 90000
  eraN   第 N 张专辑卡片（1..12）  scripts/ttpd-shot.sh era11
  typeN  打字机的四个瞬间 1..4     scripts/ttpd-shot.sh type2
  writeN 题词写字的三拍 1..3       scripts/ttpd-shot.sh write1

装包: ./gradlew :app:assembleTtpdpreview && adb install -r app/build/outputs/apk/ttpdpreview/app-ttpdpreview.apk （只支持 arm64 真机）
EOF
  exit 1
}

# ── 源码路径与取值 ────────────────────────────────────────────────
TIMELINE="app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieTimeline.kt"
CARD="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieEraCard.kt"
BACKDROP="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieEraBackdrop.kt"
MOTIFS="app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras/SwiftieEraMotifs.kt"

# 取 `const val X: Long = 12_345L` 里的 12_345。刻意不从整行里抓第一串数字：
# 常量名 `CARD_BASE_MS` 本身带下划线，`[0-9_]+` 会先命中名字里的 `_`。
# 所以先定位等号（允许等号前后有空格），再取下划线分隔的数字串。
num() { grep -m1 -E "$2" "$1" | grep -oE '= *[0-9][0-9_]*' | grep -oE '[0-9_]+' | tr -d '_'; }

# 第 N 张（0 起）卡片的起点毫秒。卡片时长按曲目数派生、锚点卡片
# （ANCHOR_INDICES 里那几个，0 起编号）各多停 600ms、TTPD 那张另有 TTPD_PREROLL_MS 的独奏前摇
# —— 前摇也在卡片时长里（SwiftieTimeline.cardDurationMs），改 TTPD 之后任何一张的
# 起点都要带上它。这里对索引 ≥ 11（TTPD 之后）把前摇补回去。
era_start() {
  local want="$1" base per anchor_bonus anchor_idx idx total i
  base="$(num "$TIMELINE" 'const val CARD_BASE_MS')"
  per="$(num "$TIMELINE" 'const val CARD_PER_TRACK_MS')"
  anchor_bonus="$(num "$TIMELINE" 'const val CARD_ANCHOR_BONUS_MS')"
  # ERAS_CARDS_START 是 val 且由常量相加派生，用它的三个加数拼
  local solve diff intro
  solve="$(num "$TIMELINE" 'const val SOLVE_MS')"
  diff="$(num "$TIMELINE" 'const val DIFFUSION_MS')"
  intro="$(num "$TIMELINE" 'const val ERAS_INTRO_MS')"
  # 锚点索引：ANCHOR_INDICES 那行的 setOf(0, 6)，取等号右边全部的整数
  anchor_idx="$(grep -m1 -A 1 'ANCHOR_INDICES' "$TIMELINE" | grep -oE 'setOf\([0-9, ]+\)' | grep -oE '[0-9]+' | paste -sd' ' -)"
  # 12 张卡片的曲目数，与 timeline 里的列表同一顺序
  counts=(11 13 14 16 13 15 18 16 15 13 31 12)
  local ttp d_idx preroll
  ttp="$(num "$TIMELINE" 'const val TTPD_INDEX')"
  preroll="$(num "$TIMELINE" 'const val TTPD_PREROLL_MS')"
  total=$(( solve + diff + intro ))
  for ((i = 0; i < want; i++)); do
    total=$(( total + base + per * counts[i] ))
    case " $anchor_idx " in *" $i "*) total=$(( total + anchor_bonus )) ;; esac
    # 前摇只加在 TTPD 那一张上（cardDurationMs 的同一条规则）
    if [ "$i" -eq "$ttp" ]; then total=$(( total + preroll )); fi
  done
  echo "$total"
}

# ── 打字机的四个瞬间，全部现算 ────────────────────────────────────
#
# 前两拍落在 TTPD 段头那 TTPD_PREROLL_MS 独奏里（era_start 就是独奏起点，还没有卡片）。
# 节拍按**字符**均分（见 SwiftieEraBackdrop 的 drawTypewriterStub / stubUnits）：
# 打的是 Fortnight 那句，第一行 `I love you,`（11 字）、第二行 `it's ruining my life`（20 字），
# 中间的回车占两个字符单位，共 33 个 —— 回车跨 11 到 13，正中是 12/33 ≈ 36%。
# 所以前两拍写成**前摇的百分比**：前摇一改，两拍跟着走（字符数不变时）。
# 后两拍是出纸：起点 + 前摇 + 出纸时长的一半 / 走完再停半秒。
type_ms() {
  local start feed preroll
  start="$(era_start "$(num "$TIMELINE" 'const val TTPD_INDEX')")"
  feed="$(num "$CARD" 'const val CARD_FEED_MS')"
  preroll="$(num "$TIMELINE" 'const val TTPD_PREROLL_MS')"
  case "$1" in
    # 第一行打到中间：打字头停在字当中（11 字的第一行走到 5~6 个），
    # 一半的字符已经落上去
    1) echo $(( start + preroll * 16 / 100 )) ;;
    # 回车：第一行刚打完，纸正往上走一行（两行都在，第二行还没开始）
    2) echo $(( start + preroll * 36 / 100 )) ;;
    # 出纸半程：卡片升到一半、滚筒暗影压在纸上（feedProgress ≈ 0.5）
    3) echo $(( start + preroll + feed / 2 )) ;;
    # 出纸落位：整张纸坐定、机器在屏幕底下（与 era11 的时刻不同：
    # 这一拍只等纸，不等 31 行曲目点完）
    4) echo $(( start + preroll + feed + 500 )) ;;
    *) die "typeN 的 N 只能是 1..4：type$1" ;;
  esac
}

# ── 题目写字的两拍，同样现算 ───────────────────────────────────────
# 写字的时钟是**卡片自己的**（SwiftieEraCard 的 elapsedInCard：卡片起点 = 出纸起点），
# 所以锚点是 start + preroll，不再加 feed —— 出纸那一秒里笔已经在写了。
write_ms() {
  local start preroll write retire
  start="$(era_start "$(num "$TIMELINE" 'const val TTPD_INDEX')")"
  preroll="$(num "$TIMELINE" 'const val TTPD_PREROLL_MS')"
  write="$(num "$MOTIFS" 'const val LETTER_WRITE_WALL_MS')"
  retire="$(num "$MOTIFS" 'const val QUILL_RETIRE_MS')"
  case "$1" in
    # 写到一半：墨铺到五六成，羽毛笔正压在句尾那一截上
    1) echo $(( start + preroll + write * 55 / 100 )) ;;
    # 写完抬笔：整句都在，笔淡走之后停一拍再抓（0.3s 余量，免得抓到正在淡的那一帧）
    2) echo $(( start + preroll + write + retire + 300 )) ;;
    # 第一行刚写完（笔在最右）：**笔横向最伸出卡片的一瞬**，看它有没有被裁。
    # 第一行 `All's fair in love` 占两句总长的 4.791/(4.791+3.215) ≈ 60%，
    # 加上抬笔停顿的分布，取 66% —— 这时笔尖正落在 `love` 的收笔上
    3) echo $(( start + preroll + write * 66 / 100 )) ;;
    *) die "writeN 的 N 只能是 1..3：write$1" ;;
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
  || die "设备上没装 $PKG，先跑 ./gradlew :app:assembleTtpdpreview 再 adb install -r（arm64 真机）"

# animator_duration_scale = 0 时彩蛋会走「减少动效」的静态终态，126s 序列一帧都不播
# （见 SwiftieReducedMotion.kt）。不先探一下，会对着一张永远不变的静态图改半小时。
SCALE="$(adb shell settings get global animator_duration_scale 2>/dev/null | tr -d '\r')"
case "$SCALE" in
  0|0.0|0.00) die "设备的 animator_duration_scale = 0，彩蛋会走静态终态、序列不播。
先跑: adb shell settings put global animator_duration_scale 1" ;;
esac

# 锁屏挡在前面时 `am start` 照样成功、screencap 照样有图 —— 抓回来的是锁屏，不是卡片
# （2026-09-13 实测：write1/write2 两拍全是锁屏，还都长得像「彩蛋没画出来」）。
# 别靠「截图是不是全黑」判断：这台机器的锁屏自己就是黑底 + 一个指纹圈。
KEYGUARD="$(adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'isKeyguardShowing=' || true)"
case "$KEYGUARD" in
  *true*) die "设备锁着（isKeyguardShowing=true），抓到的是锁屏。先在机器上解锁再跑。" ;;
esac

# app 侧的 era 是 0 起，脚本的 eraN 是 1 起
EXTRAS=()
case "$TARGET" in
  era[1-9]|era1[0-2])
    EXTRAS=(--ei era "$(( ${TARGET#era} - 1 ))")
    ;;
  type[1-4])
    EXTRAS=(--el ms "$(type_ms "${TARGET#type}")")
    ;;
  write[1-3])
    EXTRAS=(--el ms "$(write_ms "${TARGET#write}")")
    ;;
  *[!0-9]*|'')
    die "看不懂的参数「$TARGET」：要么是纯毫秒，要么是 era1..era12 / type1..type4 / write1..write3"
    ;;
  *)
    EXTRAS=(--el ms "$TARGET")
    ;;
esac
[ "${EGG_SHOT_HUD:-0}" = "0" ] || EXTRAS+=(--ez hud true)

[ -n "$OUT" ] || OUT="$ROOT/build/egg-shots/ttpd-$TARGET-$(date +%Y%m%d-%H%M%S).png"
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
