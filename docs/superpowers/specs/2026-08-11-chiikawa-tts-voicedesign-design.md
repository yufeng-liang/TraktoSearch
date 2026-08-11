# Chiikawa 音色设计 TTS V1 设计

> 状态：已获用户确认，进入实现计划
>
> 日期：2026-08-11

## 目标

将 AI 精灵的 TTS V1 从依赖授权音色样本的 `mimo-v2.5-tts-voiceclone` 切换为基于中文音色设计提示词的 `mimo-v2.5-tts-voicedesign`，先以稳定、可辨识、可缓存的角色化中文语音上线。

V1 覆盖七个 Chiikawa 角色和三个场景：`AUDITION`（试听）、`ACTIVATION_ACK`（激活应答）、`GREETING`（动态欢迎）。提示词使用“角色 / 场景 / 指导”三段式，角色基础声线由 Worker 静态配置，场景只能调整表达方式，不能重写基础声线。

## 非目标

- V1 不上传、不处理、不依赖任何角色或真人的录音样本。
- V1 不开放客户端自定义音色提示词；旧 `style` 字段只保留兼容解析。
- V1 不在仓库提交生成的 MP3、R2 对象或任何供应商密钥。
- V1 不改造现有音频播放器；播放器已有 `audioDataUrl` 与 `audioUrl` 两种输入能力。

## 方案选择

### 方案 A：MiMo voicedesign（采用）

Worker 将角色基础声线和场景指导组合为 1-4 句中文设计提示词，使用 `mimo-v2.5-tts-voicedesign` 请求音频。MiMo 官方接口通过第一条 `user` 消息接收音色描述，第二条 `assistant` 消息接收要播报的原文；`audio` 设置为 MP3 并关闭 `optimize_text_preview`。

优点是无需样本授权、可以按场景表达、上线边界清楚。代价是同一角色可能有轻微音色漂移，因此通过短提示词、固定版本、候选音频验收和跨用户缓存控制漂移。

### 方案 B：voiceclone

保留现有能力供未来在取得书面授权后使用，但 V1 继续依赖样本会阻塞角色上线，也无法满足当前先用音色设计上线的目标。

### 方案 C：系统 TTS

仅可作为失败回退，不能提供角色辨识度，因此不作为主链路。

## 角色提示词矩阵

所有请求都附加以下固定指导：

> 使用中文普通话；保持角色基础声线不变；只朗读 `assistant` 消息中的原文，不增词、不删词、不解释提示词。角色口癖由 Worker 在 `spokenText` 中预先写入。

### 吉伊

- 基础音色：细小、柔软、明亮的中高音；温柔认真，带一点害羞和紧张，亲近而清楚。
- `AUDITION`：像第一次自我介绍，轻声、慢半拍，句尾温柔上扬。
- `ACTIVATION_ACK`：只说“到、到！”，先犹豫后坚定，短而清楚。
- `GREETING`：真诚、轻柔、有陪伴感；允许在 `spokenText` 末尾加入“一起看吧”。

### 小八

- 基础音色：清澈明亮的中高音；友善乐观，带自然笑意和鼓励感，不吵闹。
- `AUDITION`：像发现片单线索后开心分享，节奏明快，关键词自然上扬。
- `ACTIVATION_ACK`：只说“到！我在哦！”，明亮积极，像马上来帮忙。
- `GREETING`：热情分享，允许在 `spokenText` 末尾加入“对吧！”。

### 乌萨奇

- 基础音色：跳跃、活泼、爆发力强的明亮中高音；短促有力，情绪反应夸张，但不能持续尖叫。
- `AUDITION`：像突然发现好电影，重点词有冲击力，结尾可有轻快口癖但不能遮住正文。
- `ACTIVATION_ACK`：只说“到——！”，明显提高音量并持续拉长“到”，随后快速收束；音量受控，不失真。
- `GREETING`：开心处抬高音量，短促有冲劲；允许在 `spokenText` 末尾加入“呀哈！”。

### 飞鼠

- 基础音色：甜、轻快、机灵的中高音；带撒娇、爱表现和得意感，尾音灵动，避免尖锐。
- `AUDITION`：像抢着展示自己的推荐，轻快、得意，句尾上扬。
- `ACTIVATION_ACK`：只说“听到啦！”，带一点撒娇和得意，尾音上扬。
- `GREETING`：像在展示自己的发现；允许在 `spokenText` 末尾轻带“嘿嘿”，但不过度拖长。

### 狮萨

- 基础音色：温暖、稳妥、清晰的中音；认真体贴，有自然服务感，不夸张。
- `AUDITION`：像服务台欢迎用户，礼貌、均匀、有条理。
- `ACTIVATION_ACK`：只说“到，我在。”，温和清楚，立即响应。
- `GREETING`：给用户安心感，清楚细致；允许在 `spokenText` 末尾加入“慢慢来就好”。

### 栗子馒头

- 基础音色：成熟、松弛的中低音；沉着、满足、略慵懒但不冷漠，停顿自然。
- `AUDITION`：像坐下后慢慢聊电影，语速从容，评价词略有重量。
- `ACTIVATION_ACK`：只说“嗯，到。”，低声从容，保留自然停顿。
- `GREETING`：像陪伴式低声旁白；允许在 `spokenText` 末尾轻声加入“嗯”。

### 獭师

