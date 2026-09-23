package com.tracktosearch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 纯字符串与文件扫描，不渲染 Composable，因此刻意不用 Robolectric —— 也就不受 Application 初始化牵连。 */
class FloatingLayerGuardTest {

    @Test
    fun `纯函数能挑出绕过 AppDialog 的裸浮层`() {
        val hits = findFloatingLayerViolations(
            sourceText = "AlertDialog(\n  containerColor = floatingDialogColor()\n",
            relativePath = "ui/screen/foo/Bar.kt",
        )
        assertThat(hits).containsExactly(
            "ui/screen/foo/Bar.kt: AlertDialog(",
            "ui/screen/foo/Bar.kt: floatingDialogColor()",
        )
    }

    @Test
    fun `白名单文件与 Popup 底色用法不算违例`() {
        assertThat(
            findFloatingLayerViolations("AlertDialog(", "ui/component/AppDialog.kt")
        ).isEmpty()
        assertThat(
            findFloatingLayerViolations(".background(floatingDialogColor())", "ui/component/DropdownAnchorMenu.kt")
        ).isEmpty()
        // DatePickerDialog 不在禁列
        assertThat(
            findFloatingLayerViolations("DatePickerDialog(", "ui/screen/foo/Bar.kt")
        ).isEmpty()
    }

    @Test
    fun `token 定义文件本身不算违例`() {
        assertThat(
            findFloatingLayerViolations("fun floatingDialogColor(): Color =", "ui/theme/Theme.kt")
        ).isEmpty()
    }

    @Test
    fun `统一组件调用与底色读取不算违例`() {
        // AppAlertDialog( 内含 AlertDialog( 子串，标识符边界检查必须放行迁移后的调用点
        assertThat(
            findFloatingLayerViolations("AppAlertDialog(onDismiss = {})", "ui/screen/foo/Bar.kt")
        ).isEmpty()
        // 读底色 token 去匹配弹窗背景（吸顶标题填充）不属造浮层
        assertThat(
            findFloatingLayerViolations("headerColor = floatingDialogColor()", "ui/screen/foo/Bar.kt")
        ).isEmpty()
        // DatePickerDialog 组件不在禁列，它所在行的 colors 也一并放行
        assertThat(
            findFloatingLayerViolations(
                "colors = DatePickerDefaults.colors(containerColor = floatingDialogColor()),",
                "ui/screen/foo/Bar.kt",
            )
        ).isEmpty()
    }

    @Test
    fun `颜色 token 拿来造浮层的用法照样报`() {
        assertThat(
            findFloatingLayerViolations("containerColor = floatingDialogColor()", "ui/screen/foo/Bar.kt")
        ).containsExactly("ui/screen/foo/Bar.kt: floatingDialogColor()")
        assertThat(
            findFloatingLayerViolations(".background(floatingDialogColor())", "ui/screen/foo/Bar.kt")
        ).containsExactly("ui/screen/foo/Bar.kt: floatingDialogColor()")
    }

    @Test
    fun `全仓 ui 源码不再有绕过 AppDialog 的裸浮层`() {
        val uiRoot = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "app/src/main/java/com/tracktosearch/ui").exists() }
            .let { java.io.File(it, "app/src/main/java/com/tracktosearch/ui") }
        val violations = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 路径带上 ui/ 前缀，与白名单条目同构，endsWith 才对得上
            .flatMap { findFloatingLayerViolations(it.readText(), "ui/" + it.invariantRelativePath(uiRoot)) }
            .toList()
        assertThat(violations).isEmpty()
    }

    private fun java.io.File.invariantRelativePath(root: java.io.File): String =
        absolutePath.removePrefix(root.absolutePath).trimStart('\\', '/').replace('\\', '/')
}

/**
 * 白名单三个，各有硬理由：
 * - AppDialog.kt —— 组件本体，四种字面量必然都有。
 * - DropdownAnchorMenu.kt —— 给 Popup 的底色复用 floatingDialogColor()，属底色层不属弹窗层。
 * - theme/Theme.kt —— `fun floatingDialogColor(): Color` 与 `fun floatingSheetColor()` 的**定义行**
 *   本身含这两个字面量，不豁免会当场误报。
 */
private val GuardAllowlist = setOf(
    "ui/component/AppDialog.kt",
    "ui/component/DropdownAnchorMenu.kt",
    "ui/theme/Theme.kt",
)

private val GuardPatterns = listOf(
    "AlertDialog(", "ModalBottomSheet(", "floatingDialogColor()", "floatingSheetColor()",
)

/**
 * 按行扫描而不是整文件 contains：违例按「文件 + 字面量」归位，行级判断才好放行
 * 标识符边界与 DatePicker 这类合法用法，整文件一票否决会把它们连坐。
 *
 * 两条放行规则，各有其人：
 * - **标识符边界**：`AppAlertDialog(` 内含子串 `AlertDialog(`，前一个字符是字母/数字/
 *   下划线就不是裸调用，这是迁移后调用点的主要形态，不检查会整仓误报。
 * - **含 DatePicker 的行**：M3 的 DatePickerDialog 是合法弹窗组件、不在禁列，它的
 *   `colors = DatePickerDefaults.colors(containerColor = floatingDialogColor())` 一并放行。
 *
 * 颜色 token 只拦「拿它造浮层」（containerColor / color = / background），
 * 读 token 去匹配弹窗底色（吸顶标题 headerColor、val 暂存）属底色复用，不算。
 */
internal fun findFloatingLayerViolations(sourceText: String, relativePath: String): List<String> {
    if (GuardAllowlist.any { relativePath.endsWith(it) }) return emptyList()
    val violations = mutableListOf<String>()
    sourceText.lineSequence().forEach { line ->
        GuardPatterns.forEach { pattern ->
            val at = line.indexOf(pattern)
            if (at < 0) return@forEach
            val prev = line.getOrNull(at - 1)
            if (prev != null && (prev.isLetterOrDigit() || prev == '_')) return@forEach
            if (line.contains("DatePicker")) return@forEach
            if (pattern.startsWith("floating") && !line.isTokenLayerUsage()) return@forEach
            violations += "$relativePath: $pattern"
        }
    }
    return violations
}

/** 颜色 token 出现在「造浮层」的槽位上才算数：容器色、背景色、直接 color 赋值。 */
private fun String.isTokenLayerUsage(): Boolean =
    contains("containerColor") || contains("background(") || contains("color = ")
