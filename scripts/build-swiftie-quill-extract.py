# -*- coding: utf-8 -*-
"""羽毛笔第一步：从 CC0 矢量取出「只有笔」的那部分，输出笔的类型数据 + 摆放参数。

源：https://svgsilh.com/image/1299326.html  （Pixabay 改，CC0）
    group: translate(0,1280) scale(0.1,-0.1)  -> disp = (0.1*px, 1280 - 0.1*py)

约定：
  * 多边形**一律保持源文件的 path 坐标**（嵌进 Kotlin 的 d 与源逐字一致）
  * 量尺寸 / 开切口在 disp 空间做，切完换算回 path
  * 预览用与 Kotlin 相同的矩阵链（自上而下 = 外到内）：
        translate(nibPx) / rotate(lean) / scale(-s, s) / translate(-nibD)
        / translate(0, 1280) / scale(0.1, -0.1)
"""
import math
import pathlib
import re
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[1]        # 仓库根（scripts/ 的上一级）
SVG = ROOT / "scripts" / "swiftie-quill-source.svg"       # CC0 源矢量（出处见文件头）
BASE = ROOT / "build" / "qa" / "quill-vec"                # 中间产物；build/ 已在 .gitignore 里
BASE.mkdir(parents=True, exist_ok=True)
OUT_TXT = BASE / "quill-final.txt"
OUT_SIM3 = BASE / "quill-sim3x.png"
OUT_SIM1 = BASE / "quill-sim1x.png"

SXA, SYA, TYA = 0.1, -0.1, 1280.0
YCUT = 930.0                                  # 瓶口椭圆上方那一刀（disp y）
KEEP = [0, 1, 3, 4, 5, 6, 7, 8, 9, 10]        # 除 #02（另切）与 #11/#12（墨水瓶）之外全留
PEN_LEN_DP = 60.0                             # 卡片上羽毛笔的目标长度（dp）

src = open(SVG, encoding="utf-8").read()
paths = re.findall(r'<path\s+d="([^"]+)"', src, re.S)
NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtZz]")


def flatten(d, step=10):
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
                cx = cy = 0.0
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


def subst(d):
    st = [m.start() for m in re.finditer(r"[Mm]", d)] + [len(d)]
    return [d[a:b].strip() for a, b in zip(st[:-1], st[1:])]


def D(p):                                     # path -> disp
    return (SXA * p[0], TYA + SYA * p[1])


def P(dx, dy):                                # disp -> path
    return (dx / SXA, (TYA - dy) / (-SYA))


# ---- 组装（path 坐标） ----------------------------------------------------
polys = [(idx, flatten(subst(paths[idx])[0])) for idx in KEEP]
path2_disp = [D(p) for p in flatten(subst(paths[2])[0])]

# 笔杆：源 #02 把「羽轴 + 瓶口椭圆」画成一条闭合轮廓，在 y=930 处穿过 4 次 ——
# 切出来是两条（瓶口椭圆之外）：一条**含羽轴顶端**（上去一条边、到顶、再下来一条边，
# 顶端 disp y≈287 —— 正是参考图里第一个缺口的高度），一条与它平行的短边（y≈496 起）。
# 只留最长那段会把羽轴上半截丢掉，卡片上笔杆就断在羽毛当中，所以两条一起要：
# 外缘那条边（一路到顶）+ 短边当另一边，顶端两点之间收成尖；
# 下端接到源图 #02 自己的最低点 —— 那正是参考图里笔尖停进瓶里的位置（不用外推）。
runs, cur = [], []
for q in path2_disp:
    if q[1] < YCUT:
        cur.append(q)
    elif cur:
        runs.append(cur); cur = []
if cur:
    runs.append(cur)
if len(runs) > 1 and path2_disp[0][1] < YCUT and path2_disp[-1][1] < YCUT:
    runs = [runs[-1] + runs[0]] + runs[1:-1]      # 轮廓起点正好落在 y<cut 那侧时，首尾本是一段
top_run = min(runs, key=lambda r: min(q[1] for q in r))
side_run = max([r for r in runs if r is not top_run], key=len)
# 这两段自己也是「双线」：一条边上去、到端点再折回来，所以两端都落在瓶口线上。
# 按各自的**顶端**劈开，只取真正在杆右侧的那条边。
tip_i = min(range(len(top_run)), key=lambda i: top_run[i][1])
down_edge, up_edge = top_run[:tip_i + 1], top_run[tip_i:]     # 瓶口->顶 / 顶->瓶口
outer = down_edge if down_edge[0][0] <= up_edge[-1][0] else up_edge[::-1]
k = min(range(len(side_run)), key=lambda i: side_run[i][1])
side_a, side_b = side_run[:k + 1], side_run[k:]               # 瓶口->顶 / 顶->瓶口
side = side_a[::-1] if side_a[0][0] >= side_b[-1][0] else side_b
shaft_tip = max(path2_disp, key=lambda q: q[1])
seg_disp = outer + [side[0]] + side + [shaft_tip]
print(f"笔杆：顶端 disp=({outer[-1][0]:.1f},{outer[-1][1]:.1f})（第一个缺口 #03 的 y 329..395）"
      f" 断面 disp=({outer[0][0]:.1f},{outer[0][1]:.1f})..({side[-1][0]:.1f},{side[-1][1]:.1f})"
      f" 右边折点 disp=({side[0][0]:.1f},{side[0][1]:.1f})"
      f" 末端 disp=({shaft_tip[0]:.1f},{shaft_tip[1]:.1f})（源图 #02 自己的最低点）")
