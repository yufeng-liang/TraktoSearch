# 豆瓣与 Trakt 影视状态双向同步设计

## 背景

当前架构是单向同步：豆瓣 → Trakt（DoubanSyncManager 把豆瓣标记推到 Trakt）。详情页标记想看/已看时只更新 Trakt，不同步豆瓣。用户从豆瓣导入的影视在详情页标记后，两平台状态会不一致。

## 目标

1. **状态统一检查**：检查豆瓣和 Trakt 对同一部影视（imdbId 相同）的状态，按「已看过优先于想看」原则统一
2. **详情页双向同步**：用户在详情页标记想看/已看时，同步更新豆瓣侧状态
3. **建立 traktId→doubanId 永久映射**：支持从 imdbId 查询 doubanId

## 设计

### 第一部分：doubanId 永久映射缓存与获取链路

#### 永久映射缓存

新增 `DoubanIdMappingCache`（基于 PersistentTtlCache，永不过期）：
- Key: `traktId_{movie|show}`
- Value: JSON `{doubanId, imdbId, title}`
- 存储 traktId→doubanId 映射，避免重复搜索豆瓣

#### 获取链路（详情页打开时预查）

```
1. 查 traktId→doubanId 永久缓存（O(1)）
   ↓ 未命中
2. 查 douban_synced_items 表 by imdbId（O(1)，已有索引）
   ↓ 未命中
3. 遍历 DoubanDetailCache by imdbId（O(n)）
   ↓ 未命中
4. 爬 m.douban.com/search/?query={imdbId}（网络请求，单条无反爬延迟）
   → Jsoup 解析 a[href^="/movie/subject/"] 拿到 doubanId
   → 命中后写入永久缓存
   ↓ 仍未命中
   跳过豆瓣同步（只标记 Trakt）
```

预查在后台异步进行，不阻塞 UI。结果存入 ViewModel 的 `doubanIdForSync` 状态。

#### 搜索端点（已逆向验证）

```
GET https://m.douban.com/search/?query={imdbId}
→ HTML（服务端渲染，非 JS 渲染）
→ Jsoup 解析 ul.search_results_subjects li → a[href^="/movie/subject/"]
→ doubanId = Regex("subject/(\\d+)").find(href)?.groupValues[1]
```

特点：
- 无需 cookie 即可使用（已测试验证）
- 因 imdbId 是唯一标识，匹配准确率极高，无需二次详情页验证
- 返回服务端渲染 HTML，Jsoup 直接解析

### 第二部分：状态统一检查

#### 触发时机

1. **豆瓣导入完成后自动触发**：对刚导入的这批条目检查冲突并统一
2. **设置页手动触发全量检查**：对所有已同步过的条目（douban_synced_items 表）做一次全量检查

#### 豆瓣侧状态来源

- 导入完成后：用刚爬取的内存数据
- 手动全量检查：重新爬取豆瓣 wish/collect 列表获取最新状态

#### 统一原则（已看过优先于想看）

| 豆瓣状态 | Trakt 状态 | 统一后状态 | 操作 |
|---------|-----------|-----------|------|
| 已看 | 想看 | 已看 | Trakt 标记已看（移除想看） |
| 想看 | 已看 | 已看 | 豆瓣标记已看（升级） |
| 已看 | 未标记 | 已看 | Trakt 标记已看 |
| 想看 | 未标记 | 想看 | Trakt 标记想看 |
| 未标记 | 已看 | 已看 | 豆瓣标记已看 |
| 未标记 | 想看 | 想看 | 豆瓣标记想看 |
| 已看 | 已看 | 已看 | 无操作 |
| 想看 | 想看 | 想看 | 无操作 |
| 未标记 | 未标记 | 未标记 | 无操作 |

### 第三部分：详情页双向同步

#### 标记动作映射

| 详情页动作 | Trakt 操作 | 豆瓣操作 |
|-----------|-----------|---------|
| 标记想看 | `addToWatchlist` | `markInterest(wish)` |
| 标记已看 | `markAsWatched`（副操作移除想看） | `markInterest(collect)` |
| 取消想看 | `removeFromWatchlist` | `removeMark` |
| 取消已看 | `removeWatched`（副操作加回想看） | `markInterest(wish)` |

#### 调用流程（以标记想看为例）

