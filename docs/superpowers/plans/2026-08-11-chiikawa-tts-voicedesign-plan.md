# Chiikawa 音色设计 TTS V1 实现计划

> **面向 AI 代理的工作说明：** 本计划在当前专用 worktree 内由主代理按检查点执行；每个逻辑改动先补失败测试，再实现、验证并单独提交。Worker 与 Android 共用同一 JSON 契约，主代理在跨层改动后集中复核。

**目标：** 将 AI 精灵 TTS 从 voiceclone 样本链路切换为 MiMo voicedesign，支持角色基础音色与 `AUDITION`、`ACTIVATION_ACK`、`GREETING` 三类场景，提供私有 R2 MP3 缓存、短时签名播放 URL、访客试听和双倍 AI 配额。

**架构：** Worker 内新增专注的 TTS 服务模块；`characters.ts` 只保存角色展示资料、基础音色和场景提示词；`mimo.ts` 只负责供应商请求形状；`store.ts` 负责现有 D1 配额/文本缓存与 KV 限流；`handler.ts` 负责路由 DTO 和业务编排；Android 保持 `AiRepository`/`AiAudioPlayer` 边界不变，仅扩展序列化字段和访客预览事件。

**技术栈：** Cloudflare Worker、TypeScript、D1、KV、可选 R2、MiMo OpenAI-compatible Chat Completions、Kotlin/Jetpack Compose、kotlinx.serialization、Retrofit、JUnit/MockK。

## 任务 1：先锁定 Worker voicedesign 契约

**文件：**
- 修改：`auth-worker/tests/ai-routes.test.mjs`
- 修改：`auth-worker/src/ai/characters.ts`
- 修改：`auth-worker/src/ai/mimo.ts`
- 修改：`auth-worker/src/ai/handler.ts`

- [ ] **步骤 1：编写失败测试**

在 `ai-routes.test.mjs` 增加三个可执行断言：上游请求的 `model` 为 `mimo-v2.5-tts-voicedesign`、`audio.format` 为 `mp3`、`audio.optimize_text_preview` 为 `false`，第一条 `user` 消息含角色/场景指导，第二条 `assistant` 消息等于固定试听文本且角色目录不含完整基础音色提示词；`scene=UNKNOWN` 返回 `INVALID_SCENE` 且旧 `style` 中的自定义音色文本不出现在上游提示词；乌萨奇激活返回“到——！”且请求场景为 `ACTIVATION_ACK`。

- [ ] **步骤 2：运行测试，确认它因旧模型/旧响应失败**

运行：

```bash
cd auth-worker
node --experimental-strip-types --test tests/ai-routes.test.mjs
```

预期：失败原因包括仍发送 `mimo-v2.5-tts-voiceclone`、WAV、样本就绪门槛，以及没有场景校验。

- [ ] **步骤 3：实现最小契约**

在 `characters.ts`：

- 增加 `TtsScene` 类型、每个角色的 `voiceDesignPrompt`、`sceneGuidance`、`activationPhrase`、`greetingCatchphrase`。
- 保留 `personality` 作为文本模型角色设定；`characterCatalog()` 只返回现有展示摘要，不返回 `voiceDesignPrompt` 或完整 `sceneGuidance`。
- 角色就绪状态改为检查 voicedesign 可用条件：生产环境检查 `MIMO_API_KEY`，测试环境通过显式测试变量标记就绪；不再用 `AI_VOICE_SAMPLES` 阻断 V1。

在 `mimo.ts`：

- 将 `mimo-v2.5-tts-voicedesign` 加入模型白名单。
- 扩展 `callMimoAudio()` 只允许文档支持的 TTS 模型，并将 `optimizeTextPreview` 映射为 `optimize_text_preview`。
- voicedesign 请求不携带 `voice` 样本字段；voiceclone 分支保留现有 `voice` 参数。

在 `handler.ts`：

- 增加场景解析和三段式提示词组装，缺省访客试听为 `AUDITION`，登录 TTS 缺省为 `GREETING`。
- `synthesizeShortReply()` 改为向 voicedesign 发送 `user` 音色描述与 `assistant` 原文，格式为 MP3，关闭文本优化。
- 激活应答使用角色静态 `activationPhrase` 与 `ACTIVATION_ACK`；欢迎语使用 `GREETING`。
- `style` 只读取用于兼容而不覆盖服务端提示词。

