#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""解析 dumpsys batterystats 快照，输出本应用 UID 的估算耗电明细与 TSV 汇总。
用法: parse_batt.py <dump文件> <标签> <UID> <窗口秒> <输出目录>
"""
import re
import sys
import os

dump, label, uid, dur, out = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
text = open(dump, encoding="utf-8", errors="replace").read()


def num(pat, default=0.0):
    m = re.search(pat, text, re.M)
    return float(m.group(1)) if m else default


global_screen = num(r"^\s*screen:\s*([\d.]+)")
global_cpu = num(r"^\s*cpu:\s*([\d.]+)")
global_wifi = num(r"^\s*wifi:\s*([\d.]+)")
global_radio = num(r"^\s*mobile_radio:\s*([\d.]+)")

uid_total = 0.0
detail = ""
m = re.search(r"^\s*UID %s:\s*([\d.]+).*$\n(\s{4,}.*)$" % re.escape(uid), text, re.M)
if m:
    uid_total = float(m.group(1))
    detail = m.group(2).strip()


def comp(name):
    mm = re.search(r"(?<![\w:])%s=([\d.]+)" % re.escape(name), detail)
    return float(mm.group(1)) if mm else 0.0


c_cpu, c_screen = comp("cpu"), comp("screen")
c_wifi, c_radio, c_gpu = comp("wifi"), comp("mobile_radio"), comp("gpu")
mm = re.search(r"(?<![\w:])cpu=[\d.]+\s+\(([^)]+)\)", detail)
cpu_time = mm.group(1) if mm else "-"


def ms(s):
    tot = 0.0
    for v, u in re.findall(r"(\d+(?:\.\d+)?)(ms|h|m|s)", s):
        tot += float(v) * {"h": 3600000, "m": 60000, "s": 1000, "ms": 1}[u]
    return tot


cpu_ms = ms(cpu_time)
app_no_screen = uid_total - c_screen
per_hour = app_no_screen / dur * 3600 if dur else 0.0
screen_per_hour = c_screen / dur * 3600 if dur else 0.0

det = os.path.join(out, "battdet_%s.txt" % label)
with open(det, "w", encoding="utf-8") as f:
    f.write("场景: %s\n窗口: %.0f s\n" % (label, dur))
    f.write("整机估算: 屏幕 %.3f, CPU %.3f, wifi %.3f, radio %.3f mAh\n"
            % (global_screen, global_cpu, global_wifi, global_radio))
    f.write("本应用 %s 合计 %.3f mAh（其中屏幕分摊 %.3f）\n" % (uid, uid_total, c_screen))
    f.write("本应用扣屏幕 %.3f mAh -> %.1f mAh/小时（屏幕本身 %.1f mAh/小时）\n"
            % (app_no_screen, per_hour, screen_per_hour))
    f.write("  cpu=%.4f mAh (%s = %.0f ms)  wifi=%.4f  radio=%.4f  gpu=%.4f\n"
            % (c_cpu, cpu_time, cpu_ms, c_wifi, c_radio, c_gpu))
    f.write("明细: %s\n" % detail)

row = "%s\t%.0f\t%.3f\t%.3f\t%.1f\t%.0f\t%.3f\t%.4f\t%.4f\t%.3f\n" % (
    label, dur, uid_total, app_no_screen, per_hour, cpu_ms, c_screen, c_wifi, c_radio, global_cpu)
with open(os.path.join(out, "batt_summary.tsv"), "a", encoding="utf-8") as f:
    f.write(row)

line = ("[%s] app-noscreen %.3f mAh / %.0fs = %.1f mAh/h | cpu %.4f mAh (%.0f ms) "
        "| wifi %.4f | screen %.1f mAh/h | device-cpu %.3f mAh"
        % (label, app_no_screen, dur, per_hour, c_cpu, cpu_ms, c_wifi, screen_per_hour, global_cpu))
sys.stdout.write(line + "\n")
