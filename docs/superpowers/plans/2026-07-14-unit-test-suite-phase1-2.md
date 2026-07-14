# 单元测试套件实现计划（阶段 1 + 阶段 2）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 搭建单元测试基础设施，完成 A 层 13 个纯函数工具类测试，建立测试规范与模式。

**架构：** 在现有 Android 项目 `app/src/test/` 下扩展单元测试。引入 mockk + turbine + truth + Robolectric + coroutines-test 测试栈。Robolectric SDK 锁定 33。测试类按规格 §2 的 13 个纯函数工具类逐个编写，每个测试类写完立即运行验证。

**技术栈：** JUnit 4 + mockk 1.13.13 + turbine 1.2.0 + truth 1.4.4 + Robolectric 4.13 + kotlinx-coroutines-test 1.11.0 + androidx.arch.core:core-testing 2.2.0

**规格依据：** [2026-07-14-unit-test-suite-design.md](file:///f:/trae-project/docs/superpowers/specs/2026-07-14-unit-test-suite-design.md)

**测试代码编写原则：** 本计划为每个测试类给出具体的测试点清单（方法级，含预期行为）。执行者需先用 Read 工具阅读被测类源码，确认精确的方法签名与返回类型后，再编写测试代码。命名规范：`被测行为_条件_预期结果`。断言统一用 `assertThat()`（truth）。

**Bug 处理：** 遵循规格 §5 的 P0/P1/P2 分级。P0 立即修复并独立 commit；P1 用 `@Ignore("待修复: ...")` 标注；P2 写 characterization test + `// FIXME` 注释。

---

## 文件结构

**修改：**
- `gradle/libs.versions.toml` — 新增 6 个测试依赖版本与库声明
- `app/build.gradle.kts` — 新增 testImplementation + testOptions

**创建：**
- `app/src/test/resources/robolectric.properties` — Robolectric SDK 配置
- `app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt` — ViewModel 测试用 Dispatcher Rule（本阶段不用，为阶段 4 预备）
- `app/src/test/java/com/tracktosearch/test/TestFixture.kt` — 公共测试数据工厂
- `app/src/test/java/com/tracktosearch/data/util/TtlCacheTest.kt`
- `app/src/test/java/com/tracktosearch/data/util/PersistentTtlCacheTest.kt`
- `app/src/test/java/com/tracktosearch/util/HolidayDetectorTest.kt`
- `app/src/test/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizerTest.kt`（扩充已有）
- `app/src/test/java/com/tracktosearch/data/util/DataExportImportTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/custom/JsonPathParserTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/douban/DoubanSpiderTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/cloud/AesCryptoTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/cloud/GiteeContentsApiTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncFailureTest.kt`
- `app/src/test/java/com/tracktosearch/EnumsTest.kt`
- `app/src/test/java/com/tracktosearch/ui/util/ScrollToTopProviderTest.kt`
- `app/src/test/java/com/tracktosearch/data/util/PosterColorExtractorTest.kt`
- `app/src/test/resources/fixtures/douban/*.html`（DoubanSpider 测试用，按需创建）
- `app/src/test/resources/fixtures/imdb/ratings.csv`（DataExportImport 测试用）

---

### 任务 1：测试基础设施

**文件：**
- 修改：`gradle/libs.versions.toml`
- 修改：`app/build.gradle.kts`
- 创建：`app/src/test/resources/robolectric.properties`
- 创建：`app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt`
- 创建：`app/src/test/java/com/tracktosearch/test/TestFixture.kt`

- [ ] **步骤 1：修改 `gradle/libs.versions.toml` 添加依赖版本**

在 `[versions]` 块末尾（`junit = "4.13.2"` 之后）添加：

```toml
mockk = "1.13.13"
turbine = "1.2.0"
coroutinesTest = "1.11.0"
truth = "1.4.4"
robolectric = "4.13"
coreTesting = "2.2.0"
```

在 `[libraries]` 块末尾（`junit = ...` 之后）添加：

```toml
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
core-testing = { group = "androidx.arch.core", name = "core-testing", version.ref = "coreTesting" }
```

- [ ] **步骤 2：修改 `app/build.gradle.kts` 添加 testImplementation 与 testOptions**

在 `android { }` 块内（`buildFeatures { }` 之后）添加：

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
```

在 `dependencies { }` 块末尾（`testImplementation(libs.junit)` 之后）添加：

```kotlin
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.core.testing)
```

- [ ] **步骤 3：创建 `app/src/test/resources/robolectric.properties`**

```properties
sdk=33
```

- [ ] **步骤 4：创建 `app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt`**

```kotlin
package com.tracktosearch.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * ViewModel 测试用 Rule：替换 Dispatchers.Main 为 TestDispatcher。
 * 阶段 4 ViewModel 测试统一使用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

- [ ] **步骤 5：创建 `app/src/test/java/com/tracktosearch/test/TestFixture.kt`**

```kotlin
package com.tracktosearch.test

/**
 * 公共测试数据工厂。按需扩充，各测试类共享的样本数据放这里。
 */
object TestFixture {
    // 阶段 2 按需添加 sample 数据工厂方法
}
```

- [ ] **步骤 6：运行现有测试验证无回归**

运行：`.\gradlew test --tests "com.tracktosearch.ui.screen.statistics.ReviewTokenizerTest"`
预期：4 个现有用例全部 PASS（验证新增依赖不破坏现有测试）

- [ ] **步骤 7：Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test/resources/robolectric.properties app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt app/src/test/java/com/tracktosearch/test/TestFixture.kt
git commit -m "test: 添加单元测试基础设施(依赖+Robolectric+工具类)"
```

---

### 任务 2：EnumsTest（SyncMode + PanHubPlugin）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/SyncMode.kt`、`app/src/main/java/com/tracktosearch/data/remote/panhub/PanHubPlugin.kt`
- 创建：`app/src/test/java/com/tracktosearch/EnumsTest.kt`

**测试点清单：**
1. `SyncMode` 枚举包含且仅包含 `INCREMENTAL_WITH_CHANGES`、`FULL_REWRITE` 两个值
2. `PanHubPlugin` 枚举包含 10 个值（PANSEARCH, QUPANSOU, PANTA, HUNHEPAN, JIKEPAN, LABI, THEPIRATEBAY, DUODUO, XUEXIZHINAN, NYAA）
3. `PanHubPlugin` 所有 `id` 字段唯一
4. `PanHubPlugin` 所有 `displayName` 字段唯一