- [ ] **步骤 4：运行测试确认通过**

运行同一条 `node --experimental-strip-types --test tests/ai-routes.test.mjs`，预期新增契约测试和既有非配额测试通过。

- [ ] **步骤 5：提交**

```bash
git add auth-worker/tests/ai-routes.test.mjs auth-worker/src/ai/characters.ts auth-worker/src/ai/mimo.ts auth-worker/src/ai/handler.ts
git commit -m "feat(ai-tts): 接入角色音色设计提示词"
```

## 任务 2：增加 R2 音频缓存、签名 URL 和限流

**文件：**
- 创建：`auth-worker/src/ai/tts.ts`
- 修改：`auth-worker/src/ai/store.ts`
- 修改：`auth-worker/src/ai/handler.ts`
- 修改：`auth-worker/src/index.ts`
- 修改：`auth-worker/src/ai/mimo.ts`
- 修改：`auth-worker/tests/ai-routes.test.mjs`
- 修改：`auth-worker/wrangler.toml`

- [ ] **步骤 1：编写失败测试**

增加最小 R2 fake bucket，并锁定五个行为：第二次相同角色/场景/文本只发生一次上游调用、返回 `/api/ai/audio/<token>` 且不带 `audioDataUrl`；签名 URL 返回 `audio/mpeg`，过期 token、篡改 token、未知对象分别拒绝；`put()` 抛错时仍返回 `data:audio/mpeg;base64,<encoded-bytes>`；同一文本的 `AUDITION` 与 `GREETING` 对象键不同，改变提示词版本也不命中；同一会话第 14 次未命中成功、第 15 次返回 `AI_SESSION_QUOTA_EXCEEDED`，缓存命中不增加测试配额计数。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
cd auth-worker
node --experimental-strip-types --test tests/ai-routes.test.mjs
```

预期：当前没有 `AI_AUDIO_CACHE`、签名音频路由、MP3 对象缓存，配额仍为 7/40，相关测试失败。

- [ ] **步骤 3：实现 TTS 服务模块**

在 `tts.ts` 实现以下边界：

- `TTS_CACHE_VERSION = "tts-vd-v1"`；缓存摘要包含版本、角色 ID、场景、完整规范化 `spokenText`、MP3 格式和最终提示词。
- R2 对象键使用 `tts-vd-v1/<sha256>.mp3`，写入 `contentType: audio/mpeg` 和 30 天过期元数据。
- 先 `head()` 检查 R2；命中时只生成短时 URL，不调用 MiMo，不返回大体积 data URL。
- 使用现有 JWT HMAC Secret 生成仅含 `audio` scope、600 秒有效期的签名播放 token；播放端点验证 scope、路径前缀和过期时间。
- 使用实例内 `Map<string, Promise<TtsSynthesisResult>>` 做同一缓存键的 single-flight，并在 `finally` 清理；Map 只保存正在进行的调用，不保存用户资料。
- R2 不存在或写入失败时保留 MiMo 返回的 `audioDataUrl`，不让缓存存储故障阻断播放。

在 `index.ts`：

- 为 `Env` 增加可选 `AI_AUDIO_CACHE?: R2Bucket`。
- 将 `GET /api/ai/audio/<token>` 放入公开路由分支，由签名 token 保护，不要求 App JWT。

在 `wrangler.toml`：

- 保留 `AI_VOICE_SAMPLES` 注释作为未来 voiceclone 配置说明。
- 添加 `AI_AUDIO_CACHE` 的部署配置注释和私有桶要求；不在本地创建或生产绑定未知的 R2 桶。

- [ ] **步骤 4：调整配额与 KV 限流**

在 `store.ts`：

- 将会话/每日上限改为 14/80，并同步 D1 UPSERT 的 14、80 边界。
- 增加 AI TTS IP 请求计数：10 分钟 20 次总请求，另计 10 分钟 5 次未命中 MiMo；测试模式使用 Map，生产使用现有 `KV`。

在 `handler.ts`：

- TTS 先做输入校验、限流和缓存查找；只有未命中且确实需要 MiMo 时才 reserve 配额。
- 对已有 greeting/taste/quiz/daily 文本缓存把 reserve 移到缓存 miss 后，统一实现“缓存命中不计配额”，并同步现有测试。
- 欢迎文本缓存命中时不触发文本模型；欢迎音频失败时保留文字响应，音频为 null。
- 激活的 ASR 交互仍计一次配额；激活确认音频命中或失败都不额外计数，失败不阻断 `activated: true`。

- [ ] **步骤 5：运行 Worker 测试确认通过**

运行：

```bash
cd auth-worker
node --experimental-strip-types --test tests/ai-routes.test.mjs
npm test
npm run typecheck
```

预期：AI 路由测试覆盖新的 14/80、缓存、签名 URL、R2 回退和限流；完整 Worker 测试与类型检查通过。

- [ ] **步骤 6：提交**

```bash
git add auth-worker/src/ai/tts.ts auth-worker/src/ai/store.ts auth-worker/src/ai/handler.ts auth-worker/src/ai/mimo.ts auth-worker/src/index.ts auth-worker/tests/ai-routes.test.mjs auth-worker/wrangler.toml
git commit -m "feat(ai-tts): 增加音频缓存与签名播放"
```

## 任务 3：更新 Android 数据契约与本地缓存键

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/ai/AiModels.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/ai/AiRepository.kt`
- 修改：`app/src/test/java/com/tracktosearch/data/ai/AiModelsTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/data/ai/AiRepositoryTest.kt`

