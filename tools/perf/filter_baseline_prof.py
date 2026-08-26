#!/usr/bin/env python3
"""把真机 ART 采样档案过滤成可直接落库的 baseline-prof.txt。

用法:
    python tools/perf/filter_baseline_prof.py <拉取的 prof.txt> <输出路径>

采集步骤（先在真机上把要覆盖的页面/滚动路径都跑一遍）:
    adb shell cmd package dump-profiles --dump-classes-and-methods com.tracktosearch
    adb pull /data/misc/profman/com.tracktosearch-primary.prof.txt <本地文件>
"""
import re
import sys

# 这些库的 AAR 里已自带 baseline profile（逐个 unzip 核实过），AGP 会自动合并。
# 重复写进 app 的档案只会拖长安装期 dexopt，不会带来额外收益。
SHIPS_OWN_PROFILE = (
    "Landroidx/compose/",
    "Landroidx/lifecycle/",
    "Landroidx/navigation/",
    "Landroidx/startup/",
    "Landroidx/activity/",
    "Ldev/chrisbanes/",
)

# framework 类由系统镜像 AOT 编译，写进应用档案不生效。
FRAMEWORK = (
    "Ljava/", "Ljavax/", "Lsun/", "Ldalvik/", "Llibcore/",
    "Landroid/", "Lorg/xml", "Lorg/json", "Lorg/apache/http/",
)

# 类描述符：Kotlin 会生成含 '-' 的名字（okio/-SegmentedByteString、measure-3p2s80s），
# 必须放进字符集，否则这些热点会被静默丢掉。
DESCRIPTOR = re.compile(r"L[A-Za-z0-9_$/\-]+;")

HEADER = """# Baseline profile —— 由真机 ART 采样档案生成，不要手写。
#
# 重新生成：真机上把要覆盖的页面与滚动路径跑一遍，然后
#   adb shell cmd package dump-profiles --dump-classes-and-methods com.tracktosearch
#   adb pull /data/misc/profman/com.tracktosearch-primary.prof.txt <本地文件>
#   python tools/perf/filter_baseline_prof.py <本地文件> app/src/main/baseline-prof.txt
#
# 过滤规则见 tools/perf/filter_baseline_prof.py 顶部常量：丢弃 framework 类与
# AAR 自带 profile 的库，保留应用自身及不自带 profile 的三方库。
#
# 本文件是未混淆签名；release 走 R8 时 AGP 用 mapping 自动改写，勿手动混淆。
"""


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]

    kept: list[str] = []
    dropped_lib = dropped_framework = unparsed = 0
    with open(src, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            # profman 的 dump 头部（=== Dex files ===、apk 路径行）和空行直接跳过
            if not line or line.startswith(("#", "===", "/data/", "/system/")):
                continue
            match = DESCRIPTOR.search(line)
            if match is None:
                unparsed += 1
                continue
            descriptor = match.group(0)
            if descriptor.startswith(FRAMEWORK):
                dropped_framework += 1
                continue
            if descriptor.startswith(SHIPS_OWN_PROFILE):
                dropped_lib += 1
                continue
            kept.append(line)

    rules = sorted(set(kept))
    with open(dst, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(HEADER)
        handle.write("\n".join(rules))
        handle.write("\n")

    print(
        "kept=%d  dropped_lib=%d  dropped_framework=%d  unparsed=%d"
        % (len(rules), dropped_lib, dropped_framework, unparsed)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
