# -*- coding: utf-8 -*-
"""羽毛笔第三步：把 quill-final.txt（笔的路径与笔杆）+ quill-vane.txt（羽面轮廓）生成 Kotlin 对象文件。

三份数据各司其职：线稿、笔杆、羽面填色 —— 手抄几百个数没有意义，一律生成。
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "build" / "qa" / "quill-vec"
SRC = BASE / "quill-final.txt"
VANE = BASE / "quill-vane.txt"
DST = (ROOT / "app/src/main/java/com/tracktosearch/ui/screen/swiftie/eras"
       / "SwiftieLetterQuill.kt")

txt = open(SRC, encoding="utf-8").read()
m = re.search(r"nibD=\(([\d.]+), ([\d.]+)\)\s+L=([\d.]+)\s+axis=(-?[\d.]+)", txt)
nx, ny, L, axis = m.group(1), m.group(2), m.group(3), m.group(4)
ds = re.findall(r'"([^"]+)"', txt)
shaft_nums = [v.strip() for v in
              re.search(r"floatArrayOf\(\s*(.+?)\s*\)", txt, re.S).group(1)
              .replace("\n", " ").split(",") if v.strip()]
vane_txt = open(VANE, encoding="utf-8").read()
vane_nums = [v.strip() for v in
             re.search(r"floatArrayOf\(\s*(.+?)\s*\)", vane_txt, re.S).group(1)
             .replace("\n", " ").split(",") if v.strip()]


def rows(nums, per_line=8, indent="        "):
    return "\n".join(indent + ", ".join(nums[i:i + per_line]) + ","
                     for i in range(0, len(nums), per_line))


body = f'''package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 卡片右下角那支羽毛笔的字形。
 *
 * **数据来源**：[Wikimedia 转出的 svgsilh 1299326](https://svgsilh.com/image/1299326.html)，
 * CC0（Pixabay 原图，potrace 描摹成路径）。所以下面每一条 `d` 都是**源文件里的原话**，
 * 一个数都没改 —— 照着参考图重画这件事，直接用现成矢量比手描贝塞尔准得多。
 *
 * 源图是一只插在墨水瓶里的羽毛笔（13 条路径）。这里只留笔：
 * - 丢掉墨水瓶身与瓶嘴标签两条
 * - 源文件把「羽轴 + 瓶口椭圆」描成**同一条闭合轮廓**（笔杆穿过椭圆），按 display
 *   y = 930 横切一刀把瓶口椭圆那段切掉之后，剩的**两段都要**：一段含羽轴顶端
 *   （一路到 disp y≈285，正好是参考图第一个缺口的高度），一段是它的平行短边 ——
 *   只留最长那段会把羽轴上半截丢掉，笔杆就断在羽毛当中。两条边在顶端收成尖，
 *   下端连到源图 #02 自己的最低点（参考图里笔尖停进瓶里的位置）-> [shaft]
 * - 源图把羽尖裁在画布左上角（轮廓绕角走了一段直角帽边），那点裁切只占几个单位 ——
 *   缩到卡片上不到一个像素，不值得做外科手术
 *
 * 三份数据：
 * - [lines]：笔迹的 10 条填充轮廓。源图是**线画**，每条是一条七点几单位宽的细带
 * - [shaft]：笔杆那一段（源图 #02 横切口以上的部分）
 * - [vane]：**羽面的填色块**。源图里羽面就是留白（空心线画），这块多边形是离线算出来的
 *   （`scripts/build-swiftie-quill-vane.py`）：抬「到笔画距离」的阈值找到羽面本体，再长回笔画上，
 *   沿轮廓把接的桥剪掉（不剪会顺着线稿的断口鼓出一排圆脚），最后补上笔杆下段那几根
 *   散羽围成的一扇 —— 参考图里羽面的下缘本来就是那几根羽枝的锯齿。
 *   不能用「填洞」—— 线稿处处留着 15~70 单位的缝（速写本来的样子），而且源图在画布
 *   左上角被裁，羽面那一头本来就贴着画布边，填洞永远围不住它
 *
 * 坐标系是源文件自己的：`viewBox 0 0 896 1280`，y 向下。
 * 摆放时按源文件那条 group 变换
 * `translate(0,1280) scale(0.1,-0.1)`（[SVG_SCALE] / [SVG_SHIFT]）先还原，
 * 再镜像 + 旋转 + 缩放到卡片上（见 `drawQuill`）。
 *
 * 为什么线稿打散成这么多条而不是一条：源图是**描摹**出来的，每条路径是一段独立笔迹的
 * 填充轮廓，合并反而要重算环绕方向。它们同色同 alpha，分开画与合并画在屏幕上没有差别。
 */
internal object SwiftieLetterQuill {{

    /** SVG 里 group 那层 `scale(0.1,-0.1)`。 */
    const val SVG_SCALE = 0.1f

    /** SVG 里 group 那层 `translate(0,1280)`。 */
    const val SVG_SHIFT = 1280f

    /** 笔尖在源文件 display 坐标里的位置（笔杆断面中点）。摆放时它落到写字的位置上。 */
    val NIB = Offset({nx}f, {ny}f)

    /** 全笔长（源文件 display 单位），用来把笔缩放到目标长度。 */
    const val SOURCE_LENGTH = {L}f

    /** 源图里笔轴相对竖直的倾角（负 = 向左倾）。镜像后要减掉它再转到目标倾角。 */
    const val SOURCE_AXIS_DEG = {axis}f

    /**
     * 笔迹。顺序无所谓 —— 都是同色的填充轮廓，互不覆盖（源图里本就不相交）。
     */
    private val PATH_DATA = listOf(
{chr(10).join(f'        "{d}",' for d in ds)}
    )

    /**
     * 笔杆：源图 #02 横切出的一段（点表，源坐标）。它原来是闭合轮廓，切完首尾用直线封口。
     */
    private val SHAFT_POINTS = floatArrayOf(
{rows(shaft_nums)}
    )

    /**
     * 羽面的填色轮廓（点表，源坐标）。
     *
     * 它贴着笔画的**内沿**走，压在线稿下面正好不留白边；线稿的断口处色块自己拐个弯收住
     * —— 那些地方本来就是没画轮廓的口子。
     */
    private val VANE_POINTS = floatArrayOf(
{rows(vane_nums)}
    )

    /** 解析一次的路径表。12 张卡片共用同一份几何 —— 每次绘制都 parse 一遍纯属浪费。 */
    val lines: List<Path> by lazy {{ PATH_DATA.map {{ PathParser().parsePathString(it).toPath() }} }}

    /** 笔杆。 */
    val shaft: Path by lazy {{ closedPath(SHAFT_POINTS) }}

    /** 羽面填色块。 */
    val vane: Path by lazy {{ closedPath(VANE_POINTS) }}

    private fun closedPath(points: FloatArray): Path = Path().apply {{
        moveTo(points[0], points[1])
        var index = 2
        while (index + 1 < points.size) {{
            lineTo(points[index], points[index + 1])
            index += 2
        }}
        close()
    }}
}}
'''
with open(DST, "w", encoding="utf-8", newline="") as f:
    f.write(body)
print(f"-> {DST}  ({len(body)} 字符；线稿 {len(ds)} 条，笔杆 {len(shaft_nums) // 2} 点，"
      f"羽面 {len(vane_nums) // 2} 点)")
