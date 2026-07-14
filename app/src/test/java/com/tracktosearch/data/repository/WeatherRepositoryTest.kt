package com.tracktosearch.data.repository

import android.content.Context
import android.location.Location
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.weather.CurrentWeather
import com.tracktosearch.data.remote.weather.OpenMeteoApi
import com.tracktosearch.data.remote.weather.OpenMeteoResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/**
 * WeatherRepository 单元测试。
 *
 * 覆盖点：
 * - getCurrentWeather：缓存命中（3h TTL）直接返回、缓存未命中调用 API、null 位置使用默认北京坐标
 * - 网络失败（IOException）/ 响应不成功 / body 为 null → 返回 null
 * - 缓存过期（>3h）→ 重新请求 API
 * - 成功响应正确映射 weatherCode 和 temperature
 *
 * DataStore 跨测试隔离策略：
 * WeatherRepository 通过 `Context.weatherDataStore`（preferencesDataStore(name = "weather")）持久化缓存，
 * 该 DataStore 是文件级单例，其内部 StateFlow 在首次读取后会缓存数据，测试无法通过删除文件清除。
 * 采用反射调用 `WeatherRepositoryKt.getWeatherDataStore(Context)` 获取生产 DataStore 实例，
 * 在每个 @Before 中 `edit { it.clear() }` 清空 StateFlow，确保每个测试从干净状态开始。
 * 缓存过期测试通过直接写入 cacheTime = 0 的缓存条目模拟过期场景，无需 mock System.currentTimeMillis()
 * （mockkStatic(System::currentTimeMillis) 会导致 mockk 内部递归调用 → StackOverflowError）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherRepositoryTest {

    private val openMeteoApi = mockk<OpenMeteoApi>(relaxed = true)
    private lateinit var context: Context
    private lateinit var repository: WeatherRepository

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = N) 不受干扰
        clearMocks(openMeteoApi)
        // 获取 Robolectric 真实 Context（项目未配置 androidx.test:core，改用 RuntimeEnvironment）
        context = RuntimeEnvironment.getApplication()
        // 每个测试创建新实例，避免状态泄漏
        repository = WeatherRepository(context, openMeteoApi)
        // 清空生产 DataStore 缓存，确保每个测试从干净状态开始
        clearWeatherCache()
    }

    // ============================================================
    // 辅助函数：DataStore 反射访问
    // ============================================================

    /**
     * 通过反射获取生产 DataStore 实例。
     * WeatherRepository.kt 中的 `private val Context.weatherDataStore` 编译生成
     * WeatherRepositoryKt 类的静态方法 getWeatherDataStore(Context)。
     */
    @Suppress("UNCHECKED_CAST")
    private fun getProductionDataStore(): DataStore<Preferences>? {
        return try {
            val kotlinClass = Class.forName("com.tracktosearch.data.repository.WeatherRepositoryKt")
            val getMethod = kotlinClass.getDeclaredMethod("getWeatherDataStore", Context::class.java)
            getMethod.isAccessible = true
            getMethod.invoke(null, context) as DataStore<Preferences>
        } catch (e: Exception) {
            null
        }
    }

    /** 清空生产 DataStore 的所有缓存条目（同步更新 StateFlow） */
    private fun clearWeatherCache() {
        val dataStore = getProductionDataStore() ?: return
        runBlocking {
            dataStore.edit { it.clear() }
        }
    }

    /**
     * 直接向生产 DataStore 写入过期缓存条目（cacheTime = 0，远超 3h TTL）。
     * 用于测试「缓存过期 → 重新请求 API」场景，无需 mock System.currentTimeMillis()。
     */
    private suspend fun writeExpiredCache(weatherCode: Int = 99, temperature: Double = 99.0) {
        val dataStore = getProductionDataStore() ?: return
        dataStore.edit { prefs ->
            prefs[intPreferencesKey("weather_code")] = weatherCode
            prefs[doublePreferencesKey("temperature")] = temperature
            prefs[longPreferencesKey("cache_time")] = 0L
        }
    }

    // ============================================================
    // 辅助函数：测试数据构造
    // ============================================================

    /** 构造 Android Location */
    private fun buildLocation(lat: Double, lon: Double): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lon
        }
    }

    /** 构造成功的 Response（可自定义 weatherCode 和 temperature） */
    private fun buildSuccessResponse(
        weatherCode: Int = 1,
        temperature: Double = 20.0
    ): Response<OpenMeteoResponse> {
        val body = OpenMeteoResponse(
            CurrentWeather(temperature = temperature, weatherCode = weatherCode)
        )
        return Response.success(body)
    }

    /** 构造不成功的 Response（isSuccessful = false） */
    private fun buildErrorResponse(): Response<OpenMeteoResponse> {
        val response = mockk<Response<OpenMeteoResponse>>()
        every { response.isSuccessful } returns false
        return response
    }

    /** 构造成功但 body 为 null 的 Response */
    private fun buildNullBodyResponse(): Response<OpenMeteoResponse> {
        val response = mockk<Response<OpenMeteoResponse>>()
        every { response.isSuccessful } returns true
        every { response.body() } returns null
        return response
    }

    // ============================================================
    // 测试点 1：缓存命中 → 返回缓存值，不调用 API
    // ============================================================

    @Test
    fun getCurrentWeather_缓存命中_返回缓存值不调用API() = runTest {
        val location = buildLocation(40.0, 116.0)
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildSuccessResponse(weatherCode = 5, temperature = 15.0)

        // 第一次调用：缓存未命中（@Before 已清空），调用 API，写入缓存
        val result1 = repository.getCurrentWeather(location)
        assertThat(result1).isNotNull()
        assertThat(result1!!.weatherCode).isEqualTo(5)
        assertThat(result1.temperature).isEqualTo(15.0)

        // 第二次调用：缓存命中（同一时间点，diff < 3h TTL），不应调用 API
        val result2 = repository.getCurrentWeather(location)
        assertThat(result2).isNotNull()
        assertThat(result2!!.weatherCode).isEqualTo(5)
        assertThat(result2.temperature).isEqualTo(15.0)

        // API 仅被调用一次（第一次填充缓存，第二次命中缓存未调用）
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点 2：缓存未命中 → 调用 API，返回结果并写入缓存
    // ============================================================

    @Test
    fun getCurrentWeather_缓存未命中_调用API返回结果() = runTest {
        val location = buildLocation(31.2, 121.5) // 上海坐标
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildSuccessResponse(weatherCode = 2, temperature = 30.0)

        val result = repository.getCurrentWeather(location)

        assertThat(result).isNotNull()
        assertThat(result!!.weatherCode).isEqualTo(2)
        assertThat(result.temperature).isEqualTo(30.0)
        // 验证 API 被调用一次，且使用了传入的坐标
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(31.2, 121.5, any(), any()) }
    }

    // ============================================================
    // 测试点 3：null 位置 → 使用默认北京坐标 (39.9, 116.4) 调用 API
    // ============================================================

    @Test
    fun getCurrentWeather_null位置_使用默认北京坐标调用API() = runTest {
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildSuccessResponse()

        val result = repository.getCurrentWeather(null)

        assertThat(result).isNotNull()
        // 验证 API 使用默认北京坐标调用（源码 location?.latitude ?: 39.9, location?.longitude ?: 116.4）
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(39.9, 116.4, any(), any()) }
    }

    // ============================================================
    // 测试点 4：API 抛 IOException → 返回 null
    // ============================================================

    @Test
    fun getCurrentWeather_API抛IOException_返回null() = runTest {
        val location = buildLocation(40.0, 116.0)
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } throws
            IOException("网络错误")

        val result = repository.getCurrentWeather(location)

        // 源码 catch (_: Exception) { null }：IOException 被捕获，返回 null
        assertThat(result).isNull()
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点 5：响应不成功 → 返回 null
    // ============================================================

    @Test
    fun getCurrentWeather_响应不成功_返回null() = runTest {
        val location = buildLocation(40.0, 116.0)
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildErrorResponse()

        val result = repository.getCurrentWeather(location)

        // 源码 if (response.isSuccessful) 分支不进入，返回 null
        assertThat(result).isNull()
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点 6：响应成功但 body 为 null → 返回 null
    // ============================================================

    @Test
    fun getCurrentWeather_响应body为null_返回null() = runTest {
        val location = buildLocation(40.0, 116.0)
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildNullBodyResponse()

        val result = repository.getCurrentWeather(location)

        // 源码 if (body != null) 分支不进入，返回 null
        assertThat(result).isNull()
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点 7：缓存过期（>3h）→ 重新请求 API
    // ============================================================

    @Test
    fun getCurrentWeather_缓存过期_重新请求API() = runTest {
        val location = buildLocation(40.0, 116.0)

        // 直接写入过期缓存（cacheTime = 0，System.currentTimeMillis() - 0 > 3h TTL → 过期）
        writeExpiredCache(weatherCode = 99, temperature = 99.0)

        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildSuccessResponse(weatherCode = 1, temperature = 20.0)

        val result = repository.getCurrentWeather(location)

        assertThat(result).isNotNull()
        // 验证返回 API 结果（weatherCode = 1），而非过期缓存（weatherCode = 99）
        assertThat(result!!.weatherCode).isEqualTo(1)
        assertThat(result.temperature).isEqualTo(20.0)
        // 验证 API 被调用（缓存已过期，未命中）
        coVerify(exactly = 1) { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点 8：成功响应正确映射 weatherCode 和 temperature
    // ============================================================

    @Test
    fun getCurrentWeather_成功响应_正确映射weatherCode和temperature() = runTest {
        val location = buildLocation(40.0, 116.0)
        coEvery { openMeteoApi.getCurrentWeather(any(), any(), any(), any()) } returns
            buildSuccessResponse(weatherCode = 61, temperature = 25.5)

        val result = repository.getCurrentWeather(location)

        assertThat(result).isNotNull()
        // 验证 weatherCode 从 CurrentWeather.weatherCode 提取
        assertThat(result!!.weatherCode).isEqualTo(61)
        // 验证 temperature 从 CurrentWeather.temperature 提取，且为 Double 类型（未被截断为 Int）
        assertThat(result.temperature).isEqualTo(25.5)
        assertThat(result.temperature).isNotEqualTo(25.0)
    }
}