```
用户点击「想看」
  ↓
1. 先调 traktRepository.addToWatchlist()（主操作）
   ↓ 成功
2. 检查 doubanIdForSync 是否就绪 + 豆瓣是否登录
   ↓ 就绪且登录
3. 后台调 doubanRepository.fetchCsrfToken(doubanId) → markInterest(wish)
   ↓ 成功
4. Toast 提示「豆瓣数据已同步更新」
   ↓ 失败
5. 分享按钮左侧显示重试按钮 + Toast 提示「豆瓣同步失败，可重试」
   ↓ doubanId 未就绪或未登录
6. 分享按钮左侧显示重试按钮 + Toast 提示「豆瓣ID未就绪，可重试」
```

#### 关键设计点

1. **Trakt 优先**：先确保 Trakt 成功，再同步豆瓣。Trakt 失败则整个操作失败
2. **豆瓣失败不回滚**：豆瓣同步失败不回滚 Trakt（Trakt 是主数据源）
3. **无反爬延迟**：单条标记不走批量延迟逻辑（仅批量操作才需反爬延迟）
4. **预查异步**：在 `loadDetail()` 中 imdbId 就绪后启动协程异步执行获取链路

#### ViewModel 状态新增

```kotlin
// DetailUiState 新增字段
val doubanIdForSync: String? = null       // 预查到的 doubanId
val isDoubanSyncing: Boolean = false      // 豆瓣同步进行中
val doubanSyncRetryable: Boolean = false  // 是否显示重试按钮
val pendingDoubanAction: DoubanSyncAction? = null  // 待重试的动作
```

`DoubanSyncAction` 枚举：`WISH`、`COLLECT`、`REMOVE_WISH`、`REMOVE_COLLECT`

#### Toast 机制

采用项目现有 Toast SharedFlow 模式（参考 PersonViewModel）：
- DetailViewModel 新增 `MutableSharedFlow<Int>` + `asSharedFlow()`
- DetailScreen 用 `LaunchedEffect` collect 后 `context.showToast()`

#### 重试按钮设计

- **位置**：分享按钮左侧，同样浮动在右上角，使用 `Row` 包裹重试按钮 + 分享按钮
- **样式**：与分享/返回按钮对称（40dp 圆形、Haze 毛玻璃、半透明背景）
- **图标**：`Icons.Rounded.Refresh`（20dp）
- **显示条件**：`uiState.doubanSyncRetryable == true`
- **隐藏条件**：重试成功后自动隐藏
- **点击行为**：重新执行豆瓣同步

#### 反馈矩阵

| 场景 | 反馈方式 |
|------|---------|
| 豆瓣同步成功 | Toast「豆瓣数据已同步更新」 |
| 豆瓣同步失败 | 重试按钮 + Toast「豆瓣同步失败，可重试」 |
| 豆瓣ID未就绪 | 重试按钮 + Toast「豆瓣ID未就绪，可重试」 |
| 未触发豆瓣同步（未登录/无 doubanId） | 静默，无反馈 |

## 涉及文件

### 新增
- `DoubanIdMappingCache` - traktId→doubanId 永久映射缓存

### 修改
- `DoubanSpider.kt` - 已新增 `parseSearchByImdb` + `DoubanSearchResultItem`（测试页验证完成）
- `DoubanRepository.kt` - 新增 `searchDoubanIdByImdb` 正式方法 + `findDoubanIdByImdb` 本地查询
- `DoubanSyncedItemDao` - 新增 `getByImdbId` 查询方法
- `DetailViewModel.kt` - 注入豆瓣依赖、预查链路、标记同步逻辑
- `DetailScreen.kt` - 新增重试按钮、Toast collect
- `DoubanSyncManager.kt` - 导入完成后状态统一检查
- `SettingsScreen.kt` - 手动触发全量检查入口
- 4 语言 `strings.xml` - 新增字符串

## 测试验证

### 已完成
- 豆瓣移动端搜索端点 `m.douban.com/search/?query={imdbId}` 已在测试页验证：无需 cookie 即可搜索，Jsoup 解析 `a[href^="/movie/subject/"]` 拿到 doubanId

### 待验证
- 详情页标记后豆瓣侧状态是否正确更新
- 重试按钮交互流程
- 状态统一检查逻辑
