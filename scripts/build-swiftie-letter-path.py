#!/usr/bin/env python3
"""生成卡片右下角那句 `All’s fair in love / and poetry.` 的笔心中线表 `SwiftieLetterPath.kt`。

与 `build-swiftie-signature-path.py`（签名那十二笔）同一套做法：把每个字形单独渲成
1px = 1 font unit 的位图，细化取骨架，距离变换取每个点的笔尖半径，再按「一支笔真的会走的
顺序」排好。区别只在**排版**：这里是两行句子，笔位按 PIL 量出来的 advance 累加，
换行时重置笔位、基线下降一行。

为什么复用那套骨架机器而不是另写一套：羽毛笔落在纸上的墨就是「沿中线铺圆头圆接的
变宽粗线」，与签名共用同一个算法。表里的半宽、笔速包络、笔顺全部与签名同源。

坐标：**em 单位**、y 向下、每条线自己的基线为 y = 0、每条线的**起笔点**为 x = 0。
运行时乘一个字号再平移即可：第 i 行要居中就减 `LINE_WIDTH_EM[i] / 2`，
每个字形的轮廓按 `PEN_X_EM[g]` 摆在行内。

用法：
    python scripts/build-swiftie-letter-path.py [--debug]
`--debug` 在 .scratch/ 下写一张「原字 vs 按表重建」的对照图。
"""
from __future__ import annotations

import argparse
import importlib.util
import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "app/src/main/res/font/era_ttpd_letter.ttf"
OUT = ROOT / "app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieLetterPath.kt"
DEBUG_DIR = ROOT / ".scratch"

#: 两句原文。撇号是弯的（U+2019），与卡片里别处的文案一致
LINES = ["All’s fair in love", "and poetry."]

#: 行距（em）。运行时按同一个字号缩放。
LINE_PITCH_EM = 1.34

#: 点表分片的大小（float 数）。见产物里那段注释：一个 object 的初始化器有 64KB 上限。
CHUNK_FLOATS = 1200

# 骨架机器直接从签名脚本拿：同一套细化 / 剪毛刺 / 走的顺序 / 半宽 / 笔速包络
_SIG_PATH = Path(__file__).resolve().parent / "build-swiftie-signature-path.py"
_spec = importlib.util.spec_from_file_location("swiftie_signature_gen", _SIG_PATH)
sig = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(sig)

# 半宽上限按**这个字体自己的笔画尺度**重设（签名那份 0.13 em 是给 Pacifico 的粗笔刷定的）。
#
# 上限的作用是拦住「法向射线在笔画交汇处一路穿进隔壁那一笔」，而签名的墨会被字形轮廓
# 裁掉，超出的部分本来就看不见；这里的墨**就是**字形本身，没有 mask 可垫 —— 沿用 0.13
# 会在每个交汇处盖出一团半径 0.13 em 的圆盘（实测面积是原字的 2.2 倍）。Great Vibes 最
# 粗的笔画内切半径 0.036 em（`A`），上限取它的一档半：足够让弯道外侧的弦落到墨外面，
# 又不至于在交汇处鼓出可见的包。
sig.MAX_HALF_WIDTH = 0.055
# 放大系数同理收一档：1.14 是给「反正会被 mask 裁」的签名用的，这里是终态墨迹，
# 只要盖住字形的边缘就够了（1.06 让抗锯齿那一圈不至于露出没写到的白边）。
sig.RADIUS_GAIN = 1.06


def layout(font: TTFont, pil: ImageFont.FreeTypeFont):
    """(行号, 行内字序, 字, 该字的落笔 x（em，已按「每行各自居中」摆好）)。

    空格只推进笔位。**居中在这里先烤进去**：运行时只知道「最长那一行的左边在哪」，
    每行的居中量必须由生成器给 —— 否则短的那一行会贴在左边。
    """
    widths = [pil.getlength(line) / sig.RENDER_PX for line in LINES]
    glyphs = []
    for line_index, line in enumerate(LINES):
        # 起始笔位（px）：第一行最长，所以它从 0 起算，其余各行按差值的一半右移
        pen = 0.5 * (widths[0] - widths[line_index]) * sig.RENDER_PX
        for char_index, char in enumerate(line):
            if not char.isspace():
                glyphs.append((line_index, char_index, char, pen / sig.RENDER_PX))
            pen += pil.getlength(char)
    return glyphs