- 基础音色：可靠、克制的中低音；沉着、耐心，像温和而有权威感的前辈，不说教。
- `AUDITION`：像开始一次认真复盘，清晰稳健，句尾落地。
- `ACTIVATION_ACK`：只说“到。开始吧。”，沉稳有力，节奏干净。
- `GREETING`：鼓励用户一步一步选择；允许在 `spokenText` 末尾加入“一步一步来”。

## MiMo 请求

Worker 的音频请求统一形状为：

```json
{
  "model": "mimo-v2.5-tts-voicedesign",
  "messages": [
    {"role": "user", "content": "角色 / 场景 / 指导提示词"},
    {"role": "assistant", "content": "spokenText"}
  ],
  "audio": {
    "format": "mp3",
    "optimize_text_preview": false
  },
  "stream": false
}
```

提示词保持简短且互不矛盾；不加入混响、EQ 等后处理描述。角色名和动画化说话风格可以出现在提示词中，但不引用具体真人声优或未经授权的录音样本。

`mimo-v2.5-tts-voiceclone` 的 Worker 封装保留为未来能力，但 V1 的角色可用性不再由 `AI_VOICE_SAMPLES` 决定。

## API 契约

### 请求

- Android `AiTtsRequest` 新增 `scene: String?`，允许值为 `AUDITION`、`ACTIVATION_ACK`、`GREETING`。
- Worker 对缺省 `scene` 使用调用方场景默认值：固定试听文本为 `AUDITION`，激活应答为 `ACTIVATION_ACK`，普通登录 TTS 为 `GREETING`。
- `style` 只接受旧客户端发送但不再覆盖服务端提示词；服务端忽略自由文本，避免用户改变角色基础声线。
- 试听访客只能请求该角色静态 `previewText`；登录用户 TTS 仍限制 500 字符。

### 响应

- `AiAudioDto` 继续同时返回 `audioDataUrl` 和 `audioUrl`，保持旧客户端兼容。
- 动态欢迎响应保留干净的 `greeting`、`nicknameMeaning`、`comment`，新增顶层 `spokenText` 表示音频实际播报文本。
- 欢迎音频的 `transcript` 与 `spokenText` 保持一致；没有音频时仍返回文字和 `spokenText`，客户端可以重试播放。
- 激活成功继续返回角色信息、静态 `activationPhrase` 和音频；不同角色的激活短句由 Worker 配置决定。

## 缓存、配额与限流

- 音频缓存键至少包含：缓存协议版本、提示词版本、角色 ID、场景、规范化播报文本和音频格式；不包含用户 ID，允许跨用户复用。
- 私有 R2 保存 MP3，缓存 TTL 为 30 天；响应中的签名 `audioUrl` 有效期 10 分钟。
- R2 写入失败时返回 `audioDataUrl`，不让一次存储故障阻断音频播放。
- Worker 实例内对相同缓存键做 single-flight，避免并发请求重复调用 MiMo。
- 缓存命中先返回且不消耗 AI 配额；只有确认未命中后才计数。
- AI 配额为每会话 14 次、每日 80 次。
- KV 频控为每 IP 10 分钟 20 次请求，其中最多 5 次未命中 MiMo 请求；超过限制返回可重试错误。

## 欢迎语与回退流程

1. 文本模型生成干净的 `greeting` 等语义字段。
2. Worker 按角色静态规则追加允许的口癖，生成 `spokenText`。
3. 以角色、场景和 `spokenText` 查询音频缓存。
4. 未命中时调用 MiMo voicedesign 并写入 R2。
5. MiMo 或 R2 失败时保留文字；访客试听额外回退 Android 系统 TTS，激活和欢迎只显示文字并提供重试。

固定试听和激活文本不交给文本模型临时改写。`optimize_text_preview` 关闭，保证音频、`spokenText` 和 UI 语义字段可追踪。

## 质量验收与上线门槛

每个角色的三个场景各生成保守版、表现版两条候选，共 42 条真实 MP3。候选不提交 Git，存放在受控验收目录或私有 R2。

每条候选按 1-5 分评估：角色辨识度、声线稳定度、场景匹配、中文清晰度、提示词执行度。每个角色 × 场景选择一条最终版本；21 个组合必须全部达到约定最低分且无明显破音、漏字、擅自增词，才允许发布 V1。

自动化测试覆盖提示词选择、场景校验、旧 `style` 兼容、`spokenText` 生成、缓存键版本隔离、缓存命中不计配额、R2 回退、访客试听限制、配额翻倍和限流。真实 MiMo 与真实 R2 另做部署后验证，不以测试回退模式代替。

## 安全与运维边界

- `MIMO_API_KEY` 只使用 Wrangler Secret；不写入源代码、日志、APK、设计文档或提交记录。
- 角色完整提示词只在 Worker 内部存在，公开角色目录返回展示摘要。
- R2 音频桶保持私有，只通过短时签名 URL 或数据 URL 输出。
- voiceclone 样本绑定未来启用前必须有覆盖知识产权、录音、声音克隆和应用分发的书面授权。
- 本设计不包含生产部署；部署前需单独确认 Wrangler 身份、R2/KV 绑定、Secret 和生产验证窗口。

## 参考资料

- [MiMo Speech Synthesis V2.5](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5)
- [Chiikawa Official Characters](https://www.chiikawaofficial.com/characters)
- [Chiikawa Anime Characters](https://www.anime-chiikawa.jp/chara.html)
