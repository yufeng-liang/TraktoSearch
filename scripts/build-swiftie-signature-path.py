#!/usr/bin/env python3
"""生成签名的笔顺中线表 `SwiftieSignaturePath.kt`。

做法：把 Pacifico 的每个字形单独渲成 1px = 1 font unit 的位图，细化取骨架，
距离变换取每个点的笔尖半径，再把骨架排成「一支笔真的会走的顺序」。

为什么不能沿用原来的横向擦除：Pacifico 是笔刷体，竖直的揭示边会从字形中间切过去，
读起来是「刷出来」而不是「写出来」。

为什么半径要逐点取距离变换而不是取一个固定值：固定半径的笔尖在下压笔锋那几处盖不住墨，
字形边缘会露出没被揭示的细条；而取距离变换值时，每个点上的圆盘正好内切于字形轮廓，
沿中线扫过去的并集就是这一笔的墨（中轴变换的性质），既不漏也不会提前揭示到下一笔。

产物是代码文件而不是资源：它是 12 个字形几何的衍生数据，跟 SwiftieCongratsPath.kt 一样。
`swiftie_script.ttf` 若再重新子集化并且字形有变，必须重跑本脚本。

用法：
    python scripts/build-swiftie-signature-path.py [--debug]
`--debug` 会在 .scratch/ 下写一张按笔序上色的检查图。
"""
from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont
from skimage.morphology import skeletonize

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "app/src/main/res/font/swiftie_script.ttf"
OUT = ROOT / "app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieSignaturePath.kt"
DEBUG_DIR = ROOT / ".scratch"

TEXT = "Taylor Swift"

#: 渲染字号。取 upem 就是 1px = 1 font unit，量化误差落在 0.1% 量级。
RENDER_PX = 1000

#: 毛刺剪除阈值，相对该分叉处的笔画半径。
#:
#: 骨架在笔锋外张的地方会长出朝向轮廓的小杈，它们不是笔画。阈值取半径的 1.15 倍：
#: 比这更短的叶边一定是毛刺（真笔画不可能只有一个笔宽长），更长的一律保留。
SPUR_FACTOR = 1.15

#: 剪一条叶边最多允许丢多少墨（占该字形墨面积）。见 [prune]。
PRUNE_INK_TOLERANCE = 0.002

#: 采样步长下限、「相对半径」系数与上限（像素）。
#:
#: 步长跟着笔宽走：粗笔画上点可以稀。但**必须有上限** —— 笔画交汇处（`w` 的谷底）
#: 距离变换的值很大而曲率也最大，只按半径给步长会正好在最该密的地方最稀。
RESAMPLE_MIN_PX = 5.0
RESAMPLE_FACTOR = 0.75
RESAMPLE_MAX_PX = 40.0

#: 半宽的绝对上限（em）。
#:
#: 半宽是沿法向量到墨边缘的距离，而不是内切圆半径 —— 弯道外侧与两笔交汇处的墨都在
#: 内切圆之外，用内切圆铺会漏（实测只盖住 77–95%）。但法向射线在笔画交叉处会一路穿进
#: 另一笔里去，所以要卡上限。卡**绝对值**而不是内切圆的倍数：`w` 的谷底内切圆很小
#: （上方那个凹口离得近），倍数上限会正好在最需要放宽的地方最紧。
#: 0.13 em 比这个字体实测最粗的笔画半宽（0.099 em，`S`）还宽一档。
MAX_HALF_WIDTH = 0.13

#: 半宽的放大系数。1 是正好量到墨的边缘；带状区域的边是**直线**，弯道外侧的墨在这条
#: 弦之外，所以要放大一点点让弦落到墨外面去 —— 溢出的部分被字形 mask 的 DstIn 裁掉。
RADIUS_GAIN = 1.14


#: 平滑窗口（点数，奇数）。细化出来的骨架是像素级锯齿，不平滑笔尖会一格一格跳。
SMOOTH_WINDOW = 5

#: 每一笔的落笔点与起笔方向。这是**人工指定**的部分。
#:
#: 细化只给拓扑，推不出「从哪头起笔、往哪边走」—— 所以每个字形指定一个落笔位置
#: （该字形墨迹框内的归一坐标，x 从左、y 从上）和一个起笔方向。落笔点会吸附到最近的
#: 骨架节点（端点或岔口）。
#:
#: 有几个字形的自由端是**出笔**而不是入笔（`o` `l` 的收尾连笔），落笔点要给在圈上的岔口，
#: 让那条尾巴最后才走 —— 从尾巴起笔会把字反着写出来。
#: 值：((x, y), 方向)。同一字形有两笔时按主体、附加笔的顺序给两项。
START_HINT: dict[str, list[tuple[tuple[float, float], str]]] = {
    # 横画从左边那个小回钩起笔、往右拉，走到头再回描到交叉处往下写竖
    "T": [((0.11, 0.36), "up-right")],
    # 碗从顶上往左逆时针绕，收尾才甩出右边那一笔
    "a": [((0.59, 0.21), "left")],
    # 连笔体的 u/w/y 都是从 x 高度那条线上落笔往下走 —— 上一个字母的出笔就停在那里
    "y": [((0.30, 0.04), "down")],
    # 圈的自由端是出笔，落笔要给在圈上的交叉点，否则整个字母是倒着写出来的
    "l": [((0.18, 0.73), "up-right")],
    "o": [((0.62, 0.49), "up")],
    "r": [((0.13, 0.88), "up")],
    "S": [((0.89, 0.29), "left")],
    "w": [((0.08, 0.14), "down")],
    # 先写主体，再回头点那一点（两块按面积排序，主体在前）
    "i": [((0.34, 0.40), "down"), ((0.46, 0.11), "right")],
    "f": [((0.21, 0.47), "up-right")],
    "t": [((0.30, 0.51), "up-right")],
}

