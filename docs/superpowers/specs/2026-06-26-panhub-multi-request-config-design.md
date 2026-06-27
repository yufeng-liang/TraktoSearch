# PanHub 多请求搜索与自定义配置设计

## 概述

将 PanHub 搜索源从单次 `src=all` 请求升级为可配置的多请求并行搜索，支持按插件和频道粒度开关、并发数和超时设置。

## 背景

当前 PanHub 搜索源发送单次 `GET /api/search?kw=xxx&src=all` 请求，由后端统一聚合所有插件和频道的结果。用户希望在设置页面对 PanHub 进行精细化控制：

1. 搜索结果等效于分别发送全部 plugin 和 tg channel 请求后合并
2. 支持按 url 去重，按网盘类型分组展示（现有 UI 保持不变）
3. 在设置页 PanHub 开关左侧增加自定义设置入口

## 新增文件

### `data/remote/panhub/PanHubApiService.kt`

Retrofit 接口，与现有 PanSouApiService 相同端点但有完整参数：

```kotlin
interface PanHubApiService {
    @GET("api/search")
    suspend fun search(
        @Query("kw") keyword: String,
        @Query("res") res: String = "merged_by_type",
        @Query("src") src: String = "plugin",
        @Query("conc") concurrency: Int = 4,
        @Query("ext") ext: String = """{"__plugin_timeout_ms":5000}""",
        @Query("plugins") plugins: String? = null,
        @Query("channels") channels: String? = null
    ): PanSouResponse
}
```

复用现有 `PanSouDtos`（响应格式一致）。

### `data/remote/panhub/PanHubPlugin.kt`

10 个插件的枚举，含唯一标识和显示名称：

| id | 显示名 |
|---|---|
| pansearch | PanSearch |
| qupansou | QPanSou |
| panta | PanTa |
| hunhepan | HunHePan |
| jikepan | JiKePan |
| labi | LaBi |
| thepiratebay | ThePirateBay |
| duoduo | DuoDuo |
| xuexizhinan | XueXiZhiNan |
| nyaa | Nyaa |

### `data/remote/panhub/PanHubChannel.kt`

频道枚举，按网盘类型分组：

**夸克组**（关键词 quark）：
Quark_Share_Channel, quarkshare, Quark_Movies, NewQuark, ypquark, ucquark, kuakeyun

**百度组**（关键词 baidu/bd）：
BaiduCloudDisk, baiduyun, bdwpzhpd

**阿里组**（关键词 ali/aliyun）：
share_aliyun, shareAliyun, aliyunys, AliyunDrive_Share_Channel, Aliyun_4K_Movies, iAliyun, NewAliPan, alyp_TV, alyp_4K_Movies, alyp_1, alyp_Animation, alyp_JLP, leoziyuan, yunpanpan

**115组**（关键词 115）：
Lsp115, oneonefivewpfx, tgsearchers115, Channel_Shares_115, tyysypzypd, vip115hot, Maidanglaocom

**其他组**（其余频道）：
tgsearchers3, yunpanxunlei, tianyifc, txtyzy, peccxinpd, gotopan, xingqiump4, yunpanqk, PanjClub, kkxlzy, baicaoZY, MCPH01, ysxb48, jdjdn1111, yggpan, MCPH086, zaihuayun, Q66Share, Oscar_4Kmovies, ucwpzy, dianyingshare, XiangxiuNBB, ydypzyfx, xx123pan, yingshifenxiang123, zyfb123, tyypzhpd, tianyirigeng, cloudtianyi, hdhhd21, wp123zy, yunpan139, yunpan189, yunpanuc, yydf_hzl, quanziyuanshe, qixingzhenren, taoxgzy

### `data/remote/panhub/PanHubConfig.kt`

配置数据类：

```kotlin
data class PanHubConfig(
    val concurrency: Int = 4,       // 1-16
    val timeoutMs: Int = 5000,      // 超时毫秒
    val enabledPlugins: Set<String> = PanHubPlugin.entries.map { it.id }.toSet(),
    val enabledChannels: Set<String> = PanHubChannel.entries.map { it.id }.toSet()
)
```

