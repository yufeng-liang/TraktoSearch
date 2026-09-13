# -*- coding: utf-8 -*-
"""羽毛笔第二步：给**空心**的线稿补一块羽面色块（羽面 + 羽面各分块）。

源图是 potrace 描出来的**线画**（羽面在源图里就是留白），所以把线条填成米白只会得到
一圈米白的线。需求方要的是「不透明 + 有填充色」= 一支米白的实物羽毛，于是这块色块
得自己算：

  栅格化线稿 -> 自动焊漏点 -> 填洞 -> 取轮廓 -> 简化

**自动焊漏点**：线稿的轮廓在几处并没有首尾相接（左缘那条折线走到笔杆边上就断了），
所以「填洞」一开始什么也填不到 —— 羽面与外面是通着的。做法是从羽面里一个种子点向
最近的「外部」像素求一条**最小代价路径**（代价随「离笔画越远越便宜」），这条路径必然
从最宽的那个缺口挤出去；把它当一段假笔画补进图里再填一次，缺口就焊上了。
反复几轮直到羽面面积对上量级。补进去的线**只进掩膜，不上屏**。

形态学闭合走不通：缺口 40+ 单位宽，要糊住它的大圆盘会把羽枝之间的缺口一起糊死。

输出的点表与 SHAFT_POINTS 一样是**源 path 坐标**，画的时候套同一串变换。
"""
import math
import pathlib
import re
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
from scipy.spatial import cKDTree
from skimage import measure
from skimage.graph import route_through_array

ROOT = pathlib.Path(__file__).resolve().parents[1]        # 仓库根（scripts/ 的上一级）
SVG = ROOT / "scripts" / "swiftie-quill-source.svg"       # CC0 源矢量（出处见文件头）
BASE = ROOT / "build" / "qa" / "quill-vec"                # 中间产物；build/ 已在 .gitignore 里
BASE.mkdir(parents=True, exist_ok=True)
OUT_TXT = BASE / "quill-vane.txt"
OUT_PREVIEW = BASE / "quill-vane-preview.png"

SXA, SYA, TYA = 0.1, -0.1, 1280.0
YCUT = 930.0
KEEP = [0, 1, 3, 4, 5, 6, 7, 8, 9, 10]
SC = 4.0                      # 出轮廓用的栅格：1 disp 单位 = SC 像素
EPS_DISP = 0.60               # 简化容差（disp 单位）≈ 卡片上 0.04px
SEED_DISP = (250.0, 250.0)    # 种子：羽面左半块里的一个点
BRIDGE_MAX_DISP = 40.0         # 端点够得着别的笔画就接桥的最大距离（disp 单位）
MIN_AREA_DISP = 60.0          # 小于这个面积的洞当杂讯丢掉
WANT_DISP = 100000.0          # 羽面量级（对上了就说明焊通了）

NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtZz]")

src = open(SVG, encoding="utf-8").read()
paths = re.findall(r'<path\s+d="([^"]+)"', src, re.S)


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


def D(p):
    return (SXA * p[0], TYA + SYA * p[1])


def P(dx, dy):
    return (dx / SXA, (TYA - dy) / (-SYA))


def cut_open(pts, ycut):
    n = len(pts)
    cross = []
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        if (a[1] - ycut) * (b[1] - ycut) < 0:
            t = (ycut - a[1]) / (b[1] - a[1])
            cross.append((i, (a[0] + t * (b[0] - a[0]), ycut)))
    segs, cur = [], []
    for i in range(n):
        if pts[i][1] < ycut:
            if not cur:
                for ci, cp in cross:
                    if ci == (i - 1) % n:
                        cur.append(cp)
            cur.append(pts[i])
        elif cur:
            for ci, cp in cross:
                if ci == (i - 1) % n:
                    cur.append(cp)
            segs.append(cur); cur = []
    if cur:
        segs.append(cur)
    return max(segs, key=len) if segs else []


# ---- 线稿（与写进 Kotlin 的同一批） --------------------------------------
polys = [flatten(subst(paths[idx])[0]) for idx in KEEP]
polys.append([P(x, y) for x, y in
              cut_open([D(p) for p in flatten(subst(paths[2])[0])], YCUT)])


def rasterize(scale):
    W, H = int(896 * scale), int(1280 * scale)
    im = Image.new("1", (W, H), 0)
    dr = ImageDraw.Draw(im)
    for poly in polys:
        dr.polygon([(D(p)[0] * scale, D(p)[1] * scale) for p in poly], fill=1)
    return np.array(im, dtype=bool)


