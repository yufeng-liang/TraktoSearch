# 极光推送接入 + 启动页视觉统一设计

## 概述

两个独立需求：
1. 接入极光推送，实现 App 被杀后服务端主动推送通知（上映提醒、新季提醒）
2. 修复双启动页问题，系统 splash 与自定义 splash 视觉统一，自定义 splash 支持最短显示时长

---

## Part 1：极光推送接入

### 架构

```
服务端定时任务 → 极光 REST API → 厂商通道(华为/小米/OPPO/vivo) → 手机通知
                                              ↓
                                    App 前台时走极光自建长连接通道
```

### 客户端改动

#### 1. 依赖添加 (build.gradle.kts)

```kotlin
// 极光推送
implementation("cn.jiguang.sdk:jpush:5.x.x")
implementation("cn.jiguang.sdk:jcore:4.x.x")
// 厂商通道（极光插件，自动适配）
implementation("cn.jiguang.sdk.plugin:xiaomi")
implementation("cn.jiguang.sdk.plugin:huawei")
implementation("cn.jiguang.sdk.plugin:oppo")
implementation("cn.jiguang.sdk.plugin:vivo")
```

#### 2. AndroidManifest.xml

- 添加极光 required 权限
- 声明极光 Service（`PushService`）和 Receiver（`PushReceiver`）
- 各厂商通道 meta-data（AppKey 等）

#### 3. 初始化 (TraktSearchApp)

- `JPushInterface.init(this)` 在 Application.onCreate 中调用
- 开发者模式下 `JPushInterface.setDebugMode(true)`

#### 4. Registration ID 上报

- App 启动时调用 `JPushInterface.getRegistrationID(context)` 获取 RID
- 将 RID 上报到服务端，关联 Trakt 用户账号
- RID 变化时重新上报

#### 5. 通知处理

- 极光 SDK 自动显示通知栏通知
- 自定义 `PushReceiver` 处理：
  - `onNotificationOpened`：点击通知跳转到详情页（复用现有 NotificationHelper 的 Intent 逻辑）
  - `onReceiveRegistrationId`：RID 变化时上报
- 前台时可通过 `onReceiveMessageRegistrationId` 处理透传消息

#### 6. ProGuard 规则

```proguard
-dontwarn cn.jpush.**
-keep class cn.jpush.** { *; }
-dontwarn cn.jiguang.**
-keep class cn.jiguang.** { *; }
```

### 服务端改动

#### 架构选择

轻量 Serverless 方案（云函数 / Cloudflare Workers / Vercel Cron）：

1. **Registration ID 存储**：KV 存储 `trakt_user_id → registration_id` 映射
2. **定时任务**：每天检查上映/新季 → 调极光 REST API 推送
3. **极光推送 API**：`POST https://api.jpush.cn/v3/push`

```json
{
  "platform": "android",
  "audience": { "registration_id": ["rid1", "rid2"] },
  "notification": {
    "android": {
      "title": "上映提醒：Movie Title",
      "alert": "已于 2026-06-23 上映/上线，快去看看吧",
      "extras": { "traktId": "123", "tmdbId": "456", "type": "movie" }
    }
  }
}
```

### 现有 WorkManager 处理

- 保留 WorkManager 作为降级方案：极光推送失败或未注册时仍靠 WorkManager 轮询
- 极光推送成功后，WorkManager 可降低频率或仅在 App 前台时执行
- 两套机制互补，不冲突

### 需要注册的开发者平台

| 厂商 | 平台 | 个人开发者 | 认证要求 |
|------|------|-----------|---------|
| 极光 | jpush.cn | 支持 | 手机号注册 |
| 华为 | 华为开发者联盟 | 支持 | 实名认证（身份证+人脸） |
| 小米 | 小米开放平台 | 支持 | 实名认证 |
| OPPO | OPPO 开放平台 | 支持 | 实名认证 |
| vivo | vivo 开发者平台 | 支持 | 实名认证 |

### 配置管理

- 极光 AppKey、各厂商 AppKey/AppSecret 存储在 `local.properties`
- 通过 `buildConfigField` 注入 BuildConfig
- 不提交到 Git

---

## Part 2：启动页视觉统一

### 当前问题

- Android 12+ 强制系统 SplashScreen，无法关闭
- 系统 splash（蓝色纯色背景+ic_launcher）→ 自定义 splash（蓝色渐变+品牌设计）视觉不统一
- 系统 splash 显示极短就切到自定义 splash，用户感知"闪了两下"

### 改动

#### 1. 系统 splash 视觉优化 (values-v31/themes.xml)

- `windowSplashScreenBackground`：保持 `@color/splash_background`（#4B88E6）
- `windowSplashScreenAnimatedIcon`：保持 `@mipmap/ic_launcher`
- `windowSplashScreenIconBackgroundColor`：保持 `@color/splash_icon_background`
- 目标：系统 splash 视觉与自定义 splash 尽可能一致

#### 2. 自定义 splash 最短显示时长 (MainActivity.kt)

```kotlin
companion object {
    private const val MIN_SPLASH_DURATION_MS = 1500L
}

override fun onCreate(savedInstanceState: Bundle?) {
    val splashStartTime = System.currentTimeMillis()
    val splashScreen = installSplashScreen()
    var keepSplashOnScreen = true
    splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }

    // ... 现有代码 ...

    lifecycleScope.launch {
        // 数据加载
        val isValid = tokenStorage.isTokenValid()
        val isGuest = guestModeStorage.isGuestMode.first()
        startDest = when {
            isValid -> Routes.MAIN
            isGuest -> Routes.MAIN
            else -> Routes.LOGIN
        }
        initialTab = if (isValid) 2 else 0
        val language = languageStorage.language.first()
        applyLanguage(language)

        // 确保最短显示时长
        val elapsed = System.currentTimeMillis() - splashStartTime
        if (elapsed < MIN_SPLASH_DURATION_MS) {
            delay(MIN_SPLASH_DURATION_MS - elapsed)
        }

        isReady = true
        keepSplashOnScreen = false
    }
}
```

#### 3. Android 12 以下兼容 (values/themes.xml)

- 添加 `Theme.App.Starting` 基础定义（parent 为 `Theme.TraktToSearch`）
- Android 12 以下不显示系统 splash，直接进入自定义 splash

### 效果

- 用户感知：一个流畅的启动页，从系统 splash 平滑过渡到自定义 splash
- 自定义 splash 至少显示 1.5 秒，品牌展示充分
- 无"闪两下"的割裂感
