package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tracktosearch.R

/**
 * 枫叶的**参考图取墨**位图：`swiftie_maple_bent_solid`（实心叶形）与 `swiftie_maple_bent_ink`（墨线）；
 * 杯套刻线只用直柄那套的墨线 `swiftie_maple_ink`（见文末「两套资产」）。
 *
 * 真叶是参考图 `build/egg-shots/ref-red/cup-sleeve-leaf.jpg` 里那片手刻的枫叶。先用程序化
 * 叶形复刻（五瓣 + 圆齿 + 掌状脉），能对上的极限是**左半 IoU 0.70** —— 参考片的叶缘是手刻的、
 * 每一瓣的圆齿数目与深度都不一样，等角等距的式子永远差那一档。2026-09-12 改走取墨：
 * `build/egg-shots/leaf_assets8.py` 把照片里的线直接抠出来当骨架，颜色我们自己填。
 *
 * 取墨的六步，每一步都对应一个能让资产报废的坑（都在脚本里）：
 * 1. 模糊差分抽墨线 → 去碎屑；外部泛洪 + 腐蚀取**实心轮廓**（叶柄那截细，腐蚀半径大了会断）；
 *    墨线只保留「轮廓 ±3px」内的 —— 差分成像会把牛皮纸的颗粒也啃成暗点，散在叶外就是脏点。
 * 2. **两档阈值**：量中轴用 18、出图用 22。刻线的凹槽中间是受光的亮芯，26 会把叶柄啃出断口。
 * 3. 摆正：顶尖 x 与叶柄末端 x 差 22px ⇒ 整片转 2.27°，把叶身摆正。
 * 4. **中轴要沿主脉量准，而且整片按它取直** —— 这一条是四版返工换来的：
 *    照片里主脉**不是直的**（4x 坐标下横向摆动 44px：顶尖 x=345 / 中段 x=328 / 叶柄 x=301），
 *    所以照「顶尖那条竖线」镜像会把主脉复制成两条、叶柄成叉（用户报的「叶柄没有沿主脉延伸」）。
 *    量轴的两个天真做法都栽了：① 对「最厚的墨线」做 DP —— 顶瓣才二十来像素宽，**叶缘那两笔比
 *    主脉还粗**，路径一路跟着右叶缘跑到 x=378（真中脉 338，差 60px）；取直时顶部被整体推平、
 *    镜像后叠成「屋檐 + 透镜」（用户报的「顶部不像参考图」）。② 从顶尖往下走「最近的墨线」——
 *    在叶身里被**侧脉**带走（出溜到 x=290）。正解是**迭代拟合**：先连「顶尖 ↔ 叶柄末端」直线当
 *    骨架，每轮只在骨架 ±8px 内挑最厚的一段墨线当样本（主脉比侧脉粗，侧脉只在交叉处短暂进窗），
 *    窄瓣/叶柄里叶缘与主脉糊成一坨、改取剪影中心；再把样本高斯平滑成新骨架，5 轮就压在主脉上。
 *    最后在这条骨架 ±6px 带里做 DP 贴到墨线的暗芯，逐行取中心、sigma=6 行平滑，整片按它平移取直。
 * 5. 镜像左半（镜面 = 取直后的中轴竖线）。
 * 6. 沿实心轮廓补一条 4px 内带把断口接上 —— 照片那些刻线本来就是断断续续的。
 *
 * 验收用的是数：叶身「离中轴最近那段墨线的中心偏移」中位 0.5px（主脉在轴上）、
 * 中轴处主脉段与叶柄段的墨线中心偏移**各 0.5px**（共线）、叶柄区「每行 >1 段」0 行（没被复制）、
 * 轮廓覆盖率 100%（无断口）。叶身里 ±18px 内还有多段的那些行是**掌状脉**从主脉分出
 * （对称化后成对出现在 ±5..±17px），不是复制。
 * 图像是**白 + alpha**（498×540）：同一张图要出红（背景五片大叶）、橘（飘落秋叶的背面）、
 * 咖啡（杯套刻线）三种颜色，颜色一律在 draw 阶段用 [ColorFilter.tint] + `SrcIn` 现染 ——
 * 存三份着色图不如现染，还能跟着各时代的配色表走。三张 PNG（直柄墨线、弯柄墨线、弯柄实心）
 * 同尺寸同裁剪（内容 bbox 都是 (6,6)-(491,532)），[drawMapleSolid] 与 [drawMapleInk] 都以
 * 「叶柄末端」对齐 ⇒ 填色与墨线天然重合。
 *
 * **两套资产，只差叶柄**（同一套取墨，`build/egg-shots/leaf_bent8.py` 出弯柄那套）：
 * 取直的代价是把参考图那根**往左弯**的叶柄也掰直了。用户 2026-09-12 定：
 * 「页面背景的枫叶叶柄可以弯曲，杯子的叶柄保持现在这样竖直」——
 * 于是背景五片大叶与飘落秋叶用弯柄那套（`swiftie_maple_bent_*`：叶身照常取直、叶柄行只做
 * 刚性平移，形状不动；镜像后把叶柄那条带从**未镜像**的图上贴回来，否则中轴镜像会复制出第二根柄），
 * 杯套刻线只用直柄那套的墨线（`swiftie_maple_ink`）。弯柄那套的叶柄末端不在画幅中线上，
 * 锚点必须按各自的实测比例给（[MapleArt.stemEndFrac]）。
 */
internal class MapleArt(
    val ink: ImageBitmap,
    val solid: ImageBitmap,
    /** 叶柄末端在画幅宽度里的位置（0..1）。直柄那套 = 0.5，弯柄那套 = 0.4699。 */
    val stemEndFrac: Float = 0.5f
)