def paint(mask, route, half):
    for y, x in route:
        mask[max(0, y - half):y + half + 1, max(0, x - half):x + half + 1] = True


# ---- 把轮廓的断口用细桥接上 ------------------------------------------------
# 线稿的轮廓有几处没接上（左缘那条折线走到笔杆边上就断了，断口约 27 单位），所以羽面与
# 外面是通着的，"填洞"一开始填不到东西。走不通的两条路：
#   * 形态学闭合：要把 27 单位的断口糊上，半径得 15+，那时候羽枝之间的缺口也一起糊死了
#   * 把离笔画最远的通道"反向膨胀贴回笔画"：沿轮廓贴得住，但在断口处会鼓出一个包
# 所以直接补断口本身：把每条笔画**单独**栅格化、抽骨架，骨架端点就是这条笔画的笔头笔尾；
# 端点若够得着**别的**笔画（同一条笔画自己的两臂不算），就在两者之间补一条细桥。
# 补的桥只进掩膜不上屏 —— 屏幕上那几处本来就是笔画的断口，色块自己会把形状收住。
low = rasterize(1.0)
free = ~low
seed = (int(SEED_DISP[1]), int(SEED_DISP[0]))


def stroke_mask(poly):
    im = Image.new("1", (896, 1280), 0)
    ImageDraw.Draw(im).polygon([(D(p)[0], D(p)[1]) for p in poly], fill=1)
    return np.array(im, dtype=bool)


seed = (int(SEED_DISP[1]), int(SEED_DISP[0]))


def stroke_mask(poly):
    im = Image.new("1", (896, 1280), 0)
    ImageDraw.Draw(im).polygon([(D(p)[0], D(p)[1]) for p in poly], fill=1)
    return np.array(im, dtype=bool)


def disc(radius):
    k = int(math.ceil(radius))
    yy, xx = np.mgrid[-k:k + 1, -k:k + 1]
    return (xx * xx + yy * yy) <= radius * radius


# 羽面 = **封住线稿的断口之后**，种子点所在的那块空白。
#
# 源图是速写：轮廓处处留着 15~70 单位的缝（羽枝之间、左缘与缺口之间、缺口彼此之间），
# 所以「填洞」一开始什么也填不到 —— 羽面与外面是通着的，而且源图在画布左上角被裁，
# 羽面那一头本来就贴着画布边，填洞永远围不住它（这条试了四轮）。
# 于是换个做法：先把**该接住的缝**接上（两两笔画最近距离小于阈值的那些对，
# 就是轮廓上本该连续的地方），接桥只进掩膜不上屏；封完缝，种子点那块空白就是羽面 ——
# 它贴着笔画的边，也把羽枝之间那几块一起收进来。
masks = [stroke_mask(p) for p in polys]
cloud, trees = [], []
for own in masks:
    pts = [(float(np.flatnonzero(own[row]).mean()), row)
           for row in range(0, own.shape[0], 2) if own[row].any()]
    arr = np.array(pts)
    cloud.append(arr)
    trees.append(cKDTree(arr))

walls = np.zeros_like(free)
bridged = []
for i in range(len(masks)):
    for j in range(i + 1, len(masks)):
        d, idx = trees[j].query(cloud[i])
        k = int(np.argmin(d))
        if d[k] > BRIDGE_MAX_DISP:
            continue
        a, b = cloud[i][k], cloud[j][idx[k]]
        bridged.append((i, j, float(d[k]), a, b))
        steps = max(2, int(d[k]) + 1)
        for step in range(steps + 1):
            t = step / steps
            x = int(round(a[0] + (b[0] - a[0]) * t))
            y = int(round(a[1] + (b[1] - a[1]) * t))
            walls[max(0, y - 1):y + 2, max(0, x - 1):x + 2] = True
print(f"接桥 {len(bridged)} 条（两两最近距离 <= {BRIDGE_MAX_DISP:.0f} 单位）：" +
      ", ".join(f"#{i}~#{j}:{d:.0f}" for i, j, d, _, _ in sorted(bridged, key=lambda r: r[2])))

