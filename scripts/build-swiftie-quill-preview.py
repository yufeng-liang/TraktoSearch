# -*- coding: utf-8 -*-
"""羽毛笔第四步（可选）：真机尺寸的验收渲染。

只读**生成物**（quill-final.txt 的笔与笔杆、quill-vane.txt 的羽面轮廓），不重算任何几何 ——
预览看到的和装到机上的必须是同一份数据，各算一遍迟早对不上（这一版之前就吃过一次：
预览里笔杆被换算到别的坐标空间，屏上看着「没有杆」，实际是预览错了）。

色与线宽与 Kotlin 对齐：羽面 LETTER_PAPER(239,231,214)、墨 LETTER_INK(74,69,62)、
线宽 1.8px、笔杆填充 + 3.4px 描边（见 SwiftieEraMotifs.drawQuill）。

笔长按真机推：笔长 = QUILL_SPAN(0.65) × 道具框宽，道具框宽 = 卡片宽 × 0.32
（SwiftieLoverArcher.swiftiePropBox）。1440px 宽、卡宽≈1338px 的机器上 ≈ 280px。
"""
import math
import pathlib
import re
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "build" / "qa" / "quill-vec"
FINAL = BASE / "quill-final.txt"
VANE = BASE / "quill-vane.txt"

SXA, SYA, TYA = 0.1, -0.1, 1280.0
PEN_LEAN_DEG = 31.0
NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtZz]")

# 与 SwiftieEraMotifs 同值
CARD = (252, 250, 247)          # 白纸 + 主色 10%
VANE_C = (239, 231, 214)        # LETTER_PAPER
INK_C = (74, 69, 62)            # LETTER_INK
LINE_PX = 1.8                   # QUILL_LINE_WIDTH × u（u≈280 时的实测像素）
SHAFT_PX = 3.4                  # QUILL_SHAFT_WIDTH × u


def flatten(d, step=10):
    """把一条 path 的 d 串拉成折线（贝塞尔采样 step 段）。"""
    pts, cx, cy, sx, sy, i, up = [], 0.0, 0.0, 0.0, 0.0, 0, "M"
    while i < len(d):
        m = CMD.search(d, i)
        if not m:
            break
        cmd = m.group(0)
        i = m.end()
        nxt = CMD.search(d, i)
        seg = d[i:nxt.start()] if nxt else d[i:]
        i = nxt.start() if nxt else len(d)
        nums = [float(v) for v in NUM.findall(seg)]
        rel = cmd.islower()
        up = cmd.upper()
        if up == "Z":
            pts.append((sx, sy))
            continue
        k = 0
        while k < len(nums):
            if up == "M":
                x, y = (cx + nums[k], cy + nums[k + 1]) if rel else (nums[k], nums[k + 1])
                cx, cy = x, y
                sx, sy = x, y
                pts.append((cx, cy)); k += 2; up = "L"
            elif up == "L":
                x, y = (cx + nums[k], cy + nums[k + 1]) if rel else (nums[k], nums[k + 1])
                cx, cy = x, y
                pts.append((cx, cy)); k += 2
            elif up == "H":
                cx = cx + nums[k] if rel else nums[k]
                pts.append((cx, cy)); k += 1
            elif up == "V":
                cy = cy + nums[k] if rel else nums[k]
                pts.append((cx, cy)); k += 1
            elif up in ("C", "S"):
                if up == "C":
                    x1, y1, x2, y2, x3, y3 = nums[k:k + 6]
                    if rel:
                        x1 += cx; y1 += cy; x2 += cx; y2 += cy; x3 += cx; y3 += cy
                    k += 6
                else:
                    x2, y2, x3, y3 = nums[k:k + 4]
                    if rel:
                        x2 += cx; y2 += cy; x3 += cx; y3 += cy
                    x1, y1 = cx, cy
                    k += 4
                for s in range(1, step + 1):
                    t = s / step; mt = 1 - t
                    pts.append((mt ** 3 * cx + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3 * x3,
                                mt ** 3 * cy + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3 * y3))
                cx, cy = x3, y3
            else:
                k = len(nums)
    return pts


