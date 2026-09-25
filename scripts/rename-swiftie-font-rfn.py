#!/usr/bin/env python3
"""OFL 1.1 第 3 条：Modified Version 不得沿用 Reserved Font Name。

子集化删掉了绝大多数字形，属于 OFL 定义的 Modified Version，因此带 RFN 的字体
必须换掉内部 name 表里的家族名。Android 按资源 ID 加载 res/font/*.ttf，
不看内部家族名，改名不影响渲染。

判据是**入包的那份二进制**里 name ID 0 有没有 "With Reserved Font Name"，
不是上游许可文本 —— 两者会不一致：
  · era_imfell 随包的 imfelldwpica-OFL.txt 版权行没有 RFN 条款，但二进制（2007 年版，
    从 fonts.googleapis.com/css2 取得）声明了 RFN，所以按二进制算，要改。
  · era_lover（Parisienne）反过来：二进制没有 RFN 条款，仍然改了名。
    多改无害，留着不动。

用法：不带参数改全部，带资源名只改指定的几个（避免无谓地重写其余文件）。
    python scripts/rename-swiftie-font-rfn.py era_imfell
"""
import sys
from fontTools.ttLib import TTFont

# 资源名 -> (新家族名, 新 PostScript 名)
RENAMES = {
    "era_fearless": ("Swiftie Fearless", "SwiftieFearless-Regular"),
    "era_lover": ("Swiftie Lover", "SwiftieLover-Regular"),
    "era_reputation": ("Swiftie Reputation", "SwiftieReputation-Regular"),
    # folklore 与 evermore 共用这一个字体，所以家族名不跟单个专辑
    "era_imfell": ("Swiftie Fell", "SwiftieFell-Italic"),
    # era_showgirl 2026-09-25 起不在表里：标题字体从 Playfair Display 换成了
    # Barlow Condensed Bold Italic（参考 The Encore 版封面重选），而 Barlow 的
    # name ID 0 只有版权行、没有 "With Reserved Font Name" 条款，按本文件的判据
    # 不需要改名。曾经那条 ("Swiftie Showgirl", "SwiftieShowgirl-Italic") 是对
    # Playfair 那一份的，留着会让下次全量跑把一个无 RFN 的字体改名。
}

only = set(sys.argv[1:])
unknown = only - RENAMES.keys()
if unknown:
    sys.exit(f"未登记的资源名：{', '.join(sorted(unknown))}")

for resource, (family, postscript) in RENAMES.items():
    if only and resource not in only:
        continue
    path = f"app/src/main/res/font/{resource}.ttf"
    font = TTFont(path)
    name = font["name"]
    subfamily = "Italic" if postscript.endswith("-Italic") else "Regular"
    # 1 家族名 · 2 子家族名 · 3 唯一 ID · 4 全名 · 6 PostScript 名
    # 16/17 是排版家族名，存在时也要一起换，否则系统仍能拼出原 RFN
    for name_id, value in (
        (1, family),
        (2, subfamily),
        (3, f"{postscript};Swiftie easter egg subset"),
        (4, f"{family} {subfamily}" if subfamily == "Italic" else family),
        (6, postscript),
        (16, family),
        (17, subfamily),
    ):
        if name_id in (16, 17) and not name.getDebugName(name_id):
            continue
        name.setName(value, name_id, 3, 1, 0x409)
        name.setName(value, name_id, 1, 0, 0)
    font.save(path)
    print(f"{resource}: {family} / {postscript}")