# 画布边本身也要当墙：源图把羽尖裁在画布左上角（轮廓绕角走了一段直角帽边），
# 羽面在那一头**沿着画布边**与外面相通 —— 这是两条笔画之间最大的缝也只有 31 单位、
# 怎么加桥都封不住的原因。把边封上，羽面就在画布边那一头收口（屏幕上就是笔尖被裁平的沿）。
walls[0, :] = walls[-1, :] = True
walls[:, 0] = walls[:, -1] = True

# ② 净空核：抬「到笔画距离」的阈值，到种子点与画布边断开的那一档 —— 羽面**本体**
dist_ink = ndi.distance_transform_edt(free)
cut = None
for t in (2, 3, 4, 5, 6, 8, 10, 12, 15, 18, 22, 26, 32, 40, 50, 64, 80):
    lab, _ = ndi.label(free & (dist_ink >= t))
    label = lab[seed]
    if label == 0:
        continue
    if not ((lab[0, :] == label).any() or (lab[-1, :] == label).any() or
            (lab[:, 0] == label).any() or (lab[:, -1] == label).any()):
        cut = t
        core = lab == label
        break
if cut is None:
    raise SystemExit("没找到净空阈值")

# ③ 长回笔画：沿轮廓正好够到笔画边。**接桥要盖在长回来的那块上面** ——
#    不然膨胀会顺着线稿的断口挤出去，沿着下缘鼓成一串圆脚（长这样：
#    `vane-lower-debug.png` 里那一排台阶），而且脚与脚之间反而漏着没填
grown = ndi.binary_dilation(core, structure=disc(cut)) & free & ~walls

# ⑤ 「羽枝那一扇」：笔杆下段两侧那几根散羽，它们之间的空隙本来就是羽面的下缘
#    （参考图里那几根羽枝自己就是羽面边缘的锯齿）。从数据里把它们认出来：算每个点到
#    笔杆轴线的**带符号横向偏移**，羽枝在偏移为负的一侧、且贴在下半段；
#    左缘那两条长边与那串缺口在另一侧（羽面本体已经收住那一侧）。
#    然后沿笔杆走：到一根羽枝就拐出去到它的尖、再拐回来到下一根的根 —— 锯齿边就成了。
svgs = KEEP      # 与 SVG 里的路径序号对应，只为打印
shaft_pts = np.array([D(p) for p in polys[-1]])
top = shaft_pts[np.argmin(shaft_pts[:, 1])]
bottom = shaft_pts[np.argmax(shaft_pts[:, 1])]
axis_vec = bottom - top
axis_len = float(np.hypot(*axis_vec)) or 1.0
axis_dir = axis_vec / axis_len
side_vec = np.array([axis_dir[1], -axis_dir[0]])

barbs = []
for index, poly in enumerate(polys[:-1]):
    pts = np.array([D(p) for p in poly])
    rel = pts - top
    along = rel @ axis_dir
    off = rel @ side_vec
    lower = (along > axis_len * 0.35) & (along < axis_len + 60)
    if not lower.any():
        continue
    mean_off = float(off[lower].mean())
    near = float(np.abs(off[lower]).min())
    far = float(np.abs(off[lower]).max())
    if mean_off < -10 and near < 60 and far < 320:
        barbs.append((float((rel[lower] @ axis_dir).mean()), index, pts, off, along))

barbs.sort()
print(f"认出的羽枝 {len(barbs)} 根（按沿杆位置排）：" +
      ", ".join(f"#{svgs[i]}@{along:.0f}" for along, i, _, _, _ in barbs))

if barbs:
    ring = [tuple(top)]
    for along, index, pts, off, al in barbs:
        mask = (al > axis_len * 0.35) & (al < axis_len + 60)
        local = np.abs(off) < 60
        sel = mask & local
        if not sel.any():
            sel = mask
        base = pts[sel][np.argmin(np.abs(off[sel]))]
        tip = pts[sel][np.argmax(np.abs(off[sel]))]
        ring.append(tuple(base))
        ring.append(tuple(tip))
    ring.append(tuple(bottom))
    im = Image.new("1", (896, 1280), 0)
    ImageDraw.Draw(im).polygon([(x, y) for x, y in ring], fill=1)
    fan = np.array(im, dtype=bool) & free
    print(f"羽枝那一扇 {int(fan.sum())} disp²")
    grown = grown | fan

vane_low = grown
print(f"羽面 {int(vane_low.sum())} disp²")
np.save(BASE / "quill-vane.npy", vane_low)   # 预览脚本直接读这份，不再各算一遍