#: 方向名 → (dy, dx)。y 向下，所以 "up" 是负的。
HEADINGS = {
    "left": (0.0, -1.0),
    "right": (0.0, 1.0),
    "up": (-1.0, 0.0),
    "down": (1.0, 0.0),
    "up-right": (-0.7, 0.7),
    "up-left": (-0.7, -0.7),
    "down-right": (0.7, 0.7),
    "down-left": (0.7, -0.7),
}

DEFAULT_HINT = ((0.0, 0.9), "up-right")

#: 单笔时长权重下限。见 [build_stroke] 里的说明。
MIN_WRITE_WEIGHT = 0.14

#: 书写总时长（ms），与 Kotlin 侧的 `SIGNATURE_WRITE_MS` 必须一致。
#: 只用来打印每笔时长和渲染 [frame_images]，不入产物 —— 产物只给相对权重。
WRITE_MS = 6800

#: 抬笔停顿总时长（ms），与 Kotlin 侧的 `SIGNATURE_PAUSE_TOTAL_MS` 必须一致。
PAUSE_MS = 500


#: 覆盖率下限。沿中线盖圆盘应该盖满整个字形，见 [coverage]。
MIN_COVERAGE = 0.99

#: 八邻域偏移。
NEIGH8 = ((-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1))


class Raster:
    """一个字形的位图、骨架、距离场，外加「像素坐标 → em 坐标」的映射。

    映射用**墨迹包围盒**两头对齐，而不是靠 PIL 的落笔锚点：锚点约定和微量 hinting
    位移都会带偏，包围盒是自校准的。字体侧的包围盒用 BoundsPen 取真实曲线极值，
    不用 `glyf` 头里那个（有些字体存的是控制点包围盒，会大一圈）。
    """

    def __init__(self, char: str, ink: np.ndarray,
                 bounds: tuple[float, float, float, float], upem: int):
        self.char = char
        self.binary = ink >= 128
        self.skeleton = skeletonize(self.binary)
        self.dist = cv2.distanceTransform(self.binary.astype(np.uint8), cv2.DIST_L2, 5)
        height, width = self.binary.shape
        x_min, y_min, x_max, y_max = bounds
        # 两头对齐：墨迹左右边缘对应字形的 xMin / xMax，上下边缘对应 yMax / yMin。
        # ink 已经裁到墨迹包围盒，所以像素原点就是包围盒左上角
        self._sx = (x_max - x_min) / max(width - 1, 1)
        self._sy = (y_max - y_min) / max(height - 1, 1)
        self._x0, self._y0 = x_min, y_max
        self.upem = upem

    @property
    def scale_x(self) -> float:
        """像素 → em 的横向比例。半宽是长度，按它换算。"""
        return self._sx / self.upem

    def to_em(self, x: float, y: float) -> tuple[float, float]:
        """像素坐标 → em 坐标。y 向下、基线为 0，与 Android 的 Path 空间一致。"""
        fx = self._x0 + x * self._sx
        fy = self._y0 - y * self._sy
        return fx / self.upem, -fy / self.upem


def render(font: TTFont, pil: ImageFont.FreeTypeFont, char: str) -> Raster:
    upem = font["head"].unitsPerEm
    pad = RENDER_PX
    canvas = Image.new("L", (RENDER_PX * 2 + pad * 2, RENDER_PX * 2 + pad * 2), 0)
    ImageDraw.Draw(canvas).text((pad, RENDER_PX + pad), char, font=pil, fill=255)
    array = np.array(canvas)
    ys, xs = np.nonzero(array >= 128)
    if len(xs) == 0:
        raise SystemExit(f"字形 {char!r} 渲染为空")
    box = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
    # 贴边就说明画布不够大，字形被裁了 —— 裁掉一截会把一笔切成两个连通块
    if box[0] == 0 or box[1] == 0 or box[2] == array.shape[1] - 1 or box[3] == array.shape[0] - 1:
        raise SystemExit(f"字形 {char!r} 触到画布边界，加大 pad")
    pen = BoundsPen(font.getGlyphSet())
    font.getGlyphSet()[font.getBestCmap()[ord(char)]].draw(pen)
    ink = array[box[1]:box[3] + 1, box[0]:box[2] + 1]
    return Raster(char, ink, pen.bounds, upem)


def degrees(skeleton: np.ndarray) -> dict[tuple[int, int], list[tuple[int, int]]]:
    """骨架的邻接表。

    斜邻居若能经由共享的正交邻居绕过去，就不算一条独立连接 —— 阶梯状的骨架会因此被
    误判成分叉，之后每个楼梯拐角都要当交叉点处理。
    """
    height, width = skeleton.shape
    points = [(int(y), int(x)) for y, x in np.argwhere(skeleton)]
    filled = set(points)
    adjacency: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for y, x in points:
        links = []
        for dy, dx in NEIGH8:
            near = (y + dy, x + dx)
            if near not in filled:
                continue
            if dy and dx and ((y + dy, x) in filled or (y, x + dx) in filled):
                continue
            links.append(near)
        adjacency[(y, x)] = links
    return adjacency


def clusters_of_nodes(adjacency, nodes) -> dict[tuple[int, int], int]:
    """把相邻的交叉点/端点并成一个超节点。细化出来的交叉是一小簇像素，不是一个点。"""
    label: dict[tuple[int, int], int] = {}
    index = 0
    for node in nodes:
        if node in label:
            continue
        stack = [node]
        label[node] = index
        while stack:
            current = stack.pop()
            for near in adjacency[current]:
                if near in nodes and near not in label:
                    label[near] = index
                    stack.append(near)
        index += 1
    return label


