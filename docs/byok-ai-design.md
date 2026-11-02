# BYOK AI 改造设计文档（共识稿）

- 日期：2026-11-03
- 状态：**待用户最终确认**（grilling 五轮已完成，fronter 为空）
- 结论先行：LLM 调用全部切到用户自配的 AI 提供商（BYOK），服务端 AI 网关仅为旧版 App 保留到硬截止日，之后 410 关停。新版 App 对 AI 网关依赖归零。

## 1. 背景与动机

用户原话（m00002）：「app内的AI功能，之前是服务端已经配置好AI能力，现在我改变主意，我想让用户自己配置自己的AI提供商，避免消耗我的额度。」

现状：`AiApiService.kt` 挂 13 个端点全部打到自建网关（gateway-pages 纯转发 → auth-worker 编排），其中 9 个烧 LLM/图片/TTS 额度（greeting、taste、quiz×4、daily×2、tts、detail/analyze、recommendations/rank + 服务端配图）。

## 2. 定案清单

### 2.1 替代范围与兼容

| # | 决策 |
|---|---|
| 1 | LLM 调用全走用户 Provider；服务端保留非 LLM 端点兼容旧版 |
| 2 | Provider 多协议原生实现：OpenAI 兼容 + Gemini 原生 + Anthropic 原生 |
| 3 | 新版 App 对 AI 网关**依赖归零**（characters/activate/profile 全本地化或删除） |
| 4 | 硬切，无双路径、无远程开关；版本号**不跳大版本**（4.2.0 量级） |

### 2.2 功能级决策

| # | 决策 |
|---|---|
| 5 | 激活门槛整个去掉：配 Key 即用全部功能；角色降级为本地 prompt 预设（7 角色人格数据直搬）；spokenName 唤醒保留纯本地实现 |
| 6 | TTS：OpenAI 兼容 `/audio/speech`（v1 不做 Gemini TTS）+ 不支持时降级安卓系统 TTS；配置页标注「朗读不可用」 |
| 7 | 角色音色：每角色映射一个标准 voice（alloy/echo/fable…）+ 自定义音色名透传，映射表本地写死；「音色跟随角色」开关可改 |
| 8 | 每日知识 AI 配图：走 OpenAI 兼容 `images/generations`；Provider 无图能力时 UI 隐藏插图位 |
| 9 | 流式：本地编排复刻 NDJSON 阶段性进度（真实串行 LLM 调用 + 真进度），客户端直连 Provider SSE |
| 10 | quiz：照搬服务端 4 单元槽并发 + 13 题槽 + repairRounds=2 编排；WorkManager 后台预生成当天 2 套题库（照搬 quiz-bank FNV-1a 确定性抽片），**默认开、可关**；关了就点开始现生成（等 1-3 分钟） |
| 11 | quiz/submit 判分本地化；quiz/feedback 不再上传服务端（prompt 迭代闭环断，属省额度自然代价） |
| 12 | 画像归本地：新版不再上传观影/行为数据；`AiProfileSyncCoordinator/Worker/Scheduler` 删除；`BehaviorRecorder` 保留本地喂 prompt；换机画像丢失，可后补本地导出 |

### 2.3 配置与存储

| # | 决策 |
|---|---|
| 13 | 配置粒度：全局默认（baseUrl + apiKey + model + 协议类型）+ 按功能覆盖模型；全部功能露出但收进「高级设置」折叠区，默认展开 quiz/tts 两行；带「测试连接」按钮 |
| 14 | 双 Provider：允许一个可选「语音/配图 Provider」（**仅 OpenAI 兼容**，单独 baseUrl+Key），TTS 与配图共用；不配则跟随全局 Provider（覆盖「DeepSeek 文本 + 硅基流动语音配图」主流组合） |
| 15 | Key 存储：Android Keystore + EncryptedSharedPreferences；不进日志/崩溃上报/备份（excludeFromBackup） |
| 16 | 没配 Key：AI 入口可见 → 配置引导页（教育「为什么要点自己的 Key」），配好才能用 |

### 2.4 Prompt 与服务端

