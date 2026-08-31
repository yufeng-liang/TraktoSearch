#!/usr/bin/env python3
"""OFL 1.1 第 3 条：Modified Version 不得沿用 Reserved Font Name。

子集化删掉了绝大多数字形，属于 OFL 定义的 Modified Version，因此带 RFN 的 5 个
字体必须换掉内部 name 表里的家族名。Android 按资源 ID 加载 res/font/*.ttf，
不看内部家族名，改名不影响渲染。
"""
import sys
from fontTools.ttLib import TTFont

# 资源名 -> (新家族名, 新 PostScript 名)
RENAMES = {
    "swiftie_marker": ("Swiftie Marker", "SwiftieMarker-Regular"),
    "era_fearless": ("Swiftie Fearless", "SwiftieFearless-Regular"),
    "era_lover": ("Swiftie Lover", "SwiftieLover-Regular"),
    "era_showgirl": ("Swiftie Showgirl", "SwiftieShowgirl-Italic"),
    "era_reputation": ("Swiftie Reputation", "SwiftieReputation-Regular"),
}

for resource, (family, postscript) in RENAMES.items():
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
