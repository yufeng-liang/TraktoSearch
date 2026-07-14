# 单元测试套件实现计划（阶段 3 - Repository 层）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 完成 B 层 12 个 Repository 单元测试，验证业务逻辑、缓存命中、降级链路、错误处理。

**架构：** 用 mockk mock Repository 的依赖（DAO/API/Storage），验证 Repository 自身的业务逻辑。StateFlow 进度用 turbine 验证。Robolectric 用于涉及 Context/BuildConfig/WakeLock 的测试。私有方法通过 public 间接覆盖。

**技术栈：** JUnit 4 + mockk 1.13.13 + turbine 1.2.0 + truth 1.4.4 + Robolectric 4.13 + coroutines-test 1.11.0

**规格依据：** [2026-07-14-unit-test-suite-design.md](file:///f:/trae-project/docs/superpowers/specs/2026-07-14-unit-test-suite-design.md) §3

**前置条件：** 阶段 1+2 已完成（commit `0b790c6` 及之前），测试基础设施可用，P0 LRU bug 已修复。

**测试代码编写原则：** 每个测试类给出测试点清单（方法级，含预期行为）。执行者需先用 Read 工具阅读被测 Repository 源码，确认精确的方法签名、构造参数、依赖列表后编写测试。命名规范：`被测行为_条件_预期结果`。断言统一用 `assertThat()`（truth）。mockk 用 `relaxed = true` + 精确 `coEvery` 桩。

**Bug 处理：** 遵循规格 §5。P0 立即修复并独立 commit（`fix: <类名> <简述>`）；P1 用 `@Ignore("待修复: ...")` 标注；P2 写 characterization test + `// FIXME` 注释。

**测试命令：** `.\gradlew testDebugUnitTest --tests "<完整类名>"`（AGP 9.x 用 testDebugUnitTest 而非 test）

**commit 策略：** 每 3-4 个测试类一次 commit，`test: 添加 Repository 层单元测试(<类名列表>)`

---

## 文件结构

**创建（12 个测试类）：**
- `app/src/test/java/com/tracktosearch/data/repository/UserReviewRepositoryTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/RatingsRepositoryTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/cloud/CloudDetailsPoolManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/cloud/CloudFailureSyncManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/cloud/CloudPersonalSyncManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanRetryManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/UpdateRepositoryTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanFailureExporterTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/WeatherRepositoryTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanBatchRemovalManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanTraktStatusConsistencyCheckerTest.kt`
- `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerTest.kt`

---

### 任务 1：UserReviewRepositoryTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/UserReviewRepository.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/UserReviewRepositoryTest.kt`

**测试点清单：**
1. `insert(review)` → 调用 dao.insert 并转发
2. `getReviewsByType(type)` → 调用 dao 查询，返回 Flow 正确映射
3. `getReviewById(id)` → 命中返回 Review，不存在返回 null
4. `delete(id)` → 调用 dao.delete，不存在的 id 不抛异常
5. `update(review)` → 调用 dao.update
6. 空表场景：getReviewsByType 返回空 Flow

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `UserReviewRepository.kt`，确认：
- 构造参数（UserReviewDao dao，是否还有其他依赖）
- 所有 public 方法签名（insert/getReviewsByType/getReviewById/delete/update，是否 suspend）
- UserReview 和 UserReviewEntity 的字段结构（用 Grep 找到定义并 Read）

- [ ] **步骤 2：编写测试类**

用 mockk mock UserReviewDao。示例框架（按源码实际签名修正）：

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Test

class UserReviewRepositoryTest {

    private val dao = mockk<UserReviewDao>(relaxed = true)
    private val repo = UserReviewRepository(dao)

    @Test
    fun insert_callsDaoInsert() = runTest {
        val review = UserReview(/* 按源码构造 */)
        coEvery { dao.insert(any()) } returns 1L

        repo.insert(review)

        coVerify { dao.insert(any()) }
    }

    @Test
    fun getReviewsByType_returnsMappedFlow() = runTest {
        val entities = listOf<UserReviewEntity>(/* 构造 */)
        coEvery { dao.getByType(any()) } returns flowOf(entities)

        val result = repo.getReviewsByType("movie").first()

        assertThat(result).hasSize(entities.size)
    }

    @Test
    fun getReviewsByType_emptyTable_returnsEmptyFlow() = runTest {
        coEvery { dao.getByType(any()) } returns flowOf(emptyList())

        val result = repo.getReviewsByType("movie").first()

        assertThat(result).isEmpty()
    }

    @Test
    fun getReviewById_exists_returnsReview() = runTest {
        val entity = UserReviewEntity(/* 构造 */)
        coEvery { dao.getById(1) } returns entity

        val result = repo.getReviewById(1)

        assertThat(result).isNotNull()
    }

    @Test
    fun getReviewById_notExists_returnsNull() = runTest {
        coEvery { dao.getById(99) } returns null

        val result = repo.getReviewById(99)

        assertThat(result).isNull()
    }

    @Test
    fun delete_callsDaoDelete() = runTest {
        repo.delete(1)

        coVerify { dao.delete(1) }
    }

    @Test
    fun delete_nonExistentId_doesNotThrow() = runTest {
        // relaxed mock 不会抛异常，验证可安全调用
        repo.delete(999)
    }

    @Test
    fun update_callsDaoUpdate() = runTest {
        val review = UserReview(/* 构造 */)

        repo.update(review)

        coVerify { dao.update(any()) }
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.UserReviewRepositoryTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/UserReviewRepositoryTest.kt
git commit -m "test: 添加 UserReviewRepository 测试"
```

---

### 任务 2：RatingsRepositoryTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/RatingsRepository.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/RatingsRepositoryTest.kt`

**测试点清单：**
1. `getRating(imdbId)` 缓存命中 → 直接返回缓存，不调用 OMDB API
2. `getRating(imdbId)` 缓存未命中 → 调用 OMDB API，缓存结果后返回
3. OMDB API 失败 → 降级用 tmdbRating（若 OMDB 返回 null 或抛异常）
4. imdbId 为空 → 返回 null 或默认值，不调用 API
5. Flow 多次收集去重（同一 imdbId 不重复请求）
6. LRU 缓存淘汰（若 RatingsRepository 内部有 LruCache）

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `RatingsRepository.kt`，确认：
- 构造参数（OmdbApiService、RemoteConfigProvider、可能还有 TmdbRepository 或 LruCache）
- `getRating` 方法签名（suspend? 返回类型 Flow<Rating>? 或 Rating?）
- 缓存机制（LruCache? TtlCache?）
- 降级逻辑（OMDB 失败时如何获取 tmdbRating）
- OmdbApiService 接口定义（用 Grep 找到）

- [ ] **步骤 2：编写测试类**

用 mockk mock OmdbApiService 和 RemoteConfigProvider。需 Robolectric（若依赖 BuildConfig.OMDB_API_KEY）。

```kotlin
package com.tracktosearch.data.repository

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
class RatingsRepositoryTest {

    private val omdbApi = mockk<OmdbApiService>(relaxed = true)
    private val remoteConfig = mockk<RemoteConfigProvider>(relaxed = true)
    private val repo = RatingsRepository(omdbApi, remoteConfig /* 按源码补全其他依赖 */)

    @Test
    fun getRating_cacheHit_doesNotCallApi() = runTest {
        // 第一次调用填充缓存
        coEvery { omdbApi.getRating(any(), any()) } returns omdbResponse(rating = "8.5")
        repo.getRating("tt0111169")
        // 第二次调用应命中缓存
        coEvery { omdbApi.getRating(any(), any()) } throws AssertionError("不应再调用API")

        val result = repo.getRating("tt0111169")

        assertThat(result).isNotNull()
    }

    @Test
    fun getRating_cacheMiss_callsApiAndCaches() = runTest {
        coEvery { omdbApi.getRating(any(), any()) } returns omdbResponse(rating = "8.5")

        val result = repo.getRating("tt0111169")

        assertThat(result).isNotNull()
        // 按源码返回类型断言评分值
    }

    @Test
    fun getRating_omdbFails_fallsBackToTmdbRating() = runTest {
        coEvery { omdbApi.getRating(any(), any()) } throws java.io.IOException("network error")
        // 按源码确认降级逻辑：是否调用 TmdbRepository 或 remoteConfig 获取 tmdbRating

        val result = repo.getRating("tt0111169")

        // 断言降级返回的评分（可能为 null 或 tmdbRating 值）
    }

    @Test
    fun getRating_emptyImdbId_returnsNull() = runTest {
        val result = repo.getRating("")

        assertThat(result).isNull()
        coEvery { omdbApi.getRating(any(), any()) } throws AssertionError("空 imdbId 不应调用API")
    }

    // 辅助方法：构造 OMDB 响应
    private fun omdbResponse(rating: String) = /* 按源码 OmdbResponse 结构构造 */
        TODO("按源码构造")
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.RatingsRepositoryTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/RatingsRepositoryTest.kt
git commit -m "test: 添加 RatingsRepository 缓存与降级测试"
```

---

### 任务 3：CloudDetailsPoolManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/cloud/CloudDetailsPoolManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/cloud/CloudDetailsPoolManagerTest.kt`

**测试点清单：**
1. `uploadDetails(条目)` → 调用 GiteeContentsApi 上传，AesCrypto 加密内容
2. `uploadDetails(批量条目)` → 批量上传，合并到云端文件
3. `downloadDetail(doubanId)` → 命中云端返回详情，未命中返回 null
4. `fetchAndMergeToLocal(doubanId)` → 从云端拉取并合并到本地缓存
5. 合并去重：云端与本地都有同一条目时，非 null 字段覆盖
6. AesCrypto 加解密往返：上传的内容加密，下载时解密

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `CloudDetailsPoolManager.kt`，确认：
- 构造参数（GiteeContentsApi、AesCrypto 相关、可能还有 Json/scope）
- uploadDetails/downloadDetail/fetchAndMergeToLocal 方法签名（suspend?）
- 云端文件路径规则（如 `details_pool/${doubanId}.json`）
- 加密方式（整体加密还是字段加密）
- 合并逻辑（字段级合并规则）

- [ ] **步骤 2：编写测试类**

用 mockk mock GiteeContentsApi。AesCrypto 用真实实例（任务 10 已验证可用）。

```kotlin
package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudDetailsPoolManagerTest {

    private val giteeApi = mockk<GiteeContentsApi>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val manager = CloudDetailsPoolManager(giteeApi, json /* 按源码补全 */)

    @Test
    fun uploadDetails_callsGiteeApiWithEncryptedContent() = runTest {
        val detail = /* 按源码构造 DoubanDetail */
        coEvery { giteeApi.createFileContent(any(), any(), any(), any()) } returns mockk(relaxed = true)

        manager.uploadDetails(detail)

        coVerify { giteeApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun downloadDetail_cloudHit_returnsDecryptedDetail() = runTest {
        val encryptedContent = AesCrypto.encrypt(/* JSON */)
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockk {
            every { isSuccessful } returns true
            every { body() } returns /* JsonElement 含 encryptedContent */
        }

        val result = manager.downloadDetail("123456")

        assertThat(result).isNotNull()
    }

    @Test
    fun downloadDetail_cloudMiss_returnsNull() = runTest {
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockk {
            every { isSuccessful } returns false
            every { code() } returns 404
        }

        val result = manager.downloadDetail("999999")

        assertThat(result).isNull()
    }

    @Test
    fun uploadDetails_batch_mergesToCloudFile() = runTest {
        // 批量上传时合并到现有云端文件
        // 按源码确认合并逻辑
    }

    @Test
    fun fetchAndMergeToLocal_mergesNonNullFields() = runTest {
        // 云端有字段 A/B/C，本地有字段 A/D
        // 合并后应有 A(云端覆盖)/B/C/D
        // 按源码字段级合并规则断言
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.remote.cloud.CloudDetailsPoolManagerTest"`
预期：PASS

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/cloud/CloudDetailsPoolManagerTest.kt
git commit -m "test: 添加 CloudDetailsPoolManager 云端池测试"
```

---

### 任务 4：CloudFailureSyncManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/cloud/CloudFailureSyncManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/cloud/CloudFailureSyncManagerTest.kt`

**测试点清单：**
1. `isLoggedIn` → 依赖 DoubanAuthStorage 的登录状态
2. `uploadIfHasFailures(空列表)` → 跳过上传，不调用 GiteeApi
3. `uploadIfHasFailures(有数据)` → 加密上传到云端
4. `downloadAndMerge` 云端无数据 → 返回 CloudEmpty 结果
5. `downloadAndMerge` 本地更新 → 返回 LocalNewer 结果
6. `downloadAndMerge` 云端有新数据 → 返回 Success，合并到本地
7. AesCrypto 加密往返一致

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `CloudFailureSyncManager.kt`，确认：
- 构造参数（GiteeContentsApi、DoubanSyncFailureDao、DoubanAuthStorage、Json/scope）
- isLoggedIn/uploadIfHasFailures/downloadAndMerge 方法签名
- downloadAndMerge 返回的密封类/枚举（CloudEmpty/LocalNewer/Success）
- 加密与合并逻辑

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CloudFailureSyncManagerTest {

    private val giteeApi = mockk<GiteeContentsApi>(relaxed = true)
    private val failureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val authStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val manager = CloudFailureSyncManager(giteeApi, failureDao, authStorage /* 按源码补全 */)

    @Test
    fun isLoggedIn_returnsAuthStorageState() {
        coEvery { authStorage.isLoggedIn() } returns true
        assertThat(manager.isLoggedIn()).isTrue()
    }

    @Test
    fun uploadIfHasFailures_emptyList_skipsUpload() = runTest {
        manager.uploadIfHasFailures(emptyList())

        coVerify(exactly = 0) { giteeApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun uploadIfHasFailures_withData_uploadsEncrypted() = runTest {
        val failures = listOf<DoubanSyncFailure>(/* 构造 */)

        manager.uploadIfHasFailures(failures)

        coVerify(exactly = 1) { giteeApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun downloadAndMerge_cloudEmpty_returnsCloudEmpty() = runTest {
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockk {
            every { isSuccessful } returns false
            every { code() } returns 404
        }

        val result = manager.downloadAndMerge()

        // 断言返回 CloudEmpty
    }

    @Test
    fun downloadAndMerge_cloudHasNewData_returnsSuccess() = runTest {
        // 云端有数据，本地无或较旧
        // 断言返回 Success 且数据合并到 failureDao
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.remote.cloud.CloudFailureSyncManagerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/cloud/CloudFailureSyncManagerTest.kt
git commit -m "test: 添加 CloudFailureSyncManager 云端失败同步测试"
```

---

### 任务 5：CloudPersonalSyncManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/remote/cloud/CloudPersonalSyncManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/remote/cloud/CloudPersonalSyncManagerTest.kt`

**测试点清单：**
1. `uploadAll` → 上传所有个人数据（3 个 DAO/Storage 数据源）到云端
2. `refreshMetaOnly` → 仅刷新元数据，不上传内容
3. `downloadAndMerge` → 返回 PullResult，合并到本地
4. `metaRefreshMutex` 并发：多次 refreshMetaOnly 互斥，不并发请求
5. AesCrypto 加密往返

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `CloudPersonalSyncManager.kt`，确认：
- 构造参数（GiteeContentsApi + 3 个 DAO/Storage + TraktRepository + Json/scope）
- uploadAll/refreshMetaOnly/downloadAndMerge 方法签名
- PullResult 结构
- metaRefreshMutex 机制（Mutex? synchronized?）

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CloudPersonalSyncManagerTest {

    private val giteeApi = mockk<GiteeContentsApi>(relaxed = true)
    // 按源码补全 3 个 DAO/Storage + TraktRepository mock
    private val manager = CloudPersonalSyncManager(giteeApi /* 按源码补全 */)

    @Test
    fun uploadAll_uploadsAllPersonalData() = runTest {
        coEvery { giteeApi.createFileContent(any(), any(), any(), any()) } returns mockk(relaxed = true)

        manager.uploadAll()

        // 验证上传了多个文件（3 个数据源）
        coVerify(atLeast = 1) { giteeApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun refreshMetaOnly_doesNotUploadContent() = runTest {
        manager.refreshMetaOnly()

        coVerify(exactly = 0) { giteeApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun downloadAndMerge_returnsPullResult() = runTest {
        // 按源码构造云端响应
        val result = manager.downloadAndMerge()

        // 断言 PullResult 结构
    }

    @Test
    fun refreshMetaOnly_concurrentCalls_areMutuallyExclusive() = runTest {
        // 并发调用 refreshMetaOnly，验证互斥
        val deferreds = (1..5).map { async { manager.refreshMetaOnly() } }
        deferreds.awaitAll()
        // 若有并发问题会崩溃或数据竞争
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.remote.cloud.CloudPersonalSyncManagerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/remote/cloud/CloudPersonalSyncManagerTest.kt
git commit -m "test: 添加 CloudPersonalSyncManager 个人数据同步测试"
```

---

### 任务 6：DoubanRetryManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanRetryManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanRetryManagerTest.kt`

**测试点清单：**
1. `refreshRetryState` → 从 DAO 加载失败项，更新 RetryState
2. `batchUpdateMediaType(items)` → 批量更新 mediaType 字段
3. `inferMediaTypeFromDetail(detail)` → 从详情推断 mediaType（movie/show）
4. `batchDeleteFailures(ids)` → 批量删除失败项
5. RetryState 转换：Idle → Loading → Ready/Error

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `DoubanRetryManager.kt`，确认：
- 构造参数（DoubanSyncFailureDao、DoubanSyncManager、CloudDetailsPoolManager 等）
- refreshRetryState/batchUpdateMediaType/inferMediaTypeFromDetail/batchDeleteFailures 方法签名
- RetryState 密封类结构（Idle/Loading/Ready/Error）
- inferMediaTypeFromDetail 的推断逻辑

- [ ] **步骤 2：编写测试类**

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DoubanRetryManagerTest {

    private val failureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val syncManager = mockk<DoubanSyncManager>(relaxed = true)
    private val poolManager = mockk<CloudDetailsPoolManager>(relaxed = true)
    private val retryManager = DoubanRetryManager(failureDao, syncManager, poolManager /* 按源码补全 */)

    @Test
    fun refreshRetryState_loadsFailures_updatesState() = runTest {
        val failures = listOf<DoubanSyncFailureEntity>(/* 构造 */)
        coEvery { failureDao.getAll() } returns failures

        retryManager.refreshRetryState()

        // 验证 RetryState 变为 Ready，含失败项列表
    }

    @Test
    fun batchUpdateMediaType_updatesAllItems() = runTest {
        val items = listOf<Pair<Long, String>>(1L to "movie", 2L to "show")

        retryManager.batchUpdateMediaType(items)

        coVerify(atLeast = 1) { failureDao.updateMediaType(any(), any()) }
    }

    @Test
    fun inferMediaTypeFromDetail_movieDetail_returnsMovie() {
        val detail = /* 构造电影详情 */
        val result = retryManager.inferMediaTypeFromDetail(detail)

        assertThat(result).isEqualTo("movie")
    }

    @Test
    fun inferMediaTypeFromDetail_tvDetail_returnsShow() {
        val detail = /* 构造剧集详情（含集数） */
        val result = retryManager.inferMediaTypeFromDetail(detail)

        assertThat(result).isEqualTo("show")
    }

    @Test
    fun batchDeleteFailures_deletesAllIds() = runTest {
        val ids = listOf(1L, 2L, 3L)

        retryManager.batchDeleteFailures(ids)

        coVerify(atLeast = 1) { failureDao.deleteById(any()) }
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanRetryManagerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanRetryManagerTest.kt
git commit -m "test: 添加 DoubanRetryManager 重试管理测试"
```

---

### 任务 7：UpdateRepositoryTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/UpdateRepository.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/UpdateRepositoryTest.kt`

**测试点清单：**
1. `checkForUpdate(force=false)` 24h 内 → 返回缓存，不调用网络
2. `checkForUpdate(force=true)` → 跳过缓存，调用 GitHub API
3. GitHub API 失败 → 降级到 Gitee API
4. GitHub + Gitee 都失败 → 返回 null 或缓存
5. `isNewerVersion` 矩阵：3段 vs 4段版本号、相同版本、旧版本
6. `sanitizeChangelog` 乱码阈值：问号占比 >30% 返回空
7. `injectDateIntoChangelog` 多格式：created_at 为 ISO/RFC/空
8. `fetchDownloadUrl` 多 APK：选最小或最新

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具完整读取 `UpdateRepository.kt`，确认：
- 构造参数（GitHub API、Gitee API、ChangelogStorage、可能还有 Context for BuildConfig）
- checkForUpdate 方法签名（force 参数、返回类型 UpdateInfo?）
- isNewerVersion/sanitizeChangelog/injectDateIntoChangelog/fetchDownloadUrl 是否私有
- 降级链路逻辑（GitHub → Gitee → 缓存）
- 缓存时间判断（24h 逻辑）

- [ ] **步骤 2：编写测试类**

需 Robolectric（BuildConfig.VERSION_NAME）。用 mockk mock API 和 Storage。

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateRepositoryTest {

    private val gitHubApi = mockk<GitHubApi>(relaxed = true)
    private val giteeApi = mockk<GiteeReleaseApi>(relaxed = true)
    private val changelogStorage = mockk<ChangelogStorage>(relaxed = true)
    private val repo = UpdateRepository(gitHubApi, giteeApi, changelogStorage /* 按源码补全 */)

    @Test
    fun checkForUpdate_within24h_returnsCachedResult() = runTest {
        every { changelogStorage.getLastCheckTimestamp() } returns System.currentTimeMillis() - 23 * 3600_000
        every { changelogStorage.getCachedUpdateInfo() } returns cachedInfo

        val result = repo.checkForUpdate(force = false)

        // 验证未调用网络 API
        coVerify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_forceTrue_callsGitHubApi() = runTest {
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns mockk(relaxed = true)

        repo.checkForUpdate(force = true)

        coVerify(atLeast = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_githubFails_fallsBackToGitee() = runTest {
        coEvery { gitHubApi.getLatestRelease(any(), any()) } throws java.io.IOException()
        coEvery { giteeApi.getLatestRelease(any(), any()) } returns mockk(relaxed = true)

        repo.checkForUpdate(force = true)

        coVerify(atLeast = 1) { giteeApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_allApisFail_returnsNull() = runTest {
        coEvery { gitHubApi.getLatestRelease(any(), any()) } throws java.io.IOException()
        coEvery { giteeApi.getLatestRelease(any(), any()) } throws java.io.IOException()
        every { changelogStorage.getCachedUpdateInfo() } returns null

        val result = repo.checkForUpdate(force = true)

        assertThat(result).isNull()
    }

    // isNewerVersion 矩阵测试（通过 checkForUpdate 间接覆盖）
    @Test
    fun checkForUpdate_newerVersion_returnsHasUpdateTrue() = runTest {
        // mock API 返回 tag_name=v99.0.0，BuildConfig.VERSION_NAME 是当前版本
        // 断言 hasUpdate = true
    }

    @Test
    fun checkForUpdate_sameVersion_returnsHasUpdateFalse() = runTest {
        // mock API 返回 tag_name=当前版本
        // 断言 hasUpdate = false
    }

    // sanitizeChangelog 通过 fetchChangelog 间接覆盖
    @Test
    fun fetchChangelog_garbledText_returnsEmpty() = runTest {
        // mock API 返回含 30%+ 问号的 body
        // 断言 fetchChangelog 返回空
    }

    // injectDateIntoChangelog 通过 checkForUpdate 间接覆盖
    @Test
    fun checkForUpdate_changelogContainsDate() = runTest {
        // mock API 返回 created_at
        // 断言最终 changelog 含日期
    }

    // fetchDownloadUrl 多 APK
    @Test
    fun fetchDownloadUrl_multipleApks_returnsCorrectUrl() = runTest {
        // mock release 含多个 APK asset
        // 断言返回正确的下载 URL（最小或最新，按源码逻辑）
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.UpdateRepositoryTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/UpdateRepositoryTest.kt
git commit -m "test: 添加 UpdateRepository 更新检查与降级测试"
```

---

### 任务 8：DoubanFailureExporterTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanFailureExporter.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanFailureExporterTest.kt`

**测试点清单：**
1. `exportFromFile(failures)` → JSON 序列化正确，含所有字段
2. `importFromFile(json)` → JSON 反序列化，字段一致
3. `importToRoom(items)` → 调用 DAO 批量插入，返回 ImportResult
4. 损坏 JSON → 返回错误 ImportResult，不崩溃
5. 空列表导出 → 有效 JSON（空数组）
6. JSON 往返一致性

注意：FileProvider I/O 部分（Uri 操作）留待 instrumented test，本测试只测 JSON 序列化逻辑。

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `DoubanFailureExporter.kt`，确认：
- 构造参数（DoubanSyncFailureDao、Json）
- exportFromFile/importFromFile/importToRoom 方法签名
- ImportResult 结构（Success/Error/Partial?）
- JSON 序列化格式

- [ ] **步骤 2：编写测试类**

需 Robolectric（Context/Uri）。

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanFailureExporterTest {

    private val failureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val exporter = DoubanFailureExporter(failureDao, json /* 按源码补全 */)

    @Test
    fun exportFromFile_emptyList_returnsValidJson() {
        val result = exporter.exportFromFile(emptyList())

        assertThat(result).contains("[]")
    }

    @Test
    fun exportFromFile_withData_jsonRoundTrip() {
        val failures = listOf<DoubanSyncFailure>(/* 构造 */)
        val exported = exporter.exportFromFile(failures)
        val imported = exporter.importFromFile(exported)

        assertThat(imported).isEqualTo(failures)
    }

    @Test
    fun importFromFile_validJson_returnsItems() {
        val json = """[{"id":1,"imdbId":"tt123"}]"""  // 按源码格式

        val result = exporter.importFromFile(json)

        assertThat(result).isNotEmpty()
    }

    @Test
    fun importFromFile_corruptedJson_returnsErrorOrNull() {
        val result = exporter.importFromFile("{invalid json}")

        // 按源码行为断言：返回空列表或抛异常被捕获
    }

    @Test
    fun importToRoom_validItems_insertsAllAndReturnsResult() = runTest {
        val items = listOf<DoubanSyncFailure>(/* 构造 */)

        val result = exporter.importToRoom(items)

        coVerify(atLeast = 1) { failureDao.insert(any()) }
        // 断言 ImportResult
    }

    @Test
    fun importToRoom_emptyItems_returnsEmptyResult() = runTest {
        val result = exporter.importToRoom(emptyList())

        // 断言返回空结果
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanFailureExporterTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanFailureExporterTest.kt
git commit -m "test: 添加 DoubanFailureExporter JSON 导入导出测试"
```

---

### 任务 9：WeatherRepositoryTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/WeatherRepository.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/WeatherRepositoryTest.kt`

**测试点清单：**
1. `getCurrentWeather(location)` 缓存命中（3h TTL）→ 直接返回，不调用 API
2. `getCurrentWeather(location)` 缓存未命中 → 调用 OpenMeteoApi
3. `getCurrentWeather(null)` → 返回 null，不调用 API
4. 网络失败 → 返回缓存（即使过期）或 null
5. 缓存过期 → 重新请求 API

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `WeatherRepository.kt`，确认：
- 构造参数（OpenMeteoApi、DataStore/缓存、scope）
- getCurrentWeather 方法签名（suspend? 参数类型 Location? 返回 Weather?）
- 缓存机制（3h TTL，DataStore 持久化?）
- 网络失败降级逻辑

- [ ] **步骤 2：编写测试类**

需 Robolectric（Context/DataStore）。

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherRepositoryTest {

    private val openMeteoApi = mockk<OpenMeteoApi>(relaxed = true)
    // 按源码补全缓存依赖
    private val repo = WeatherRepository(openMeteoApi /* 按源码补全 */)

    @Test
    fun getCurrentWeather_nullLocation_returnsNull() = runTest {
        val result = repo.getCurrentWeather(null)

        assertThat(result).isNull()
        coVerify(exactly = 0) { openMeteoApi.getCurrentWeather(any(), any()) }
    }

    @Test
    fun getCurrentWeather_cacheMiss_callsApi() = runTest {
        coEvery { openMeteoApi.getCurrentWeather(any(), any()) } returns mockk(relaxed = true)

        val result = repo.getCurrentWeather(/* location */)

        coVerify(atLeast = 1) { openMeteoApi.getCurrentWeather(any(), any()) }
    }

    @Test
    fun getCurrentWeather_cacheHit_doesNotCallApi() = runTest {
        // 第一次调用填充缓存
        coEvery { openMeteoApi.getCurrentWeather(any(), any()) } returns mockk(relaxed = true)
        repo.getCurrentWeather(/* location */)
        // 第二次应命中缓存
        coEvery { openMeteoApi.getCurrentWeather(any(), any()) } throws AssertionError("不应再调用")

        repo.getCurrentWeather(/* location */)
    }

    @Test
    fun getCurrentWeather_networkFails_returnsCachedOrNull() = runTest {
        coEvery { openMeteoApi.getCurrentWeather(any(), any()) } throws java.io.IOException()

        val result = repo.getCurrentWeather(/* location */)

        // 按源码行为断言：返回缓存或 null
        assertThat(result).isNull()  // 或 isNotNull() 若有缓存
    }

    @Test
    fun getCurrentWeather_expiredCache_refreshesFromApi() = runTest {
        // 填充过期缓存，调用应重新请求 API
        // 按源码缓存过期逻辑实现
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.WeatherRepositoryTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/WeatherRepositoryTest.kt
git commit -m "test: 添加 WeatherRepository 缓存与降级测试"
```

---

### 任务 10：DoubanBatchRemovalManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanBatchRemovalManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanBatchRemovalManagerTest.kt`

**测试点清单：**
1. `startRemoval(items)` → 批量移除，调用 DoubanRepository.removeMark
2. `Semaphore(2)` 并发控制：最多 2 个并发移除
3. 单条失败不影响其他：一条 removeMark 抛异常，其他仍继续
4. `isRunning` 状态：运行中为 true，完成后为 false
5. `resetProgress` → 重置进度 StateFlow
6. WakeLock：acquire/release 正确
7. 未登录豆瓣 → 静默跳过

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `DoubanBatchRemovalManager.kt`，确认：
- 构造参数（DoubanRepository、DoubanAuthStorage、DoubanSyncedItemDao、Context for WakeLock）
- startRemoval/isRunning/resetProgress 方法签名
- Semaphore(2) 并发控制实现
- WakeLock 获取/释放逻辑
- 进度 StateFlow 结构

- [ ] **步骤 2：编写测试类**

需 Robolectric（Context/PowerManager）。用 turbine 验证 StateFlow。

```kotlin
package com.tracktosearch.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanBatchRemovalManagerTest {

    private val doubanRepo = mockk<DoubanRepository>(relaxed = true)
    private val authStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val syncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val manager = DoubanBatchRemovalManager(doubanRepo, authStorage, syncedItemDao /* Context 按源码补全 */)

    @Test
    fun startRemoval_loggedIn_removesAllItems() = runTest {
        coEvery { authStorage.isLoggedIn() } returns true
        coEvery { doubanRepo.removeMark(any(), any()) } returns true
        val items = listOf(/* 构造 */)

        manager.startRemoval(items)

        coVerify(atLeast = items.size) { doubanRepo.removeMark(any(), any()) }
    }

    @Test
    fun startRemoval_notLoggedIn_skipsSilently() = runTest {
        coEvery { authStorage.isLoggedIn() } returns false

        manager.startRemoval(listOf(/* 构造 */))

        coVerify(exactly = 0) { doubanRepo.removeMark(any(), any()) }
    }

    @Test
    fun startRemoval_singleFailure_doesNotAffectOthers() = runTest {
        coEvery { authStorage.isLoggedIn() } returns true
        coEvery { doubanRepo.removeMark(item1, any()) } throws java.io.IOException()
        coEvery { doubanRepo.removeMark(item2, any()) } returns true

        manager.startRemoval(listOf(item1, item2))

        // 验证 item2 仍被调用，item1 失败不影响
        coVerify { doubanRepo.removeMark(item2, any()) }
    }

    @Test
    fun isRunning_duringRemoval_returnsTrue() = runTest {
        coEvery { authStorage.isLoggedIn() } returns true
        coEvery { doubanRepo.removeMark(any(), any()) } coAnswers { delay(100); true }

        val job = launch { manager.startRemoval(items) }
        // 等待开始
        assertThat(manager.isRunning).isTrue()
        job.join()
        assertThat(manager.isRunning).isFalse()
    }

    @Test
    fun resetProgress_resetsStateFlow() = runTest {
        manager.progress.test {
            manager.resetProgress()
            // 断言进度重置为初始状态
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun startRemoval_acquiresAndReleasesWakeLock() = runTest {
        // Robolectric ShadowPowerManager 验证 WakeLock
        // 按源码确认 WakeLock 获取/释放
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanBatchRemovalManagerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanBatchRemovalManagerTest.kt
git commit -m "test: 添加 DoubanBatchRemovalManager 批量移除测试"
```

---

### 任务 11：DoubanTraktStatusConsistencyCheckerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanTraktStatusConsistencyChecker.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanTraktStatusConsistencyCheckerTest.kt`

**测试点清单：**
1. `checkAndUnify` → 一致性比对，发现差异并修复
2. `cancel` → 中断检查，isRunning 变 false
3. `checkProgress` StateFlow → 进度更新序列
4. `lastConsistencyCheckStorage` 时间戳 → 检查后更新
5. 一致状态（无差异）→ 不修改任何数据

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具读取 `DoubanTraktStatusConsistencyChecker.kt`，确认：
- 构造参数（5 个依赖：DoubanRepository、TraktRepository、DoubanSyncedItemDao 等）
- checkAndUnify/cancel 方法签名
- checkProgress StateFlow 结构
- 一致性比对逻辑（豆瓣标记 vs Trakt 状态）

- [ ] **步骤 2：编写测试类**

需 Robolectric（Context）。用 turbine 验证 StateFlow。

```kotlin
package com.tracktosearch.data.repository

import app.cash.turbine.test
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
class DoubanTraktStatusConsistencyCheckerTest {

    // mock 5 个依赖
    private val checker = DoubanTraktStatusConsistencyChecker(/* 按源码补全 */)

    @Test
    fun checkAndUnify_consistentState_modifiesNothing() = runTest {
        // 豆瓣标记与 Trakt 状态一致
        // 断言无修改操作
    }

    @Test
    fun checkAndUnify_inconsistentState_fixesDifferences() = runTest {
        // 豆瓣有标记但 Trakt 无，或反之
        // 断言修复操作被调用
    }

    @Test
    fun cancel_setsIsRunningFalse() = runTest {
        coEvery { /* 某依赖返回大量数据使检查耗时 */ } coAnswers { delay(1000) }
        val job = launch { checker.checkAndUnify() }
        checker.cancel()
        job.join()
        assertThat(checker.isRunning).isFalse()
    }

    @Test
    fun checkProgress_emitsProgressUpdates() = runTest {
        checker.checkProgress.test {
            checkAndUnify()
            // 断言进度序列：0% → 50% → 100%
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun checkAndUnify_updatesLastCheckTimestamp() = runTest {
        checker.checkAndUnify()
        // 验证 lastConsistencyCheckStorage 时间戳更新
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanTraktStatusConsistencyCheckerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanTraktStatusConsistencyCheckerTest.kt
git commit -m "test: 添加 DoubanTraktStatusConsistencyChecker 一致性检查测试"
```

---

### 任务 12：DoubanSyncManagerTest

**文件：**
- 被测：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerTest.kt`

**测试点清单：**
1. `startSync(SyncMode.INCREMENTAL_WITH_CHANGES)` → 增量同步
2. `startSync(SyncMode.FULL_REWRITE)` → 全量重写
3. `isRunning` 状态：同步中为 true，完成后为 false
4. `cancel` → 中断同步，清理状态
5. `resetProgress` → 重置进度 StateFlow
6. `clearPendingItems` → 清空待同步队列
7. `restoreRollback` → 回滚到同步前状态
8. WakeLock：acquire/release 正确
9. Progress StateFlow：进度更新序列

- [ ] **步骤 1：阅读被测类源码**

用 Read 工具完整读取 `DoubanSyncManager.kt`，确认：
- 构造参数（13 个依赖：DoubanRepository、TraktRepository、DoubanSyncedItemDao、DoubanAuthStorage、Context 等）
- startSync/cancel/isRunning/resetProgress/clearPendingItems/restoreRollback 方法签名
- Progress StateFlow 结构（DoubanSyncProgress）
- WakeLock 逻辑
- 回滚机制

- [ ] **步骤 2：编写测试类**

需 Robolectric（Context/PowerManager）。用 turbine 验证 StateFlow。这是最复杂的测试，mock 13 个依赖。

```kotlin
package com.tracktosearch.data.repository

import app.cash.turbine.test
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
class DoubanSyncManagerTest {

    // mock 13 个依赖
    private val syncManager = DoubanSyncManager(/* 按源码补全 13 个依赖 */)

    @Test
    fun startSync_incrementalMode_completesSuccessfully() = runTest {
        coEvery { /* 各依赖返回正常数据 */ } returns /* 正常 */

        val result = syncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES)

        assertThat(result).isTrue()  // 或按源码返回类型
    }

    @Test
    fun startSync_fullRewriteMode_completesSuccessfully() = runTest {
        val result = syncManager.startSync(SyncMode.FULL_REWRITE)

        assertThat(result).isTrue()
    }

    @Test
    fun isRunning_duringSync_returnsTrue() = runTest {
        coEvery { /* 耗时操作 */ } coAnswers { delay(1000) }

        val job = launch { syncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES) }
        assertThat(syncManager.isRunning).isTrue()
        job.join()
        assertThat(syncManager.isRunning).isFalse()
    }

    @Test
    fun cancel_setsIsRunningFalse() = runTest {
        coEvery { /* 耗时操作 */ } coAnswers { delay(1000) }

        val job = launch { syncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES) }
        syncManager.cancel()
        job.join()
        assertThat(syncManager.isRunning).isFalse()
    }

    @Test
    fun resetProgress_resetsStateFlow() = runTest {
        syncManager.progress.test {
            syncManager.resetProgress()
            // 断言进度重置为初始状态
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearPendingItems_emptiesQueue() = runTest {
        syncManager.clearPendingItems()
        // 验证待同步队列清空
    }

    @Test
    fun restoreRollback_revertsToPreSyncState() = runTest {
        // 先同步，再回滚
        // 断言状态恢复
    }

    @Test
    fun startSync_emitsProgressUpdates() = runTest {
        syncManager.progress.test {
            syncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES)
            // 断言进度序列
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun startSync_acquiresAndReleasesWakeLock() = runTest {
        // Robolectric ShadowPowerManager 验证
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanSyncManagerTest"`

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerTest.kt
git commit -m "test: 添加 DoubanSyncManager 同步管理测试"
```

---

### 阶段 3 结束验证

- [ ] **步骤 1：运行全部单元测试**

运行：`.\gradlew testDebugUnitTest`
预期：所有非 @Ignore 测试通过（阶段 1+2 的 A 层 + 阶段 3 的 B 层）

- [ ] **步骤 2：构建 debug 包验证无编译破坏**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：汇总 P0/P1/P2 bug 清单**

整理本阶段发现的所有 bug，按规格 §5 分级记录。