| # | 决策 |
|---|---|
| 17 | Prompt 打进 App，接受可被反编译提取 |
| 18 | Prompt/角色直搬：服务端在本仓库 `auth-worker/src/ai/`，TS 模板字符串 + 纯数据，移植为 Kotlin 常量/函数，按当前 App 语言渲染 |
| 19 | 隐私声明：配置页明示「你的 API 请求（含观影记录）将发送到你配置的提供商」+ 隐私政策页更新 + 四语言 strings |

### 2.5 旧版关停路线

| # | 决策 |
|---|---|
| 20 | 硬截止：公告写死固定日历日期（发布后 90 天量级） |
| 21 | 发新版当天：服务端旧版配额砍半（运维动作，止血） |
| 22 | 截止日：`auth-worker` LLM 端点全部改回 `410 + message:"AI 服务已停止，请升级新版"`（同仓提交；旧版错误链路会把 message 展示在 snackbar） |
| 23 | 服务端画像接口（profile/settings、profile/sync）仅为旧版活到截止日 |

### 2.6 验证

| # | 决策 |
|---|---|
| 24 | 验证阶段真实 Key 由用户自己在模拟器里填（不进代码、不进 git），全自动跑完验证链路 |

## 3. 勘察事实（实施依据）

### 3.1 服务端代码位置（同仓库）

