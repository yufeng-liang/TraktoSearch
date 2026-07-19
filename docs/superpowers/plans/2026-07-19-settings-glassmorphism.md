# 设置页拟态玻璃化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标**：将「设置」页改造为拟态玻璃风格，与「发现」「我的」「搜索」三页统一视觉，同时接入跨四页连续彩色光晕背景。

**架构**：
- 标题栏：复用「发现/我的」实现（thin 毛玻璃 + 28sp ExtraBold「设置」+ 状态栏沉浸）
- 页面背景：接入 `PageBackground(currentPage = 3)`，由 `MainScreen` 统一管理
- Section 容器：抽离 `SettingsSectionCard` 通用组件，统一 6 个分组的标题+玻璃卡片样式
- 设置项：复用 `NeumorphicFrostedSurface`，1px 描线分隔，可点击项加 4% 主色高亮反馈

**技术栈**：
- Jetpack Compose + Hilt + MVVM
- Haze（毛玻璃）、NeumorphicGlass（拟态玻璃）、PageBackground（连续光晕）

---

## 文件结构

**修改文件**：
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`：标题栏 + PageBackground 接入 + 6 个分组重构
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsAccountSection.kt`：玻璃卡化
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsCacheSection.kt`：玻璃卡化（保留展开逻辑）
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`：新增 `SettingsSectionCard` 通用组件
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`：确认 PageBackground 参数 `currentPage = 3`
- `app/src/main/res/values/strings.xml`：新增 6 个 Section 标题字符串
- `app/src/main/res/values-zh/strings.xml`、`app/src/main/res/values-ja/strings.xml`、`app/src/main/res/values-ko/strings.xml`：4 语言同步

**已有依赖**：
- `NeumorphicFrostedSurface`（`ui/component/NeumorphicGlass.kt`）
- `PageBackground`（`ui/component/PageBackground.kt`，已支持 `pageCount = 4`）

---

## 任务 1：新建 SettingsSectionCard 通用组件

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`

- [ ] **步骤 1：在 SettingsComponents.kt 末尾添加 SettingsSectionCard 函数**

```kotlin
/**
 * 设置页 Section 容器：标题（13sp Medium 主色）+ 玻璃卡片
 * 统一 6 个分组的标题+卡片样式，避免重复代码
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isAppDarkTheme()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        )
        NeumorphicFrostedSurface(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            shape = RoundedCornerShape(20.dp),
            backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                              else Color.White.copy(alpha = 0.55f),
            borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                          else Color.White.copy(alpha = 0.75f),
            elevation = 4.dp,
            blurRadius = 16.dp,
            hazeState = hazeState,
            hazeStyle = HazeMaterials.thin()
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}
```

- [ ] **步骤 2：补充缺失的 imports**

在文件顶部添加：
```kotlin
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.isAppDarkTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt
git commit -m "feat(settings): 新增 SettingsSectionCard 通用组件"
```

---

## 任务 2：在 strings.xml 中新增 6 个 Section 标题

**文件：**
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：在 values/strings.xml 中添加 6 个 Section 标题**

```xml
<string name="settings_section_account">Account &amp; Login</string>
<string name="settings_section_sync">Sync &amp; Data</string>
<string name="settings_section_appearance">Appearance</string>
<string name="settings_section_cache">Cache</string>
<string name="settings_section_data">Import &amp; Export</string>
<string name="settings_section_about">About</string>
```

- [ ] **步骤 2：在 values-zh/strings.xml 中添加 6 个 Section 标题**

```xml
<string name="settings_section_account">账号与登录</string>
<string name="settings_section_sync">同步与数据</string>
<string name="settings_section_appearance">外观</string>
<string name="settings_section_cache">缓存管理</string>
<string name="settings_section_data">导入与导出</string>
<string name="settings_section_about">关于</string>
```

- [ ] **步骤 3：在 values-ja/strings.xml 中添加 6 个 Section 标题**

```xml
<string name="settings_section_account">アカウントとログイン</string>
<string name="settings_section_sync">同期とデータ</string>
<string name="settings_section_appearance">外観</string>
<string name="settings_section_cache">キャッシュ管理</string>
<string name="settings_section_data">インポートとエクスポート</string>
<string name="settings_section_about">について</string>
```