seg = [P(x, y) for x, y in seg_disp]
polys.append((2, seg))

allp = [D(p) for _, poly in polys for p in poly]
nib_d = max((D(p) for p in seg), key=lambda q: q[1])
far_d = max(allp, key=lambda q: (q[0] - nib_d[0]) ** 2 + (q[1] - nib_d[1]) ** 2)
L = math.hypot(far_d[0] - nib_d[0], far_d[1] - nib_d[1])
axis = math.degrees(math.atan2(far_d[0] - nib_d[0], nib_d[1] - far_d[1]))
print(f"保留路径 {[i for i, _ in polys]}；#02 切出笔杆 {len(seg)} 点（切口 disp y={YCUT}）")
print(f"笔尖 disp=({nib_d[0]:.2f},{nib_d[1]:.2f}) 羽尖 disp=({far_d[0]:.2f},{far_d[1]:.2f})")
print(f"全笔长 L={L:.2f} disp 单位；轴相对竖直 {axis:.2f}°（负=向左倾）")

# ---- 变换链 ---------------------------------------------------------------
# 镜像之后笔轴变成「向右 -axis 度」；再转 lean 度，最终落在 PEN_LEAN_DEG。
PEN_LEAN_DEG = 31.0
LEAN = PEN_LEAN_DEG + axis
print(f"镜像后笔轴右倾 {-axis:.2f}° -> 转 {LEAN:.2f}° 后为 {PEN_LEAN_DEG:.1f}°")


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


def chain(nib_px, s, lean_deg, mirror=True):
    m = T(*nib_px)
    m = mul(m, R(lean_deg))
    m = mul(m, S(-s if mirror else s, s))
    m = mul(m, T(-nib_d[0], -nib_d[1]))
    m = mul(m, T(0.0, TYA))            # SVG 自带 group 变换，顺序照抄（translate 在外）
    m = mul(m, S(SXA, SYA))
    return m


def apply(m, p):
    return (m[0] * p[0] + m[2] * p[1] + m[4], m[1] * p[0] + m[3] * p[1] + m[5])


for up, tag, out in ((3.0, "3x", OUT_SIM3), (1.0, "1x", OUT_SIM1)):
    pen_px = PEN_LEN_DP * up
    s = pen_px / L
    W, H = int(pen_px * 2.4), int(pen_px * 2.6)
    nibpx = (W * 0.62, H * 0.80)
    m = chain(nibpx, s, LEAN, mirror=True)
    chk = apply(m, P(*nib_d))
    tip = apply(m, P(*far_d))
    print(f"  [{tag}] 自检 笔尖->({chk[0]:.1f},{chk[1]:.1f}) 应={nibpx}；"
          f"羽尖距笔尖 {math.hypot(tip[0]-chk[0], tip[1]-chk[1]):.1f}px 应={pen_px:.0f}px")
    img = Image.new("RGB", (W, H), (239, 231, 214))
    dr = ImageDraw.Draw(img)
    for idx, poly in polys:
        dr.polygon([apply(m, p) for p in poly], fill=(74, 69, 62))
    dr.line([(0, nibpx[1]), (W, nibpx[1])], fill=(205, 130, 130), width=1)
    img.save(out)
    print(f"  -> {out} ({W}x{H})")

# ---- 输出 Kotlin 数据 -----------------------------------------------------
with open(OUT_TXT, "w", encoding="utf-8", newline="") as f:
    f.write("源 https://svgsilh.com/image/1299326.html  CC0（Pixabay 改，potrace 描摹）\n")
    f.write(f"nibD=({nib_d[0]:.2f}, {nib_d[1]:.2f})  L={L:.2f}  axis={axis:.2f}°  lean={LEAN:.2f}°\n")
    for idx, poly in polys:
        if idx == 2:
            continue
        flat = re.sub(r"\s+", " ", subst(paths[idx])[0])
        f.write(f'\n// #{idx:02d}（{len(flat)} 字符）\n"{flat}"\n')
    f.write(f"\n// 笔杆：源 #02 在 disp y={YCUT} 横切出的一段（{len(seg)} 点，path 坐标）\n")
    f.write("floatArrayOf(\n    " + ", ".join(f"{x:.1f}f, {y:.1f}f" for x, y in seg) + "\n)\n")
print(f"数据 -> {OUT_TXT}")