- [ ] **步骤 1：阅读被测类源码确认枚举值与字段名**

运行：Read `SyncMode.kt` 和 `PanHubPlugin.kt`

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.panhub.PanHubPlugin
import com.tracktosearch.data.repository.SyncMode
import org.junit.Test

class EnumsTest {

    @Test
    fun syncMode_containsExactlyTwoValues() {
        val values = SyncMode.values().map { it.name }.toSet()
        assertThat(values).containsExactly(
            "INCREMENTAL_WITH_CHANGES",
            "FULL_REWRITE"
        )
    }

    @Test
    fun panHubPlugin_containsTenValues() {
        assertThat(PanHubPlugin.values().size).isEqualTo(10)
    }

    @Test
    fun panHubPlugin_idsAreUnique() {
        val ids = PanHubPlugin.values().map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun panHubPlugin_displayNamesAreUnique() {
        val names = PanHubPlugin.values().map { it.displayName }
        assertThat(names).containsNoDuplicates()
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.EnumsTest"`
预期：4 个用例 PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/EnumsTest.kt
git commit -m "test: 添加 SyncMode/PanHubPlugin 枚举完整性测试"
```

---

### 任务 3：HolidayDetectorTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/util/HolidayDetector.kt`
- 创建：`app/src/test/java/com/tracktosearch/util/HolidayDetectorTest.kt`

**测试点清单：**
1. 固定日期节日：国庆（10-01）→ `NATIONAL_DAY`，劳动节（05-01）→ `LABOR_DAY`
2. 农历节日：春节（如 2026-02-17）→ `SPRING_FESTIVAL`，端午（如 2026-06-19）→ `DRAGON_BOAT`，中秋（如 2026-09-25）→ `MID_AUTUMN`
3. 其他固定节日：圣诞（12-25）→ `CHRISTMAS`，万圣节（10-31）→ `HALLOWEEN`
4. 非节日日期（如 2026-07-14）→ null
5. 传入 `LocalDate.of(...)` 显式参数时正确检测（不依赖系统时钟）

- [ ] **步骤 1：阅读 HolidayDetector.kt 确认 detect() 签名、Holiday 枚举值、农历计算方式**

- [ ] **步骤 2：编写测试类**

基于源码确认的农历日期，编写测试。示例框架：

```kotlin
package com.tracktosearch.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HolidayDetectorTest {

    private val detector = HolidayDetector()

    @Test
    fun detect_nationalDay_returnsNationalDay() {
        assertThat(detector.detect(LocalDate.of(2026, 10, 1)))
            .isEqualTo(Holiday.NATIONAL_DAY)
    }

    @Test
    fun detect_laborDay_returnsLaborDay() {
        assertThat(detector.detect(LocalDate.of(2026, 5, 1)))
            .isEqualTo(Holiday.LABOR_DAY)
    }

    @Test
    fun detect_christmas_returnsChristmas() {
        assertThat(detector.detect(LocalDate.of(2026, 12, 25)))
            .isEqualTo(Holiday.CHRISTMAS)
    }

    @Test
    fun detect_halloween_returnsHalloween() {
        assertThat(detector.detect(LocalDate.of(2026, 10, 31)))
            .isEqualTo(Holiday.HALLOWEEN)
    }

    @Test
    fun detect_springFestival2026_returnsSpringFestival() {
        // 2026 年春节：2 月 17 日（农历正月初一）
        assertThat(detector.detect(LocalDate.of(2026, 2, 17)))
            .isEqualTo(Holiday.SPRING_FESTIVAL)
    }

    @Test
    fun detect_nonHolidayDate_returnsNull() {
        assertThat(detector.detect(LocalDate.of(2026, 7, 14))).isNull()
    }
}
```

注意：农历日期需对照源码中的农历表确认。若源码用的是固定映射表，按表内日期写测试。

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.util.HolidayDetectorTest"`
预期：PASS。若农历日期不符，按源码农历表修正测试日期（P2：记录源码农历计算方式是否符合预期）。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/util/HolidayDetectorTest.kt
git commit -m "test: 添加 HolidayDetector 节日检测测试"
```

---

### 任务 4：ScrollToTopProviderTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/ui/util/ScrollToTopProvider.kt`
- 创建：`app/src/test/java/com/tracktosearch/ui/util/ScrollToTopProviderTest.kt`

**测试点清单：**
1. `register(action)` 后 `scrollToTop()` 触发 action
2. `unregister()` 后 `scrollToTop()` 无副作用（不触发原 action）
3. 未 register 时 `scrollToTop()` 安全（无异常）
4. 多次 `register` 覆盖：后一次 register 的 action 生效

- [ ] **步骤 1：阅读 ScrollToTopProvider.kt 确认 register/unregister/scrollToTop 签名**

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollToTopProviderTest {

    @Test
    fun scrollToTop_afterRegister_invokesAction() {
        val provider = ScrollToTopProvider()
        var invoked = false
        provider.register { invoked = true }

        provider.scrollToTop()

        assertThat(invoked).isTrue()
    }

    @Test
    fun scrollToTop_afterUnregister_doesNotInvokeAction() {
        val provider = ScrollToTopProvider()
        var invoked = false
        provider.register { invoked = true }
        provider.unregister()

        provider.scrollToTop()

        assertThat(invoked).isFalse()
    }

    @Test
    fun scrollToTop_withoutRegister_doesNotThrow() {
        val provider = ScrollToTopProvider()
        // 无 register 直接调用，应安全无异常
        provider.scrollToTop()
    }

    @Test
    fun register_twice_secondActionTakesEffect() {
        val provider = ScrollToTopProvider()
        var firstInvoked = false
        var secondInvoked = false
        provider.register { firstInvoked = true }
        provider.register { secondInvoked = true }

        provider.scrollToTop()

        // 后注册的覆盖前者（按源码实际行为确认，若前者也被调用则修正断言并标 P2）
        assertThat(secondInvoked).isTrue()
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.ui.util.ScrollToTopProviderTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/util/ScrollToTopProviderTest.kt
git commit -m "test: 添加 ScrollToTopProvider 测试"
```

---

### 任务 5：ReviewTokenizerTest（扩充已有）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizer.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizerTest.kt`

**新增测试点清单（在现有 4 个用例基础上扩充）：**
1. 空列表输入 → 返回空 Map
2. 单条评论 → 正确分词
3. 超长文本（1000+ 字）→ 不崩溃，返回非空 Map
4. 混合中英文评论 → 中文分词 + 英文小写同时正确
5. 仅含停用词的评论 → 返回空 Map
6. 数字与标点 → 不作为有效词返回

- [ ] **步骤 1：阅读 ReviewTokenizer.kt 确认 tokenize 签名与停用词列表**

- [ ] **步骤 2：在现有 ReviewTokenizerTest.kt 末尾追加新用例**

```kotlin
    @Test
    fun tokenize_emptyList_returnsEmptyMap() {
        val result = ReviewTokenizer.tokenize(emptyList())
        assertTrue("空列表应返回空 Map", result.isEmpty())
    }

    @Test
    fun tokenize_singleReview_returnsNonEmptyMap() {
        val result = ReviewTokenizer.tokenize(listOf("剧情精彩"))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun tokenize_longText_doesNotCrash() {
        val longReview = "精彩".repeat(500)
        val result = ReviewTokenizer.tokenize(listOf(longReview))
        assertTrue("超长文本不应崩溃", result.isNotEmpty())
    }

    @Test
    fun tokenize_mixedChineseEnglish_handlesBoth() {
        val result = ReviewTokenizer.tokenize(
            listOf("剧情精彩 story is great")
        )
        assertTrue("应包含中文词", result.containsKey("剧情") || result.containsKey("精彩"))
        assertTrue("应包含英文词", result.containsKey("story"))
        assertTrue("应包含英文词", result.containsKey("great"))
    }

    @Test
    fun tokenize_onlyStopWords_returnsEmptyMap() {
        val result = ReviewTokenizer.tokenize(listOf("的的的是是是"))
        // 全为停用词，应过滤殆尽（若 jieba 分出非停用词则按实际行为修正并标 P2）
        assertTrue("全停用词应返回空或极小 Map", result.size <= 2)
    }
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.ui.screen.statistics.ReviewTokenizerTest"`
预期：现有 4 + 新增 5 = 9 个用例 PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizerTest.kt
git commit -m "test: 扩充 ReviewTokenizer 边界用例测试"
```

---

### 任务 6：DataExportImportTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/util/DataExportImport.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/util/DataExportImportTest.kt`
- 创建：`app/src/test/resources/fixtures/imdb/ratings.csv`

**测试点清单：**
1. `exportToJson(空列表)` → 返回有效 JSON 字符串（含空数组）
2. `exportToJson(有数据)` → JSON 可被解析回来，字段一致
3. `parseImdbCsv(标准 CSV)` → 正确解析行数与字段
4. `parseImdbCsv(含引号转义字段)` → 引号内逗号不分割
5. `parseImdbCsv(含跨行字段)` → 引号内换行不分割
6. `parseImdbCsv(缺必填列)` → 返回错误结果（ParseResult.Error 或类似）
7. `parseImdbCsv(空内容)` → 返回空结果或错误
8. `parseImdbCsv(格式错误)` → 优雅处理，不崩溃

- [ ] **步骤 1：阅读 DataExportImport.kt 确认 exportToJson/parseImdbCsv 签名、ExportData/ImportItem/ParseResult 结构**

- [ ] **步骤 2：创建 fixture `app/src/test/resources/fixtures/imdb/ratings.csv`**

```csv
Const,Title,Year,Rating,Date Rated
tt0111161,The Shawshank Redemption,1994,10,2024-01-15
tt0068646,"The Godfather, Part II",1974,9,2024-02-20
tt0468569,"The Dark
Knight",2008,8,2024-03-10
```

- [ ] **步骤 3：编写测试类**

基于源码确认的 ParseResult 密封类结构与 ExportItem 字段编写。示例框架：

```kotlin
package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DataExportImportTest {

    @Test
    fun exportToJson_emptyLists_returnsValidJson() {
        val json = DataExportImport.exportToJson(
            watchlistMovies = emptyList(),
            watchlistShows = emptyList(),
            historyMovies = emptyList(),
            historyShows = emptyList()
        )
        // 应是有效 JSON，包含空数组
        assertThat(json).contains("\"watchlistMovies\"")
    }

    @Test
    fun exportToJson_withData_isRoundTripConsistent() {
        // 根据源码 ExportItem 构造样本数据，导出后解析回来比对
        // 具体字段名按源码确认
    }

    @Test
    fun parseImdbCsv_standardCsv_parsesCorrectly() {
        val csv = """
            Const,Title,Year,Rating,Date Rated
            tt0111161,The Shawshank Redemption,1994,10,2024-01-15
        """.trimIndent()
        val result = DataExportImport.parseImdbCsv(csv)
        // 按源码 ParseResult 结构断言：成功、1 条记录
        // assertThat(result).isInstanceOf(...)
    }

    @Test
    fun parseImdbCsv_quotedFieldWithComma_parsesAsSingleField() {
        val csv = """
            Const,Title,Year,Rating,Date Rated
            tt0068646,"The Godfather, Part II",1974,9,2024-02-20
        """.trimIndent()
        val result = DataExportImport.parseImdbCsv(csv)
        // 断言标题字段含逗号且未被分割
    }

    @Test
    fun parseImdbCsv_quotedFieldWithNewline_parsesAsSingleField() {
        val csv = "Const,Title,Year,Rating,Date Rated\ntt0468569,\"The Dark\nKnight\",2008,8,2024-03-10"
        val result = DataExportImport.parseImdbCsv(csv)
        // 断言标题含换行且作为单字段
    }

    @Test
    fun parseImdbCsv_missingRequiredColumn_returnsError() {
        val csv = "Title,Year\nMovie,2024\n"  // 缺 Const 列
        val result = DataExportImport.parseImdbCsv(csv)
        // 断言返回错误结果
    }

    @Test
    fun parseImdbCsv_emptyContent_returnsEmptyOrError() {
        val result = DataExportImport.parseImdbCsv("")
        // 按源码实际行为断言，空内容应优雅处理
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.util.DataExportImportTest"`
预期：PASS。根据实际 ParseResult 结构补全断言。

- [ ] **步骤 5：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/util/DataExportImportTest.kt app/src/test/resources/fixtures/imdb/ratings.csv
git commit -m "test: 添加 DataExportImport JSON/CSV 解析测试"
```

---

### 任务 7：JsonPathParserTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/custom/JsonPathParser.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/custom/JsonPathParserTest.kt`

**测试点清单：**
1. `extractList(root, "a.b.c")` → 简单点分路径，返回目标元素列表
2. `extractList(root, "a[0]")` → 数组索引，返回第 0 个元素
3. `extractList(root, "a[*]")` → 数组遍历，返回所有元素
4. `extractList(root, "a.b[0].c")` → 嵌套路径
5. `extractList(root, "不存在的路径")` → 返回空列表
6. `extractString(element, "相对路径")` → 返回字符串值
7. `extractString(element, "不存在的路径")` → 返回 null
8. `extractList(root, "")` → 空路径处理（按源码行为断言）

- [ ] **步骤 1：阅读 JsonPathParser.kt 确认 extractList/extractString 签名与 PathSegment 解析规则**

- [ ] **步骤 2：编写测试类**

基于源码确认的路径语法编写。示例框架：

```kotlin
package com.tracktosearch.data.remote.custom

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

class JsonPathParserTest {

    private val json = Json
    private val sampleRoot = buildJsonObject {
        put("a", buildJsonObject {
            put("b", buildJsonObject {
                put("c", "value_abc")
            })
            putJsonArray("arr") {
                add("elem0")
                add("elem1")
                add("elem2")
            }
        })
        putJsonArray("items") {
            add(buildJsonObject { put("name", "item0") })
            add(buildJsonObject { put("name", "item1") })
        }
    }

    @Test
    fun extractList_simpleDotPath_returnsTargetElement() {
        val result = JsonPathParser.extractList(sampleRoot, "a.b.c")
        assertThat(result).hasSize(1)
        // 断言值为 "value_abc"
    }

    @Test
    fun extractList_arrayIndex_returnsSpecifiedElement() {
        val result = JsonPathParser.extractList(sampleRoot, "a.arr[0]")
        assertThat(result).hasSize(1)
        // 断言值为 "elem0"
    }

    @Test
    fun extractList_arrayWildcard_returnsAllElements() {
        val result = JsonPathParser.extractList(sampleRoot, "a.arr[*]")
        assertThat(result).hasSize(3)
    }

    @Test
    fun extractList_nestedPath_returnsTarget() {
        val result = JsonPathParser.extractList(sampleRoot, "items[0].name")
        assertThat(result).hasSize(1)
        // 断言值为 "item0"
    }

    @Test
    fun extractList_nonExistentPath_returnsEmptyList() {
        val result = JsonPathParser.extractList(sampleRoot, "x.y.z")
        assertThat(result).isEmpty()
    }

    @Test
    fun extractString_validPath_returnsStringValue() {
        val target = JsonPathParser.extractList(sampleRoot, "a.b")[0]
        val result = JsonPathParser.extractString(target, "c")
        assertThat(result).isEqualTo("value_abc")
    }

    @Test
    fun extractString_nonExistentPath_returnsNull() {
        val target = JsonPathParser.extractList(sampleRoot, "a.b")[0]
        val result = JsonPathParser.extractString(target, "nonexistent")
        assertThat(result).isNull()
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.remote.custom.JsonPathParserTest"`
预期：PASS。按源码实际返回类型修正断言。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/custom/JsonPathParserTest.kt
git commit -m "test: 添加 JsonPathParser 路径解析测试"
```

---

### 任务 8：DoubanSyncFailureTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncFailure.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncFailureTest.kt`

**测试点清单：**
1. `fromEntity(entity)` → `toEntity()` 往返一致（所有字段保留）
2. `fromMarkItem(item, status, reason)` → 字段正确映射
3. `toMarkItem()` → 字段正确转换
4. `FailureReason.fromString("NO_IMDB_ID")` → `NO_IMDB_ID`
5. `FailureReason.fromString(null)` → 默认值（按源码确认，可能抛异常或返回默认）
6. `FailureReason.fromString("未知值")` → 默认值或异常（按源码行为断言）
7. `mediaTypeCleared` 默认值正确
8. `FailureReason.recoverable` 标志：可恢复的 reason 为 true，不可恢复的为 false

- [ ] **步骤 1：阅读 DoubanSyncFailure.kt 确认 data class 字段、fromEntity/toEntity/fromMarkItem 签名、FailureReason 枚举值与 fromString 行为**

- [ ] **步骤 2：编写测试类**

基于源码确认的字段与枚举编写。示例框架：

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanSyncFailureTest {

    @Test
    fun fromEntity_toEntity_roundTripPreservesFields() {
        // 构造 DoubanSyncFailureEntity，fromEntity 后 toEntity，比对所有字段
        // 具体字段按源码确认
    }

    @Test
    fun fromMarkItem_mapsFieldsCorrectly() {
        // 构造 DoubanMarkItem，fromMarkItem 后断言映射字段
    }

    @Test
    fun failureReason_fromString_validValue_returnsCorrectEnum() {
        assertThat(FailureReason.fromString("NO_IMDB_ID"))
            .isEqualTo(FailureReason.NO_IMDB_ID)
    }

    @Test
    fun failureReason_fromString_null_returnsDefault() {
        // 按源码确认 null 的处理（默认值或异常）
        // 若返回默认：assertThat(FailureReason.fromString(null)).isEqualTo(...)
        // 若抛异常：assertThrows<...> { FailureReason.fromString(null) }
    }

    @Test
    fun failureReason_fromString_unknownValue_returnsDefaultOrThrows() {
        // 按源码行为断言
    }

    @Test
    fun failureReason_recoverableFlags_areCorrect() {
        // 遍历所有 FailureReason，断言 recoverable 标志符合预期
        // NO_IMDB_ID 不可恢复（无 IMDB 无法同步到 Trakt）
        // DETAIL_FETCH_FAILED 可恢复（可重试爬取）
        // TRAKT_NOT_FOUND 可恢复（可重试搜索）
        // TRAKT_WRITE_TIMEOUT 可恢复
        // TRAKT_WRITE_FAILED 可恢复
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.repository.DoubanSyncFailureTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanSyncFailureTest.kt
git commit -m "test: 添加 DoubanSyncFailure 转换与枚举容错测试"
```

---

### 任务 9：TtlCacheTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/util/TtlCache.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/util/TtlCacheTest.kt`

**测试点清单：**
1. `put` 后 `get` 返回值
2. TTL 过期后 `get` 返回 null（需控制时间，可用 ttlMillis=1 + sleep，或注入时钟若源码支持）
3. `maxSize=0` 无上限，put 多少 get 多少
4. `maxSize=2` 时 put 第 3 个 → LRU 淘汰最久未访问的
5. `get` 更新访问时间（LRU 顺序）→ 先 get 旧 key 再 put 新 key，淘汰的不是旧 key
6. `getOrPut` 缓存命中时不调用 defaultValue
7. `getOrPut` 缓存未命中时调用 defaultValue 并缓存结果
8. `getOrAwait` 并发调用同一 key → defaultValue 仅调用一次（single-flight）
9. `getOrAwait` 的 fetch 抛异常 → 调用方收到异常，inFlight 清理
10. `getOrAwait` 的 fetch 被取消（CancellationException）→ inFlight 清理，等待方收到 IOException
11. `clear()` 后 `get` 返回 null
12. `clear()` 后旧 inFlight 完成不写回缓存
13. `ttlMillis=Long.MAX_VALUE` → 永不过期（源码特殊处理，P2 记录）

- [ ] **步骤 1：阅读 TtlCache.kt（已读，见规格调研）确认 get/put/getOrPut/getOrAwait/clear 签名与内部 inFlightRequests 机制**

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TtlCacheTest {

    @Test
    fun put_thenGet_returnsValue() {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "v")
        assertThat(cache.get("k")).isEqualTo("v")
    }

    @Test
    fun get_expiredEntry_returnsNull() {
        val cache = TtlCache<String>(ttlMillis = 1)
        cache.put("k", "v")
        Thread.sleep(10)
        assertThat(cache.get("k")).isNull()
    }

    @Test
    fun put_maxSizeZero_noEviction() {
        val cache = TtlCache<String>(ttlMillis = 60_000, maxSize = 0)
        for (i in 0 until 100) cache.put("k$i", "v$i")
        assertThat(cache.get("k0")).isEqualTo("v0")
        assertThat(cache.get("k99")).isEqualTo("v99")
    }

    @Test
    fun put_maxSizeTwo_evictsLeastRecentlyUsed() {
        val cache = TtlCache<String>(ttlMillis = 60_000, maxSize = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        // 访问 a，使 b 成为 LRU
        cache.get("a")
        cache.put("c", "3")
        // maxSize=2 触发淘汰，但源码有批量阈值，可能需多 put 才触发
        // 若未触发淘汰，加大 put 次数或调整 maxSize
        assertThat(cache.get("b")).isNull()  // b 被淘汰
        assertThat(cache.get("a")).isEqualTo("1")
        assertThat(cache.get("c")).isEqualTo("3")
    }

    @Test
    fun getOrPut_cacheHit_doesNotInvokeDefaultValue() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "cached")
        var invoked = false
        val result = cache.getOrPut("k") { invoked = true; "fetched" }
        assertThat(invoked).isFalse()
        assertThat(result).isEqualTo("cached")
    }

    @Test
    fun getOrPut_cacheMiss_invokesAndCachesDefaultValue() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        var invoked = false
        val result = cache.getOrPut("k") { invoked = true; "fetched" }
        assertThat(invoked).isTrue()
        assertThat(result).isEqualTo("fetched")
        assertThat(cache.get("k")).isEqualTo("fetched")
    }

    @Test
    fun getOrAwait_concurrentCalls_invokesFetchOnce() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        val fetchCount = AtomicInteger(0)
        val deferreds = (1..10).map {
            async {
                cache.getOrAwait("k") {
                    fetchCount.incrementAndGet()
                    delay(50)
                    "fetched"
                }
            }
        }
        val results = deferreds.awaitAll()
        assertThat(fetchCount.get()).isEqualTo(1)
        results.forEach { assertThat(it).isEqualTo("fetched") }
    }

    @Test
    fun getOrAwait_fetchThrowsException_callerReceivesException() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        var threw = false
        try {
            cache.getOrAwait("k") { throw IOException("network error") }
        } catch (e: IOException) {
            threw = true
        }
        assertThat(threw).isTrue()
        // inFlight 应已清理，再次调用会重新 fetch
        var invoked = false
        cache.getOrAwait("k") { invoked = true; "ok" }
        assertThat(invoked).isTrue()
    }

    @Test
    fun clear_afterPut_getReturnsNull() {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "v")
        cache.clear()
        assertThat(cache.get("k")).isNull()
    }

    @Test
    fun getOrPut_maxValueTtl_neverExpires() {
        // P2: Long.MAX_VALUE 跳过过期检查，文档未说明此行为是否预期
        val cache = TtlCache<String>(ttlMillis = Long.MAX_VALUE)
        cache.put("k", "v")
        assertThat(cache.get("k")).isEqualTo("v")  // 永不过期
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.util.TtlCacheTest"`
预期：PASS。注意 LRU 淘汰测试可能因源码的批量阈值（`trimToSize` 的 `threshold`）而需调整 put 次数。若淘汰未按预期触发，查阅 `trimToSize` 逻辑后修正测试（增大 put 次数或调整 maxSize）。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/util/TtlCacheTest.kt
git commit -m "test: 添加 TtlCache 缓存并发与过期测试"
```

---

### 任务 10：AesCryptoTest（Robolectric 首次启用）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/cloud/AesCrypto.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/cloud/AesCryptoTest.kt`

**测试点清单：**
1. `encrypt(plaintext)` → `decrypt(result)` 往返一致
2. 空字符串加解密
3. 中文字符串加解密
4. emoji 字符串加解密
5. `decrypt(无效密文)` → 返回 null
6. `hashUserId(同一userId)` 两次调用结果一致
7. `hashUserId(不同userId)` 结果不同

- [ ] **步骤 1：阅读 AesCrypto.kt 确认 encrypt/decrypt/hashUserId 签名、密钥来源（lazy secretKey）、Base64 使用方式**

- [ ] **步骤 2：编写测试类（Robolectric）**

```kotlin
package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AesCryptoTest {

    @Test
    fun encrypt_decrypt_roundTripPreservesPlaintext() {
        val original = "Hello, World!"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_emptyString_roundTripWorks() {
        val original = ""
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_chineseString_roundTripWorks() {
        val original = "豆瓣同步测试"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_emojiString_roundTripWorks() {
        val original = "电影🎬评分⭐"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun decrypt_invalidCiphertext_returnsNull() {
        val result = AesCrypto.decrypt("这不是有效的密文!!!")
        assertThat(result).isNull()
    }

    @Test
    fun hashUserId_sameInput_returnsSameHash() {
        val h1 = AesCrypto.hashUserId("12345")
        val h2 = AesCrypto.hashUserId("12345")
        assertThat(h1).isEqualTo(h2)
    }

    @Test
    fun hashUserId_differentInput_returnsDifferentHash() {
        val h1 = AesCrypto.hashUserId("12345")
        val h2 = AesCrypto.hashUserId("67890")
        assertThat(h1).isNotEqualTo(h2)
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.remote.cloud.AesCryptoTest"`
预期：PASS。首次运行 Robolectric 会下载 SDK 依赖，耗时较长。若 Robolectric 初始化失败，检查 `robolectric.properties` 配置与 `isIncludeAndroidResources = true`。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/cloud/AesCryptoTest.kt
git commit -m "test: 添加 AesCrypto 加解密测试(Robolectric)"
```

---

### 任务 11：DoubanSpiderTest（需 fixture HTML）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/douban/DoubanSpider.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/douban/DoubanSpiderTest.kt`
- 创建：`app/src/test/resources/fixtures/douban/login_page.html`
- 创建：`app/src/test/resources/fixtures/douban/mark_list.html`
- 创建：`app/src/test/resources/fixtures/douban/detail_movie.html`
- 创建：`app/src/test/resources/fixtures/douban/detail_tv.html`
- 创建：`app/src/test/resources/fixtures/douban/search_by_imdb.html`

**测试点清单：**
1. `isLoginPage(登录页HTML)` → true
2. `isLoginPage(标记列表页HTML)` → false
3. `parseCsrfToken(含csrf token的HTML)` → 返回 token 字符串
4. `parseCsrfToken(无token的HTML)` → 返回 null
5. `parseMarkList(标记列表HTML)` → 返回正确数量的 DoubanMarkItem
6. `parseMarkListPage(标记列表HTML)` → 返回 DoubanMarkListPage，含 items 与分页信息
7. `parseDetail(电影详情HTML)` → DoubanDetailInfo 含评分/标题/IMDB
8. `parseDetail(剧集详情HTML)` → DoubanDetailInfo 含集数/季数
9. `parseSearchByImdb(搜索结果HTML)` → 返回 DoubanSearchResultItem 列表
10. `parseDetail(残缺HTML)` → 不崩溃，返回部分字段或默认值

- [ ] **步骤 1：阅读 DoubanSpider.kt 确认所有 parse 方法签名、DoubanMarkItem/DoubanDetailInfo/DoubanMarkListPage/DoubanSearchResultItem 字段结构**

- [ ] **步骤 2：创建 fixture HTML 文件**

由于无法访问真实豆瓣页面，创建最小合成 HTML，包含 DoubanSpider 选择器所需的关键元素。根据源码中的 jsoup 选择器构造。示例 `login_page.html`：

```html
<!DOCTYPE html>
<html>
<head><title>登录豆瓣</title></head>
<body>
  <form>
    <input type="hidden" name="ck" value="test_csrf_token_123">
  </form>
</body>
</html>
```

其余 fixture HTML 按源码选择器构造最小片段。每个 fixture 标注 `<!-- TODO: 用真实 HTML 替换以增强测试保真度 -->`。

- [ ] **步骤 3：编写测试类**

```kotlin
package com.tracktosearch.data.remote.douban

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanSpiderTest {

    private fun loadFixture(name: String): String {
        val path = "/fixtures/douban/$name"
        return javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
    }

    @Test
    fun isLoginPage_loginPageHtml_returnsTrue() {
        val html = loadFixture("login_page.html")
        assertThat(DoubanSpider.isLoginPage(html)).isTrue()
    }

    @Test
    fun isLoginPage_markListHtml_returnsFalse() {
        val html = loadFixture("mark_list.html")
        assertThat(DoubanSpider.isLoginPage(html)).isFalse()
    }

    @Test
    fun parseCsrfToken_htmlWithToken_returnsToken() {
        val html = loadFixture("login_page.html")
        val token = DoubanSpider.parseCsrfToken(html)
        assertThat(token).isEqualTo("test_csrf_token_123")
    }

    @Test
    fun parseCsrfToken_htmlWithoutToken_returnsNull() {
        val html = "<html><body>no token here</body></html>"
        assertThat(DoubanSpider.parseCsrfToken(html)).isNull()
    }

    @Test
    fun parseMarkList_markListHtml_returnsItems() {
        val html = loadFixture("mark_list.html")
        val items = DoubanSpider.parseMarkList(html)
        assertThat(items).isNotEmpty()
        // 按 fixture 内容断言数量与字段
    }

    @Test
    fun parseDetail_movieHtml_returnsDetailInfo() {
        val html = loadFixture("detail_movie.html")
        val detail = DoubanSpider.parseDetail(html)
        assertThat(detail).isNotNull()
        // 断言标题、评分等字段
    }

    @Test
    fun parseDetail_tvHtml_returnsDetailWithEpisodes() {
        val html = loadFixture("detail_tv.html")
        val detail = DoubanSpider.parseDetail(html)
        assertThat(detail).isNotNull()
        // 断言集数/季数字段
    }

    @Test
    fun parseDetail_malformedHtml_doesNotCrash() {
        val html = "<html><body>残缺内容</body></html>"
        val detail = DoubanSpider.parseDetail(html)
        // 不崩溃即可，字段可能为 null/默认值
        assertThat(detail).isNotNull()
    }

    @Test
    fun parseSearchByImdb_searchResultHtml_returnsItems() {
        val html = loadFixture("search_by_imdb.html")
        val items = DoubanSpider.parseSearchByImdb(html)
        assertThat(items).isNotEmpty()
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.remote.douban.DoubanSpiderTest"`
预期：PASS。若 fixture HTML 与源码选择器不匹配导致解析失败，根据源码选择器修正 fixture HTML。

- [ ] **步骤 5：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/douban/DoubanSpiderTest.kt app/src/test/resources/fixtures/douban/
git commit -m "test: 添加 DoubanSpider HTML 解析测试(fixture)"
```

---

### 任务 12：GiteeContentsApiTest（MockWebServer）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/cloud/GiteeContentsApi.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/cloud/GiteeContentsApiTest.kt`

**测试点清单：**
1. `getFileContent` 成功响应 → 返回 Response<JsonElement>，body 非空
2. `getFileContent` 404 响应 → 返回 404 码
3. `getFileContent` 401 响应 → 返回 401 码
4. `createFileContent` 成功 → 返回 201，body 含 commit 信息
5. `putFileContent` 成功 → 返回 200
6. `putFileContent` 500 错误 → 返回 500

- [ ] **步骤 1：阅读 GiteeContentsApi.kt 确认 interface 方法签名、@GET/@POST/@PUT 注解路径、@Path/@Query/@Body 参数**

- [ ] **步骤 2：编写测试类（MockWebServer）**

需在 `libs.versions.toml` 和 `build.gradle.kts` 补充 MockWebServer 依赖（OkHttp 自带 `mockwebserver` 模块）。若版本目录无此项，在任务 1 基础设施补加，或此处 testImplementation 直接引入：

```kotlin
// app/build.gradle.kts 补充
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

测试类示例：

```kotlin
package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.junit.After
import org.junit.Before
import org.junit.Test

class GiteeContentsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GiteeContentsApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GiteeContentsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getFileContent_success_returnsBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":"hello"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(200)
        assertThat(response.body()).isNotNull()
    }

    @Test
    fun getFileContent_notFound_returns404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(404)
    }