# ---- 取轮廓（区域本身已是实心，只需沿它走一圈） ---------------------------
region = np.kron(vane_low, np.ones((int(SC), int(SC)), dtype=bool))
# 上采样出来的边界是 4px 一级的台阶，简化器会当成真拐点留下几千个点 —— 先模糊再取阈值
smooth = ndi.gaussian_filter(region.astype(np.float32), sigma=SC * 0.8, mode="nearest") >= 0.5
lab, count = ndi.label(smooth)
main = lab == lab[seed[0] * int(SC), seed[1] * int(SC)]
print(f"羽面区域（{SC:.0f}x 栅格）{int(main.sum())} px²，拆开 {count} 块，取种子那块")

contours = [c for c in measure.find_contours(main.astype(float), 0.5) if len(c) > 12]
fields = []
for outer in sorted(contours, key=len, reverse=True)[:1]:
    simple = measure.approximate_polygon(outer, tolerance=EPS_DISP * SC)
    pts = [(c / SC, r / SC) for r, c in simple]
    pts = [p for j, p in enumerate(pts) if j == 0 or
           math.hypot(p[0] - pts[j - 1][0], p[1] - pts[j - 1][1]) > 1e-6]
    fields.append(pts)
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    print(f"  轮廓 {len(outer)} -> {len(pts)} 点  bbox x {min(xs):.0f}..{max(xs):.0f} "
          f"y {min(ys):.0f}..{max(ys):.0f}")

# ---- 输出 Kotlin 点表（源 path 坐标） -------------------------------------
with open(OUT_TXT, "w", encoding="utf-8") as f:
    f.write("源 https://svgsilh.com/image/1299326.html  CC0\n")
    f.write(f"羽面 {len(fields)} 块，简化容差 {EPS_DISP} disp，源 path 坐标\n")
    f.write("VANE_POINTS = listOf(\n")
    for pts in fields:
        vals = []
        for x, y in pts:
            px, py = P(x, y)
            vals += [f"{px:.2f}f", f"{py:.2f}f"]
        f.write("    floatArrayOf(\n")
        for j in range(0, len(vals), 12):
            f.write("        " + ", ".join(vals[j:j + 12]) + ",\n")
        f.write("    ),\n")
    f.write(")\n")

# ---- 预览：填色 + 线稿（与真机同一套色同一串变换） ------------------------
VANE_C = (239, 231, 214)
INK_C = (74, 69, 62)
PEN_PX = 60.0 * 3.0


def mul(m1, m2):
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
            a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)


def T(tx, ty): return (1, 0, 0, 1, tx, ty)
def R(deg): r = math.radians(deg); return (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
def S(sx, sy): return (sx, 0, 0, sy, 0, 0)


seg_disp = cut_open([D(p) for p in flatten(subst(paths[2])[0])], YCUT)
nib_d = max(seg_disp, key=lambda q: q[1])
allp = [D(p) for poly in polys for p in poly]
far_d = max(allp, key=lambda q: (q[0] - nib_d[0]) ** 2 + (q[1] - nib_d[1]) ** 2)
L = math.hypot(far_d[0] - nib_d[0], far_d[1] - nib_d[1])
axis = math.degrees(math.atan2(far_d[0] - nib_d[0], nib_d[1] - far_d[1]))
s = PEN_PX / L
PW, PH = int(PEN_PX * 2.6), int(PEN_PX * 2.8)
m = T(PW * 0.60, PH * 0.82)
m = mul(m, R(31.0 + axis)); m = mul(m, S(-s, s)); m = mul(m, T(-nib_d[0], -nib_d[1]))
m = mul(m, T(0.0, TYA)); m = mul(m, S(SXA, SYA))


def ap(p):
    return (m[0] * p[0] + m[2] * p[1] + m[4], m[1] * p[0] + m[3] * p[1] + m[5])


big = 3
img = Image.new("RGB", (PW * big, PH * big), (255, 255, 255))
dr = ImageDraw.Draw(img)
for pts in fields:
    dr.polygon([(x * big, y * big) for x, y in (ap(P(*p)) for p in pts)], fill=VANE_C)
for poly in polys:
    dr.line([(x * big, y * big) for x, y in (ap(P(*D(p))) for p in poly)] +
            [(x * big, y * big) for x, y in [ap(P(*D(poly[0])))]],
            fill=INK_C, width=int(1.04 * big), joint="curve")
img.save(OUT_PREVIEW)
print(f"-> {OUT_TXT}\n-> {OUT_PREVIEW}")