def line_widths(pil: ImageFont.FreeTypeFont) -> list[float]:
    return [pil.getlength(line) / sig.RENDER_PX for line in LINES]


def collect():
    font = TTFont(FONT)
    pil = ImageFont.truetype(str(FONT), sig.RENDER_PX)
    jitter_source = np.random.default_rng(2024)
    strokes: list[sig.Stroke] = []
    covered: dict[str, float] = {}
    glyphs: list[tuple[int, str, float]] = []
    for line_index, char_index, char, pen_x in layout(font, pil):
        ordinal = len(glyphs)
        glyphs.append((line_index, char, pen_x))
        raster = sig.render(font, pil, char)
        count, labels = cv2.connectedComponents(raster.skeleton.astype(np.uint8), connectivity=8)
        # 主体在前、分离的小笔画（撇号、点）在后：按像素数从多到少
        order = sorted(range(1, count), key=lambda c: -int((labels == c).sum()))
        hints = sig.START_HINT.get(char, [])
        here: list[sig.Stroke] = []
        for slot, component in enumerate(order):
            hint = hints[slot] if slot < len(hints) else sig.DEFAULT_HINT
            walked = sig.component_strokes(raster, labels == component, hint)
            jitter = 1.0 + float(jitter_source.uniform(-0.12, 0.12))
            stroke = sig.build_stroke(raster, char_index, walked, jitter)
            if stroke is not None:
                # build_stroke 给的是**字形自己**的坐标（原点在该字形的左边缘），
                # 这里把落笔位并进去 —— 产物里的 x 是**整行**坐标，运行时只做居中平移
                stroke.x = [value + pen_x for value in stroke.x]
                stroke.line = line_index
                stroke.ordinal = ordinal
                here.append(stroke)
        strokes += here
        covered[char] = sig.coverage(raster, here)
    return strokes, covered, glyphs


def pause_weights(strokes: list[sig.Stroke]) -> list[float]:
    """抬笔停顿的权重。换行 / 词间停一下，同一个字形的第二笔（撇号、点）停久一点。"""
    weights = []
    for index, stroke in enumerate(strokes):
        if index == len(strokes) - 1:
            weights.append(0.0)
            continue
        following = strokes[index + 1]
        if following.ordinal == stroke.ordinal:
            weights.append(2.6)          # 同一个字形的第二笔（撇号上的点、句点）
        elif following.line != stroke.line:
            weights.append(2.4)          # 换行
        else:
            gap = LINES[stroke.line][stroke.glyph + 1:following.glyph]
            weights.append(1.8 if gap.strip() == "" else 1.0)   # 词间
    return weights


