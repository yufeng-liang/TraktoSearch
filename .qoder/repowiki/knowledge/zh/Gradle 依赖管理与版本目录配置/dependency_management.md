该项目采用 **Gradle (Kotlin DSL)** 作为构建与依赖管理系统，遵循现代 Android 开发的标准化实践。核心策略包括使用 **Version Catalogs** (`libs.versions.toml`) 进行集中式依赖管理，以及通过 `settings.gradle.kts` 统一配置仓库源。

### 1. 依赖声明与版本控制
- **Version Catalogs**: 项目根目录下的 `gradle/libs.versions.toml` 是依赖管理的核心文件。它定义了所有第三方库的版本号（`[versions]`）、坐标别名（`[libraries]`）以及插件映射（`[plugins]`）。
- **类型安全访问器**: 在 `app/build.gradle.kts` 中，通过 `implementation(libs.androidx.core.ktx)` 等方式引用依赖，利用 Gradle 的类型安全访问器避免了硬编码字符串，提高了重构安全性。
- **BOM 管理**: 使用 `androidx-compose-bom` 来统一管理 Jetpack Compose 相关组件的版本，确保 UI 库之间的兼容性。

### 2. 仓库配置与镜像加速
- **统一仓库入口**: 在 `settings.gradle.kts` 的 `dependencyResolutionManagement` 块中配置了 `google()`, `mavenCentral()` 以及华为开发者仓库 (`developer.huawei.com`)。
- **强制集中管理**: 设置了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，禁止在模块级 `build.gradle.kts` 中重复定义仓库，确保所有依赖均从顶层配置的源获取。
- **国内镜像优化**: `gradle-wrapper.properties` 中配置了腾讯云镜像 (`mirrors.cloud.tencent.com`) 以加速 Gradle 发行版的下载，适应国内网络环境。

### 3. 敏感信息与构建配置
- **本地属性注入**: API Keys（如 Trakt, TMDB, OMDB）和签名信息不直接提交至代码库，而是通过 `local.properties` 文件读取，并利用 `Properties` 类在 `build.gradle.kts` 中动态注入到 `BuildConfig` 或 `manifestPlaceholders` 中。
- **插件管理**: 顶层 `build.gradle.kts` 使用 `alias(libs.plugins...)` 声明插件并设置 `apply false`，实现插件版本的统一管控，子模块按需应用。

### 4. 开发者规范
- **新增依赖**: 应优先在 `libs.versions.toml` 中定义版本和坐标，然后在模块中使用 `libs.xxx` 引用。
- **密钥管理**: 严禁将 `local.properties` 提交至 Git。新成员需根据模板创建该文件并填入必要的 API 凭证。
- **仓库约束**: 不得在子模块中私自添加 `repositories { ... }` 块，所有源必须在 `settings.gradle.kts` 中维护。