class Edge:
    """骨架上两个超节点之间的一段。[pts] 从 [a] 侧走到 [b] 侧。"""

    def __init__(self, a: int, b: int, pts: list[tuple[int, int]]):
        self.a, self.b, self.pts = a, b, pts

    def oriented(self, frm: int) -> list[tuple[int, int]]:
        return self.pts if frm == self.a else list(reversed(self.pts))

    def other(self, frm: int) -> int:
        return self.b if frm == self.a else self.a

    def length(self) -> float:
        return sum(
            math.dist(self.pts[i], self.pts[i + 1]) for i in range(len(self.pts) - 1)
        )


def trace_edges(adjacency, nodes, label) -> list[Edge]:
    edges: list[Edge] = []
    seen: set[tuple] = set()
    for node in nodes:
        for step in adjacency[node]:
            path = [node]
            current, previous = step, node
            while current not in nodes:
                path.append(current)
                nexts = [p for p in adjacency[current] if p != previous]
                if not nexts:
                    break
                previous, current = current, nexts[0]
            if current in nodes:
                path.append(current)
            key = min(tuple(path), tuple(reversed(path)))
            if key in seen:
                continue
            seen.add(key)
            edges.append(Edge(label[path[0]], label[path[-1]], path))
    return edges


def _stamp(mask: np.ndarray, raster: Raster, pixels) -> None:
    """沿一串骨架像素盖内切圆盘。"""
    height, width = mask.shape
    for row, col in pixels:
        radius = float(raster.dist[row, col]) * RADIUS_GAIN
        reach = int(math.ceil(radius))
        top, bottom = max(0, row - reach), min(height, row + reach + 1)
        left, right = max(0, col - reach), min(width, col + reach + 1)
        if top >= bottom or left >= right:
            continue
        ys, xs = np.ogrid[top:bottom, left:right]
        mask[top:bottom, left:right] |= (
            (ys - row) ** 2 + (xs - col) ** 2 <= radius * radius)


def _inked(raster: Raster, edges: list[Edge]) -> np.ndarray:
    mask = np.zeros_like(raster.binary, dtype=bool)
    for edge in edges:
        _stamp(mask, raster, edge.pts)
    return mask & raster.binary


def prune(edges: list[Edge], raster: Raster) -> list[Edge]:
    """剪掉毛刺：一头是端点、短过该处笔宽、而且**剪掉不丢墨**的叶边。

    长度只是筛候选 —— 真正的判据是覆盖率：骨架在笔锋外张处会长出朝向轮廓的小杈，
    它们盖住的墨本来就被主干的圆盘盖住了，剪掉不丢东西；而 `w` 那种一个笔宽长的入笔
    短枝是真笔画，剪掉就有一小块墨永远不会被揭示。只按长度剪会连它一起剪掉。

    环上的边一概不剪：再短也是笔画的一部分，剪掉会把闭合的圈剪开。
    """
    kept = list(edges)
    ink = float(raster.binary.sum()) or 1.0
    while len(kept) > 1:
        degree: dict[int, int] = {}
        for edge in kept:
            degree[edge.a] = degree.get(edge.a, 0) + 1
            degree[edge.b] = degree.get(edge.b, 0) + 1
        before = _inked(raster, kept)
        victim = None
        for edge in kept:
            if edge.a == edge.b:
                continue
            tip_a, tip_b = degree.get(edge.a) == 1, degree.get(edge.b) == 1
            if not (tip_a or tip_b):
                continue
            root = edge.pts[-1] if tip_a else edge.pts[0]
            if edge.length() >= SPUR_FACTOR * float(raster.dist[root[0], root[1]]):
                continue
            rest = [e for e in kept if e is not edge]
            lost = float((before & ~_inked(raster, rest)).sum()) / ink
            if lost < PRUNE_INK_TOLERANCE:
                victim = edge
                break
        if victim is None:
            return kept
        kept.remove(victim)
    return kept


def _unit(dy: float, dx: float) -> tuple[float, float]:
    norm = math.hypot(dx, dy) or 1.0
    return dy / norm, dx / norm


def _tip_direction(points: list[tuple[int, int]], at_start: bool) -> tuple[float, float]:
    """一段的起始 / 收尾方向。取 6 个像素的跨度，单像素差分全是 45° 的台阶。"""
    seg = points[:6] if at_start else points[-6:]
    if len(seg) < 2:
        return 0.0, 0.0
    return _unit(seg[-1][0] - seg[0][0], seg[-1][1] - seg[0][1])


def _route_to_unused(start: int, edges: list[Edge], incident, used) -> list[int] | None:
    """从 [start] 沿已走过的边找到最近一个还有未走边的节点。返回途经的边序号。"""
    from collections import deque

    queue = deque([(start, [])])
    seen = {start}
    while queue:
        node, route = queue.popleft()
        if route and any(i not in used for i in incident[node]):
            return route
        for index in incident[node]:
            nxt = edges[index].other(node)
            if nxt not in seen:
                seen.add(nxt)
                queue.append((nxt, route + [index]))
    return None


