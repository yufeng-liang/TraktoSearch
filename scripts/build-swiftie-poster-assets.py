#!/usr/bin/env python3
"""Regenerate the Swiftie easter-egg poster assets from the two source images.

Run from the repository root:

    python scripts/build-swiftie-poster-assets.py

Inputs (committed under docs/previews/swiftie-poster/):

  sky-clean.png  1600x2848  the poster sky with all lettering painted out.
                            Lossless master; the only source for the shipped sky.
  9x16.jpg       2160x3840  the untouched poster. Source for two things:
                            the royal-blue script outlines and the glitter grain.

Outputs:

  app/src/main/res/drawable-nodpi/swiftie_poster_sky.webp
  app/src/main/res/drawable-nodpi/swiftie_glitter.webp
  app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieCongratsPath.kt

Requires: pillow, numpy, opencv-python.
"""

from __future__ import annotations

import os
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "previews", "swiftie-poster")
RES = os.path.join(ROOT, "app", "src", "main", "res")
KOTLIN = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "tracktosearch", "ui", "screen", "swiftie"
)

# ---------------------------------------------------------------- sky

# 1200x2136 at quality 86 measures 41,874 B. One step above the 1080-wide screens
# so QHD panels upscale by ~1% instead of ~12%; the source is only 1600 wide, so
# shipping more pixels than this just spends bitrate on blur.
SKY_WIDTH = 1200
SKY_QUALITY = 86

# ---------------------------------------------------------------- glitter

# The tile is cropped at native scale, never resized: the specks are 6-10 px in the
# source and any resampling averages them into flat pink, which is exactly the look
# the hand-rolled gradient had. Drawn with TileMode.Mirror so no seam work is needed.
GLITTER_QUALITY = 92


def sky_plate() -> None:
    src = Image.open(os.path.join(SRC, "sky-clean.png"))
    # The master carries a few thousand pixels at alpha 245-254. Flatten onto the
    # image's own edge colour rather than white so nothing lightens at the border.
    flat = Image.new("RGB", src.size, tuple(np.asarray(src)[:, :, :3].reshape(-1, 3).mean(0).astype(int)))
    flat.paste(src.convert("RGB"), mask=src.getchannel("A"))
    height = round(src.size[1] * SKY_WIDTH / src.size[0])
    out = os.path.join(RES, "drawable-nodpi", "swiftie_poster_sky.webp")
    flat.resize((SKY_WIDTH, height), Image.LANCZOS).save(
        out, "WEBP", quality=SKY_QUALITY, method=6
    )
    print(f"sky      {SKY_WIDTH}x{height} q{SKY_QUALITY}  {os.path.getsize(out):,} B")


def poster() -> np.ndarray:
    return np.asarray(Image.open(os.path.join(SRC, "9x16.jpg")).convert("RGB"))