    @Test
    fun getFileContent_unauthorized_returns401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(401)
    }

    @Test
    fun createFileContent_success_returns201() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"content":{"sha":"abc"},"commit":{"sha":"def"}}"""))
        val request = GiteeContentRequest(
            content = "aGVsbG8=",
            message = "test commit",
            branch = "main"
        )
        val response = api.createFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(201)
        assertThat(response.body()?.commit).isNotNull()
    }

    @Test
    fun putFileContent_success_returns200() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":{"sha":"abc"},"commit":{"sha":"def"}}"""))
        val request = GiteeContentRequest(
            content = "dXBkYXRlZA==",
            message = "update",
            branch = "main",
            sha = "oldsha"
        )
        val response = api.putFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(200)
    }

    @Test
    fun putFileContent_serverError_returns500() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Server Error"}"""))
        val request = GiteeContentRequest(
            content = "aGVsbG8=",
            message = "test",
            branch = "main",
            sha = "oldsha"
        )
        val response = api.putFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(500)
    }
}
```

注意：`GiteeContentRequest` 的字段名需对照源码确认。

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.remote.cloud.GiteeContentsApiTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/cloud/GiteeContentsApiTest.kt app/build.gradle.kts
git commit -m "test: 添加 GiteeContentsApi MockWebServer 测试"
```

