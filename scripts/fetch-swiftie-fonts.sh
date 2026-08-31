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
gf ofl/gochihand/GochiHand-Regular.ttf gochi.ttf
gf ofl/greatvibes/GreatVibes-Regular.ttf greatvibes.ttf
gf ofl/bebasneue/BebasNeue-Regular.ttf bebas.ttf
gf apache/permanentmarker/PermanentMarker-Regular.ttf marker.ttf
gf ofl/unifrakturmaguntia/UnifrakturMaguntia-Book.ttf unifraktur.ttf
gf ofl/parisienne/Parisienne-Regular.ttf parisienne.ttf
gf ofl/librecaslondisplay/LibreCaslonDisplay-Regular.ttf caslon.ttf

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

sub() { # sub <src> <dest-name> <text>
  python -m fontTools.subset "$TMP/$1.ttf" \
    --text="$3" --layout-features='*' --no-hinting --desubroutinize \
    --output-file="$OUT/$2.ttf"
}

sub pacifico    swiftie_script      'CongratsFover!TaylorSwiftHerlucknumb. '
sub gochi       swiftie_marker      '0123456789+=X? '
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

# 授权文本随字体一起入仓。-f 让 404 直接失败，否则 GitHub 的错误页会被当成授权文本写进仓库
for f in pacifico gochihand greatvibes bebasneue unifrakturmaguntia parisienne \
         librecaslondisplay imfelldwpica josefinsans inter playfairdisplay; do
  curl -sfL "https://github.com/google/fonts/raw/main/ofl/$f/OFL.txt" -o "$LIC/$f-OFL.txt"
done
# Permanent Marker 与 Yellowtail 是 Apache-2.0，不在 ofl/ 下
for f in permanentmarker yellowtail; do
  curl -sfL "https://github.com/google/fonts/raw/main/apache/$f/LICENSE.txt" \
    -o "$LIC/$f-LICENSE.txt"
done

# 子集化是 OFL 定义的 Modified Version，带 Reserved Font Name 的 5 个字体必须改内部家族名
python scripts/rename-swiftie-font-rfn.py

du -ch "$OUT"/*.ttf | tail -1
rm -rf "$TMP"