- [ ] **步骤 1：编写失败测试**

增加三个具体断言：`AiGreetingDto(greeting = "干净文本", spokenText = "干净文本，呀哈！").toDomain()` 必须同时保留两个字段；`AiTtsRequest(characterId = "usagi", text = "到！", scene = "ACTIVATION_ACK")` 的 JSON 必须包含该场景；`AiQuotaDto()` 的 `sessionLimit`/`dailyLimit` 必须分别为 14/80。

测试必须验证：`spokenText` 不覆盖 `greeting`；`scene` 区分本地 TTS 缓存键；旧 `style` 字段仍能反序列化但不参与服务端音色控制；默认配额为 14/80。

- [ ] **步骤 2：运行 Android 定向测试确认失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --tests com.tracktosearch.data.ai.AiModelsTest --tests com.tracktosearch.data.ai.AiRepositoryTest
```

预期：当前 DTO 没有 `spokenText`/`scene`，默认配额仍为 7/40，测试失败或无法编译。

- [ ] **步骤 3：实现契约**

在 `AiModels.kt`：

- `AiTtsRequest` 增加可选 `scene`，保留 `style` 兼容字段。
- `AiGreetingDto`/`AiGreeting` 增加 `spokenText`，并在转换中保留外层 quota。
- `AiQuotaDto` 默认值改为 14/80。

在 `AiRepository.kt`：

- TTS 本地缓存摘要加入 `scene`，不把客户端自由 `style` 纳入角色音色身份。
- 保留现有 `audioDataUrl`/`audioUrl` 播放兼容。

- [ ] **步骤 4：运行定向测试确认通过**

运行同一条 `:app:testDebugUnitTest` 命令，预期 AI Models/Repository 测试通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/tracktosearch/data/ai/AiModels.kt app/src/main/java/com/tracktosearch/data/ai/AiRepository.kt app/src/test/java/com/tracktosearch/data/ai/AiModelsTest.kt app/src/test/java/com/tracktosearch/data/ai/AiRepositoryTest.kt
git commit -m "feat(ai-tts): 扩展 Android 音频场景契约"
```