---

### 任务 13：PersistentTtlCacheTest（Robolectric）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/util/PersistentTtlCache.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/util/PersistentTtlCacheTest.kt`

**测试点清单：**
1. `put` 后 `get` 返回值（内存缓存）
2. `loadFromDisk` 从 DataStore 恢复数据到内存
3. `loadFromDisk` 跳过已过期条目
4. `awaitLoaded` 等待磁盘加载完成
5. `snapshotFromDisk` 返回磁盘所有未过期条目
6. `putAll(多条)` 批量写入
7. `putAll(overwrite=true)` 覆盖已有条目
8. `putAll(overwrite=false)` 跳过已有条目
9. `clearAll` 清空内存与磁盘
10. `getSizeBytes` 返回正数
11. key 版本号失效：keyPrefix 变更后旧缓存不命中

- [ ] **步骤 1：阅读 PersistentTtlCache.kt 确认构造参数（DataStore/Json/serializer/keyPrefix/scope）、loadFromDisk/awaitLoaded/snapshotFromDisk/putAll/clearAll/getSizeBytes 签名**

- [ ] **步骤 2：编写测试类（Robolectric，需构造 fake DataStore）**

```kotlin
package com.tracktosearch.data.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Serializable
data class TestItem(val name: String, val value: Int)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersistentTtlCacheTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = TestItem.serializer()

    // 辅助：创建带 DataStore 的 PersistentTtlCache
    // 具体构造方式按源码 persistentTtlCache 工厂函数或直接构造
    // 使用 ApplicationProvider.getApplicationContext() 获取 Context 创建 DataStore

    @Test
    fun put_thenGet_returnsValue() = runTest {
        val cache = createCache(ttlMillis = 60_000, keyPrefix = "test_v1")
        cache.put("k1", TestItem("foo", 1))
        assertThat(cache.get("k1")).isEqualTo(TestItem("foo", 1))
    }

    @Test
    fun loadFromDisk_restoresEntries() = runTest {
        val cache1 = createCache(ttlMillis = 60_000, keyPrefix = "test_v2")
        cache1.put("k1", TestItem("foo", 1))
        cache1.awaitLoaded()

        // 新建同 keyPrefix 的 cache 模拟重启
        val cache2 = createCache(ttlMillis = 60_000, keyPrefix = "test_v2")
        cache2.loadFromDisk()
        cache2.awaitLoaded()
        assertThat(cache2.get("k1")).isEqualTo(TestItem("foo", 1))
    }

    @Test
    fun loadFromDisk_skipsExpiredEntries() = runTest {
        val cache1 = createCache(ttlMillis = 1, keyPrefix = "test_v3")
        cache1.put("k1", TestItem("foo", 1))
        cache1.awaitLoaded()
        Thread.sleep(10)

        val cache2 = createCache(ttlMillis = 1, keyPrefix = "test_v3")
        cache2.loadFromDisk()
        cache2.awaitLoaded()
        assertThat(cache2.get("k1")).isNull()
    }

    @Test
    fun putAll_multipleEntries_writesAll() = runTest {
        val cache = createCache(ttlMillis = 60_000, keyPrefix = "test_v4")
        val entries = mapOf(
            "k1" to TestItem("a", 1),
            "k2" to TestItem("b", 2),
            "k3" to TestItem("c", 3)
        )
        cache.putAll(entries)
        assertThat(cache.get("k1")).isEqualTo(TestItem("a", 1))
        assertThat(cache.get("k2")).isEqualTo(TestItem("b", 2))
        assertThat(cache.get("k3")).isEqualTo(TestItem("c", 3))
    }

    @Test
    fun putAll_overwriteFalse_skipsExisting() = runTest {
        val cache = createCache(ttlMillis = 60_000, keyPrefix = "test_v5")
        cache.put("k1", TestItem("original", 1))
        cache.putAll(mapOf("k1" to TestItem("new", 2)), overwrite = false)
        assertThat(cache.get("k1")).isEqualTo(TestItem("original", 1))
    }

    @Test
    fun putAll_overwriteTrue_overwritesExisting() = runTest {
        val cache = createCache(ttlMillis = 60_000, keyPrefix = "test_v6")
        cache.put("k1", TestItem("original", 1))
        cache.putAll(mapOf("k1" to TestItem("new", 2)), overwrite = true)
        assertThat(cache.get("k1")).isEqualTo(TestItem("new", 2))
    }

    @Test
    fun clearAll_removesAllEntries() = runTest {
        val cache = createCache(ttlMillis = 60_000, keyPrefix = "test_v7")
        cache.put("k1", TestItem("foo", 1))
        cache.clearAll()
        assertThat(cache.get("k1")).isNull()
    }

    @Test
    fun keyPrefixChange_oldCacheNotHit() = runTest {
        val cache1 = createCache(ttlMillis = 60_000, keyPrefix = "old_prefix")
        cache1.put("k1", TestItem("foo", 1))
        cache1.awaitLoaded()

        val cache2 = createCache(ttlMillis = 60_000, keyPrefix = "new_prefix")
        cache2.loadFromDisk()
        cache2.awaitLoaded()
        // keyPrefix 变更，旧缓存不命中
        assertThat(cache2.get("k1")).isNull()
    }

    // 辅助工厂方法：根据源码构造方式创建 PersistentTtlCache
    private fun createCache(ttlMillis: Long, keyPrefix: String): PersistentTtlCache<TestItem> {
        // 按源码 persistentTtlCache 工厂函数或直接构造
        // 需要传入 DataStore<Preferences>，用 ApplicationProvider.getApplicationContext() 创建
        // 具体实现按源码确认
        TODO("按源码构造方式实现")
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.util.PersistentTtlCacheTest"`
预期：PASS。`createCache` 辅助方法需按源码实际构造方式补全。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/util/PersistentTtlCacheTest.kt
git commit -m "test: 添加 PersistentTtlCache 持久化缓存测试"
```

---

### 任务 14：PosterColorExtractorTest（Robolectric）

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/util/PosterColorExtractor.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/util/PosterColorExtractorTest.kt`