HEADER = '''package com.tracktosearch.ui.screen.swiftie

/**
 * 卡片右下角那句 `All’s fair in love / and poetry.` 的**笔心中线**，由
 * `scripts/build-swiftie-letter-path.py` 从 `res/font/era_ttpd_letter.ttf`
 * （子集化的 Great Vibes）的字形骨架生成。
 *
 * 与 `SwiftieSignaturePath` 同一套数据：每一笔给中线点列 + 每个点上的半宽，运行时沿中线
 * 铺圆头圆接的变宽粗线，铺到哪就是写到哪；羽笔的笔尖沿同一条曲线跟着走
 * （见 `SwiftieEraMotifs` 的 `drawLetterWriting`）。已经写完的字形换成**字形轮廓**画 ——
 * 与中线铺出来的是同一块墨，但每帧要重建的裁剪路径小一个数量级。
 *
 * 坐标是 **em 单位**、y 向下、**每条线自己的基线为 y = 0**、**每条线自己的起笔点为 x = 0**：
 * 乘一个字号、加上该行的基线 y 与居中后的 x 就落到位，与字号无关。
 * 第 i 行要居中，x 平移量是 `-LINE_WIDTH_EM[i] / 2`。
 *
 * 字形若变（重新子集化并且轮廓有改动），必须重跑生成脚本。改这个文件里的数字没有意义。
 */
internal object SwiftieLetterPath {

    /** 两行原文，生成时的输入。 */
    val LINES: Array<String> = arrayOf("%L0%", "%L1%")

    /** 每个点占的 float 数：x, y, w, t。 */
    const val STRIDE: Int = 4

    /** 行距（em）。 */
    const val LINE_PITCH_EM: Float = %PITCH%f

    /** 每一行的排字宽度（em，含笔画外伸），用来把这一行居中。 */
    val LINE_WIDTH_EM: FloatArray = floatArrayOf(%WIDTHS%)

    /**
     * 字形表：全句按阅读顺序排下来的字。
     *
     * 运行时用 `Paint.getTextPath` 取这里每个字的轮廓，所以表里的字必须与子集化的字体
     * 一致 —— 改一个字符就要重跑生成脚本。
     */
    val GLYPHS: Array<String> = arrayOf(%GLYPHS%)

    /** 每个字形属于第几行。 */
    val GLYPH_LINE: IntArray = intArrayOf(%GLYPHLINE%)

    /** 每个字形在这一行里的落笔 x（em）。 */
    val PEN_X_EM: FloatArray = floatArrayOf(%PENX%)

    /** 每一笔属于哪个字形（[GLYPHS] 的下标）。同一个下标出现两次就是该字形有分离的笔画。 */
    val GLYPH_ORDINAL: IntArray = intArrayOf(%ORDINALS%)

    /** 每一笔的书写时长权重（弧长^0.85 × 固定种子的 ±12% 抖动）。 */
    val WRITE_WEIGHT: FloatArray = floatArrayOf(%WRITE%)

    /** 每一笔之后抬笔停顿的权重。末笔为 0。 */
    val PAUSE_WEIGHT: FloatArray = floatArrayOf(%PAUSE%)

    /** 点表：每 [STRIDE] 个 float 一个点 —— `x, y` 中线；`w` 半宽；`t` 累计时间比例。 */
    val POINTS: Array<FloatArray> = arrayOf(
%POINT_REFS%
    )
}

/**
 * 点表分片。
 *
 * 整张表 27 笔、五千来个 float，一次全塞进同一个 object 会撞上 JVM 的 64KB 方法上限
 * （`Method too large: <clinit>`）—— 一个 object 的属性初始化器全部编译进同一个
 * `<clinit>`。按**总 float 数**分成每片 %CHUNK% 个（不是按笔数：`A` 那一笔有 557 个点），
 * 每片一个 object，运行时在 [SwiftieLetterPath.POINTS] 里首尾接起来。
 */
%PARTS%
'''


def emit(strokes, glyphs, widths) -> str:
    pauses = pause_weights(strokes)
    # 点表分片：每片最多 CHUNK_FLOATS 个 float，见 HEADER 里那段
    parts: list[tuple[str, list[str]]] = []
    current: list[str] = []
    current_floats = 0
    for order, stroke in enumerate(strokes):
        numbers = []
        for index in range(len(stroke.x)):
            numbers += [
                stroke.x[index], stroke.y[index],
                stroke.width[index], stroke.t[index],
            ]
        body = ", ".join("%.4ff" % value for value in numbers)
        char = LINES[stroke.line][stroke.glyph]
        current.append(
            "        // 第 %d 笔：'%s'（第 %d 行），%d 点\n        floatArrayOf(%s)"
            % (order + 1, char, stroke.line + 1, len(stroke.x), body))
        current_floats += len(numbers)
        if current_floats >= CHUNK_FLOATS:
            parts.append(("LetterPoints%d" % len(parts), current))
            current, current_floats = [], 0
    if current:
        parts.append(("LetterPoints%d" % len(parts), current))
    blocks = [entry for _, body in parts for entry in body]
    return HEADER \
        .replace("%L0%", LINES[0]) \
        .replace("%L1%", LINES[1]) \
        .replace("%PITCH%", "%.4f" % LINE_PITCH_EM) \
        .replace("%WIDTHS%", ", ".join("%.4ff" % w for w in widths)) \
        .replace("%GLYPHS%", ", ".join('"%s"' % g[1] for g in glyphs)) \
        .replace("%GLYPHLINE%", ", ".join(str(g[0]) for g in glyphs)) \
        .replace("%PENX%", ", ".join("%.4ff" % g[2] for g in glyphs)) \
        .replace("%ORDINALS%", ", ".join(str(s.ordinal) for s in strokes)) \
        .replace("%WRITE%", ", ".join("%.4ff" % s.weight for s in strokes)) \
        .replace("%PAUSE%", ", ".join("%.4ff" % w for w in pauses)) \
        .replace("%POINT_REFS%", ",\n".join("        *%s.POINTS" % name for name, _ in parts)) \
        .replace("%CHUNK%", str(CHUNK_FLOATS)) \
        .replace("%PARTS%", "\n\n".join(
            "private object %s {\n    val POINTS: Array<FloatArray> = arrayOf(\n%s\n    )\n}"
            % (name, ",\n".join(body)) for name, body in parts))


