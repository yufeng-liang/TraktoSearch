package com.tracktosearch.ui.screen.splash

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.util.Random

/**
 * 印泥不匀：盖在印面上把墨吃掉一部分的噪点瓦片。
 *
 * 真印章按下去从来不是一块匀色——印泥厚薄不均，边角先干，笔画上会缺一点。这层是整枚印
 * 「像盖上去的」而不是「画上去的」唯一来源，去掉它印面就是一个干净的圆角方框加两个字。
 *
 * 做法是拿这张瓦片当遮罩，按噪点在印面上盖一层纸色（见 QuoteSeal 的 mottle）。
 * 不用 [androidx.compose.ui.graphics.BlendMode.DstOut] 抠洞是有原因的：日签卡片要在软件
 * Canvas 上重放一遍导出成 PNG，抠洞依赖离屏图层，多一层图层就多一处可能在导出路径上
 * 表现不一致的地方；盖纸色只是普通的 drawImage，两条路径的结果一定一样。代价是印章
 * 必须知道自己压在什么颜色上，所以有 ground 参数。
 *
 * 两层噪点各管一件事：
 * - 咬痕（[BITE_CELLS] 格）是大块的，管边框缺口和笔画断口，[BITE_BIAS] 抬得很高，
 *   只让噪点的顶部一小片透出来——咬痕多了就不像旧印章，像脏。
 * - 麻点（[SPECKLE_CELLS] 格）是细的，铺满整个印面，透明度封顶 [SPECKLE_MAX]，
 *   只做「印泥有颗粒」的暗示，不咬穿笔画。
 *
 * 种子固定：同一天的日签在开屏、日历卡片、导出的 PNG 里必须是同一枚印，
 * 每次生成一份新噪点就会变成三枚不同的印。
 */
internal object SealInk {

    /** 瓦片边长（像素）。它是平铺的，不需要盖满印面，64 已经足够看不出重复 */
    const val TILE_PX = 64

    /** 平铺时一块瓦片占的边长。小于印面尺寸，颗粒才有密度；调大会变成一块块色斑 */
    val tileSize = 14.dp

    /** 整层遮罩的不透明度。这是「做旧感」的总开关，0 就是全新的印 */
    const val OPACITY = 0.62f

    private const val BITE_CELLS = 10
    private const val BITE_BIAS = 0.78f
    private const val BITE_GAIN = 8.0f

    private const val SPECKLE_CELLS = 28
    private const val SPECKLE_BIAS = 0.62f
    private const val SPECKLE_GAIN = 6.0f
    private const val SPECKLE_MAX = 0.32f

    private const val SEED = 20260830L

    /** 全进程一份：64×64 ARGB 是 16 KB，比每次进开屏都算一遍划算 */
    val tile: ImageBitmap by lazy { buildTile() }

    private fun buildTile(): ImageBitmap {
        val bite = valueNoise(BITE_CELLS, SEED)
        val speckle = valueNoise(SPECKLE_CELLS, SEED + 1)
        val pixels = IntArray(TILE_PX * TILE_PX)
        for (i in pixels.indices) {
            val big = ((bite[i] - BITE_BIAS) * BITE_GAIN).coerceIn(0f, 1f)
            val fine = ((speckle[i] - SPECKLE_BIAS) * SPECKLE_GAIN).coerceIn(0f, 1f) * SPECKLE_MAX
            // 白色 RGB + 变化的 alpha：绘制时会被 ColorFilter 换成纸色，只有 alpha 留下来
            val alpha = (maxOf(big, fine) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(pixels, TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    /**
     * 值噪声：随机格点 + 双线性插值，取模让左右上下首尾相接，平铺时看不到缝。
     *
     * 没有做 smoothstep 平滑。平滑之后的噪点是一团团渐变，盖在印面上像模糊而不像缺墨；
     * 线性插值加上后面那道 gain 才接近二值，边缘是硬的，才像印泥没吃到纸。
     */
    private fun valueNoise(cells: Int, seed: Long): FloatArray {
        val random = Random(seed)
        val grid = FloatArray(cells * cells) { random.nextFloat() }
        val out = FloatArray(TILE_PX * TILE_PX)
        for (y in 0 until TILE_PX) {
            val fy = y.toFloat() / TILE_PX * cells
            val iy = fy.toInt()
            val ty = fy - iy
            val y0 = (iy % cells) * cells
            val y1 = ((iy + 1) % cells) * cells
            for (x in 0 until TILE_PX) {
                val fx = x.toFloat() / TILE_PX * cells
                val ix = fx.toInt()
                val tx = fx - ix
                val x0 = ix % cells
                val x1 = (ix + 1) % cells
                val top = grid[y0 + x0] * (1f - tx) + grid[y0 + x1] * tx
                val bottom = grid[y1 + x0] * (1f - tx) + grid[y1 + x1] * tx
                out[y * TILE_PX + x] = top * (1f - ty) + bottom * ty
            }
        }
        return out
    }
}
