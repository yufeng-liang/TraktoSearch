# Chiikawa 音色设计 TTS V1 验证报告

日期：2026-08-12
当前提交：`0b4becb`（detached HEAD）

## 结论

Worker 的 voicedesign TTS 代码链路已经部署到生产，角色目录、访客试听、R2 私有缓存和签名 MP3 播放均完成线上 smoke。Android/Worker 的本地验证通过。

声音候选已按设计稿生成并保存在仓库外的受控目录，但“角色辨识度、声线跨次稳定度、破音”的人耳验收仍未完成。因此本报告把 21 条作为自动化预选，不把它们描述为最终听感签字结果。

## 实现范围

- 7 个角色分别配置基础音色和 `AUDITION`、`ACTIVATION_ACK`、`GREETING` 场景指导，调用模型为 `mimo-v2.5-tts-voicedesign`。
- Worker 使用 MP3、`optimize_text_preview=false`、角色/场景提示词加 `assistant` 播报原文。
- R2 对象键包含 `tts-vd-v1`、角色、场景、文本、提示词版本和格式摘要；R2 写入 `expiresAt`，命中会检查 30 天过期。
- 音频响应返回短时签名 URL及 `audioUrlExpiresAt`；Android 遇到缺失或过期的签名 URL 会重新请求，不会永久复用旧 URL。
- 激活确认音频和欢迎音频失败时保留文字成功响应，`audio` 为 `null`；音频 `transcript` 固定为规范化 `spokenText`。
- Android 将 Worker 的三个场景收敛为可序列化 `AiTtsScene` 枚举，保留旧 `style` 字段解析兼容。
- 鉴权失败日志对 `/api/ai/audio/<token>` 使用 `/api/ai/audio/:token`，避免把签名 token 写入日志或计数键。

## 代码与测试证据

已提交的逻辑改动：

`65a3061` → 角色提示词；`7db69fe` → R2、签名 URL、single-flight、限流；`b6b4f7b` → Android `spokenText`、场景和 14/80 配额；`288fa07` → 访客试听和回退；`9aa16e3` → JWT base64url 校验；`6565fea` → 网关音频地址；`3fcb6b1` → 场景枚举、过期缓存、transcript 和失败回退；`0b4becb` → 问候缓存到期和 R2 元数据校验。

新鲜执行结果：

| 检查 | 结果 |
|---|---|
| `auth-worker/npm test` | 108 pass，0 fail，0 skipped |
| `auth-worker/npm run typecheck` | 退出码 0 |
| `npx wrangler deploy --dry-run` | 退出码 0，R2 `tracktosearch-ai-audio` 绑定存在 |
| `:app:testDebugUnitTest` | 1774 tests，0 failures，0 errors，0 skipped |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| ADB/AVD | `adb devices -l` 无在线设备；`emulator -list-avds` 无可用 AVD |

## 生产证据

- 最新 Worker 版本：`69edf722-4156-432d-8337-bb7b22d1f3fe`。
- `https://auth-worker.douban-movie-api-peak.workers.dev/health`：`200/SUCCESS`。
- `https://tracktosearch-gateway.pages.dev/gateway-api/health`：`200/SUCCESS`。
- 角色目录：`200/SUCCESS`，7 个角色，全部 `ready`，`usagi.available=true`，完整 `voiceDesignPrompt`/`sceneGuidance` 未返回。
- 访客试听：`200/SUCCESS`，签名 URL 存在，`audioDataUrl=null`，`mimeType=audio/mpeg`，`transcript` 与请求文本一致。
- 签名音频 GET：`200`，`Content-Type=audio/mpeg`，本次响应 19416 bytes；篡改签名返回 `403`，签名 URL 到期时间字段在 10 分钟窗口内。

本次未用真实认证账号执行激活和欢迎链路；没有伪造 JWT 或改变生产 D1 数据。R2 写入、签名播放的真实公开试听结果已验证，认证链路和 Android 设备播放仍是独立证据层。

## 候选音频验收

受控目录：`C:\Users\15778\Documents\Codex\2026\08\12\chiikawa-tts-v1-acceptance`。目录不在 Git 内，包含 42 个真实 MP3、`candidates.json`、`asr.json`、`scores.json` 和 `scores.csv`。

自动化结构检查：

- 42/42 请求成功，42/42 文件存在 MP3 frame sync，42 个 SHA-256 全部唯一。
- 21 个角色×场景组合均各有保守版和表现版；最终自动预选 21 条。
- MiMo ASR 去 NFKC、去标点后完全匹配 25/42；其余主要为“到/道”同音、语气词差异或个别词误识别。
- 自动预评估五项均按 1–5 分记录；预选总分范围 19–20/25，最低单项为 3。声线稳定度和角色辨识度的分数是提示词/单次产物预估，不能代替人耳复核。

自动预选矩阵：

| 角色 | AUDITION | ACTIVATION_ACK | GREETING |
|---|---|---|---|
| 吉伊 | conservative | expressive | conservative |
| 小八 | conservative | expressive | conservative |
| 乌萨奇 | conservative | expressive | expressive |
| 飞鼠 | conservative | conservative | expressive |
| 狮萨 | conservative | conservative | conservative |
| 栗子馒头 | conservative | conservative | conservative |
| 獭师 | conservative | conservative | conservative |

发布前仍需人工听感复核：每个角色至少比较两版，确认角色辨识度、三场景表达、长音“到——！”的控制、中文漏字/增字、破音和二次生成的稳定性；确认后再把 21 条迁移到私有 R2 作为最终候选基线。

## 邮件与安全

- 本报告不包含 MiMo key、JWT secret、SMTP 授权码或签名 token。
- MiMo key 仅从本地受控文件读入进程内存，未写入仓库、日志、APK 或邮件正文。
- 首次 SMTP 配置检查因当前 shell 未设置 `SMTP_HOST` 失败；发送时应在当前进程临时注入 `smtp.qq.com:465` 和授权码，不写入项目文件或命令历史。
