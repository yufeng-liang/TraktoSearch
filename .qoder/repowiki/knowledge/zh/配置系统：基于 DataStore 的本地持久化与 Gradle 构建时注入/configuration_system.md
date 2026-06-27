该应用采用分层配置策略，将运行时用户偏好与编译时环境密钥分离，主要依赖 **Jetpack DataStore** 和 **Gradle BuildConfig** 机制。

### 1. 核心架构与工具
*   **运行时配置 (Runtime Configuration)**：使用 `androidx.datastore:datastore-preferences` 存储用户设置。所有配置项均以 `@Singleton` 形式的 Storage 类封装，通过 Hilt 进行依赖注入。配置读取基于 Kotlin Coroutines Flow，确保 UI 能响应式地更新。
    *   **存储模式**：每个功能模块（如主题、语言、搜索源）拥有独立的 DataStore 实例（例如 `theme`、`language_settings`、`search_sources`），避免单文件过大导致的读写竞争。
    *   **复杂配置**：对于结构化数据（如自定义搜索源 `CustomSearchSource`），采用 JSON 序列化后存入 `stringPreferencesKey`。
*   **编译时/环境配置 (Build-time Configuration)**：敏感 API 密钥（Trakt, TMDB, OMDB 等）和推送服务标识通过 `local.properties` 文件定义，并在 `app/build.gradle.kts` 中读取并注入到 `BuildConfig` 类中。
    *   **安全实践**：`local.properties` 已被加入 `.gitignore`，防止密钥泄露。代码中通过 `BuildConfig.TRAKT_CLIENT_ID` 等方式访问。

### 2. 关键配置模块
*   **UI 与国际化**：
    *   `ThemeStorage`：管理深色/浅色/跟随系统模式。
    *   `LanguageStorage`：支持中、英、日、韩及跟随系统。
*   **功能开关与数据源**：
    *   `SearchSourceStorage`：控制内置资源站（Pansou, Panhub, Zreso）的启用状态。
    *   `CustomSearchSourceStorage`：管理用户自定义的爬虫规则配置。
    *   `NotificationStorage`：细粒度控制上映提醒和新季开播提醒。
*   **界面布局配置**：
    *   `DiscoverSectionStorage` 和 `DetailSectionStorage`：允许用户自定义“发现页”和“详情页”的栏目显示顺序及可见性，配置以逗号分隔的 ID 字符串或布尔值形式存储。
*   **网络与环境**：
    *   `NetworkModule`：根据 `BuildConfig.DEBUG` 动态开启 OkHttp 日志拦截器。针对不同 API（Trakt, TMDB 等）配置了独立的 OkHttpClient，包括超时时间、重试策略及特定的 Header（如 User-Agent, Referer）。

### 3. 开发规范
*   **新增配置项**：应在 `data/local` 下创建新的 Storage 类，使用 `preferencesDataStore` 委托属性初始化，并提供 `Flow` 读取接口和 `suspend` 写入接口。
*   **密钥管理**：严禁在代码中硬编码 API Key。新密钥需在 `local.properties` 中添加，并在 `build.gradle.kts` 的 `defaultConfig` 块中通过 `buildConfigField` 映射。
*   **默认值处理**：在 Storage 类中通过 `prefs[KEY] ?: DEFAULT_VALUE` 提供合理的默认值，确保首次安装应用时功能正常。