def walk(edges: list[Edge], start: int,
         heading: tuple[float, float] | None) -> list[tuple[tuple[int, int], bool]]:
    """把一堆边排成一支笔走的顺序。

    在交叉点上选**最接近当前朝向**的那条未走边 —— 手写笔画是连贯的，遇到岔口顺着
    原方向走下去，而不是拐进旁边那笔。所有边都走过才算这一笔写完；一笔里有环
    （`a o l f t` 的圈）时，进环、绕完、回到岔口再出去，正好是写这些字母的顺序。

    走不动而还有未走边时沿已走过的边**回描**：回描的点也要留在点表里，它们经过的地方
    已经揭示过，但笔尖得看得见地走回去。回描段在时间上会走得快（见 [stroke_timing]）。
    """
    from collections import defaultdict

    incident = defaultdict(list)
    for index, edge in enumerate(edges):
        incident[edge.a].append(index)
        if edge.b != edge.a:
            incident[edge.b].append(index)

    used: set[int] = set()
    out: list[tuple[tuple[int, int], bool]] = []

    def emit(points, fresh: bool):
        for point in points:
            if out and out[-1][0] == point:
                continue
            out.append((point, fresh))

    current = start
    while len(used) < len(edges):
        options = [i for i in incident[current] if i not in used]
        if options:
            if heading is None:
                pick = options[0]
            else:
                pick = max(options, key=lambda i: sum(
                    a * b for a, b in zip(
                        heading, _tip_direction(edges[i].oriented(current), True))
                ))
            points = edges[pick].oriented(current)
            emit(points, True)
            used.add(pick)
            heading = _tip_direction(points, False)
            current = edges[pick].other(current)
            continue
        route = _route_to_unused(current, edges, incident, used)
        if route is None:
            break
        for index in route:
            points = edges[index].oriented(current)
            emit(points, False)
            heading = _tip_direction(points, False)
            current = edges[index].other(current)
    return out


class Stroke:
    """一笔：em 坐标的点表 + 相对时长权重。"""

    def __init__(self, glyph: int, char: str, x, y, width, t, weight: float):
        self.glyph, self.char = glyph, char
        self.x, self.y, self.t = x, y, t
        #: 每个点上的半宽（沿法向到墨边缘，取左右较大的那侧，再加余量）。
        self.width = width
        self.weight = weight
        #: 像素空间的 (行, 列, 半宽)，只给覆盖率自检用，不入产物。
        self.ribbon: list[tuple[float, float, float]] = []


def resample(points, flags, raster: Raster):
    """按局部笔宽变步长取样：粗笔画上稀、细笔画上密。"""
    picked = [(points[0], flags[0])]
    acc = 0.0
    for index in range(1, len(points)):
        acc += math.dist(points[index - 1], points[index])
        row, col = points[index]
        step = min(
            max(RESAMPLE_MIN_PX, RESAMPLE_FACTOR * float(raster.dist[row, col])),
            RESAMPLE_MAX_PX,
        )
        if acc >= step:
            picked.append((points[index], flags[index]))
            acc = 0.0
    if picked[-1][0] != points[-1]:
        picked.append((points[-1], flags[-1]))
    return picked


