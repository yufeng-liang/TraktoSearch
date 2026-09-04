package com.tracktosearch.ui.theme

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * 取票机上那些用点阵字体渲染的文案，必须每个字都在 `ark_pixel_12px.ttf` 的子集里。
 *
 * 为什么需要这条：字体是子集化过的（上游 4.74 MB，装进 APK 只留几十 KB），
 * 第一版子集是为一句占位文案手工裁的。点阵字体后来成了整台取票机的字体，
 * 新加的字缺一个就静默回退系统字体 —— 而回退字体的字宽跟点阵网格不一致，
 * 屏上看到的是两个字挤在一起，不是「少了一个字」那种显眼的错。
 * 装机截图上「请输**入**取票码」的 入 就是这么overlap 的。
 *
 * 这条测试直接读 res 里的字体文件和四个语言的 strings.xml，规则跟
 * `scripts/subset-ark-pixel.py` 里的 KEY_PATTERNS 一字不差：改了那边要改这边。
 * 于是往显示屏上加一句新文案、忘了重跑子集脚本，会在这里红，而不是在装机截图上。
 */
class PixelFontCoverageTest {

    @Test
    fun 取票机用到的每个字都在点阵字体子集里() {
        val covered = TrueTypeCmap.coveredCodePoints(fontFile())
        assertWithMessage("字体里一个字形都没读到，cmap 解析大概是错的")
            .that(covered.size).isGreaterThan(64)

        val missing = linkedMapOf<Int, MutableSet<String>>()
        for ((path, strings) in machineStrings()) {
            for ((key, value) in strings) {
                value.codePoints().forEach { cp ->
                    if (cp !in covered && HANGUL_RANGES.none { cp in it }) {
                        missing.getOrPut(cp) { linkedSetOf() }.add("$path:$key")
                    }
                }
            }
        }

        val report = missing.entries.joinToString("\n") { (cp, sources) ->
            "  U+%04X %s <- %s".format(cp, String(Character.toChars(cp)), sources.first())
        }
        assertWithMessage(
            "点阵字体子集缺 ${missing.size} 个字，这些字会回退系统字体并把邻字挤歪。\n" +
                "重跑 scripts/subset-ark-pixel.py 补进子集：\n$report"
        ).that(missing).isEmpty()
    }

    /** 读 [KEY_PATTERNS] 命中的所有字符串，返回 `目录名 to (键 to 值)`。 */
    private fun machineStrings(): List<Pair<String, List<Pair<String, String>>>> {
        val resDir = resDir()
        val valuesDirs = resDir.listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertWithMessage("res 下没找到 values* 目录：${resDir.absolutePath}")
            .that(valuesDirs).isNotEmpty()

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return valuesDirs.mapNotNull { dir ->
            val xml = File(dir, "strings.xml").takeIf { it.isFile } ?: return@mapNotNull null
            val nodes = builder.parse(xml).getElementsByTagName("string")
            val hits = (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .mapNotNull { el ->
                    val name = el.getAttribute("name")
                    if (KEY_PATTERNS.none { it.matches(name) }) null else name to el.textContent
                }
            dir.name to hits
        }
    }

    private fun resDir(): File = CANDIDATE_ROOTS
        .map { File(it, "src/main/res") }
        .firstOrNull { it.isDirectory }
        ?: error("找不到 app/src/main/res，测试工作目录是 ${File("").absolutePath}")

    private fun fontFile(): File = File(resDir(), "font/ark_pixel_12px.ttf")
        .also { assertWithMessage("点阵字体不在 ${it.absolutePath}").that(it.isFile).isTrue() }

    private companion object {
        /** Gradle 把单测的工作目录设在模块根，但直接在仓库根跑时要往下走一层。 */
        val CANDIDATE_ROOTS = listOf(File("."), File("app"))

        /**
         * 跟 scripts/subset-ark-pixel.py 的 KEY_PATTERNS 一一对应。
         * 那边加一条这边就要加一条，否则脚本补了字、测试却不知道该验它。
         */
        val KEY_PATTERNS = listOf(
            Regex("^machine_.*"),
            Regex("^auth_error_.*"),
            Regex("^auth_migration_invite_hint$"),
            Regex("^login_activation_locked$"),
        )

        /**
         * 谚文豁免段。Ark Pixel 12px 本身不含谚文字形（Type.kt:22 已注明），
         * 不是子集裁掉的，补不进来 —— 韩语的点阵屏文案回退系统字体是既有限制。
         *
         * 三段分别是谚文音节、谚文字母 Jamo、兼容 Jamo。
         */
        val HANGUL_RANGES = listOf(0xAC00..0xD7A3, 0x1100..0x11FF, 0x3130..0x318F)
    }
}

/**
 * 够用的 TrueType `cmap` 读取器：只回答「这个码位有没有字形」。
 *
 * 不引第三方字体库：验的是一个几十 KB 的构建产物，为它加一条依赖不值得，
 * 而 format 4 / 12 两种子表加起来就是下面这些位移运算。
 */
private object TrueTypeCmap {