### `data/local/PanHubConfigStorage.kt`

DataStore 持久化，存：
- `panhub_concurrency` → Int (1-16)
- `panhub_timeout_ms` → Int
- `panhub_enabled_plugins` → Set<String> (逗号分隔)
- `panhub_enabled_channels` → Set<String> (逗号分隔)

提供 Flow 和挂起 setter。

### `ui/screen/settings/PanHubConfigDialog.kt`

配置弹窗 Composable：

- 标题："PanHub 搜索配置"
- **并发数**：滑块 1-16，显示当前值，默认 4
- **超时(ms)**：OutlinedTextField 数字输入，默认 5000
- **插件**：Flat 列表，每行带 Switch，顶部全选/取消
- **频道**：5 个分组折叠卡片（夸克、百度、阿里、115、其他）
  - 每组标题行带总 Switch
  - 展开后逐个频道带 Switch

## 修改文件

### `di/NetworkModule.kt`

新增 PanHub Retrofit 实例绑定（复用 `@Named("panhub")` OkHttpClient）：

```kotlin
@Provides @Singleton
fun providePanHubApiService(@Named("panhub") okHttpClient: OkHttpClient): PanHubApiService
```

### `data/repository/ResourceRepository.kt`

新增 `searchPanHubGranular()` 方法：

1. 获取 `PanHubConfig`（并发数 N，超时 T，启用插件列表 P，启用频道列表 C）
2. 将 P 拆为单个 plugin 请求，C 每 4 个一组拆为 tg 请求
3. 使用 `kotlinx.coroutines.semaphore.Semaphore(N)` 控制并发
4. 每个请求带 `ext={"__plugin_timeout_ms":T}` 超时参数
5. 收集所有响应，合并 `merged_by_type` Map
6. 按 `url` 去重，转换为 `ResourceItem` 列表
7. 返回合并结果

`s`earchResourcesFlow()` 中，当 PanHub 启用时调用此新方法替换原有单次请求。

### `ui/screen/settings/SettingsScreen.kt`

`SearchSourceItem` 中 PanHub 行：

```kotlin
Row {
    IconButton(settings) { showPanHubConfig = true }  // 新增
    Switch                                               // 现有
    Text("PanHub")                                     // 现有
}
```

### `ui/screen/settings/SettingsViewModel.kt`

暴露 `panHubConfig: StateFlow<PanHubConfig>` 和 setter 方法。

## API 请求模式

搜索时根据配置发送以下请求（同时受并发数 semaphore 限制）：

**Plugin 请求**（每个启用的插件发一次）：
```
GET /api/search?kw=xxx&res=merged_by_type&src=plugin&conc=N&ext={...}&plugins=pansearch
GET /api/search?kw=xxx&res=merged_by_type&src=plugin&conc=N&ext={...}&plugins=qupansou
...
```

**Channel 请求**（每 4 个启用的频道一组）：
```
GET /api/search?kw=xxx&res=merged_by_type&src=tg&conc=N&ext={...}&channels=a,b,c,d
GET /api/search?kw=xxx&res=merged_by_type&src=tg&conc=N&ext={...}&channels=e,f,g,h
...
```

## 结果合并

1. 每个请求得到 `PanSouResponse` → `merged_by_type: Map<String, List<PanSouMergedLink>>`
2. 将所有 Map 合并为单个 Map（同 key 的 List 合并）
3. 展平为 `PanSouMergedLink` 列表
4. 按 `url` 去重（distinctBy）
5. 映射为 `ResourceItem` 列表
6. 输入到现有搜索流中，按现有逻辑排序和展示

## 边界情况

- **所有插件和频道都禁用**：返回空结果，标记 PanHub 搜索完成
- **某请求超时或失败**：忽略该请求的响应，其他请求结果正常合并
- **并发数=1**：串行执行所有请求
- **配置变更**：仅影响后续搜索，已缓存的结果不受影响
