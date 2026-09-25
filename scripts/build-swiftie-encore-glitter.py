#!/usr/bin/env python3
"""Regenerate the Encore title's gold glitter plate from the shipped orange one.

Run from the repository root:

    python scripts/build-swiftie-encore-glitter.py

Input:

  app/src/main/res/drawable-nodpi/swiftie_showgirl_glitter.webp
      320x320 of real grain, cut from the 3778x3778 "The Life of a Showgirl"
      cover by build-swiftie-showgirl-glitter.py. That plate is orange-red.

Output:

  app/src/main/res/drawable-nodpi/swiftie_encore_glitter.webp
      The same grain, hue-rotated to gold.

Why rotate instead of cutting a second plate from the Encore cover: the only
Encore cover available is a 1500x1500 JPEG. A 320px plate cut from it would be
upsampled from roughly a quarter of its own resolution, which smears the 1-3px
sparkle grains into flat powder -- the exact failure remembered in
SwiftieGlitter.rememberGlitterBrush's note about the 42px poster plate. Rotating
the existing 320px plate keeps every grain at native resolution.

Why hue rotation is the right transform: glitter reads as glitter because of its
*value* structure (the grain), not its hue. Rotating hue in HSV leaves S and V
untouched, so the grain survives byte-for-byte and only the colour moves. The
near-white sparkle grains sit at low saturation, so they barely move at all --
which is what we want, they are the highlights.

Requires: pillow, numpy.
"""

from __future__ import annotations

import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
SRC = os.path.join(RES, "swiftie_showgirl_glitter.webp")
OUT = os.path.join(RES, "swiftie_encore_glitter.webp")
PREVIEW = os.path.join(ROOT, "build", "glitter-probe", "encore-plate-preview.png")

# The Encore lettering on the reference cover is gold. Measured from that cover:
# the saturated gold pixels average around hue 42 deg in HSV, which is a clean
# yellow-gold rather than a muddy ochre (the difference matters: 48 deg starts
# reading as brass, 36 deg as amber).
TARGET_HUE_DEG = 42.0

# Pixels below this saturation carry no meaningful hue (the white sparkles), so
# they are excluded when working out the plate's current dominant hue.
HUE_SAMPLE_SAT_MIN = 100


def dominant_hue(hsv: np.ndarray) -> float:
    """Median hue, in degrees, over the saturated pixels only."""
    h, s = hsv[..., 0].astype(np.float32), hsv[..., 1]
    mask = s > HUE_SAMPLE_SAT_MIN
    if not mask.any():
        raise SystemExit("plate has no saturated pixels -- wrong input?")
    return float(np.median(h[mask])) * 360.0 / 255.0


def rotate_hue(rgb: np.ndarray, target_deg: float) -> tuple[np.ndarray, float]:
    """Rotate every pixel to `target_deg`, keeping S and V exactly as they are."""
    hsv = np.array(Image.fromarray(rgb).convert("HSV"), dtype=np.uint8)
    current = dominant_hue(hsv)
    delta = (target_deg - current) * 255.0 / 360.0
    shifted = (hsv[..., 0].astype(np.float32) + delta) % 256.0
    out = np.stack([shifted.astype(np.uint8), hsv[..., 1], hsv[..., 2]], axis=-1)
    return np.array(Image.fromarray(out, "HSV").convert("RGB"), dtype=np.uint8), current


def main() -> None:
    if not os.path.exists(SRC):
        raise SystemExit(f"missing source plate: {os.path.relpath(SRC, ROOT)}")
    rgb = np.array(Image.open(SRC).convert("RGB"))
    gold, current = rotate_hue(rgb, TARGET_HUE_DEG)
    print(f"source plate {rgb.shape[1]}x{rgb.shape[0]}, dominant hue {current:.1f} deg "
          f"-> rotated to {TARGET_HUE_DEG:.1f} deg")

    # The grain must be untouched: report the value channel's spread before and
    # after, and the mean absolute difference of V. A non-zero value here would
    # mean the transform touched the grain, which is the whole thing we rely on.
    v_before = np.array(Image.fromarray(rgb).convert("HSV"))[..., 2].astype(np.int16)
    v_after = np.array(Image.fromarray(gold).convert("HSV"))[..., 2].astype(np.int16)
    print(f"  value channel drift: max {np.abs(v_before - v_after).max()} "
          f"(0 means the grain is bit-identical)")
    sat_mask = np.array(Image.fromarray(gold).convert("HSV"))[..., 1] > 100
    if sat_mask.any():
        med = np.median(gold.reshape(-1, 3)[sat_mask.reshape(-1)], axis=0)
        print(f"  median saturated colour: #{int(med[0]):02X}{int(med[1]):02X}{int(med[2]):02X}")

    Image.fromarray(gold).save(OUT, "WEBP", quality=92, method=6)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    Image.fromarray(gold).save(PREVIEW)
    print(f"wrote {os.path.relpath(OUT, ROOT)} and {os.path.relpath(PREVIEW, ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