- [ ] **步骤 4：在 values-ko/strings.xml 中添加 6 个 Section 标题**

```xml
<string name="settings_section_account">계정 및 로그인</string>
<string name="settings_section_sync">동기화 및 데이터</string>
<string name="settings_section_appearance">외관</string>
<string name="settings_section_cache">캐시 관리</string>
<string name="settings_section_data">가져오기 및 내보내기</string>
<string name="settings_section_about">정보</string>
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-ja/strings.xml \
        app/src/main/res/values-ko/strings.xml
git commit -m "feat(settings): 新增 6 个 Section 标题字符串(4 语言)"
```

---

## 任务 3：改造 SettingsAccountSection 为玻璃卡化

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsAccountSection.kt`

- [ ] **步骤 1：将账号资料卡改用 NeumorphicFrostedSurface**

找到账号区代码（包含 `Surface(color = ...)` 包裹 `Row { ... Avatar ... }` 的位置），替换为：

```kotlin
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AccountProfileCard(
    username: String,
    subtitle: String,
    isLoggedIn: Boolean,
    avatarText: String,
    onClick: () -> Unit,
    hazeState: HazeState? = null
) {
    val isDark = isAppDarkTheme()
    NeumorphicFrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        isDark = isDark,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                          else Color.White.copy(alpha = 0.55f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else Color.White.copy(alpha = 0.75f),
        elevation = 4.dp,
        blurRadius = 16.dp,
        hazeState = hazeState,
        hazeStyle = HazeMaterials.thin()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarText,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = username,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isLoggedIn) {
                        Text(
                            text = stringResource(R.string.status_logged_in),
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(
                                    color = Color(0xFF4CAF50).copy(alpha = if (isDark) 0.25f else 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.status_not_logged_in),
                            fontSize = 11.sp,
                            color = Color(0xFFEF6C00),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(
                                    color = Color(0xFFFF9800).copy(alpha = if (isDark) 0.25f else 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **步骤 2：在 strings.xml 检查或新增 status_logged_in/status_not_logged_in**

如已存在则跳过；如不存在则在 `values/strings.xml` 添加：
```xml
<string name="status_logged_in">Logged in</string>
<string name="status_not_logged_in">Not logged in</string>
```
并同步在 `values-zh/strings.xml`、`values-ja/strings.xml`、`values-ko/strings.xml` 添加。

- [ ] **步骤 3：补充缺失的 imports**

在文件顶部添加：
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
```

- [ ] **步骤 4：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsAccountSection.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-ja/strings.xml \
        app/src/main/res/values-ko/strings.xml
git commit -m "feat(settings): 账号资料卡玻璃化"
```

---

## 任务 4：改造 SettingsCacheSection 为玻璃卡化

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsCacheSection.kt`

- [ ] **步骤 1：将 CacheManagementItem 外层 Surface 替换为 NeumorphicFrostedSurface**

找到 `CacheManagementItem` 函数中的 `Surface(...)` 调用，替换为：

```kotlin
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun CacheManagementItem(
    breakdown: SettingsViewModel.CacheBreakdown,
    onClearCategory: (SettingsViewModel.CacheCategory) -> Unit,
    onClearAll: () -> Unit,
    hazeState: HazeState? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isDark = isAppDarkTheme()

    NeumorphicFrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        isDark = isDark,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                          else Color.White.copy(alpha = 0.55f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else Color.White.copy(alpha = 0.75f),
        elevation = 4.dp,
        blurRadius = 16.dp,
        hazeState = hazeState,
        hazeStyle = HazeMaterials.thin()
    ) {
        // 保留原有 Column + 概览行 + AnimatedVisibility 展开逻辑
        Column(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            // 概览行（原有代码）
            CacheOverviewRow(
                breakdown = breakdown,
                expanded = expanded,
                onClick = { expanded = !expanded }
            )

            // 展开后 5 个分类行（原有代码）
            AnimatedVisibility(visible = expanded) {
                Column {
                    // 5 个 CacheCategoryRow 调用（保留原代码）
                }
            }

            // 底部「全部清除」按钮（保留原代码）
            Button(
                onClick = onClearAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.settings_clear_all))
            }
        }
    }
}
```

- [ ] **步骤 2：补充缺失的 imports**

在文件顶部添加：
```kotlin
import androidx.compose.foundation.layout.Arrangement
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsCacheSection.kt
git commit -m "feat(settings): 缓存管理卡玻璃化"
```

---

## 任务 5：改造 SettingsScreen 标题栏与 PageBackground 接入

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`

- [ ] **步骤 1：在 SettingsScreen.kt 顶部添加 PageBackground 接入**

找到 `SettingsScreen` 函数体起始位置（`val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()` 之前），添加：

```kotlin
// 跨四页连续彩色光晕背景（currentPage=3 标识设置页）
val hazeState = remember { HazeState() }
Box(modifier = Modifier.fillMaxSize()) {
    PageBackground(
        modifier = Modifier.fillMaxSize(),
        currentPage = 3
    )
    // 原有 Scaffold + 内容保持不变
    Scaffold(
        ...
    )
}
```

注意：`PageBackground` 已经在 `MainScreen` 顶层接入（`pageCount = 4`），设置页**不需要重复接入**。本步骤仅在 `SettingsScreen` 内部确认已移除任何重复的背景实现。

- [ ] **步骤 2：在 MainScreen.kt 确认 SettingsScreen 调用未重复 PageBackground**

打开 `MainScreen.kt`，搜索 `SettingsScreen(`，确认其参数中没有重复传入 `PageBackground`。如发现重复，删除 `SettingsScreen` 内部的 PageBackground 调用。

- [ ] **步骤 3：改造标题栏为 thin 毛玻璃**

找到标题栏代码（包含 `TopAppBar` 或自实现的 `Row` + 「设置」Text），替换为：

```kotlin
// 标题栏：与「发现/我的」完全一致
val isDark = isAppDarkTheme()
Box(
    modifier = Modifier
        .fillMaxWidth()
        .hazeEffect(
            state = hazeState,
            style = HazeMaterials.thin()
        )
) {
    Column {
        Spacer(modifier = Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tab_settings),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
```

注意：如已存在外层 Box 包装的 hazeEffect + Column，可只保留内部 Row 并确认 padding 正确，避免重复叠加导致变白。

- [ ] **步骤 4：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt \
        app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt
git commit -m "feat(settings): 标题栏改造为 thin 毛玻璃 + 移除重复 PageBackground"
```

---

## 任务 6：重构 SettingsScreen 6 个分组使用 SettingsSectionCard

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`

- [ ] **步骤 1：识别现有 6 个 Section 在 SettingsScreen.kt 中的位置**

通过搜索以下图标 import 定位：
- `Movie` / `Person` → 账号与登录
- `CloudDownload` / `CloudUpload` / `Sync` / `SyncAlt` → 同步与数据
- `DarkMode` / `Palette` / `Language` → 外观
- `Storage` / `CloudDownload` → 缓存管理
- `FileDownload` / `FileUpload` → 导入与导出
- `Info` / `HelpOutline` / `Code` → 关于

- [ ] **步骤 2：将每个分组的 Surface 包裹替换为 SettingsSectionCard**

模式替换（每组）：
```kotlin
// 旧
Column {
    Text("账号与登录", style = MaterialTheme.typography.labelSmall)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column { /* 设置项 */ }
    }
}

// 新
SettingsSectionCard(
    title = stringResource(R.string.settings_section_account),
    hazeState = hazeState
) {
    /* 设置项（不再需要内部 Column 包装，直接写 Row） */
}
```

- [ ] **步骤 3：确保 6 个分组完整重构**

逐一确认：
1. `R.string.settings_section_account`（账号与登录）
2. `R.string.settings_section_sync`（同步与数据）
3. `R.string.settings_section_appearance`（外观）
4. `R.string.settings_section_cache`（缓存管理）
5. `R.string.settings_section_data`（导入与导出）
6. `R.string.settings_section_about`（关于）

- [ ] **步骤 4：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
git commit -m "feat(settings): 6 个分组重构使用 SettingsSectionCard"
```

---

## 任务 7：设置项可点击高亮反馈

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`

- [ ] **步骤 1：为可点击设置项添加 4% 主色高亮反馈**

定位所有可点击的 `Row` 或 `Surface`，将 `.clickable { ... }` 包裹替换为：

```kotlin
val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
val isPressed by interactionSource.collectIsPressedAsState()

Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = { /* 原 onClick 逻辑 */ }
        )
        .background(
            if (isPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
            else Color.Transparent
        )
        .padding(14.dp, 16.dp),
    verticalAlignment = Alignment.CenterVertically
) { /* 内容 */ }
```

- [ ] **步骤 2：补充分隔线样式**

设置项间 1px 描线分隔，使用：
```kotlin
import androidx.compose.material3.HorizontalDivider
HorizontalDivider(
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (isAppDarkTheme()) 0.06f else 0.07f
    )
)
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
git commit -m "feat(settings): 设置项可点击高亮反馈 + 1px 分隔线"
```

---

## 任务 8：安装并截图亮/暗模式

**文件：**
- 修改：无

- [ ] **步骤 1：assembleDebug + installDebug**

```bash
./gradlew :app:installDebug
```

- [ ] **步骤 2：启动 App 并导航到设置页**

```bash
H:\android\Sdk\platform-tools\adb.exe shell am force-stop com.tracktosearch
H:\android\Sdk\platform-tools\adb.exe shell am start -n com.tracktosearch/.MainActivity
Start-Sleep -Seconds 6
# 切到设置页（底部导航 4 号位置 Pixel 5 屏幕宽 1080）
H:\android\Sdk\platform-tools\adb.exe shell input tap 945 2220
Start-Sleep -Seconds 3
```

- [ ] **步骤 3：亮色模式截图**

```bash
H:\android\Sdk\platform-tools\adb.exe shell screencap -p /sdcard/settings_light.png
H:\android\Sdk\platform-tools\adb.exe pull /sdcard/settings_light.png f:/trae-project/shot_settings_light.png
```

打开 `f:\trae-project\shot_settings_light.png` 检查：
- 标题栏「设置」28sp ExtraBold，thin 毛玻璃透出背景
- 6 个分组完整，每组 Section 标题 + 玻璃卡片
- 设置项可点击区域正确

- [ ] **步骤 4：切到暗色模式截图**

```bash
H:\android\Sdk\platform-tools\adb.exe shell cmd uimode night yes
Start-Sleep -Seconds 3
H:\android\Sdk\platform-tools\adb.exe shell screencap -p /sdcard/settings_dark.png
H:\android\Sdk\platform-tools\adb.exe pull /sdcard/settings_dark.png f:/trae-project/shot_settings_dark.png
```

打开 `f:\trae-project\shot_settings_dark.png` 检查：
- 暗色背景光晕透出
- 玻璃卡片半透明白
- 文字可读

- [ ] **步骤 5：恢复亮色模式**

```bash
H:\android\Sdk\platform-tools\adb.exe shell cmd uimode night no
```

- [ ] **步骤 6：Commit 截图记录（可选）**

如截图无问题，无需 commit；如发现问题，按对应任务回退修复。

---

## 任务 9：整体验收

**文件：**
- 修改：无

- [ ] **步骤 1：对照设计文档验收标准**

逐项检查：
- ✅ 标题栏视觉与「发现/我的」完全一致（thin 毛玻璃、28sp ExtraBold「设置」）
- ✅ 6 个分组完整保留，所有现有功能可点击
- ✅ 4 页背景光晕连续（搜索/发现/我的/设置）
- ✅ 设置项可点击区域有 4% 主色高亮反馈
- ✅ 退出登录红色文字保留
- ✅ 亮/暗模式截图无视觉割裂
- ✅ 4 语言 strings 完整

- [ ] **步骤 2：运行所有测试**

```bash
./gradlew :app:test
```

预期：所有现有测试通过

- [ ] **步骤 3：总结改动**

输出修改文件列表 + commit hash 列表 + 截图路径。

---

## 风险与依赖

- **风险**：Haze 模糊叠加导致背景过白（已在「我的」页验证通过，本页同源）
- **风险**：缓存管理折叠状态需保留（`rememberSaveable`），玻璃卡化后状态不丢失
- **依赖**：`NeumorphicFrostedSurface` 组件已就绪
- **依赖**：`PageBackground` 已配置 `pageCount = 4`
