#!/usr/bin/env python3
"""Rebuild app/src/main/res/font/ark_pixel_12px.ttf from an upstream Ark Pixel release.

Why this script exists: the first subset was cut by hand for one placeholder string
("输入 12 位激活码"). The pixel font later became the typeface for the whole ticket
machine -- display, keypad, nameplate, submit key -- and every character added since
then silently fell back to the system font with a mismatched advance width, which
shows up as overlapping glyphs. Subsetting has to be reproducible, so it lives here.

The character set is derived from the string resources, not hand-listed: every
`<string>` in every values*/strings.xml whose name matches one of KEY_PATTERNS is a
string the machine can render in the pixel font. Add a new machine_* string and the
next run picks it up.

Usage:
    python3 scripts/subset-ark-pixel.py --zip /path/to/ark-pixel-font-12px-monospaced-ttf-vYYYY.MM.DD.zip

Requires fontTools (pip install fonttools).

Known gap: Ark Pixel 12px ships no Hangul glyphs, so Korean strings cannot be
covered. The script reports them and moves on -- Korean falls back to the system
font on the pixel display. That is a documented limitation, not a build failure.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "app/src/main/res/font/ark_pixel_12px.ttf"

# The variant the app uses. zh_cn picks the mainland glyph forms; monospaced keeps
# every advance width on the 12px grid, which is what pixelFontSize() relies on.
#
# Two accepted containers. The plain `ttf` release zip is 35 MB and kept timing out on
# a slow link; `ttf.woff2` is the same sfnt tables under brotli at 5 MB, and fontTools
# reads it directly, so subsetting either produces byte-identical outlines. Prefer
# whichever you already have.
UPSTREAM_MEMBERS = (
    "ark-pixel-12px-monospaced-zh_cn.ttf",
    "ark-pixel-12px-monospaced-zh_cn.ttf.woff2",
)

# String families the ticket machine renders in PixelFontFamily.
#   machine_*                    display status, keypad labels, submit key, code cells
#   auth_error_*                 display line 2 when activation fails
#   auth_migration_invite_hint   display line 2 during invite migration
#   login_activation_locked      display line 2 in the idle state
#   login_personal_cinema_access nameplate
KEY_PATTERNS = [
    re.compile(r"^machine_"),
    re.compile(r"^auth_error_"),
    re.compile(r"^auth_migration_invite_hint$"),
    re.compile(r"^login_activation_locked$"),
]

# Kept regardless of what the strings happen to use today: the six code cells and the
# keypad are pure ASCII, and dropping a punctuation mark because no current string
# uses it would make the next copy edit a font rebuild.
ALWAYS = set(range(0x20, 0x7F)) | {
    0x00B7,  # ·  middle dot, used by the nameplate separator
    0x2022,  # •
    0x2018, 0x2019, 0x201C, 0x201D,  # curly quotes
    0x2026,  # …
    0x2014, 0x2013,  # em/en dash
    0x3000,  # ideographic space
    0x3001, 0x3002,  # 、。
    0xFF01, 0xFF08, 0xFF09, 0xFF0C, 0xFF1A, 0xFF1B, 0xFF1F,  # ！（）， ：；？
    0x25CB,  # ○ the "not collected yet" bullet
}


def required_codepoints() -> dict[int, set[str]]:
    """Every code point the machine can render, mapped to the string keys that use it."""
    used: dict[int, set[str]] = {cp: {"<always>"} for cp in ALWAYS}
    for path in sorted(glob.glob(str(REPO / "app/src/main/res/values*/strings.xml"))):
        root = ET.parse(path).getroot()
        for el in root.iter("string"):
            name = el.get("name") or ""
            if not any(p.match(name) for p in KEY_PATTERNS):
                continue
            for ch in "".join(el.itertext()):
                if ch in "\n\t":
                    continue
                used.setdefault(ord(ch), set()).add(name)
    return used


def font_coverage(path: Path) -> set[int]:
    from fontTools.ttLib import TTFont

    with TTFont(str(path)) as font:
        covered: set[int] = set()
        for table in font["cmap"].tables:
            covered |= set(table.cmap.keys())
        return covered


def describe(cp: int) -> str:
    try:
        name = unicodedata.name(chr(cp))
    except ValueError:
        name = "?"
    return f"U+{cp:04X} {name}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, help="upstream 12px monospaced ttf release zip")
    parser.add_argument("--sha256", help="expected sha256 of the zip; verified when given")
    args = parser.parse_args()

    archive = Path(args.zip)
    if not archive.is_file():
        print(f"no such zip: {archive}", file=sys.stderr)
        return 1

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    print(f"zip sha256 {digest}")
    if args.sha256 and digest.lower() != args.sha256.lower():
        print(f"sha256 mismatch, expected {args.sha256}", file=sys.stderr)
        return 1

    used = required_codepoints()
    print(f"required code points: {len(used)}")

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        with zipfile.ZipFile(archive) as zf:
            member = next(
                (n for n in zf.namelist() if any(n.endswith(m) for m in UPSTREAM_MEMBERS)),
                None,
            )
            if member is None:
                print(
                    f"none of {UPSTREAM_MEMBERS} in {archive.name}",
                    file=sys.stderr,
                )
                return 1
            full = tmpdir / Path(member).name
            with zf.open(member) as src, full.open("wb") as dst:
                shutil.copyfileobj(src, dst)
        print(f"extracted {member} ({full.stat().st_size // 1024} KB)")

        upstream = font_coverage(full)
        unavailable = sorted(cp for cp in used if cp not in upstream)
        if unavailable:
            print(f"\nnot in upstream ({len(unavailable)}), will fall back to the system font:")
            for cp in unavailable[:12]:
                print(f"  {describe(cp)} <- {','.join(sorted(used[cp])[:2])}")
            if len(unavailable) > 12:
                print(f"  ... and {len(unavailable) - 12} more")

        wanted = sorted(cp for cp in used if cp in upstream)
        subset = tmpdir / "subset.ttf"
        unicodes = ",".join(f"U+{cp:04X}" for cp in wanted)
        subprocess.run(
            [
                sys.executable, "-m", "fontTools.subset", str(full),
                f"--unicodes={unicodes}",
                f"--output-file={subset}",
                # The pixel look depends on hinting-free integer scaling; layout
                # features would only add tables the app never asks for.
                "--layout-features=",
                "--no-hinting",
                "--desubroutinize",
                "--name-IDs=*",
                "--drop-tables+=DSIG",
            ],
            check=True,
        )

        OUT.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(subset, OUT)

    result = font_coverage(OUT)
    still_missing = sorted(cp for cp in wanted if cp not in result)
    print(f"\nwrote {OUT.relative_to(REPO)} ({OUT.stat().st_size // 1024} KB)")
    print(f"glyphs covered: {len(result)} of {len(wanted)} requested")
    if still_missing:
        print("subset dropped code points it should have kept:", file=sys.stderr)
        for cp in still_missing[:12]:
            print(f"  {describe(cp)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