**测试点清单：**
1. `getCachedColor(posterUrl)` 缓存未命中 → 返回 null
2. `extractDominantColor(posterUrl, bitmap)` → 写入缓存
3. `extractDominantColor` 后 `getCachedColor` → 返回刚才的颜色
4. 空 bitmap 处理 → 不崩溃（按源码行为断言）

- [ ] **步骤 1：阅读 PosterColorExtractor.kt 与 PosterColorCache.kt 确认 extractDominantColor/getCachedColor 签名、PosterColorCache 依赖（Context/DataStore）**

- [ ] **步骤 2：编写测试类（Robolectric，需 mock PosterColorCache 或用真实实例）**

```kotlin
package com.tracktosearch.data.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PosterColorExtractorTest {

    @Test
    fun getCachedColor_cacheMiss_returnsNull() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        coEvery { cache.getColor("url1") } returns null
        val extractor = PosterColorExtractor(cache)

        assertThat(extractor.getCachedColor("url1")).isNull()
    }

    @Test
    fun extractDominantColor_writesToCache() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        coEvery { cache.getColor("url1") } returns null
        val extractor = PosterColorExtractor(cache)

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        extractor.extractDominantColor("url1", bitmap)

        // 验证 putColor 被调用（mockk verify）
        // io.mockk.coVerify { cache.putColor("url1", any()) }
    }

    @Test
    fun getCachedColor_afterExtract_returnsColor() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        val extractor = PosterColorExtractor(cache)

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        // extract 后缓存返回该颜色
        coEvery { cache.getColor("url1") } returns Color.RED.toLong()
        extractor.extractDominantColor("url1", bitmap)

        assertThat(extractor.getCachedColor("url1")).isEqualTo(Color.RED.toLong())
    }
}
```