    fun coveredCodePoints(font: File): Set<Int> {
        val data = ByteBuffer.wrap(font.readBytes()).order(ByteOrder.BIG_ENDIAN)
        val cmapOffset = findTable(data, "cmap") ?: error("字体里没有 cmap 表")
        val covered = mutableSetOf<Int>()
        for (subtable in unicodeSubtables(data, cmapOffset)) {
            when (data.getShort(subtable).toInt() and 0xFFFF) {
                4 -> readFormat4(data, subtable, covered)
                12 -> readFormat12(data, subtable, covered)
                // 其余格式（0/2/6/13/14）这个字体不会用到，遇到就跳过而不是报错：
                // 只要有一张 Unicode 子表读出了字形，覆盖率断言就成立
            }
        }
        return covered
    }

    private fun findTable(data: ByteBuffer, tag: String): Int? {
        val numTables = data.getShort(4).toInt() and 0xFFFF
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            val name = String(ByteArray(4) { data.get(record + it) }, Charsets.US_ASCII)
            if (name == tag) return data.getInt(record + 8)
        }
        return null
    }

    private fun unicodeSubtables(data: ByteBuffer, cmapOffset: Int): List<Int> {
        val numTables = data.getShort(cmapOffset + 2).toInt() and 0xFFFF
        return (0 until numTables).mapNotNull { i ->
            val record = cmapOffset + 4 + i * 8
            val platformId = data.getShort(record).toInt() and 0xFFFF
            val encodingId = data.getShort(record + 2).toInt() and 0xFFFF
            val isUnicode = platformId == 0 ||
                (platformId == 3 && (encodingId == 1 || encodingId == 10))
            if (isUnicode) cmapOffset + data.getInt(record + 4) else null
        }
    }

    /** BMP 段映射。字形 0 是 .notdef，映到它等于没有这个字。 */
    private fun readFormat4(data: ByteBuffer, offset: Int, out: MutableSet<Int>) {
        val segCount = (data.getShort(offset + 6).toInt() and 0xFFFF) / 2
        val endCodes = offset + 14
        val startCodes = endCodes + segCount * 2 + 2
        val idDeltas = startCodes + segCount * 2
        val idRangeOffsets = idDeltas + segCount * 2
        for (seg in 0 until segCount) {
            val end = data.getShort(endCodes + seg * 2).toInt() and 0xFFFF
            val start = data.getShort(startCodes + seg * 2).toInt() and 0xFFFF
            if (start > end || start == 0xFFFF) continue
            val delta = data.getShort(idDeltas + seg * 2).toInt()
            val rangeOffsetPos = idRangeOffsets + seg * 2
            val rangeOffset = data.getShort(rangeOffsetPos).toInt() and 0xFFFF
            for (code in start..end) {
                val glyph = if (rangeOffset == 0) {
                    (code + delta) and 0xFFFF
                } else {
                    val glyphPos = rangeOffsetPos + rangeOffset + (code - start) * 2
                    val raw = data.getShort(glyphPos).toInt() and 0xFFFF
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) out.add(code)
            }
        }
    }

    /** 分组映射，覆盖 BMP 以外的码位。 */
    private fun readFormat12(data: ByteBuffer, offset: Int, out: MutableSet<Int>) {
        val groups = data.getInt(offset + 12)
        for (group in 0 until groups) {
            val record = offset + 16 + group * 12
            val start = data.getInt(record)
            val end = data.getInt(record + 4)
            val startGlyph = data.getInt(record + 8)
            if (startGlyph == 0 || start > end) continue
            for (code in start..end) out.add(code)
        }
    }
}
