# AI 供应商健康监控与 Dashboard 外链 — 设计文档

日期：2026-09-10
状态：已批准（用户按推荐拍板）
范围：auth-worker（探针 + 被动记录 + 查询端点）+ app-config 后台前端（「AI 健康」页）

## 背景与动机

2026-09-09 出题链路「三家上游全灭」排查暴露的运维盲区：zhipu 生产 secret 是旧 key（401）、mimo 欠费（402）、agnes 共享池 429 冷却——三个问题都只能靠临时免认证探测端点 + Workers Logs 才定位。需要常驻手段：

1. **被动记录**：每次真实 AI 调用的成败/耗时/错误码落库，反映真实流量健康度
2. **主动探测**：后台手动发最小请求，发现「没有真实流量才暴露不了」的问题（欠费、key 失效）
3. **Dashboard 外链**：一键前往各家控制台看用量/账单/模型详情

## 方案（已选定 A）

全部装进 auth-worker + 后台新页签。不建独立 health-worker（监控规模不值得新服务/新部署/新鉴权）。Pages 同源代理 `/admin-api` → auth-worker service binding 零改动。

## ① 数据模型（migration 0018）

```sql
CREATE TABLE IF NOT EXISTS ai_health_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,     -- epoch 秒
    source TEXT NOT NULL,            -- 'traffic' 真实调用 | 'probe' 主动探测
    route TEXT,                      -- quiz / quiz-units / quiz-review / daily-candidate / daily-review / taste / greeting / probe
    provider TEXT NOT NULL,          -- zhipu | agnes | mimo
    model TEXT NOT NULL,
    outcome TEXT NOT NULL,           -- success | upstream_error | invalid_output
    error_code TEXT,                 -- AI_UPSTREAM_ERROR / INVALID_AI_OUTPUT / INVALID_MODEL / REQUEST_TIMEOUT / NULL(成功)
    http_status INTEGER,             -- 上游 HTTP 状态码（402 欠费 / 401 坏 key / 429 限频）
    duration_ms INTEGER,
    request_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_ai_health_created ON ai_health_events(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_health_provider ON ai_health_events(provider, created_at);
```

### 写入钩子（3 处）

1. **`callLlmJson` candidate 循环**：每次上游调用（含轮替中间失败）结束后，`ctx.waitUntil` 异步写一条。成功也写。能看到「agnes 连续垃圾 JSON → 换 zhipu 成功」全过程。需要把 `ExecutionContext`（或等价 waitUntil 能力）从 `handleAiApi` 传进 `callLlmJson`（目前未传）。
2. **`parseTextResultOrFallback` 校验失败处**：补写 `outcome='invalid_output'`（本地硬校验打回，上游 200 但内容不合格）。
3. **探测结果**：`source='probe'` 落表，卡片显示「最近探测 X 分钟前 ✅/❌」。

写入约束：
- 用 `waitUntil` 异步，任何写入失败只 console.warn，不影响主响应
- 写入粒度 = 每次上游 candidate 尝试一条（正常成功路径 1 条；轮替/模型降级时按实际尝试次数增多），不去重——当前流量规模（每天几十~几百次调用）逐条存可承受
- 校验失败补写的 `invalid_output` 与该 candidate 的成功写入是两条独立事件（上游返回 200 ✅ + 本地校验 ❌），都是真实链路信号
- 不记录 prompt、响应正文、key（沿用 logAiDiagnostic 的安全边界）

### 保留期

30 天。现有 6h cron 的 `cleanupRetention` 加一条 `DELETE FROM ai_health_events WHERE created_at < ?`。

## ② Worker API（3 端点，走 `/admin/` 现有 Access JWT + admin 邮箱白名单鉴权）

| 端点 | 作用 |
|---|---|
| `GET /admin/ai/health?window=24h\|7d` | 按 provider+model GROUP BY 聚合（请求量/成功率/平均耗时/P95）+ 最近 50 条事件明细 + agnes KeyPool 状态（读 KV `key-pool:agnes`，只读，不触发状态机写） |
| `POST /admin/ai/health/probe` | body `{ provider, model? }` 或 `{ all: true }`；最小 prompt（`max_tokens≈10`，如「回复 OK」）并行探测，单请求 10s 超时；同步返回结果并落表（`source='probe'`） |
| `GET /admin/ai/health/summary` | 轻量聚合（供仪表盘嵌状态卡，可选实现） |