def D(p):
    return (SXA * p[0], TYA + SYA * p[1])


def P(dx, dy):
    return (dx / SXA, (TYA - dy) / (-SYA))


txt = FINAL.read_text(encoding="utf-8")
m = re.search(r"nibD=\(([\d.]+), ([\d.]+)\)\s+L=([\d.]+)\s+axis=(-?[\d.]+)", txt)
nib_d = (float(m.group(1)), float(m.group(2)))
L = float(m.group(3))
axis = float(m.group(4))
lines = [flatten(d) for d in re.findall(r'"([^"]+)"', txt)]
shaft_nums = [float(v) for v in
              re.findall(r"[-+]?[0-9]*\.?[0-9]+",
                         txt.split("floatArrayOf(")[1].split(")")[0])]
shaft = [(shaft_nums[i], shaft_nums[i + 1]) for i in range(0, len(shaft_nums) - 1, 2)]

vane_txt = VANE.read_text(encoding="utf-8")
vane_nums = [float(v) for v in
             re.findall(r"[-+]?[0-9]*\.?[0-9]+", vane_txt.split("floatArrayOf(")[1].split(")")[0])]
vane = [(vane_nums[i], vane_nums[i + 1]) for i in range(0, len(vane_nums) - 1, 2)]
print(f"读到：线稿 {len(lines)} 条、笔杆 {len(shaft)} 点、羽面 {len(vane)} 点；"
      f"L={L:.1f} axis={axis:.2f}° 笔尖 disp=({nib_d[0]:.1f},{nib_d[1]:.1f})")


def mul(m1, m2):
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
            a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)


def T(tx, ty):
    return (1, 0, 0, 1, tx, ty)


def R(deg):
    r = math.radians(deg)
    return (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)


def S(sx, sy):
    return (sx, 0, 0, sy, 0, 0)


if __name__ == "__main__":
    for PEN, tag in ((280.0, "280"), (250.0, "250"), (430.0, "430")):
        s = PEN / L
        W, H = int(PEN * 1.9), int(PEN * 1.75)
        m = T(W * 0.52, H * 0.88)
        m = mul(m, R(PEN_LEAN_DEG + axis))
        m = mul(m, S(-s, s))                                  # 镜像 = 右手握笔
        m = mul(m, T(-nib_d[0], -nib_d[1]))                   # 笔尖落到写字的位置
        m = mul(m, T(0.0, TYA))                               # SVG 自带的 group 变换
        m = mul(m, S(SXA, SYA))

        def ap(p):                                            # path 坐标 -> 屏幕
            return (m[0] * p[0] + m[2] * p[1] + m[4], m[1] * p[0] + m[3] * p[1] + m[5])

        SS = 4
        img = Image.new("RGB", (W * SS, H * SS), CARD)
        dr = ImageDraw.Draw(img)
        dr.polygon([(x * SS, y * SS) for x, y in (ap(P(*D(p))) for p in vane)], fill=VANE_C)
        for poly in lines:
            pts = [ap(P(*D(p))) for p in poly]
            dr.line([(x * SS, y * SS) for x, y in pts] + [(pts[0][0] * SS, pts[0][1] * SS)],
                    fill=INK_C, width=max(1, int(round(LINE_PX * SS))), joint="curve")
        shaft_pts = [ap(P(*D(p))) for p in shaft]
        dr.polygon([(x * SS, y * SS) for x, y in shaft_pts], fill=INK_C)
        dr.line([(x * SS, y * SS) for x, y in shaft_pts] + [(shaft_pts[0][0] * SS, shaft_pts[0][1] * SS)],
                fill=INK_C, width=max(1, int(round(SHAFT_PX * SS))), joint="curve")
        out = BASE / f"vane-preview-{tag}.png"
        img.resize((W, H), Image.LANCZOS).save(out)
        print(f"-> {out}  笔长 {PEN:.0f}px")