def smooth(coords: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """滑动平均。窗口在两端对称收缩，所以首尾点不动 —— 落笔和收笔的位置不能被抹走。"""
    half = SMOOTH_WINDOW // 2
    out = []
    for index in range(len(coords)):
        reach = min(half, index, len(coords) - 1 - index)
        window = coords[index - reach:index + reach + 1] if reach else [coords[index]]
        out.append((
            sum(c[0] for c in window) / len(window),
            sum(c[1] for c in window) / len(window),
        ))
    return out


def timing(xs, ys, fresh) -> list[float]:
    """每个点的累计时间比例。

    三件事叠在一起：按弧长走（笔速恒定的底子）、曲率大处减速（拐弯要慢，直线可以快）、
    起笔收笔减速（人手起停都要加速度）。回描段乘 0.2：那截墨已经揭示过，笔尖快速掠回去。
    """
    count = len(xs)
    spans = [math.dist((xs[i], ys[i]), (xs[i + 1], ys[i + 1])) for i in range(count - 1)]
    total = sum(spans) or 1.0
    headings = [
        _unit(ys[i + 1] - ys[i], xs[i + 1] - xs[i]) for i in range(count - 1)
    ]
    turns = [0.0] * (count - 1)
    for i in range(1, count - 1):
        dot = sum(a * b for a, b in zip(headings[i - 1], headings[i]))
        turns[i] = math.acos(max(-1.0, min(1.0, dot)))
    rates = [turns[i] / max(spans[i], 1e-6) for i in range(count - 1)]
    reference = float(np.percentile(rates, 90)) if any(rates) else 1.0
    weights = []
    travelled = 0.0
    for i, span in enumerate(spans):
        middle = (travelled + span / 2) / total
        travelled += span
        curve = 1.0 + 0.9 * min(1.0, rates[i] / max(reference, 1e-6))
        ends = 1.0 + 0.8 * (1.0 - 2.0 * min(middle, 1.0 - middle))
        retrace = 0.2 if not (fresh[i] and fresh[i + 1]) else 1.0
        weights.append(span * curve * ends * retrace)
    scale = sum(weights) or 1.0
    stamps, run = [0.0], 0.0
    for weight in weights:
        run += weight
        stamps.append(run / scale)
    return stamps


def component_strokes(raster: Raster, mask: np.ndarray,
                      hint: tuple[tuple[float, float], str]):
    """一个连通块 → 一支笔走过的像素序列。"""
    adjacency = degrees(mask)
    if not adjacency:
        return []
    nodes = {p for p, links in adjacency.items() if len(links) != 2}
    if not nodes:
        # 纯环（没有任何端点或岔口）：在最上最左处剪开当落笔点
        nodes = {min(adjacency, key=lambda p: (p[0], p[1]))}
    label = clusters_of_nodes(adjacency, nodes)
    edges = prune(trace_edges(adjacency, nodes, label), raster)
    if not edges:
        return []

    members: dict[int, list[tuple[int, int]]] = {}
    for pixel, cluster in label.items():
        members.setdefault(cluster, []).append(pixel)
    live = {c for edge in edges for c in (edge.a, edge.b)}
    height, width = raster.binary.shape
    (want_x, want_y), heading = hint
    target = (want_y * (height - 1), want_x * (width - 1))

    def anchor(cluster: int) -> tuple[float, float]:
        pixels = members[cluster]
        return (
            sum(p[0] for p in pixels) / len(pixels),
            sum(p[1] for p in pixels) / len(pixels),
        )

    start = min(live, key=lambda c: math.dist(anchor(c), target))
    return walk(edges, start, HEADINGS[heading])


def node_map(raster: Raster, mask: np.ndarray):
    """诊断用：列出这个连通块的节点（归一坐标、度数）。指定落笔点时按它挑。"""
    adjacency = degrees(mask)
    nodes = {p for p, links in adjacency.items() if len(links) != 2}
    if not nodes:
        nodes = {min(adjacency, key=lambda p: (p[0], p[1]))}
    label = clusters_of_nodes(adjacency, nodes)
    edges = prune(trace_edges(adjacency, nodes, label), raster)
    members: dict[int, list[tuple[int, int]]] = {}
    for pixel, cluster in label.items():
        members.setdefault(cluster, []).append(pixel)
    degree: dict[int, int] = {}
    for edge in edges:
        degree[edge.a] = degree.get(edge.a, 0) + 1
        degree[edge.b] = degree.get(edge.b, 0) + 1
    height, width = raster.binary.shape
    out = []
    for cluster, pixels in sorted(members.items()):
        if cluster not in degree:
            continue
        row = sum(p[0] for p in pixels) / len(pixels)
        col = sum(p[1] for p in pixels) / len(pixels)
        out.append((cluster, col / max(width - 1, 1), row / max(height - 1, 1),
                    degree[cluster]))
    return out, len(edges)


def _tip_reach(raster: Raster, coords, index: int) -> float:
    """这个点是不是笔锋的尖端；是的话返回沿笔向到墨边缘的距离，不是就返回 0。

    细化会把笔画两头**啃掉**大约一个笔宽（Zhang-Suen 一类的细化对凸出的端头是逐层
    剥的），所以中线收得比墨早，末点上按半宽扣一个圆盘补不上那一截 —— `w` 第三个峰
    整个尖是漏的。这里把尖端那个点的半宽撑到够盖住笔锋：撑出字形外面没有代价，
    多出来的部分会被字形 mask 裁掉。

    笔画中途折回的点（回描的折返处）也算尖端 —— `w` 的峰、`T` 的横画两头都是这么来的。
    """
    if len(coords) < 2:
        return 0.0
    if index == 0:
        toward = (coords[0][0] - coords[1][0], coords[0][1] - coords[1][1])
    elif index == len(coords) - 1:
        toward = (coords[-1][0] - coords[-2][0], coords[-1][1] - coords[-2][1])
    else:
        into = _unit(coords[index][0] - coords[index - 1][0],
                    coords[index][1] - coords[index - 1][1])
        out = _unit(coords[index + 1][0] - coords[index][0],
                    coords[index + 1][1] - coords[index][1])
        if into[0] * out[0] + into[1] * out[1] > -0.5:
            return 0.0
        toward = into
    dy, dx = _unit(toward[0], toward[1])
    row, col = coords[index]
    radius = float(raster.dist[
        min(max(int(round(row)), 0), raster.binary.shape[0] - 1),
        min(max(int(round(col)), 0), raster.binary.shape[1] - 1),
    ])
    return march(raster, row, col, dy, dx, radius * 3.0)


def build_stroke(raster: Raster, glyph: int, walked, jitter: float) -> Stroke | None:
    if len(walked) < 2:
        return None
    picked = resample([p for p, _ in walked], [f for _, f in walked], raster)
    fresh = [f for _, f in picked]
    raw = [(float(p[0]), float(p[1])) for p, _ in picked]
    coords = smooth(raw)
    # 尖端判定必须在平滑**之前**做：平滑会把折返处那个尖角抹成一个圆弧，
    # 折返判据（前后方向接近相反）就失效了，`w` 第三个峰会整个漏掉
    tips = [_tip_reach(raster, raw, index) for index in range(len(raw))]
    xs, ys = [], []
    for row, col in coords:
        em_x, em_y = raster.to_em(col, row)
        xs.append(em_x)
        ys.append(em_y)
    normals = []
    for index in range(len(xs)):
        before = max(0, index - 1)
        after = min(len(xs) - 1, index + 1)
        tangent_x, tangent_y = xs[after] - xs[before], ys[after] - ys[before]
        norm = math.hypot(tangent_x, tangent_y) or 1.0
        # 法向 = 切向转 90°。只用来存点表的相邻关系，量半宽用的是**每一段自己的法向**
        normals.append((-tangent_y / norm, tangent_x / norm))
    widths, ribbon = [], []
    limit = MAX_HALF_WIDTH / raster.scale_x
    for index, (row, col) in enumerate(coords):
        span = 0.0
        # 沿**前后两段各自的法向**量，取最宽的那条射线。
        # 不能只用顶点的中心差分法向：`w` 谷底那种尖角上中心差分退化成零向量，
        # 法向变成随机方向，量出来的宽度跟这个点上的墨没关系
        for other in (index - 1, index + 1):
            if not 0 <= other < len(coords):
                continue
            first, second = coords[min(index, other)], coords[max(index, other)]
            ny, nx = _unit(-(second[1] - first[1]), second[0] - first[0])
            span = max(
                span,
                march(raster, row, col, -ny, -nx, limit),
                march(raster, row, col, ny, nx, limit),
            )
        if tips[index] > 0.0:
            # 圆盘扣在平滑后的位置上，而尖端的伸出量是在平滑前量的：把平滑挪走的那段补回去
            span = max(span, tips[index] + math.dist(coords[index], raw[index]))
        span = min(span * RADIUS_GAIN, limit)
        widths.append(span * raster.scale_x)
        ribbon.append((row, col, span))
    stamps = timing(xs, ys, fresh)
    written = sum(
        math.dist((xs[i], ys[i]), (xs[i + 1], ys[i + 1]))
        for i in range(len(xs) - 1)
        if fresh[i] and fresh[i + 1]
    )
    # 长笔画写得快：时长按弧长的 0.85 次方，不是正比。指数是手写的经验值。
    # 下限 0.14：`i` 上那一点按弧长算只有十几毫秒，等于凭空出现；一点也要看得见地点下去
    weight = max((written ** 0.85) * jitter, MIN_WRITE_WEIGHT)
    stroke = Stroke(glyph, raster.char, xs, ys, widths, stamps, weight)
    stroke.ribbon = ribbon
    return stroke


def march(raster: Raster, row: float, col: float,
          dy: float, dx: float, limit: float) -> float:
    """从 (row, col) 沿 (dy, dx) 走到墨的边缘，返回走过的距离（像素）。

    上限 [limit] 是为了不让射线在笔画交叉处穿进另一笔里；至少给半个像素，
    否则中线正好压在边缘上的点会得到 0 宽。
    """
    height, width = raster.binary.shape
    step = 0.5
    distance = 0.0
    while distance + step <= limit:
        probe = distance + step
        y, x = int(round(row + dy * probe)), int(round(col + dx * probe))
        if not (0 <= y < height and 0 <= x < width) or not raster.binary[y, x]:
            break
        distance = probe
    return max(distance, 0.5)


def coverage(raster: Raster, strokes: list[Stroke]) -> float:
    """按**运行时真正画的形状**算这个字形的墨被盖住多少。

    运行时的揭示区是一条圆头圆接的变宽粗线：每一段用**该段自己的法向**铺一个四边形，
    每个点上再扣一个半宽的圆盘。两件事都不能省：
    法向不能取顶点的中心差分 —— 笔画交汇处（`w` 的谷底）中线是个尖角，
    中心差分在那里退化成零向量，法向变成随机方向，四边形跟着塌掉；
    圆盘不能省 —— 尖角处两段四边形之间会豁开一块，正是那些谷底漏掉的墨。

    中轴变换的性质是「内切圆的并集 = 原形状」，所以这个比例应该接近 1。低了就说明
    骨架被剪过头、采样太稀，或者半宽给得不够。
    """
    canvas = np.zeros(raster.binary.shape, dtype=np.uint8)
    for stroke in strokes:
        pts = stroke.ribbon
        for index in range(len(pts) - 1):
            row, col, span = pts[index]
            next_row, next_col, next_span = pts[index + 1]
            dy, dx = next_row - row, next_col - col
            norm = math.hypot(dy, dx) or 1.0
            ny, nx = -dx / norm, dy / norm
            quad = np.array([
                [col - nx * span, row - ny * span],
                [next_col - nx * next_span, next_row - ny * next_span],
                [next_col + nx * next_span, next_row + ny * next_span],
                [col + nx * span, row + ny * span],
            ], dtype=np.int32)
            cv2.fillConvexPoly(canvas, quad, 1)
        for row, col, span in pts:
            cv2.circle(canvas, (int(round(col)), int(round(row))),
                       max(1, int(round(span))), 1, -1)
    ink = raster.binary
    return float((canvas.astype(bool) & ink).sum()) / float(ink.sum())


def collect() -> tuple[list[Stroke], dict[str, float]]:
    font = TTFont(FONT)
    pil = ImageFont.truetype(str(FONT), RENDER_PX)
    #: 固定种子的笔速抖动，±12%。固定是刻意的：同一台机器每次重看都是同一份笔迹
    jitter_source = np.random.default_rng(1989)
    strokes: list[Stroke] = []
    covered: dict[str, float] = {}
    for index, char in enumerate(TEXT):
        if char.isspace():
            continue
        raster = render(font, pil, char)
        count, labels = cv2.connectedComponents(
            raster.skeleton.astype(np.uint8), connectivity=8)
        # 主体在前、分离的小笔画（`i` 的点）在后：按像素数从多到少
        order = sorted(range(1, count), key=lambda c: -int((labels == c).sum()))
        hints = START_HINT.get(char, [])
        here: list[Stroke] = []
        for slot, component in enumerate(order):
            hint = hints[slot] if slot < len(hints) else DEFAULT_HINT
            walked = component_strokes(raster, labels == component, hint)
            jitter = 1.0 + float(jitter_source.uniform(-0.12, 0.12))
            stroke = build_stroke(raster, index, walked, jitter)
            if stroke is not None:
                here.append(stroke)
        strokes += here
        covered[char] = coverage(raster, here)
    return strokes, covered


def pause_weights(strokes: list[Stroke]) -> list[float]:
    """抬笔停顿的权重。

    补 `i` 上那一点之前要停久一点 —— 笔要从字底抬到字顶，那段空程是看得见的时间；
    换词之前也停一下，真人写完 `Taylor` 会顿一下再起 `Swift`。
    """
    weights = []
    for index, stroke in enumerate(strokes):
        if index == len(strokes) - 1:
            weights.append(0.0)
            continue
        following = strokes[index + 1]
        if following.glyph == stroke.glyph:
            weights.append(2.6)
        elif TEXT[stroke.glyph + 1:following.glyph].strip() == "" and \
                following.glyph - stroke.glyph > 1:
            weights.append(1.8)
        else:
            weights.append(1.0)
    return weights


HEADER = '''package com.tracktosearch.ui.screen.swiftie

/**
 * 「Taylor Swift」这十二笔的**笔心中线**，由 `scripts/build-swiftie-signature-path.py`
 * 从 `res/font/swiftie_script.ttf`（子集化的 Pacifico）的字形骨架生成。
 *
 * 签名不是横向擦出来的：竖直的揭示边会从字形中间切过去。这张表给的是每一笔的中线与
 * 每个点上的半宽，运行时沿中线铺一条圆头圆接的变宽粗线，铺过的地方就是
 * 「已经写出来的墨」。
 *
 * 坐标是 **em 单位**、y 向下、基线为 0、原点在该字形自己的落笔点 —— 与
 * `Paint.getTextPath(text, i, i + 1, 0f, 0f, path)` 的坐标空间一致，所以乘一个字号、
 * 加上该字形的笔位就落到位，与字号无关。
 *
 * 字形若变（重新子集化并且轮廓有改动），必须重跑生成脚本。改这个文件里的数字没有意义。
 */
internal object SwiftieSignaturePath {

    /** 签名文字。生成时的输入，运行时用它量笔位。 */
    const val TEXT: String = "%TEXT%"

    /** 每个点占的 float 数：x, y, w, t。 */
    const val STRIDE: Int = 4

    /** 每一笔属于 [TEXT] 的哪个下标。同一个下标出现两次就是那个字形有分离的笔画。 */
    val GLYPH_INDEX: IntArray = intArrayOf(%GLYPHS%)

    /** 每一笔的书写时长权重（弧长^0.85 × 固定种子的 ±12% 抖动）。 */
    val WRITE_WEIGHT: FloatArray = floatArrayOf(%WRITE%)

    /** 每一笔之后抬笔停顿的权重。末笔为 0。 */
    val PAUSE_WEIGHT: FloatArray = floatArrayOf(%PAUSE%)

    /**
     * 点表。每 [STRIDE] 个 float 一个点：
     * `x, y` 中线坐标；`w` 该点的半宽；`t` 该点的累计时间比例。
     *
     * 半宽不是内切圆半径，是沿法向量到墨边缘的距离再加一点余量 —— 弯道外侧的墨在内切圆
     * 之外，用内切圆铺会在外侧漏出没揭示的细条。溢出字形的部分由字形 mask 裁掉。
     *
     * `t` 不是弧长比例 —— 曲率大处、起笔收笔处都放慢，回描段加快，全部烤进这个值里。
     */
    val POINTS: Array<FloatArray> = arrayOf(
%POINTS%
    )
}
'''


def emit(strokes: list[Stroke]) -> str:
    pauses = pause_weights(strokes)
    blocks = []
    for order, stroke in enumerate(strokes):
        numbers = []
        for index in range(len(stroke.x)):
            numbers += [
                stroke.x[index], stroke.y[index],
                stroke.width[index], stroke.t[index],
            ]
        rows = []
        for start in range(0, len(numbers), 12):
            rows.append("            " + ", ".join(
                f"{value:.4f}f" for value in numbers[start:start + 12]))
        blocks.append(
            f"        // 第 {order + 1} 笔：{stroke.char!r}，{len(stroke.x)} 点\n"
            "        floatArrayOf(\n"
            + ",\n".join(rows) + "\n        )"
        )
    return (HEADER
            .replace("%TEXT%", TEXT)
            .replace("%GLYPHS%", ", ".join(str(s.glyph) for s in strokes))
            .replace("%WRITE%", ", ".join(f"{s.weight:.4f}f" for s in strokes))
            .replace("%PAUSE%", ", ".join(f"{p:.2f}f" for p in pauses))
            .replace("%POINTS%", ",\n".join(blocks)))


def debug_image(strokes: list[Stroke], path: Path) -> None:
    """检查图。

    颜色从深蓝到亮黄表示同一笔里的先后（也就是笔尖走的方向），绿点是落笔处、
    红点是收笔处，数字是第几笔。笔顺对不对只能这么看出来 —— 单色线看不出方向。
    """
    scale = 320
    pil = ImageFont.truetype(str(FONT), scale)
    pens = [pil.getlength(TEXT[:i]) for i in range(len(TEXT) + 1)]
    baseline = int(scale * 1.35)
    canvas = Image.new("RGB", (int(pens[-1]) + 90, int(scale * 2.1)), (247, 245, 242))
    draw = ImageDraw.Draw(canvas)
    draw.text((45, baseline), TEXT, font=pil, fill=(214, 209, 203), anchor="ls")
    for order, stroke in enumerate(strokes):
        pts = [
            (45 + pens[stroke.glyph] + x * scale, baseline + y * scale)
            for x, y in zip(stroke.x, stroke.y)
        ]
        for index in range(len(pts) - 1):
            fraction = stroke.t[index]
            color = (
                int(25 + 230 * fraction),
                int(45 + 165 * fraction),
                int(150 - 110 * fraction),
            )
            draw.line([pts[index], pts[index + 1]], fill=color, width=4)
        draw.ellipse([pts[0][0] - 8, pts[0][1] - 8, pts[0][0] + 8, pts[0][1] + 8],
                     fill=(20, 190, 90))
        draw.ellipse([pts[-1][0] - 6, pts[-1][1] - 6, pts[-1][0] + 6, pts[-1][1] + 6],
                     fill=(220, 30, 30))
        draw.text((pts[0][0] + 9, pts[0][1] - 30), str(order + 1), fill=(10, 10, 10))
    canvas.save(path)


def report_nodes() -> None:
    """打印每个字形的骨架节点，用来给 [START_HINT] 挑落笔点。"""
    font = TTFont(FONT)
    pil = ImageFont.truetype(str(FONT), RENDER_PX)
    for char in TEXT:
        if char.isspace():
            continue
        raster = render(font, pil, char)
        count, labels = cv2.connectedComponents(
            raster.skeleton.astype(np.uint8), connectivity=8)
        order = sorted(range(1, count), key=lambda c: -int((labels == c).sum()))
        for slot, component in enumerate(order):
            nodes, edge_count = node_map(raster, labels == component)
            marks = "  ".join(
                f"[{c}] x={x:.2f} y={y:.2f} deg={d}" for c, x, y, d in nodes)
            print(f"{char!r} 块{slot} 边{edge_count}: {marks}")


def frame_images(strokes: list[Stroke], path: Path) -> None:
    """把动画在几个时刻的画面直接铺出来 —— 这是唯一能看出「像不像写出来的」的检查。

    时间分配复刻 Kotlin 侧：书写 [WRITE_MS]、停顿 [PAUSE_MS]，各按权重分给每一笔。
    这两个常量改了这里也要改，否则检查图和真机不是一回事。
    """
    scale = 300
    pil = ImageFont.truetype(str(FONT), scale)
    pens = [pil.getlength(TEXT[:i]) for i in range(len(TEXT) + 1)]
    baseline = int(scale * 1.35)
    width, height = int(pens[-1]) + 90, int(scale * 2.0)
    ink = Image.new("L", (width, height), 0)
    ImageDraw.Draw(ink).text((45, baseline), TEXT, font=pil, fill=255, anchor="ls")
    ink_mask = np.array(ink) >= 128

    write_total = sum(s.weight for s in strokes)
    pauses = pause_weights(strokes)
    pause_total = sum(pauses) or 1.0
    windows, cursor = [], 0.0
    for index, stroke in enumerate(strokes):
        span = WRITE_MS * stroke.weight / write_total
        windows.append((cursor, cursor + span))
        cursor += span + PAUSE_MS * pauses[index] / pause_total

    tiles = []
    for fraction in (0.15, 0.35, 0.55, 0.75, 1.0):
        now = (WRITE_MS + PAUSE_MS) * fraction
        canvas = np.zeros((height, width), dtype=np.uint8)
        for index, stroke in enumerate(strokes):
            start, end = windows[index]
            if now < start:
                continue
            progress = 1.0 if now >= end else (now - start) / max(end - start, 1e-6)
            pts = [
                (baseline + stroke.y[i] * scale,
                 45 + pens[stroke.glyph] + stroke.x[i] * scale,
                 stroke.width[i] * scale)
                for i in range(len(stroke.x))
                if stroke.t[i] <= progress
            ] or [(baseline + stroke.y[0] * scale,
                   45 + pens[stroke.glyph] + stroke.x[0] * scale,
                   stroke.width[0] * scale)]
            for step in range(len(pts) - 1):
                row, col, span_a = pts[step]
                next_row, next_col, span_b = pts[step + 1]
                ny, nx = _unit(-(next_col - col), next_row - row)
                quad = np.array([
                    [col - nx * span_a, row - ny * span_a],
                    [next_col - nx * span_b, next_row - ny * span_b],
                    [next_col + nx * span_b, next_row + ny * span_b],
                    [col + nx * span_a, row + ny * span_a],
                ], dtype=np.int32)
                cv2.fillConvexPoly(canvas, quad, 1)
            for row, col, span_a in pts:
                cv2.circle(canvas, (int(round(col)), int(round(row))),
                           max(1, int(round(span_a))), 1, -1)
        shown = np.zeros((height, width, 3), dtype=np.uint8)
        shown[:] = (247, 245, 242)
        shown[ink_mask] = (223, 218, 212)
        shown[ink_mask & canvas.astype(bool)] = (214, 51, 132)
        tiles.append(Image.fromarray(shown))
    sheet = Image.new("RGB", (width, height * len(tiles)), (255, 255, 255))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, (0, index * height))
    sheet.save(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", action="store_true", help="额外写一张按笔序上色的检查图")
    parser.add_argument("--frames", action="store_true", help="额外写一张五个时刻的画面图")
    parser.add_argument("--nodes", action="store_true", help="只打印骨架节点，不写产物")
    args = parser.parse_args()

    if args.nodes:
        report_nodes()
        return

    strokes, covered = collect()
    if len(strokes) < len(TEXT.replace(" ", "")):
        sys.exit(f"只排出 {len(strokes)} 笔，少于字形数，骨架化或排序出了问题")
    worst = min(covered, key=lambda c: covered[c])
    if covered[worst] < MIN_COVERAGE:
        sys.exit(f"字形 {worst!r} 的墨只被盖住 {covered[worst]:.1%}，"
                 f"低于 {MIN_COVERAGE:.0%}：骨架剪过头或采样太稀")

    budget = WRITE_MS
    total = sum(s.weight for s in strokes)
    print(f"{len(strokes)} 笔，书写预算 {budget:.0f}ms，"
          f"最差覆盖率 {worst!r} {covered[worst]:.1%}：")
    for order, stroke in enumerate(strokes):
        span = sum(
            math.dist((stroke.x[i], stroke.y[i]), (stroke.x[i + 1], stroke.y[i + 1]))
            for i in range(len(stroke.x) - 1)
        )
        print(f"  {order + 1:2d} {stroke.char!r} {len(stroke.x):3d} 点  "
              f"中线 {span:.3f} em  盖住 {covered[stroke.char]:.1%}  "
              f"{budget * stroke.weight / total:6.1f}ms")

    OUT.write_text(emit(strokes), encoding="utf-8")
    print(f"写出 {OUT.relative_to(ROOT)}（{OUT.stat().st_size} 字节）")
    if args.debug:
        DEBUG_DIR.mkdir(exist_ok=True)
        target = DEBUG_DIR / "signature-strokes.png"
        debug_image(strokes, target)
        print(f"检查图 {target.relative_to(ROOT)}")
    if args.frames:
        DEBUG_DIR.mkdir(exist_ok=True)
        target = DEBUG_DIR / "signature-frames.png"
        frame_images(strokes, target)
        print(f"画面图 {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