def masks(poster_rgb: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Colour-key the two ink layers out of the untouched poster.

    The royal-blue key has to reject the blue *sky* in the top-left corner, which is
    also blue-dominant. `r < 95 and g < 125` does it: the ink is #1E3CB6 (r=30, g=60),
    the sky around #7BB7DC (r=123, g=183).

    The glitter key runs on HSV saturation, not on r-g: soft sky pink (#F8ACD0) sits
    at S=0.31 while the glitter core (#DB578A) is at S=0.60+. A 25 px close then fills
    the white specks that sit inside the strokes.
    """
    hsv = cv2.cvtColor(poster_rgb, cv2.COLOR_RGB2HSV_FULL).astype(np.int16)
    sat = hsv[:, :, 1]
    r, g, b = (poster_rgb[:, :, i].astype(np.int16) for i in range(3))

    blue = ((b - r) > 55) & (r < 95) & (g < 125)
    pink = (
        (sat > 118)
        & (((r - g) > 70) | (((r - g) > 45) & ((b - g) > 20)))
        & (r > 120)
        & (b < 215)
    )

    def close(mask: np.ndarray, k: int) -> np.ndarray:
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
        return cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_CLOSE, kernel).astype(bool)

    return close(blue, 9), close(pink, 25)


def drop_specks(mask: np.ndarray, min_area: int) -> np.ndarray:
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), 8)
    keep = np.zeros_like(mask)
    for i in range(1, count):
        if stats[i][4] >= min_area:
            keep |= labels == i
    return keep


def glitter_tile(poster_rgb: np.ndarray, pink: np.ndarray) -> None:
    """Crop the largest square that fits entirely inside the glitter strokes."""
    solid = drop_specks(pink, 4000).astype(np.uint8)
    distance = cv2.distanceTransform(solid, cv2.DIST_C, 5)
    half = int(distance.max())
    y, x = np.unravel_index(distance.argmax(), distance.shape)
    patch = poster_rgb[y - half : y + half, x - half : x + half]
    out = os.path.join(RES, "drawable-nodpi", "swiftie_glitter.webp")
    Image.fromarray(patch).save(out, "WEBP", quality=GLITTER_QUALITY, method=6)
    print(
        f"glitter  {patch.shape[1]}x{patch.shape[0]} q{GLITTER_QUALITY} "
        f"from ({x},{y})  {os.path.getsize(out):,} B"
    )


# ---------------------------------------------------------------- script outlines

# Components are grouped into the three written lines by the top edge of their
# bounding box. The thresholds come from the boxes the mask actually produces
# (printed on every run so a future source image can be re-checked):
#
#   y0= 424  Congrats            -> line 1
#   y0= 894  on                  -> line 2
#   y0= 915  ! stem   of Forever -> line 3
#   y0=1040  F        of Forever -> line 3
#   y0=1169  orever              -> line 3
#   y0~1270  ! dot               -> line 3
#
# Clustering on the box *centre* gets this wrong: the "!" is tall and the baseline
# rises to the right, so its centre lands nearer to "on" than to its own line.
LINE_SPLITS = (700, 905)

# Kept small enough to keep the dot of the "!" (a ~40 px blob) while still dropping
# JPEG speckle. The old 3000 threshold silently ate that dot.
BLUE_MIN_AREA = 150

# Douglas-Peucker tolerance in source pixels. 1.2 lands ~1000 points across the 27
# contours; the emitted path smooths every corner into a quadratic, so at 1080 px
# wide there is no visible faceting.
SIMPLIFY_EPSILON = 1.2


def line_masks(blue: np.ndarray) -> list[np.ndarray]:
    ink = drop_specks(blue, BLUE_MIN_AREA)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(ink.astype(np.uint8), 8)
    lines = [np.zeros_like(ink) for _ in range(3)]
    rows = []
    for i in range(1, count):
        x, y, w, h, area = stats[i]
        index = 0 if y < LINE_SPLITS[0] else (1 if y < LINE_SPLITS[1] else 2)
        lines[index] |= labels == i
        rows.append((y, x, w, h, area, index))
    for y, x, w, h, area, index in sorted(rows):
        print(f"  ink x{x:5d} y{y:5d} w{w:5d} h{h:5d} area{area:7d} -> line {index + 1}")
    return lines


def smooth_path(points: np.ndarray, origin: tuple[int, int]) -> str:
    """Emit one closed contour as quadratics through the polygon's edge midpoints.

    Starting at a midpoint and using each vertex as the control point rounds every
    corner by half a segment. On a brush script that reads as the stroke it already
    is; on a polygon it would read as facets.
    """
    pts = [(int(p[0]) - origin[0], int(p[1]) - origin[1]) for p in points[:, 0, :]]
    n = len(pts)
    if n < 3:
        return ""
    mid = [((pts[i][0] + pts[(i + 1) % n][0]) // 2, (pts[i][1] + pts[(i + 1) % n][1]) // 2)
           for i in range(n)]
    out = [f"M{mid[0][0]},{mid[0][1]}"]
    for i in range(1, n + 1):
        control, end = pts[i % n], mid[i % n]
        out.append(f"Q{control[0]},{control[1]} {end[0]},{end[1]}")
    out.append("Z")
    return "".join(out)


KOTLIN_HEADER = '''package com.tracktosearch.ui.screen.swiftie

/**
 * 「Congrats on Forever!」的矢量轮廓 —— **生成产物，不要手改**。
 *
 * 由 `scripts/build-swiftie-poster-assets.py` 从 `docs/previews/swiftie-poster/9x16.jpg`
 * 直接描出来：色键提取宝蓝墨层 → 连通域按行分组 → Douglas-Peucker 简化 → 每个角落
 * 平滑成二次曲线。所以它不是「用某个字体重排一遍这句话」，而是原图那几个像素本身，
 * 字距、基线倾角、`!` 的落点都不用猜。
 *
 * 原作者用的是 Filmotype LaCrosse（Font Diner 商业字体，字体文件不可随包分发）。
 * 描成轮廓等于随包一件**设计成品**而不是字体软件 —— 见 ASSET-LICENSES.md。
 *
 * 分成三行三条路径，是因为书写动画要一行写完再写下一行：整句做一次从左到右的揭示，
 * 会让第 1 行和第 3 行同时长出来（它们在 x 上重叠）。
 */
internal object SwiftieCongratsPath {

    /** 路径坐标空间的宽高，等于宝蓝墨层在原图里的包围盒。 */
    const val VIEWPORT_WIDTH: Float = %(vw)df
    const val VIEWPORT_HEIGHT: Float = %(vh)df

    /** 这个包围盒在原图（2160×3840）里的位置，用来把它摆回海报的相对位置。 */
    const val POSTER_LEFT_FRACTION: Float = %(lf).4ff
    const val POSTER_TOP_FRACTION: Float = %(tf).4ff
    const val POSTER_WIDTH_FRACTION: Float = %(wf).4ff
    const val POSTER_HEIGHT_FRACTION: Float = %(hf).4ff

    /** 每行的 x 起止（路径坐标系），书写时间按这个跨度分配。 */
    val LINE_X_RANGES: List<ClosedFloatingPointRange<Float>> = listOf(
%(ranges)s
    )

    /** 三行的路径数据，`PathFillType.EvenOdd` 解释 —— 字腔是嵌套轮廓。 */
    val LINES: List<String> = listOf(
%(paths)s
    )
}
'''


def write_script_kotlin(blue: np.ndarray, out_dir: str) -> None:
    lines = line_masks(blue)
    union = np.zeros_like(lines[0])
    for mask in lines:
        union |= mask
    ys, xs = np.where(union)
    origin = (int(xs.min()), int(ys.min()))
    viewport = (int(xs.max()) - origin[0] + 1, int(ys.max()) - origin[1] + 1)

    paths: list[str] = []
    ranges: list[tuple[int, int]] = []
    total_points = 0
    for index, mask in enumerate(lines):
        contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_CCOMP,
                                       cv2.CHAIN_APPROX_SIMPLE)
        pieces = []
        for contour in contours:
            simplified = cv2.approxPolyDP(contour, SIMPLIFY_EPSILON, True)
            total_points += len(simplified)
            piece = smooth_path(simplified, origin)
            if piece:
                pieces.append(piece)
        paths.append("".join(pieces))
        line_ys, line_xs = np.where(mask)
        ranges.append((int(line_xs.min()) - origin[0], int(line_xs.max()) - origin[0]))
        print(f"  line {index + 1}: {len(contours):2d} contours, "
              f"x {ranges[-1][0]}..{ranges[-1][1]}, {len(paths[-1]):,} chars")

    body = KOTLIN_HEADER % {
        "vw": viewport[0],
        "vh": viewport[1],
        "lf": origin[0] / blue.shape[1],
        "tf": origin[1] / blue.shape[0],
        "wf": viewport[0] / blue.shape[1],
        "hf": viewport[1] / blue.shape[0],
        "ranges": "\n".join(f"        {a}f..{b}f," for a, b in ranges),
        "paths": "\n".join(f'        "{p}",' for p in paths),
    }
    out = os.path.join(out_dir, "SwiftieCongratsPath.kt")
    with open(out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(body)
    print(f"script   viewport {viewport[0]}x{viewport[1]}, {total_points} points, "
          f"{os.path.getsize(out):,} B")


if __name__ == "__main__":
    if not os.path.isdir(SRC):
        sys.exit(f"missing source directory: {SRC}")
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    sky_plate()
    rgb = poster()
    blue_mask, pink_mask = masks(rgb)
    glitter_tile(rgb, pink_mask)
    write_script_kotlin(blue_mask, KOTLIN)
