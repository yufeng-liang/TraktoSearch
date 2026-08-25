#!/usr/bin/env python
# 从 uiautomator dump 里按 text / content-desc 精确定位节点，输出中心点坐标。
# 用法: python tools/perf/uinode.py <关键字> [--all]
import re, subprocess, sys

SERIAL = "eeb30d23"
key = sys.argv[1]
show_all = "--all" in sys.argv

xml = subprocess.run(
    ["adb", "-s", SERIAL, "exec-out", "uiautomator", "dump", "/dev/tty"],
    capture_output=True).stdout.decode("utf-8", "replace")

hits = []
for m in re.finditer(r"<node\b[^>]*>", xml):
    tag = m.group(0)
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if key not in label:
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    clickable = re.search(r'clickable="true"', tag) is not None
    hits.append((label, (x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2, clickable))

if not hits:
    print("NOT_FOUND")
    sys.exit(1)
for h in (hits if show_all else hits[:1]):
    print("%s\t%d %d\tbounds=[%d,%d][%d,%d]\tclickable=%s" % (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]))