def debug_images(strokes, widths) -> None:
    """原字 vs 按表重建：同一个字号、同一条基线，上下摆一张图里比。"""
    DEBUG_DIR.mkdir(exist_ok=True)
    scale = 0.33
    font_px = int(sig.RENDER_PX * scale)
    pitch_px = LINE_PITCH_EM * font_px
    canvas_w = int(max(widths) * font_px) + 120
    canvas_h = int(pitch_px + 260)

    truth = Image.new("L", (canvas_w, canvas_h), 0)
    draw = ImageDraw.Draw(truth)
    pil_small = ImageFont.truetype(str(FONT), font_px)
    for index, line in enumerate(LINES):
        draw.text((canvas_w / 2 - widths[index] * font_px / 2, 130 + index * pitch_px),
                  line, font=pil_small, fill=255, anchor="ls")

    rebuilt = np.zeros((canvas_h, canvas_w), np.uint8)
    for stroke in strokes:
        # 笔位里已经含了「每行各自居中」的位移，所以两行共用同一个左边
        base_x = canvas_w / 2 - widths[0] * font_px / 2
        base_y = 130 + stroke.line * pitch_px
        pts = [(base_x + stroke.x[i] * font_px, base_y + stroke.y[i] * font_px,
                stroke.width[i] * font_px) for i in range(len(stroke.x))]
        for index in range(len(pts) - 1):
            x0, y0, r0 = pts[index]
            x1, y1, r1 = pts[index + 1]
            dy, dx = y1 - y0, x1 - x0
            norm = math.hypot(dy, dx) or 1.0
            ny, nx = -dx / norm, dy / norm
            cv2.fillConvexPoly(rebuilt, np.array([
                [x0 - nx * r0, y0 - ny * r0], [x1 - nx * r1, y1 - ny * r1],
                [x1 + nx * r1, y1 + ny * r1], [x0 + nx * r0, y0 + ny * r0],
            ], dtype=np.int32), 1)
        for x, y, r in pts:
            cv2.circle(rebuilt, (int(round(x)), int(round(y))), max(1, int(round(r))), 1, -1)

    truth_arr = np.array(truth) >= 128
    ink = np.array([70, 66, 60], np.uint8)
    paper = np.array([250, 248, 245], np.uint8)
    sheet = Image.new("RGB", (canvas_w, canvas_h * 2 + 12), tuple(paper))
    sheet.paste(Image.fromarray(np.where(truth_arr[:, :, None], ink, paper).astype(np.uint8)), (0, 0))
    sheet.paste(Image.fromarray(np.where(rebuilt[:, :, None], ink, paper).astype(np.uint8)),
                (0, canvas_h + 12))
    sheet.save(DEBUG_DIR / "letter-compare.png")
    hit = int((truth_arr & rebuilt.astype(bool)).sum())
    print("重建覆盖率 %.4f（原字 %d px，重建 %d px）"
          % (hit / max(int(truth_arr.sum()), 1), int(truth_arr.sum()), int(rebuilt.sum())))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    strokes, covered, glyphs = collect()
    widths = line_widths(ImageFont.truetype(str(FONT), sig.RENDER_PX))
    if not strokes:
        sys.exit("没有抽到任何笔画")
    print("共 %d 笔 / %d 个字形，行宽(em) %s"
          % (len(strokes), len(glyphs), ["%.3f" % w for w in widths]))
    low = [char for char, value in covered.items() if value < sig.MIN_COVERAGE]
    OUT.write_text(emit(strokes, glyphs, widths), encoding="utf-8")
    print("写出", OUT)
    if args.debug:
        debug_images(strokes, widths)
    if low:
        print("覆盖率不足的字形：", low, file=sys.stderr)


if __name__ == "__main__":
    main()