/**
 * 弯柄那套的叶柄末端比例（实测：叶柄末端 x=234 / 画幅宽 498 = 0.4699；直柄那套是 0.5000）。
 *
 * 背景五片大叶的落位是按「叶柄末端钉在某个点」定的，锚点错了整片叶会横move ——
 * 弯柄比中轴左 15px，在 150px 高的背景叶上就是 4px，五片一起偏能看出来。
 */
internal const val STEM_END_FRAC_BENT = 0.4699f

/**
 * 一片叶的**总高 ÷ 叶身半高**。
 *
 * 换图前程序化叶形的口径是「叶身 1 half + 叶柄 0.30 half」。位图版沿用它，
 * 三处的尺寸就与换图前一样，一处比例都不用重调。[drawMapleSolid] 等的 `height`
 * 一律按 `MAPLE_SPAN * half` 给；真要调大小，调调用点的 half。
 */
internal const val MAPLE_SPAN = 1.30f

/**
 * 叶柄末端相对「局部原点」的下沉量（单位 half）。
 *
 * 程序化那版的原点在叶柄根上方 0.55 half 处（[MAPLE_SPAN] 里那截叶身的一半），
 * 叶柄末端就在原点下方 `0.55 + 0.30 = 0.85` half。位图版的锚点直接钉在**叶柄末端**，
 * 所以背景五片与飘落秋叶这两处要给 `Offset(0f, MAPLE_STEM_END_R * half)`，
 * 落点才与换图前逐像素一致（这两处原来是 `translate(...)` + 原点式叶形）。
 */
internal const val MAPLE_STEM_END_R = 0.85f

/**
 * Red 那一张的枫叶资产（**弯柄**那套：实心 + 墨线两张小 PNG，13.4KB + 5.2KB）。
 *
 * 位置放在两个图层自己的组合里，而不是由 `SwiftieEggScreen` 传下来：这两层拿到的
 * 只有「每帧都在变的时钟派生 lambda」，在组合阶段读它们就会把整层拖成逐帧重组 ——
 * 省一次 ~19KB 的解码，换一个每帧重组，不划算。
 */
@Composable
internal fun rememberMapleBentArt(): MapleArt {
    val ink = ImageBitmap.imageResource(R.drawable.swiftie_maple_bent_ink)
    val solid = ImageBitmap.imageResource(R.drawable.swiftie_maple_bent_solid)
    return remember(ink, solid) { MapleArt(ink, solid, STEM_END_FRAC_BENT) }
}

/**
 * 只取墨线的那一处（杯套上的刻线徽记，见 `SwiftieEraMotifs.drawMapleLatte`）：
 * 压印只有线、不填色，没必要连实心图一起解码进来。用的是**直柄**那张
 * （`swiftie_maple_ink`）—— 杯套上的叶柄要竖直，背景那套才是弯的。
 */
@Composable
internal fun rememberMapleInkArt(): ImageBitmap = ImageBitmap.imageResource(R.drawable.swiftie_maple_ink)

/**
 * 实心叶形（填色层）：背景那五片红枫叶、L2 层飘落的秋叶用它。
 *
 * `blade` 为 null = 这一帧不画（位图还没解码完，或别的时代根本没解码）——
 * **不要**在这时回落到程序化叶形：同一屏上出现两种枫叶比少画一片更糟。
 */
internal fun DrawScope.drawMapleSolid(
    blade: ImageBitmap?,
    height: Float,
    stemEnd: Offset,
    color: Color,
    alpha: Float,
    stemEndFrac: Float = 0.5f
) {
    if (blade != null) drawMapleImage(blade, height, stemEnd, color, alpha, stemEndFrac)
}

/** 墨线层：叶缘 + 掌状脉 + 叶柄都在这张图里（与实心图同尺寸，天然对齐）。 */
internal fun DrawScope.drawMapleInk(
    line: ImageBitmap?,
    height: Float,
    stemEnd: Offset,
    color: Color,
    alpha: Float,
    stemEndFrac: Float = 0.5f
) {
    if (line != null) drawMapleImage(line, height, stemEnd, color, alpha, stemEndFrac)
}

/**
 * 把一片叶画进当前坐标系：**叶柄末端**钉在 [stemEnd]，总高 [height]。
 *
 * 锚点不是「底边中点」而是「叶柄末端」：[stemEndFrac] 说叶柄末端落在画幅宽度的哪个位置
 * （直柄 0.5、弯柄 0.4699）。两者混用会让背景五片整体平移几个像素，而它们的位置是逐像素对过的。
 *
 * 缩放走 canvas 变换而不是 `dstSize`（那条路只吃整数）：飘落那十几片一直在漂移，
 * 目标框取整会让它们一格一格地跳。变换是「先平移到左上角、再按 s = 目标/原图 缩放」，
 * 坐标序不能反（先缩放再平移，位移会被 s 缩掉）。
 *
 * [FilterQuality.Medium]（双线性 + mipmap）：叶在屏上要从 539px 缩到 50–200px，
 * 只按双线性抽点，细到 1.8px 的刻线会随帧间采样点变而闪；
 * 也不给 High（双三次），一帧几十片叶，代价不值那一点锐度。
 */
private fun DrawScope.drawMapleImage(
    image: ImageBitmap,
    height: Float,
    stemEnd: Offset,
    color: Color,
    alpha: Float,
    stemEndFrac: Float
) {
    if (height < 1f || alpha <= 0.004f) return
    val w = height * image.width / image.height
    withTransform({
        translate(stemEnd.x - w * stemEndFrac, stemEnd.y - height)
        scale(w / image.width, height / image.height, Offset.Zero)
    }) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(image.width, image.height),
            alpha = alpha.coerceIn(0f, 1f),
            colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
            filterQuality = FilterQuality.Medium
        )
    }
}