## 任务 4：接入访客 Worker 试听与失败回退

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/ai/AiSpriteViewModel.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/ai/AiUiLogic.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/ai/AiSpriteCenter.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/ai/AiUiLogicTest.kt`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：先增加可验证的 ViewModel 预览行为**

沿用现有 `audioEvents`，增加仅访客使用的文字回退事件流，并在 `AiUiLogic.kt` 抽取 `buildAuditionTtsRequest(character, sessionId)` 纯函数；在 `AiUiLogicTest.kt` 锁定以下行为：

- `buildAuditionTtsRequest(AiCharacter("usagi", "乌萨奇", "乌萨奇", auditionText = "到！"), "sprite-test")` 产生的请求文本为“到！”，场景为 `AUDITION`，会话 ID 为“sprite-test”。
- 记录预览分支规则：访客调用 `playGuestTts()`，授权用户调用 `playTts()`；成功只发音频事件，失败只发文字回退事件。
- 角色目录暂不可用时，访客仍可使用当前角色的静态试听文本回退到系统 TTS。

- [ ] **步骤 2：运行定向 Android 测试确认失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --tests com.tracktosearch.data.ai.AiRepositoryTest
```

预期：当前不存在 `buildAuditionTtsRequest` 和访客预览事件流，新增纯逻辑测试无法编译。

- [ ] **步骤 3：实现 UI 链路**

在 `AiSpriteViewModel.kt`：

- `loadCharacters()` 即使访客也读取公开角色目录，失败时保留本地目录。
- `previewSelectedCharacter()` 根据授权状态选择 `playGuestTts()` 或登录 TTS，显式发送 `scene = AUDITION`。
- 增加 `guestPreviewFallbackEvents`；Worker 失败、未就绪或网络失败时发送静态试听文本。
- 初始化选中角色和切换角色共用同一取消 Job，避免旧请求返回后播放错误角色。

在 `AiSpriteCenter.kt`：

- 收集音频事件和访客回退事件；删除会在 Worker 音频成功时造成重复播放的无条件系统 TTS `LaunchedEffect`。
- 登录后的欢迎 UI 继续显示语义 `greeting`，音频按钮播放 `AiAudio`；不把口癖重复拼到 UI 文本。

在四套 `strings.xml`：

- 更新帮助页关于“音频样本保存在私有服务”的旧说明，改为“V1 使用角色与场景的音色设计提示词，音频失败时试听回退系统语音”。
- 保持所有用户可见文字走既有 `stringResource`，不在 Kotlin 中硬编码新文案。

- [ ] **步骤 4：运行 Android 测试确认通过**

运行：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/ai/AiSpriteViewModel.kt app/src/main/java/com/tracktosearch/ui/screen/ai/AiUiLogic.kt app/src/main/java/com/tracktosearch/ui/screen/ai/AiSpriteCenter.kt app/src/test/java/com/tracktosearch/ui/screen/ai/AiUiLogicTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat(ai-tts): 接入访客角色试听回退"
```

## 任务 5：集成验证与上线前边界检查

- [ ] **步骤 1：静态契约审查**

检查以下内容：

- Worker 和 Android 的 `scene` 枚举完全一致。
- `spokenText` 只出现在动态欢迎的实际播报契约，干净 `greeting` 仍用于 UI 语义展示。
- Worker 目录不返回 `voiceDesignPrompt`/`sceneGuidance`。
- `MIMO_API_KEY`、JWT Secret、音频内容未进入日志、源码或 APK。
- R2 写入失败、MiMo 失败、访客回退和签名 URL 过期都有明确行为。

- [ ] **步骤 2：运行全量本地验证**

```bash
cd auth-worker
npm run typecheck
npm test
git diff --check

cd ..
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

如果 Gradle 工具超时，先检查 Gradle 进程、测试 XML、报告和 APK/编译产物，再决定结果；不把工具层超时直接当作失败。

- [ ] **步骤 3：检查 Git 范围**

```bash
git status --short
git diff --name-only HEAD~4..HEAD
```

只允许出现本计划涉及的 Worker、Android、四套字符串和已提交的 specs/plans 文档；不提交测试音频、R2 文件、构建产物、临时目录或密钥。

- [ ] **步骤 4：保留部署前阻塞项**

代码验证不等于生产上线。部署前必须单独配置私有 `AI_AUDIO_CACHE` R2 桶、确认 Wrangler Secret、执行 Worker 部署，并分别验证角色目录、访客试听、认证激活、欢迎音频、签名音频 URL、配额和回退。没有完整命令证据时不宣称已上线。
