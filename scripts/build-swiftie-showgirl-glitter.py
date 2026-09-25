#!/usr/bin/env python3
"""Regenerate the Showgirl title glitter plate from the official album cover.

Run from the repository root:

    python scripts/build-swiftie-showgirl-glitter.py

Input (gitignored, same convention as docs/previews/swiftie-poster/):

  docs/previews/swiftie-showgirl/showgirl-cover.jpg
      3778x3778  the untouched "The Life of a Showgirl" cover. The title lettering on it
      is real orange-red glitter; that grain is the only thing we take.

Output:

  app/src/main/res/drawable-nodpi/swiftie_showgirl_glitter.webp

How it works: slide a window over the cover and keep the position holding the most
glitter, then repaint every non-glitter pixel inside that window by resampling a glitter
pixel from the same window. Resampling rather than cv2.inpaint because inpaint smears a
blurry streak straight through the sparkle, while a borrowed grain is indistinguishable
from the ones around it.

The plate is 512 source pixels of real, non-repeating grain, so the Kotlin side can
mirror-tile it without the wallpaper effect that sank the 42px plate on the poster
equation (see SwiftieGlitter.rememberGlitterBrush's note).

Requires: pillow, numpy, opencv-python.
"""

from __future__ import annotations

import os

import cv2
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "previews", "swiftie-showgirl", "showgirl-cover.jpg")
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
OUT = os.path.join(RES, "swiftie_showgirl_glitter.webp")
PREVIEW = os.path.join(ROOT, "build", "glitter-probe", "plate-preview.png")

PLATE_SIDE = 320        # shipped plate size, in source pixels -- native grain, no rescale
STEP = 64               # window slide pitch
MIN_SOLID = 0.72        # share of solid-glitter pixels a window must reach to ship
GRAIN_WINDOW = 15       # neighbourhood the local std is measured over
GRAIN_STD_MIN = 18.0    # local std of luminance: grain, not a smooth sheen (skin 12, glitter 30)

# Glitter is saturated red-orange plus its own near-white sparkle grains. Skin is also
# saturated orange but carries no sparkle, and the teal backdrop / olive beadwork are
# neither -- so "glitter" = orange OR sparkle, and everything else gets repainted.
HUE_LO, HUE_HI = 21, 165
ORANGE_SAT_MIN = 90
SPARK_V, SPARK_S = 215, 90


def sparkle_mask(rgb: np.ndarray) -> np.ndarray:
    """The near-white grains."""
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    _, s, v = (ch.astype(np.int32) for ch in cv2.split(hsv))
    return (v > SPARK_V) & (s < SPARK_S)


def glitter_mask(rgb: np.ndarray) -> np.ndarray:
    """Orange grains plus their sparkles. Colours alone: skin passes this too."""
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    h, s, v = (ch.astype(np.int32) for ch in cv2.split(hsv))
    orange = ((h <= HUE_LO) | (h >= HUE_HI)) & (s > ORANGE_SAT_MIN)
    return orange | ((v > SPARK_V) & (s < SPARK_S))


def solid_mask(rgb: np.ndarray) -> np.ndarray:
    """Glitter that is also *grainy*.

    This is the gate that actually works. Her skin measures 98% glitter by colour and 8%
    by this test; a dense stroke measures 68% and 67%. Glitter is high-frequency, skin and
    the water sheen are not, so the local standard deviation of luminance separates them
    where no hue threshold can.
    """
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    mean = cv2.boxFilter(gray, -1, (GRAIN_WINDOW, GRAIN_WINDOW))
    sq = cv2.boxFilter(gray * gray, -1, (GRAIN_WINDOW, GRAIN_WINDOW))
    std = np.sqrt(np.maximum(sq - mean * mean, 0.0))
    return glitter_mask(rgb) & (std > GRAIN_STD_MIN)


def best_window(solid: np.ndarray) -> tuple[int, int, float]:
    """Window holding the highest share of solid glitter, and that share."""
    integral = cv2.integral(solid.astype(np.uint8))
    height, width = solid.shape
    side = PLATE_SIDE
    cells = float(side * side)
    best, at = -1.0, (0, 0, 0.0)
    for y in range(0, height - side + 1, STEP):
        for x in range(0, width - side + 1, STEP):
            frac = float(integral[y + side, x + side] - integral[y, x + side]
                         - integral[y + side, x] + integral[y, x]) / cells
            if frac > best:
                best, at = frac, (x, y, frac)
    return at


def main() -> None:
    src = Image.open(SRC)
    if src.mode != "RGB":
        src = src.convert("RGB")
    rgb = np.array(src)
    mask = glitter_mask(rgb)
    print(f"source {src.size[0]}x{src.size[1]}  glitter {100.0 * mask.mean():.1f}%")
    x, y, frac = best_window(solid_mask(rgb))
    print(f"window at ({x},{y}) is {100.0 * frac:.1f}% solid glitter")
    if frac < MIN_SOLID:
        raise SystemExit("no window is solid enough -- raise PLATE_SIDE tolerance or check masks")
    hole = ~mask[y:y + PLATE_SIDE, x:x + PLATE_SIDE]
    # One pixel of dilation so the halo each grain carries in the JPEG leaves no rim
    hole = cv2.dilate(hole.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    crop = rgb[y:y + PLATE_SIDE, x:x + PLATE_SIDE].copy()
    rng = np.random.default_rng(13)  # 13 = Taylor's number, and the seed stays fixed
    keep = np.nonzero(~hole.ravel())[0]
    flat = crop.reshape(-1, 3)
    flat[hole.ravel()] = flat[rng.choice(keep, size=int(hole.sum()), replace=True)]
    os.makedirs(RES, exist_ok=True)
    Image.fromarray(crop).save(OUT, "WEBP", quality=92, method=6)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    Image.fromarray(crop).save(PREVIEW)
    print(f"repainted {100.0 * hole.mean():.1f}% of the window; wrote "
          f"{os.path.relpath(OUT, ROOT)} and {os.path.relpath(PREVIEW, ROOT)}")


if __name__ == "__main__":
    main()
