@file:JvmName("Util")
package okhttp3.internal

import java.io.Closeable

/**
 * OkHttp 5 兼容垫片 —— 只为 `openimage-coil` 的 Coil2 适配层而存在。
 *
 * ## 为什么需要这个文件
 *
 * `com.flyjingfish:openimage-coil`（本项目用 2.5.2）为了在同一个 AAR 里同时支持 Coil2 与
 * Coil3，把两套适配层都打进来了，运行期按 `isCoil3` 二选一（见 `OpenImageCoilInitProvider`）。
 * 其中 **Coil2 轨**的 `CoilLoadImageUtils` 直接调用了 OkHttp 的内部 API：
 *
 * ```kotlin
 * // openimage-coil 2.5.2 / master 的 CoilLoadImageUtils.kt
 * import okhttp3.internal.closeQuietly
 * ...
 * snap?.closeQuietly()   // 第 117 行、第 138 行两处
 * ```
 *
 * 而 OkHttp 5 把 `okhttp3/internal/Util` 这个类删掉了，内部工具函数被拆成文件门面类
 * `_UtilCommonKt` / `_UtilJvmKt`。于是 `Util.closeQuietly` 在 OkHttp 5 下根本不存在：
 * release 走 R8 fullMode 时按「缺类即错误」直接编不过（debug 不跑 R8 所以复现不了）：
 *
 * ```
 * ERROR: R8: Missing class okhttp3.internal.Util (referenced from:
 *   void com.flyjingfish.openimagecoillib.CoilLoadImageUtils$loadWebImage$listener$1.onSuccess(...)
 *   and 1 other context)
 * ```
 *
 * 上游至今未修（master 分支仍是 `import okhttp3.internal.closeQuietly`，issue 区也无人报过），
 * 本项目已升到 OkHttp 5.5.0，所以在本模块里补一个同名同签名的类把缺口填上。
 *
 * ## 为什么不用 `-dontwarn okhttp3.internal.Util`
 *
 * AGP 的 `missing_rules.txt` 建议的是 `-dontwarn`，但那只是**让构建通过**：R8 会保留那条
 * `invokestatic` 指令，而该类在运行期依旧不存在。本项目 Coil 2.7.0 在位 ⇒
 * `OpenImageCoilInitProvider` 探测 `coil3.imageLoader` 抛 `NoClassDefFoundError` ⇒
 * `isCoil3 = false` ⇒ **运行期真的走 Coil2 轨**，全屏看图时会踩到该调用点，
 * 于是构建期错误被换成了运行期 `NoClassDefFoundError`（更糟）。补真实实现可以同时满足
 * R8 的类解析与运行期调用。
 *
 * ## 语义与 OkHttp 4.12.0 一致
 *
 * 逐行对齐 `okhttp-4.12.0-sources.jar` 的 `okhttp3/internal/Util.kt:492-500`：
 * 吞掉受检异常，但 `RuntimeException` 原样抛出（`Error` 本来就不被 `Exception` 捕获）。
 *
 * ## 什么时候可以删掉它
 *
 * 满足任一条件即可删除，并移除本节注释：
 * 1. 上游 `openimage-coil` 改用 OkHttp 5 可用 API（或改用 `use {}` / `Closeable.close()`）；
 * 2. 本项目完成 Coil3 迁移，且 R8 能证明 Coil2 轨不可达（单靠迁移不够——两轨在同一个
 *    `ContentProvider` 里按运行期探测分支选择，R8 无法静态判定）；
 * 3. 换掉 OpenImage 大图查看器。
 *
 * 注意：将来 OkHttp 若把 `Util` 加回来，会与本文件产生 **duplicate class** 编译错误 ——
 * 那是响亮且可定位的失败，比静默的缺类好，届时删掉本文件即可。
 */
fun Closeable.closeQuietly() {
    try {
        close()
    } catch (rethrown: RuntimeException) {
        throw rethrown
    } catch (_: Exception) {
        // 与 OkHttp 4 一致：受检异常静默忽略
    }
}