### 探测覆盖粒度

- zhipu：默认 `glm-5.3-flash`，可指定模型梯队 5 个中任意一个（`glm-4.7` / `glm-4.7-flash` / `glm-4.6v` / `glm-4.5-air`）
- agnes：仅 `agnes-2.5-flash`
- mimo：仅 `mimo-v2.5-pro`

探测下钻能发现 zhipu 梯队内单模型限频（如 glm-4.7-flash 高峰期 1305）这类问题。

### 探测实现要点

- 复用 `callZhipuJson` / `callAgnesJson` / `callMimoJson`（各自处理鉴权头、response_format、错误码），但**不走** `callLlmJson` 轮替——探测就要看单家真实状态，轮替会掩盖目标家的失败
- 探测结果映射：上游成功 → success；AppError 带 status → upstream_error + http_status；无 status 异常 → upstream_error
- `{ all: true }` 时三家并行 `Promise.allSettled`（各家独立落表），总响应以最慢家为上限（10s）

## ③ 前端「AI 健康」页（`app-config/public/admin/`）

路由 `#/ai-health`，加入顶栏与侧栏导航（图标：心电/脉冲样式）。文件：`app.js` 加 render 函数 + `index.html` 加导航项 + `styles.css` 加样式，沿用现有 glass 卡片/状态灯语言，无新依赖。

### 布局

- **顶栏**：24h / 7d 窗口切换 + 「全部探测」按钮（探测中禁用转圈，三家卡片逐个亮结果）
- **三家卡片**（zhipu / agnes / mimo 各一张）：
  - 状态灯：🔴 最近探测失败 或 24h 成功率 <80%；🟡 有 invalid_output 或 KeyPool 有 key 冷却中；🟢 其余
  - 指标行：24h 请求量、成功率、平均耗时
  - 最近错误：时间 + HTTP 状态 + 错误码（如 `3h 前 402 PAYMENT_REQUIRED`）
  - agnes 卡片额外区块：KeyPool 各 key（指纹尾 4 位 + ACTIVE / COOLING 剩余分钟）
  - 按钮：「探测」（单家）+「前往控制台」外链（`target="_blank"`）
- **事件明细表**：时间 / 来源(traffic/probe) / 路由 / 供应商 / 模型 / 耗时 / 结果；provider 下拉筛选；invalid_output 与 upstream_error 标红；最近 50 条，无分页

### Dashboard 外链（前端常量）

实现时用 anysearch 核实三家控制台准确 URL，存为 `app.js` 常量：
- zhipu → open.bigmodel.cn 控制台（用量/密钥管理）
- agnes → apihub.agnes-ai.com 平台页
- mimo → api.xiaomimimo.com 平台页

## ④ 测试与验证

### Worker（vitest，auth-worker/tests/）

- `ai-health.test.mjs`：
  - probe 端点 mock 上游 fetch，验证 200 / 401 / 402 / 429 四态 → 响应字段与 D1 落表行一致
  - health 聚合：插入已知事件集 → 断言 GROUP BY 结果（成功率/平均耗时/P95）
  - 鉴权：无 token / 非白名单邮箱 → 401/403
- `ai-routes.test.mjs` 改造：callLlmJson 测试断言健康记录写入不阻塞响应（waitUntil mock 收集任务，主响应先返回）；现有 204 测试全量回归

### 前端

- 本地 preview 手核布局与状态灯逻辑
- 部署后真开后台页点「全部探测」，对照真实场景验收（mimo 欠费应显示 402 红灯）

## 明确不做（YAGNI）

- 不做定时自动探测（仅手动）
- 不做 KV 滚动计数（D1 逐条）
- 不做图片（agnes-image）/ TTS（mimo-tts）链路探针与外链
- 不做 90 天趋势图
- 不做前端框架迁移（保持原生 JS）
