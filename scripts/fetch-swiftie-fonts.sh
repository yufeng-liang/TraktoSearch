#!/usr/bin/env bash
# 一次性脚本：下载并子集化霉粉彩蛋所需字体。产物已提交进仓库，正常构建无需再跑。
# 依赖：curl、python + fonttools（已确认环境有 fonttools 4.63.0）
set -euo pipefail
OUT="app/src/main/res/font"
LIC="app/src/main/assets/fonts/licenses"
TMP="$(mktemp -d)"
mkdir -p "$OUT" "$LIC"

gf() { curl -sL "https://github.com/google/fonts/raw/main/$1" -o "$TMP/$2"; }

# 静态字体（直接下载）
gf ofl/pacifico/Pacifico-Regular.ttf pacifico.ttf
gf ofl/greatvibes/GreatVibes-Regular.ttf greatvibes.ttf
gf ofl/bebasneue/BebasNeue-Regular.ttf bebas.ttf
gf apache/permanentmarker/PermanentMarker-Regular.ttf marker.ttf
gf ofl/unifrakturmaguntia/UnifrakturMaguntia-Book.ttf unifraktur.ttf
gf ofl/parisienne/Parisienne-Regular.ttf parisienne.ttf
gf ofl/librecaslondisplay/LibreCaslonDisplay-Regular.ttf caslon.ttf
# TTPD 那张曲目单是打字机打出来的（见 era_typewriter）：Special Elite 是老式打字机
# 打出来的活字，正是这一张的语义；Apache-2.0，许可证在 apache/ 下
gf apache/specialelite/SpecialElite-Regular.ttf specialelite.ttf

# Honey Script SemiBold 不在 Google Fonts 上，来自 dafont（标「100% Free」，
# 二进制 fsType=0 允许嵌入，无随附许可证文本 —— 详见
# app/src/main/assets/fonts/licenses/honeyscript-NOTICE.txt）。
# 它是原海报「13 + 87 = 100」用的字体，逐字对得上。
curl -sSL -A "Mozilla/5.0" "https://dl.dafont.com/dl/?f=honey_script" -o "$TMP/honey.zip"
unzip -p "$TMP/honey.zip" HoneyScript-SemiBold.ttf > "$TMP/honey.ttf"

resolve_css() { curl -s -A "Mozilla/4.0" "https://fonts.googleapis.com/css2?family=$1" \
  | grep -o "https://[^)]*\.ttf" | head -1; }

curl -sL "$(resolve_css 'Yellowtail')" -o "$TMP/yellowtail.ttf"
curl -sL "$(resolve_css 'IM+Fell+DW+Pica:ital@1')" -o "$TMP/imfell.ttf"

# 变体字体：先定到目标字重，再子集化
gf "ofl/josefinsans/JosefinSans%5Bwght%5D.ttf" josefin-var.ttf
gf "ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf" inter-var.ttf
gf "ofl/playfairdisplay/PlayfairDisplay-Italic%5Bwght%5D.ttf" playfair-var.ttf

python -m fontTools.varLib.instancer "$TMP/josefin-var.ttf"  wght=600 -o "$TMP/josefin.ttf"
python -m fontTools.varLib.instancer "$TMP/inter-var.ttf"     wght=500 opsz=28 -o "$TMP/inter.ttf"
python -m fontTools.varLib.instancer "$TMP/playfair-var.ttf"  wght=400 -o "$TMP/playfair.ttf"

sub() { # sub <src> <dest-name> <text> [extra fontTools.subset args...]
  local src="$1" dest="$2" text="$3"
  shift 3
  python -m fontTools.subset "$TMP/$src.ttf" \
    --text="$text" --layout-features='*' --no-hinting --desubroutinize \
    "$@" --output-file="$OUT/$dest.ttf"
}

# swiftie_script 留签名那两个词，加上纪念页最下面那句 Taylor & Me — forever & always.
# （FINALE_TAGLINE）。破折号是 U+2014，不在 --text 里写，免得脚本文件的编码影响产物。
# 改了这一行的字符集就要重跑 scripts/build-swiftie-signature-path.py —— 签名的中线是按
# 这份轮廓量出来的。「Congrats on Forever!」已改成描原图的矢量（见 SwiftieCongratsPath.kt），
# 提示行改用 Honey Script
sub pacifico    swiftie_script      'TaylorSwift Me&forever always.' --unicodes=U+2014
sub honey       swiftie_honey       '0123456789+=XHerluckynmb. '
sub greatvibes  era_taylor_swift    'Taylor Swift'
sub josefin     era_fearless        'Fearless'
sub yellowtail  era_speak_now       'Speak Now'
sub bebas       era_red             'Red'
sub marker      era_1989            '1989'
sub unifraktur  era_reputation      'reputation'
sub parisienne  era_lover           'Lover'
sub imfell      era_imfell          'folklore evermore'
sub inter       era_midnights       'Midnights'
sub caslon      era_ttpd            'The Tortured Poets Department'
sub playfair    era_showgirl        'The Life of a Showgirl'
# TTPD 那张纸上的字：31 首曲目名 + 前摇打在露头纸上的两行 + 序号列的数字。
# 曲目名里的撇号是直引号（源码里就是 U+0027），所以字符集里直接写它、不另开 --unicodes
sub specialelite era_typewriter " !'(),-0123456789?ABCDEFGHIKLMNOPRSTWabcdefghiklmnoprstuvwxy"
# 信纸上那句 All’s fair in love / and poetry.：弯撇号走 --unicodes，
# 免得脚本文件的编码影响产物 —— 与 swiftie_script 那行的破折号同一个理由
sub greatvibes  era_ttpd_letter     'Alls fairin loveand poetry.' --unicodes=U+2019

# 授权文本随字体一起入仓。-f 让 404 直接失败，否则 GitHub 的错误页会被当成授权文本写进仓库。
# Honey Script 不在这个循环里 —— 它没有许可证文本，随包的是手写的 honeyscript-NOTICE.txt
for f in pacifico greatvibes bebasneue unifrakturmaguntia parisienne \
         librecaslondisplay imfelldwpica josefinsans inter playfairdisplay; do
  curl -sfL "https://github.com/google/fonts/raw/main/ofl/$f/OFL.txt" -o "$LIC/$f-OFL.txt"
done
# Permanent Marker 与 Yellowtail、Special Elite 是 Apache-2.0，不在 ofl/ 下
for f in permanentmarker yellowtail specialelite; do
  curl -sfL "https://github.com/google/fonts/raw/main/apache/$f/LICENSE.txt" \
    -o "$LIC/$f-LICENSE.txt"
done

# 子集化是 OFL 定义的 Modified Version，带 Reserved Font Name 的 5 个字体必须改内部家族名。
# Pacifico 与 Honey Script 的 name[0] 都没有 RFN 条款，不在名单里
python scripts/rename-swiftie-font-rfn.py

du -ch "$OUT"/*.ttf | tail -1
rm -rf "$TMP"