- 编排层：`F:\trae-project\auth-worker\`（Cloudflare Worker，main=src/index.ts），AI 逻辑约 12700 行在 `src/ai/`
- 入口代理：`F:\trae-project\gateway-pages\`（Pages name=tracktosearch-gateway），唯一函数 `functions/gateway-api/[[path]].js`（412 行）纯转发；流式端点超时 600s，其余 30s

### 3.2 Prompt 与人格位置（直搬清单）

| 内容 | 位置 |
|---|---|
| greeting prompt | `auth-worker/src/ai/handler.ts` ~2510 `greetingMessages()` |
| taste prompt | `handler.ts` ~2523 `tasteMessages()` |
| quiz 候选单元 prompt | `handler.ts` ~2575；二审转换器 ~2597 |
| quiz 槽位 prompt | `auth-worker/src/ai/quiz-slot-messages.ts`（unitSlotMessages ~108 / questionSlotMessages ~174） |
| daily 候选/审校 prompt | `handler.ts` ~992 / ~1018 |
| detail/analyze（无剧透助理） | `auth-worker/src/ai/detail-handler.ts` ~347 |
| recommendations/rank（排序器） | `detail-handler.ts` ~360 |
| 7 角色人格（chiikawa/hachiware/usagi/flying-squirrel/shisa/kurimanju/rakko） | `auth-worker/src/ai/characters.ts`（纯数据：name/aliases/personality/activationText/voiceDesignPrompt/sceneGuidance/activationPhrase/greetingCatchphrase/catchphrasePosition/spokenInAllScenes） |

### 3.3 编排与数据流

- quiz 流式路径：units 阶段 4 槽并发（QUIZ_UNIT_SLOT_COUNT=4，mapWithGate 并发池）→ review 阶段 13 槽（QUIZ_QUESTION_SLOT_COUNT=13），每槽 repairRounds=2；NDJSON stage/progress 事件 + 10s 心跳
- daily：candidate → review 两阶段，DAILY_REPAIR_ROUNDS=2
- quiz 题库：`quiz-bank.ts` = 确定性抽片（FNV-1a + 收尾混淆，不用 Math.random），QUIZ_SETS_PER_DAY=2，36h TTL；题库正文 LLM 现生成后缓存（**不是**现成题库）
- 配图：`daily-illustration.ts`，agnes-image-2.5-flash，style v3，R2 持久化 365 天，2 次尝试 + unavailable 10 分钟重试
- TTS：MiMo mimo-v2.5-tts-voicedesign → MP3 写 R2（tracktosearch-ai-audio）→ 10 分钟签名 URL 播放；音色样本只在服务端私有 R2（BYOK 后此层全换，与 #6/#7 一致）
- 服务端供应商梯队：PROVIDER_LADDER = bailian > minimax > zhipu > agnes > mimo（handler.ts:249）；密钥全在 wrangler secret，源码无密钥
- 客户端全部 AI 请求体**纯文本**（AiDetailAnalyzeRequest 等无图片字段）——BYOK 协议层不需多模态输入

### 3.4 错误码体系

`AiErrorCode`（AiRepository.kt:43-54）：UNAUTHORIZED/QUOTA_EXCEEDED/RATE_LIMITED/CHARACTER_UNAVAILABLE/ACTIVATION_REQUIRED/INVALID_REQUEST/INVALID_RESPONSE/NETWORK/SERVER/UNKNOWN。BYOK 后 ACTIVATION_REQUIRED/QUOTA_EXCEEDED 语义消失，CHARACTER_UNAVAILABLE 不再适用；新增 PROVIDER_INVALID（Key 无效/模型名错）、PROVIDER_NO_TTS、PROVIDER_NO_IMAGE。

## 4. 端点去向总表

| 现端点 | 新版去向 |
|---|---|
| characters | 本地 Kotlin 数据（characters.ts 直搬） |
| activate + spokenName | 本地纯本地实现（无网络） |
| profile/settings、profile/sync | 删除（新版）；服务端仅为旧版活到截止日 |
| greeting、taste | 本地 prompt → 用户 Provider chat |
| quiz（非流式）/quiz/stream | 本地 4+13 槽位编排 → 用户 Provider chat（流式 SSE） |
| quiz/submit | 本地判分 |
| quiz/feedback | 本地仅展示，不上传 |
| daily/daily/stream | 本地两阶段编排 → 用户 Provider chat |
| （daily 配图，隐含在 daily 响应里） | 语音/配图 Provider images/generations，无能力隐藏 |
| tts | 语音/配图 Provider audio/speech → 系统 TTS 兜底 |
| detail/analyze | 本地 prompt → 用户 Provider chat |
| recommendations/rank | 本地 prompt → 用户 Provider chat |

## 5. 实施计划（阶段划分，按依赖排序）

1. **配置层**：AiProviderConfig（DataStore + 加密）；双 Provider 模型（全局 + 可选语音/配图）；协议类型字段；「测试连接」
2. **协议适配层**：OpenAI 兼容 / Gemini 原生 / Anthropic 原生三套 adapter；chat（含 SSE 流式）、images/generations、audio/speech；错误映射到新 AiErrorCode；单测打底
3. **Prompt/角色移植**：characters.ts → Kotlin 数据类；handler.ts/quiz-slot-messages.ts/detail-handler.ts 全部 prompt → Kotlin（按 App 语言渲染）；quiz-slots.ts 规则表 + quiz-bank 确定性抽片 → Kotlin 纯函数（可单测对拍 TS 版）
4. **功能逐个切**（由简到繁）：greeting/taste → detail/analyze + recommendations/rank → daily（两阶段 + 配图）→ quiz（4+13 编排 + WorkManager 预生成 + 本地判分）→ tts（双路 + 角色音色映射）
5. **拆除**：激活流程删除；AiProfileSyncCoordinator/Worker/Scheduler 删除；quiz feedback 上传删除；BehaviorRecorder 保留改本地消费
6. **UI**：配置页（引导 + 双 Provider + 高级折叠 + 测试连接）；AI 入口无 Key → 引导页；隐私声明更新；帮助页同步；四语言 strings（en/zh/ja/ko）
7. **服务端**：auth-worker 配额砍半 + 410 关停改造（同仓提交）；发布公告文案
8. **验证**：`app/src/main/java/com/tracktosearch/...` 全量 `:app:compileDebugKotlin` → assembleDebug → 模拟器实测（用户填真实 Key）：逐功能走查 + logcat -b crash + 预生成 WorkManager 行为

## 6. 风险与已知代价

- **Prompt 反编译可提取**：已接受（#17）
- **quiz feedback 收集闭环断**：服务端不再回收反馈迭代 prompt（#11 自然代价）
- **DeepSeek 用户缺语音/图**：靠可选第二 Provider（#14）补齐，不配则降级
- **预生成扣费无感**：隐私声明覆盖（#19），且提供开关（#10）
- **换机画像丢失**：可后补本地导出（#12）
- **多协议维护成本**：三套 adapter + 双 Provider 组合矩阵的测试面，靠单测打底（阶段 2）

## 7. 实施时再定的小项（非阻塞）

- 配置页 Provider 预设模板（DeepSeek/硅基流动/Gemini 一键填 baseUrl），提升配置成功率
- 各功能默认参数（temperature/maxTokens）按服务端现有值等价迁移
- 具体版本号（4.2.0 量级）与截止日期，发布时定