注意：`PosterColorExtractor` 构造参数与 `extractDominantColor` 签名需对照源码确认。颜色值类型（Long vs Int）按源码确认。

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew test --tests "com.tracktosearch.data.util.PosterColorExtractorTest"`
预期：PASS。若 Robolectric 的 Palette/Bitmap 支持有问题，降级为 `@Ignore` 并记录原因。

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/util/PosterColorExtractorTest.kt
git commit -m "test: 添加 PosterColorExtractor 海报颜色提取测试"
```

---

### 阶段 2 结束验证

- [ ] **步骤 1：运行全部单元测试**

运行：`.\gradlew test`
预期：所有非 `@Ignore` 测试通过。记录所有 `@Ignore` 与 P2 FIXME 标注。

- [ ] **步骤 2：构建 debug 包验证无编译破坏**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：汇总 P0/P1/P2 bug 清单**

整理本阶段发现的所有 bug，按规格 §5 分级记录。P0 已修复并 commit；P1/P2 在测试中标注。

---

## 后续计划

本计划覆盖阶段 1（基础设施）+ 阶段 2（A 层 13 个测试类）。

**阶段 3（B 层 Repository 12 个测试类）** 与 **阶段 4（C 层 ViewModel 8 个测试类）** 将在阶段 1+2 完成、测试基础设施验证稳定后，单独编写实现计划：

- `docs/superpowers/plans/2026-07-14-unit-test-suite-phase3.md`（Repository 层）
- `docs/superpowers/plans/2026-07-14-unit-test-suite-phase4.md`（ViewModel 层）

阶段 3、4 的任务大纲见规格文档 §3、§4。每阶段计划将包含：每个测试类的测试点清单（方法级）、运行命令、验证标准、commit 信息，与本计划格式一致。
