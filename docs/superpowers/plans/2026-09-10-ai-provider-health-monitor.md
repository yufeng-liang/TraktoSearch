# AI 供应商健康监控与 Dashboard 外链 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** auth-worker 增加被动 AI 健康记录 + 手动主动探针 + 聚合查询端点，后台网页新增「AI 健康」页（三家卡片/状态灯/事件明细/控制台外链）。

**Architecture:** 被动记录挂在 `callLlmJson` candidate 循环与 `parseTextResultOrFallback` 校验失败处，`waitUntil` 异步写 D1 `ai_health_events` 表；主动探测复用三家 `call*Json` 函数（不走轮替），新增 `/admin/ai/health*` 三端点走现有 Access JWT 鉴权；前端原生 JS SPA 加 hash 路由页。

**Tech Stack:** Cloudflare Workers + D1 + KV、原生 JS/CSS/HTML 后台、node:test（auth-worker 测试运行器，非 vitest）。

**Spec:** docs/superpowers/specs/2026-09-10-ai-provider-health-monitor-design.md

## Global Constraints

- 禁止记录 prompt、上游响应正文、API key 明文到健康事件（沿用 logAiDiagnostic 安全边界）
- 健康写入必须 `waitUntil` 异步，任何写失败只 console.warn，不得阻塞/破坏主响应
- Conventional Commits 中文：`<type>(<scope>): <描述>`
- 用户可见前端文字中文；代码注释中文
- 本仓库测试命令：`cd auth-worker && npm test`（node --test）；类型检查：`npm run typecheck`
- 主供应商契约不可破坏：agnes/zhipu 主路径失败返回 null、mimo 上抛（现有 204 测试为回归基线）
- 子代理并行期间禁止 Gradle 任务（本计划纯 worker/前端，无安卓改动，天然满足）
- 设计文档硬规则：注释正文避免出现 `/*` 序列

---

### Task 1: D1 迁移 0018 + AppError.upstreamStatus

**Files:**
- Create: `auth-worker/migrations/0018_ai_health_events.sql`
- Modify: `auth-worker/src/util/errors.ts`（AppError 类，约 39-52 行）

**Interfaces:**
- Produces: `ai_health_events` 表（schema 见下）；`AppError` 构造签名 `new AppError(code, message, statusCode?, upstreamStatus?)`，新只读属性 `upstreamStatus: number | null`

- [ ] **Step 1: 写迁移 SQL**

```sql
-- AI 供应商健康事件：被动记录真实调用 + 主动探针结果，30 天保留
CREATE TABLE IF NOT EXISTS ai_health_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    source TEXT NOT NULL,
    route TEXT,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    outcome TEXT NOT NULL,
    error_code TEXT,
    http_status INTEGER,
    duration_ms INTEGER,
    request_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_health_created
    ON ai_health_events(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_health_provider
    ON ai_health_events(provider, created_at);
```

- [ ] **Step 2: AppError 加 upstreamStatus**

`errors.ts` 中 AppError 类改为（构造函数加第 4 个可选参数，其余成员不动）：

```typescript
export class AppError extends Error {
    public readonly code: string;
    public readonly statusCode: number;
    // 上游原始 HTTP 状态码（402 欠费/401 坏 key/429 限频）：statusCode 已折算成 502，
    // 健康监控需要原始状态定位根因。仅上游调用错误设置，业务错误为 null。
    public readonly upstreamStatus: number | null;

    constructor(
        code: string,
        message: string,
        statusCode: number = 400,
        upstreamStatus: number | null = null
    ) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.upstreamStatus = upstreamStatus;
        this.name = 'AppError';
    }
}
```

- [ ] **Step 3: 验证类型检查**

Run: `cd auth-worker && npm run typecheck`
Expected: 无错误（AppError 新参数有默认值，现有调用点不破坏）

- [ ] **Step 4: Commit**

```bash
git add auth-worker/migrations/0018_ai_health_events.sql auth-worker/src/util/errors.ts
git commit -m "feat(ai-health): 健康事件表迁移与 AppError 上游状态码透传"
```

---

### Task 2: 健康事件写入模块 health.ts

**Files:**
- Create: `auth-worker/src/ai/health.ts`

**Interfaces:**
- Consumes: `AppError`（Task 1）
- Produces:
  - `type HealthOutcome = 'success' | 'upstream_error' | 'invalid_output'`
  - `type HealthSource = 'traffic' | 'probe'`
  - `interface HealthRouteContext { route: string; requestId: string; background: BackgroundScheduler | undefined }`
  - `recordHealthEvent(env: AiHealthEnvironment, ctx: HealthRouteContext, source: HealthSource, provider: string, model: string, outcome: HealthOutcome, error?: unknown, durationMs?: number): void` — 内部 `background?.waitUntil` 异步写 D1；background 为 undefined 时改为 `void promise` fire-and-forget（测试环境）
  - `readKeyPoolSnapshot(kv: KVNamespace): Promise<Array<{ fingerprint: string; status: string; cooldownRemainingSec: number }>>` — 读 KV `key-pool:agnes`，只读
  - `type AiHealthEnvironment = { DB: D1Database; KV?: KVNamespace }`

- [ ] **Step 1: 写测试（先失败）**

`auth-worker/tests/ai-health.test.mjs` 新建，先只写本任务相关用例：

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { recordHealthEvent } from '../src/ai/health.ts';

function createCaptureEnv() {
    const statements = [];
    const waits = [];
    return {
        env: {
            DB: {
                prepare(sql) {
                    return {
                        bind(...args) {
                            statements.push({ sql, args });
                            return { async run() { return { meta: { changes: 1 } }; } };
                        },
                    };
                },
            },
        },
        background: { waitUntil(p) { waits.push(p); } },
        statements,
        waits,
    };
}

test('recordHealthEvent writes success event via waitUntil without blocking', async () => {
    const capture = createCaptureEnv();
    recordHealthEvent(
        capture.env,
        { route: 'quiz', requestId: 'req-1', background: capture.background },
        'traffic', 'zhipu', 'glm-5.3-flash', 'success', undefined, 1234,
    );
    assert.equal(capture.waits.length, 1, 'should schedule exactly one background task');
    await capture.waits[0];
    assert.equal(capture.statements.length, 1);
    const { sql, args } = capture.statements[0];
    assert.match(sql, /INSERT INTO ai_health_events/);
    assert.match(sql, /created_at[\s\S]*source[\s\S]*route[\s\S]*provider[\s\S]*model[\s\S]*outcome/);
    assert.equal(args[2], 'quiz');
    assert.equal(args[3], 'zhipu');
    assert.equal(args[4], 'glm-5.3-flash');
    assert.equal(args[5], 'success');
    assert.equal(args[6], null);        // error_code
    assert.equal(args[7], null);        // http_status
    assert.equal(args[8], 1234);        // duration_ms
    assert.equal(args[9], 'req-1');     // request_id
});

test('recordHealthEvent maps AppError upstream status into http_status', async () => {
    const capture = createCaptureEnv();
    const { AppError } = await import('../src/util/errors.ts');
    recordHealthEvent(
        capture.env,
        { route: 'probe', requestId: 'req-2', background: capture.background },
        'probe', 'mimo', 'mimo-v2.5-pro', 'upstream_error',
        new AppError('AI_UPSTREAM_ERROR', 'AI provider request failed', 502, 402),
        88,
    );
    await capture.waits[0];
    const { args } = capture.statements[0];
    assert.equal(args[5], 'upstream_error');
    assert.equal(args[6], 'AI_UPSTREAM_ERROR');
    assert.equal(args[7], 402, '原始上游状态码必须透传');
});

test('recordHealthEvent swallows DB write failures', async () => {
    const capture = createCaptureEnv();
    capture.env.DB.prepare = () => ({ bind() { return { async run() { throw new Error('d1 down'); } }; } });
    const consoleWarns = [];
    const originalWarn = console.warn;
    console.warn = (...a) => consoleWarns.push(a);
    try {
        recordHealthEvent(
            capture.env,
            { route: 'taste', requestId: 'req-3', background: capture.background },
            'traffic', 'agnes', 'agnes-2.5-flash', 'success',
        );
        await capture.waits[0];
    } finally {
        console.warn = originalWarn;
    }
    assert.equal(consoleWarns.length, 1, '写失败只允许 console.warn');
});

test('recordHealthEvent without background still fires write', async () => {
    const capture = createCaptureEnv();
    recordHealthEvent(
        capture.env,
        { route: 'daily-candidate', requestId: 'req-4', background: undefined },
        'traffic', 'zhipu', 'glm-4.5-air', 'success',
    );
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(capture.statements.length, 1);
});

test('readKeyPoolSnapshot parses KV states with cooldown remaining', async () => {
    const { readKeyPoolSnapshot } = await import('../src/ai/health.ts');
    const nowMs = Date.now();
    const kv = {
        async get(key) {
            assert.equal(key, 'key-pool:agnes');
            return JSON.stringify([
                { fingerprint: 'a'.repeat(64), status: 'ACTIVE', cooldownUntilMs: 0 },
                { fingerprint: 'b'.repeat(64), status: 'COOLING', cooldownUntilMs: nowMs + 120_000 },
            ]);
        },
    };
    const snapshot = await readKeyPoolSnapshot(kv);
    assert.equal(snapshot.length, 2);
    assert.equal(snapshot[0].status, 'ACTIVE');
    assert.equal(snapshot[0].cooldownRemainingSec, 0);
    assert.ok(snapshot[1].cooldownRemainingSec >= 100 && snapshot[1].cooldownRemainingSec <= 120);
    assert.equal(snapshot[1].fingerprint.length, 64);
});

test('readKeyPoolSnapshot returns empty on missing or malformed KV', async () => {
    const { readKeyPoolSnapshot } = await import('../src/ai/health.ts');
    assert.deepEqual(await readKeyPoolSnapshot({ async get() { return null; } }), []);
    assert.deepEqual(await readKeyPoolSnapshot({ async get() { return 'not-json'; } }), []);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd auth-worker && node --experimental-strip-types --test tests/ai-health.test.mjs`
Expected: FAIL（`Cannot find module '../src/ai/health.ts'`）

- [ ] **Step 3: 实现 health.ts**

```typescript
// AI 供应商健康事件：被动记录真实调用与主动探针结果到 D1，供后台「AI 健康」页聚合展示。
// 安全边界与 logAiDiagnostic 一致：只记路由/供应商/模型/错误码/状态码，禁止 prompt、响应正文与 key。

import { AppError } from '../util/errors.ts';

export type HealthOutcome = 'success' | 'upstream_error' | 'invalid_output';
export type HealthSource = 'traffic' | 'probe';

export interface AiHealthEnvironment {
    DB: D1Database;
    KV?: KVNamespace;
}

export interface HealthRouteContext {
    route: string;
    requestId: string;
    background: BackgroundScheduler | undefined;
}

interface BackgroundScheduler {
    waitUntil(promise: Promise<unknown>): void;
}

interface PendingHealthEvent {
    created_at: number;
    source: HealthSource;
    route: string | null;
    provider: string;
    model: string;
    outcome: HealthOutcome;
    error_code: string | null;
    http_status: number | null;
    duration_ms: number | null;
    request_id: string;
}

function normalizeHttpStatus(error: unknown): number | null {
    if (error instanceof AppError) {
        return error.upstreamStatus ?? (error.statusCode >= 400 && error.statusCode < 500 ? error.statusCode : null);
    }
    return null;
}

export function recordHealthEvent(
    env: AiHealthEnvironment,
    ctx: HealthRouteContext,
    source: HealthSource,
    provider: string,
    model: string,
    outcome: HealthOutcome,
    error?: unknown,
    durationMs?: number,
): void {
    const event: PendingHealthEvent = {
        created_at: Math.floor(Date.now() / 1000),
        source,
        route: ctx.route,
        provider,
        model,
        outcome,
        error_code: error instanceof AppError ? error.code : (error ? 'UNKNOWN' : null),
        http_status: normalizeHttpStatus(error),
        duration_ms: typeof durationMs === 'number' ? Math.round(durationMs) : null,
        request_id: ctx.requestId,
    };
    const write = (async () => {
        try {
            await env.DB.prepare(`
                INSERT INTO ai_health_events
                    (created_at, source, route, provider, model, outcome, error_code, http_status, duration_ms, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            `).bind(
                event.created_at, event.source, event.route, event.provider, event.model,
                event.outcome, event.error_code, event.http_status, event.duration_ms, event.request_id,
            ).run();
        } catch (writeError) {
            // 健康记录失败绝不能影响主链路
            console.warn('[AI_HEALTH]', JSON.stringify({
                event: 'write_failed', requestId: ctx.requestId,
                message: writeError instanceof Error ? writeError.message.slice(0, 120) : 'UNKNOWN',
            }));
        }
    })();
    if (ctx.background) {
        ctx.background.waitUntil(write);
    } else {
        // 测试/无 ExecutionContext 环境：fire-and-forget
        void write;
    }
}

export interface KeyPoolKeySnapshot {
    fingerprint: string;
    status: string;
    cooldownRemainingSec: number;
}

// 只读快照：不触碰 KeyPool 状态机（不续期、不解冻），COOLING 剩余时间在展示侧换算
export async function readKeyPoolSnapshot(kv: KVNamespace): Promise<KeyPoolKeySnapshot[]> {
    try {
        const raw = await kv.get('key-pool:agnes');
        if (!raw) return [];
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        const nowMs = Date.now();
        return parsed
            .filter((item): item is { fingerprint: string; status: string; cooldownUntilMs: number } =>
                typeof item === 'object' && item !== null
                && typeof (item as { fingerprint?: unknown }).fingerprint === 'string'
                && typeof (item as { status?: unknown }).status === 'string')
            .map(item => ({
                fingerprint: item.fingerprint,
                status: item.status,
                cooldownRemainingSec: Math.max(0, Math.round(((item.cooldownUntilMs ?? 0) - nowMs) / 1000)),
            }));
    } catch {
        return [];
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd auth-worker && node --experimental-strip-types --test tests/ai-health.test.mjs`
Expected: 6 个用例全 PASS

- [ ] **Step 5: Commit**

```bash
git add auth-worker/src/ai/health.ts auth-worker/tests/ai-health.test.mjs
git commit -m "feat(ai-health): 健康事件写入模块与 agnes key 池快照"
```

---

### Task 3: 上游调用接线——原始状态码 + 写入钩子

**Files:**
- Modify: `auth-worker/src/ai/zhipu.ts`（约 100-119 行错误上抛处）
- Modify: `auth-worker/src/ai/mimo.ts`（约 145-172 行错误上抛处）
- Modify: `auth-worker/src/ai/agnes.ts`（约 128-140 行 + image 路径可不动）
- Modify: `auth-worker/src/ai/handler.ts`（callLlmJson 约 2398-2453 行、handleAiApi 237-330 行、LlmRequestContext 232-235 行、parseTextResultOrFallback 2455-2470 行、各业务 handler 透传）

**Interfaces:**
- Consumes: `recordHealthEvent`（Task 2）；`BackgroundScheduler`（daily-illustration.ts 已有导出）
- Produces: `callLlmJson(env, provider, model, messages, options, fallbackModel, context, health?: HealthRouteContext)` 新增第 8 个可选参数；`handleAiApi(request, env, requestId, path, payload, background?)` 签名不变（background 已是第 6 参），内部把 `{ route, requestId, background }` 下传各业务 handler

**改动逻辑（handler.ts）:**

1. `handleAiApi` 内对 greeting/taste/quiz/daily 四个业务分支：把已有的 `background`（如有）与 `requestId` 组装成 `healthCtx = { route: 'quiz', requestId, background }` 传入各 handler。greeting/taste 现签名无 background，需加第 5/6 参透传。
2. `LlmRequestContext.route` 联合类型已覆盖各路由；`HealthRouteContext.route` 直接用其字符串。
3. `callLlmJson` candidate 循环（约 2426-2447 行）改为记录耗时并在每次尝试后写健康事件：

```typescript
        for (const m of candidates) {
            const startedAtMs = Date.now();
            try {
                const result = p === 'agnes'
                    ? await callAgnesJson(env, m, messages as unknown as AgnesMessage[], options)
                    : p === 'zhipu'
                        ? await callZhipuJson(env, m, messages as unknown as ZhipuMessage[], options)
                        : await callMimoJson(env, m as MimoModel, messages, options);
                const durationMs = Date.now() - startedAtMs;
                // 未配置/测试模式下供应商返回 null 表示该家不可用：继续轮下一家；
                // 全部轮完仍未成功时由函数末尾按主供应商契约返回 null 或上抛。
                if (result === null) {
                    logAiDiagnostic('upstream_failure', context, p, m, new AppError('AI_UPSTREAM_ERROR', 'AI provider unavailable', 502));
                    lastError = new AppError('AI_UPSTREAM_ERROR', 'AI provider unavailable', 502);
                    recordHealthEvent(env, healthCtx, 'traffic', p, m, 'upstream_error', lastError, durationMs);
                    continue;
                }
                recordHealthEvent(env, healthCtx, 'traffic', p, m, 'success', undefined, durationMs);
                return { payload: result, provider: p, model: m };
            } catch (error) {
                const durationMs = Date.now() - startedAtMs;
                // 模型非法属于请求错误，不回退，直接上抛。
                if (error instanceof AppError && error.code === 'INVALID_MODEL') throw error;
                logAiDiagnostic('upstream_failure', context, p, m, error);
                lastError = error;
                recordHealthEvent(env, healthCtx, 'traffic', p, m, 'upstream_error', error, durationMs);
            }
        }
```

4. `parseTextResultOrFallback` 加 healthCtx 参数（在 context 后），agnes invalid_output 分支补写：

```typescript
        if (result.provider !== 'agnes') throw error;
        logAiDiagnostic('invalid_output', context, result.provider, result.model, error);
        if (healthCtx) recordHealthEvent(envForHealth, healthCtx, 'traffic', result.provider, result.model, 'invalid_output', error);
        return fallback();
```

实现注记：`parseTextResultOrFallback` 当前签名无 env；调用方（greeting/taste/daily/quiz 各 1 处）都持有 env 与 healthCtx，直接给函数加 `env: AiEnvironment, healthCtx: HealthRouteContext | undefined` 两个参数（插在 fallback 之后、context 之前），调用点同步改。可选参数顺序不要交错。

5. 测试模式下（`AI_TEST_MODE`）recordHealthEvent 照常写——测试用 mock DB 会捕获写入，不影响现有断言（现有测试 env 的 `DB.prepare` 直接 throw，故**必须**保证现有测试不触达写入路径：现有 ai-routes 测试走 TEST_MODE 兜底，根本不调 callLlmJson 成功路径；agnes.test.mjs 走真实 callLlmJson——其 createTestEnv 的 DB mock 需要能接住 INSERT（本任务同步改 agnes.test.mjs 的 mock DB，见 Step 1）。

- [ ] **Step 1: 改测试先锁行为**

`auth-worker/tests/agnes.test.mjs`：
1. `createTestEnv`（或等价 env 工厂）的 DB mock 改成 Task 2 的 capture 形态：`prepare(sql)` 对 `INSERT INTO ai_health_events` 返回可 run 的 stub，对其他 SQL 维持原行为（原样 throw 或原有逻辑）
2. 在「agnes quiz 走 zhipu 轮替」相关用例（route 断言 `['taste','quiz','quiz','daily','daily']` 那组）加断言：

```javascript
    // 健康事件：agnes 两轮 quiz 尝试 + zhipu 成功，各写一条
    const healthRows = healthInserts.filter(r => r.route === 'quiz');
    assert.ok(healthRows.some(r => r.provider === 'agnes' && r.outcome === 'upstream_error'));
    assert.ok(healthRows.some(r => r.provider === 'zhipu' && r.outcome === 'success'));
```

（`healthInserts` 为 mock DB 捕获的 INSERT bind 参数数组，测试文件内自行收集。）

`auth-worker/tests/ai-health.test.mjs` 追加集成用例（import handleAiApi 或复用 ai-routes 的 call 辅助——直接在本文件内建最小 greeting 请求走通 callLlmJson 成功路径，断言 200 且 health INSERT 恰好 1 条）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd auth-worker && node --experimental-strip-types --test tests/agnes.test.mjs tests/ai-health.test.mjs`
Expected: FAIL（断言找不到健康行 / 新集成用例无 INSERT）

- [ ] **Step 3: 实现接线**

按上文「改动逻辑」1-4 修改。zhipu.ts 三处 `AI_UPSTREAM_ERROR` 上抛改带原始状态：

```typescript
// 响应处理处（response.ok else 分支）：
throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider request failed', 502, response.status);
// 限频 1305 处：
throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider rate limited', 502, response.status);
// catch 网络异常处无 response.status，保持不传（null）
```

mimo.ts 两处同理（`'AI provider request failed', 502, response.status`）；agnes.ts `callAgnesPayload` 末段上抛处同理（response 已消费，可用其 status 变量）。注意 agnes 走 `fetchWithKeyRotation`，最终 response 变量在 throw 前仍可见，直接取 `response.status`。

- [ ] **Step 4: 全量测试回归**

Run: `cd auth-worker && npm test`
Expected: 全 PASS（含存量 204；如存量用例因 DB mock 行为变化失败，按「TEST_MODE 不触达写入」原则修正 mock 而非生产代码）

- [ ] **Step 5: typecheck + Commit**

Run: `cd auth-worker && npm run typecheck`

```bash
git add auth-worker/src/ai/handler.ts auth-worker/src/ai/zhipu.ts auth-worker/src/ai/mimo.ts auth-worker/src/ai/agnes.ts auth-worker/tests/agnes.test.mjs auth-worker/tests/ai-health.test.mjs
git commit -m "feat(ai-health): callLlmJson 被动健康记录接线与上游原始状态码透传"
```

---

### Task 4: 探针端点 + 聚合查询端点 + cron 清理

**Files:**
- Create: `auth-worker/src/admin/ai-health.ts`
- Modify: `auth-worker/src/index.ts`（handleAdminApi 约 455 行 `/admin/health` 分支后加 3 个路由；scheduled 155-158 行不动）
- Modify: `auth-worker/src/retention.ts`（cleanupRetention 加一条 DELETE）

**Interfaces:**
- Consumes: `recordHealthEvent`、`readKeyPoolSnapshot`（Task 2）；三家 `call*Json`（已有）；`verifyAccessJWT`（index.ts handleAdminApi 入口已有，新路由自动受保护）
- Produces:
  - `handleAiHealthList(request, env, requestId, path): Promise<Response>` — `GET /admin/ai/health?window=24h|7d`
  - `handleAiHealthProbe(request, env, requestId): Promise<Response>` — `POST /admin/ai/health/probe`
  - `handleAiHealthSummary(env, requestId): Promise<Response>` — `GET /admin/ai/health/summary`
- 索引路由分支（index.ts）：

```typescript
    // AI 供应商健康监控（探针 + 聚合）
    if (path === '/admin/ai/health' && request.method === 'GET') {
        return handleAiHealthList(request, env, requestId, path);
    }
    if (path === '/admin/ai/health/summary' && request.method === 'GET') {
        return handleAiHealthSummary(env, requestId);
    }
    if (path === '/admin/ai/health/probe' && request.method === 'POST') {
        return handleAiHealthProbe(request, env, requestId);
    }
```

（注意必须放在 `/admin/health` 精确匹配之后或任意位置均可——`/admin/health` 是全等匹配，不会被 `/admin/ai/health` 干扰。）

- [ ] **Step 1: 写测试（先失败）**

`auth-worker/tests/ai-health.test.mjs` 追加：

```javascript
test('probe endpoint returns per-provider result and writes probe events', async () => {
    // 直接调 handleAiHealthProbe（不经 verifyAccessJWT——那是 index.ts 层职责，已有独立层）
    const { handleAiHealthProbe } = await import('../src/admin/ai-health.ts');
    const originalFetch = globalThis.fetch;
    const calls = [];
    globalThis.fetch = async (input, init) => {
        const body = JSON.parse(init.body);
        calls.push({ model: body.model, auth: init.headers });
        // zhipu 200 OK、mimo 402、agnes 200 OK（fetchWithKeyRotation 内部 fetch 同 mock）
        if (body.model?.startsWith('glm')) {
            return new Response(JSON.stringify({ choices: [{ message: { content: 'OK' } }] }), { status: 200 });
        }
        return new Response(JSON.stringify({ error: { message: 'insufficient balance' } }), { status: 402 });
    };
    const statements = [];
    const env = {
        DB: {
            prepare(sql) {
                return {
                    bind(...args) { statements.push({ sql, args }); return { async run() { return { meta: { changes: 1 } }; } }; },
                };
            },
        },
        KV: { async get() { return null; }, async put() {} },
        ZHIPU_API_KEY: 'test-zhipu',
        MIMO_API_KEY: 'test-mimo',
        AGNES_API_KEYS: 'test-agnes-key',
        AI_TEST_MODE: false,
    };
    try {
        const request = new Request('https://gw.test/admin/ai/health/probe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ all: true }),
        });
        const response = await handleAiHealthProbe(request, env, 'req-probe-1');
        assert.equal(response.status, 200);
        const data = (await response.json()).data;
        const byProvider = Object.fromEntries(data.results.map(r => [r.provider, r]));
        assert.equal(byProvider.zhipu.outcome, 'success');
        assert.equal(byProvider.mimo.outcome, 'upstream_error');
        assert.equal(byProvider.mimo.httpStatus, 402);
        assert.equal(byProvider.agnes.outcome, 'success');
        // 三家各写一条 probe 健康事件
        const probeRows = statements.filter(s => s.sql.includes('ai_health_events'));
        assert.equal(probeRows.length, 3);
        const mimoRow = probeRows.map(r => r.args).find(a => a[3] === 'mimo');
        assert.equal(mimoRow[5], 'upstream_error');
        assert.equal(mimoRow[6], 'AI_UPSTREAM_ERROR');
        assert.equal(mimoRow[7], 402);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('probe endpoint validates provider and model params', async () => {
    const { handleAiHealthProbe } = await import('../src/admin/ai-health.ts');
    const env = { DB: { prepare() { throw new Error('no'); } }, KV: { async get() { return null; } } };
    const badProvider = new Request('https://gw.test/admin/ai/health/probe', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: 'openai' }),
    });
    const response = await handleAiHealthProbe(badProvider, env, 'req-p2');
    assert.equal(response.status, 400);
    const badModel = new Request('https://gw.test/admin/ai/health/probe', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: 'zhipu', model: 'gpt-4o' }),
    });
    const response2 = await handleAiHealthProbe(badModel, env, 'req-p3');
    assert.equal(response2.status, 400);
});

test('health list aggregates by provider and model with window filter', async () => {
    const { handleAiHealthList } = await import('../src/admin/ai-health.ts');
    const queries = [];
    const env = {
        DB: {
            prepare(sql) {
                queries.push(sql);
                return {
                    bind(...args) {
                        return {
                            async all() {
                                if (sql.includes('GROUP BY')) {
                                    return { results: [
                                        { provider: 'zhipu', model: 'glm-5.3-flash', total: 10, success: 9, avg_duration_ms: 3200, p95_duration_ms: 8100 },
                                        { provider: 'mimo', model: 'mimo-v2.5-pro', total: 2, success: 0, avg_duration_ms: 900, p95_duration_ms: 950 },
                                    ] };
                                }
                                if (sql.includes('ORDER BY') && sql.includes('LIMIT')) {
                                    return { results: [
                                        { created_at: 1725900000, source: 'traffic', route: 'quiz', provider: 'mimo', model: 'mimo-v2.5-pro', outcome: 'upstream_error', error_code: 'AI_UPSTREAM_ERROR', http_status: 402, duration_ms: 900, request_id: 'r1' },
                                    ] };
                                }
                                return { results: [] };
                            },
                        };
                    },
                };
            },
        },
        KV: { async get(key) { return key === 'key-pool:agnes' ? JSON.stringify([{ fingerprint: 'a'.repeat(64), status: 'ACTIVE', cooldownUntilMs: 0 }]) : null; } },
    };
    const request = new Request('https://gw.test/admin/ai/health?window=24h', { method: 'GET' });
    const response = await handleAiHealthList(request, env, 'req-l1');
    assert.equal(response.status, 200);
    const data = (await response.json()).data;
    assert.equal(data.window, '24h');
    assert.equal(data.aggregates.length, 2);
    const zhipu = data.aggregates.find(a => a.provider === 'zhipu');
    assert.equal(zhipu.successRate, 0.9);
    assert.equal(data.keyPool.length, 1);
    assert.ok(queries.some(q => q.includes('created_at >= ?')));
    assert.ok(queries.some(q => q.includes('LIMIT 50')));
});

test('health list rejects invalid window', async () => {
    const { handleAiHealthList } = await import('../src/admin/ai-health.ts');
    const env = { DB: { prepare() { throw new Error('no'); } }, KV: { async get() { return null; } } };
    const request = new Request('https://gw.test/admin/ai/health?window=90d', { method: 'GET' });
    const response = await handleAiHealthList(request, env, 'req-l2');
    assert.equal(response.status, 400);
});
```

Run: `cd auth-worker && node --experimental-strip-types --test tests/ai-health.test.mjs`
Expected: 新增 4 用例 FAIL（模块不存在）

- [ ] **Step 2: 实现 src/admin/ai-health.ts**

```typescript
// AI 供应商健康监控端点：聚合查询 + 手动探针。鉴权由 index.ts handleAdminApi 统一 verifyAccessJWT。

import { AppError, errorResponse, now, successResponse } from '../util/errors.ts';
import { recordHealthEvent, readKeyPoolSnapshot } from '../ai/health.ts';
import { callZhipuJson, ZHIPU_MODELS } from '../ai/zhipu.ts';
import { callAgnesJson, AGNES_MODELS } from '../ai/agnes.ts';
import { callMimoJson, MIMO_MODELS } from '../ai/mimo.ts';
import { DEFAULT_MODEL_BY_PROVIDER } from '../ai/handler.ts';

const WINDOW_SECONDS: Record<string, number> = { '24h': 86_400, '7d': 7 * 86_400 };
const PROBE_TIMEOUT_MS = 10_000;

const PROBE_MESSAGES = [{ role: 'user', content: '回复 OK 两个字，不要输出其他内容。' }];
const PROBE_OPTIONS = { maxCompletionTokens: 16, temperature: 0, responseFormat: false, timeoutMs: PROBE_TIMEOUT_MS };

const VALID_MODELS: Record<string, readonly string[]> = {
    zhipu: ZHIPU_MODELS,
    agnes: AGNES_MODELS,
    mimo: MIMO_MODELS,
};

interface ProbeResult {
    provider: string;
    model: string;
    outcome: 'success' | 'upstream_error';
    errorCode: string | null;
    httpStatus: number | null;
    durationMs: number;
}

async function probeOne(env, provider: string, model: string, requestId: string): Promise<ProbeResult> {
    const startedAtMs = Date.now();
    try {
        const options = { ...PROBE_OPTIONS };
        if (provider === 'zhipu') await callZhipuJson(env, model, PROBE_MESSAGES, options);
        else if (provider === 'agnes') await callAgnesJson(env, model, PROBE_MESSAGES, options);
        else await callMimoJson(env, model as never, PROBE_MESSAGES, options);
        const result: ProbeResult = { provider, model, outcome: 'success', errorCode: null, httpStatus: null, durationMs: Date.now() - startedAtMs };
        recordHealthEvent(env, { route: 'probe', requestId, background: undefined }, 'probe', provider, model, 'success', undefined, result.durationMs);
        return result;
    } catch (error) {
        const appError = error instanceof AppError ? error : new AppError('AI_UPSTREAM_ERROR', 'Probe failed', 502);
        const result: ProbeResult = {
            provider, model, outcome: 'upstream_error',
            errorCode: appError.code,
            httpStatus: appError.upstreamStatus,
            durationMs: Date.now() - startedAtMs,
        };
        recordHealthEvent(env, { route: 'probe', requestId, background: undefined }, 'probe', provider, model, 'upstream_error', appError, result.durationMs);
        return result;
    }
}

export async function handleAiHealthProbe(request: Request, env, requestId: string): Promise<Response> {
    let body: Record<string, unknown>;
    try {
        body = await request.json();
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid JSON body', 400);
    }
    const targets: Array<{ provider: string; model: string }> = [];
    if (body.all === true) {
        for (const provider of ['zhipu', 'agnes', 'mimo']) {
            targets.push({ provider, model: DEFAULT_MODEL_BY_PROVIDER[provider] });
        }
    } else {
        const provider = typeof body.provider === 'string' ? body.provider : '';
        if (!(provider in VALID_MODELS)) throw new AppError('INVALID_REQUEST', 'Unknown provider', 400);
        const model = body.model === undefined ? DEFAULT_MODEL_BY_PROVIDER[provider] : body.model;
        if (typeof model !== 'string' || !VALID_MODELS[provider].includes(model)) {
            throw new AppError('INVALID_REQUEST', 'Unknown model for provider', 400);
        }
        targets.push({ provider, model });
    }

    // 三家并行探测；各家独立落表（recordHealthEvent 内部吞写失败）
    const settled = await Promise.all(targets.map(t => probeOne(env, t.provider, t.model, requestId)));
    return successResponse({ results: settled, probedAt: now() }, requestId);
}

export async function handleAiHealthList(request: Request, env, requestId: string): Promise<Response> {
    const url = new URL(request.url);
    const window = url.searchParams.get('window') ?? '24h';
    if (!(window in WINDOW_SECONDS)) throw new AppError('INVALID_REQUEST', 'Invalid window', 400);
    const since = now() - WINDOW_SECONDS[window];

    // p95 用窗口函数按分位数近似（D1/SQLite 无 percentile 函数）
    const aggregateStmt = env.DB.prepare(`
        SELECT provider, model,
               COUNT(*) AS total,
               SUM(CASE WHEN outcome = 'success' THEN 1 ELSE 0 END) AS success,
               AVG(duration_ms) AS avg_duration_ms,
               MAX(duration_ms) AS p95_duration_ms
        FROM ai_health_events
        WHERE created_at >= ?
        GROUP BY provider, model
        ORDER BY total DESC
    `).bind(since);
    const recentStmt = env.DB.prepare(`
        SELECT created_at, source, route, provider, model, outcome, error_code, http_status, duration_ms, request_id
        FROM ai_health_events
        WHERE created_at >= ?
        ORDER BY created_at DESC
        LIMIT 50
    `).bind(since);

    const [aggregateRes, recentRes, keyPool] = await Promise.all([
        aggregateStmt.all(),
        recentStmt.all(),
        env.KV ? readKeyPoolSnapshot(env.KV) : Promise.resolve([]),
    ]);

    const aggregates = (aggregateRes.results ?? []).map(row => ({
        ...row,
        successRate: row.total > 0 ? Number(row.success) / Number(row.total) : 0,
    }));
    return successResponse({ window, aggregates, recent: recentRes.results ?? [], keyPool }, requestId);
}

export async function handleAiHealthSummary(env, requestId: string): Promise<Response> {
    const since = now() - 86_400;
    const res = await env.DB.prepare(`
        SELECT provider,
               COUNT(*) AS total,
               SUM(CASE WHEN outcome = 'success' THEN 1 ELSE 0 END) AS success
        FROM ai_health_events
        WHERE created_at >= ?
        GROUP BY provider
    `).bind(since).all();
    return successResponse({ window: '24h', byProvider: res.results ?? [] }, requestId);
}

// errorResponse 仅供未来扩展；当前无需
void errorResponse;
```

实现注记：
- p95 按 spec 本应取 95 分位，但 D1 免费层 SQL 无分位函数；用 MAX 近似并在响应字段名保持 `p95_duration_ms`（值语义为最差耗时）。此取舍写入代码注释。
- 探针不走 `callLlmJson`（避免轮替掩盖目标家失败），直接调三家底层函数——但**注意** zhipu 内部对 5xx 有一次 400ms 退避重试、agnes 有 key 轮换，属可接受的重试语义，不算跨家轮替。
- `callMimoJson` 的 model 参数类型是 `MimoModel`，测试/实现里用 `as never` 或显式窄化（`if (!MIMO_MODELS.includes(model)) throw`）皆可，以 typecheck 通过为准。
- 空消息数组在 AGNES/MIMO/ZHIPU 三家的响应解析是安全的（返回 null → 测试模式），但探针在无 key 时会抛 `AI_NOT_CONFIGURED`（503）——映射 outcome `upstream_error`，errorCode `AI_NOT_CONFIGURED`，符合语义（未配置=不健康）。

- [ ] **Step 3: 跑本文件测试确认通过**

Run: `cd auth-worker && node --experimental-strip-types --test tests/ai-health.test.mjs`
Expected: 全 PASS

- [ ] **Step 4: index.ts 接线路由 + retention 清理**

index.ts `handleAdminApi` 内 `/admin/health` 分支后加 3 个路由（代码见上文 Interfaces）。retention.ts `cleanupRetention` 的 `Promise.all` 数组加一条：

```typescript
        env.DB.prepare(`
            DELETE FROM ai_health_events
            WHERE created_at < ?
        `).bind(currentTime - 30 * 24 * 60 * 60).run(),
```

并把解构加 `healthResult`、日志 JSON 加 `expiredHealthEvents: Number(healthResult.meta.changes || 0)`。

- [ ] **Step 5: 全量测试 + typecheck + Commit**

Run: `cd auth-worker && npm test && npm run typecheck`
Expected: 全 PASS

```bash
git add auth-worker/src/admin/ai-health.ts auth-worker/src/index.ts auth-worker/src/retention.ts auth-worker/tests/ai-health.test.mjs
git commit -m "feat(ai-health): 探针与聚合查询端点及 30 天保留清理"
```

---

### Task 5: 后台前端「AI 健康」页

**Files:**
- Modify: `app-config/public/admin/index.html`（topbar-nav 33-38 行 + sidebar-list 61-102 行加导航项；135 行 script 版本号 bump）
- Modify: `app-config/public/admin/app.js`（API 对象 232 行起加 3 个方法；render() switch 926-938 行加 case；文件尾加 renderAiHealth）
- Modify: `app-config/public/admin/styles.css`（文件尾加样式）

**Interfaces:**
- Consumes: `GET /admin-api/admin/ai/health?window=…`、`POST /admin-api/admin/ai/health/probe`（Task 4 响应结构）
- Produces: 路由 `#/ai-health`；`API.getAiHealth(window)`、`API.probeAiHealth(body)`

- [ ] **Step 1: index.html 导航**

topbar-nav 在「仪表盘」后加：

```html
<a href="#/ai-health" class="nav-link" data-route="ai-health">AI 健康</a>
```

sidebar-list 在「仪表盘」`</li>` 后加同结构项（图标用脉冲线 SVG）：

```html
<li>
    <a href="#/ai-health" class="sidebar-link" data-route="ai-health">
        <span class="sidebar-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
        </span>
        <span>AI 健康</span>
    </a>
</li>
```

135 行 script 标签：`app.js?v=20260910-ai-health-v1`。

- [ ] **Step 2: app.js API 方法与路由**

API 对象内加（getStats 之后）：

```javascript
    async getAiHealth(window = '24h') {
        return this.get(`/admin/ai/health?window=${encodeURIComponent(window)}`);
    },
    async probeAiHealth(body) {
        // 探针可能比常规请求慢（最长 10s 超时），用更长超时直连 fetch
        const token = this.getToken();
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 20000);
        activeRequestControllers.add(controller);
        try {
            const response = await fetch(`${API_BASE}/admin/ai/health/probe`, {
                method: 'POST', headers,
                body: JSON.stringify(body), signal: controller.signal,
            });
            if (response.status === 401) {
                localStorage.removeItem('tts-access-token');
                window.location.href = this.getAccessLoginUrl();
                throw new Error('UNAUTHORIZED');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data?.message || `Probe failed (${response.status})`);
            return data.data;
        } finally {
            clearTimeout(timeoutId);
            activeRequestControllers.delete(controller);
        }
    },
```

render() switch 加 `case 'ai-health': renderAiHealth(main, renderToken); break;`（default 之前）。

- [ ] **Step 3: app.js renderAiHealth 主体**

文件尾（renderCrashLogDetailView 之后）加：

```javascript
// ===== AI 健康 =====
const AI_PROVIDER_META = {
    zhipu: {
        label: '智谱 GLM',
        consoleUrl: 'https://open.bigmodel.cn/console/overview',
        models: ['glm-5.3-flash', 'glm-4.7', 'glm-4.7-flash', 'glm-4.6v', 'glm-4.5-air'],
    },
    agnes: { label: 'Agnes AI', consoleUrl: 'https://agnes-ai.com/', models: ['agnes-2.5-flash'] },
    mimo: { label: '小米 MiMo', consoleUrl: 'https://platform.xiaomimimo.com/', models: ['mimo-v2.5-pro'] },
};
const AI_HEALTH_STATE = { window: '24h', data: null };

function renderAiHealth(container, renderToken) {
    container.innerHTML = `
        <h1 class="section-title">AI 健康</h1>
        <p class="section-subtitle">供应商探针与真实流量健康度</p>
        <div class="ai-health-toolbar">
            <div class="segmented" id="aiHealthWindow">
                <button type="button" data-window="24h">24 小时</button>
                <button type="button" data-window="7d">7 天</button>
            </div>
            <button type="button" class="btn btn-sm" id="aiProbeAllBtn">全部探测</button>
        </div>
        <div class="detail-grid" id="aiProviderCards"></div>
        <div class="card" style="margin-top:16px">
            <div class="card-header"><span class="card-title">事件明细（最近 50 条）</span></div>
            <div id="aiEventTable"></div>
        </div>
    `;
    container.querySelector('#aiHealthWindow').addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-window]');
        if (!btn) return;
        AI_HEALTH_STATE.window = btn.dataset.window;
        loadAiHealth(container, renderToken);
    });
    container.querySelector('#aiProbeAllBtn').addEventListener('click', () => runAiProbeAll(container));
    loadAiHealth(container, renderToken);
}

async function loadAiHealth(container, renderToken) {
    const cardsEl = container.querySelector('#aiProviderCards');
    const tableEl = container.querySelector('#aiEventTable');
    if (!cardsEl || !tableEl) return;
    container.querySelectorAll('#aiHealthWindow button').forEach(b =>
        b.classList.toggle('active', b.dataset.window === AI_HEALTH_STATE.window));
    cardsEl.innerHTML = '<div class="loading-skeleton" style="height:180px"></div>';
    tableEl.innerHTML = '<div class="loading-skeleton" style="height:120px"></div>';
    try {
        const data = await API.getAiHealth(AI_HEALTH_STATE.window);
        if (renderToken !== state.renderToken || !container.isConnected) return;
        AI_HEALTH_STATE.data = data;
        cardsEl.innerHTML = Object.entries(AI_PROVIDER_META).map(([key, meta]) =>
            aiProviderCardHtml(key, meta, data)).join('');
        bindAiCardActions(container);
        tableEl.innerHTML = aiEventTableHtml(data);
    } catch (error) {
        if (renderToken !== state.renderToken) return;
        cardsEl.innerHTML = `<div class="card"><div class="empty-state"><div class="empty-title">加载失败</div><div class="empty-desc">${escapeHtml(error.message || '未知错误')}</div></div></div>`;
        tableEl.innerHTML = '';
    }
}

function aiHealthLight(providerKey, data) {
    const agg = (data.aggregates || []).filter(a => a.provider === providerKey);
    const total = agg.reduce((sum, a) => sum + Number(a.total || 0), 0);
    const success = agg.reduce((sum, a) => sum + Number(a.success || 0), 0);
    const recent = (data.recent || []).filter(r => r.provider === providerKey);
    const lastFail = recent.find(r => r.outcome !== 'success');
    const lastProbe = recent.find(r => r.source === 'probe');
    const hasInvalid = recent.some(r => r.outcome === 'invalid_output');
    const coolingKeys = (data.keyPool || []).filter(k => providerKey === 'agnes' && k.status === 'COOLING' && k.cooldownRemainingSec > 0);
    let level = 'ok';
    if ((total > 0 && success / total < 0.8) || lastFail?.outcome === 'upstream_error' && (Date.now() / 1000 - lastFail.created_at) < 86400) level = 'fail';
    else if (hasInvalid || coolingKeys.length > 0) level = 'warn';
    return { level, total, successRate: total > 0 ? success / total : null, lastFail, lastProbe, hasInvalid };
}

function aiProviderCardHtml(providerKey, meta, data) {
    const s = aiHealthLight(providerKey, data);
    const aggRows = (data.aggregates || []).filter(a => a.provider === providerKey);
    const avgMs = aggRows.length && aggRows[0].avg_duration_ms != null ? Math.round(aggRows[0].avg_duration_ms) : null;
    const lastErr = s.lastFail;
    const keyPoolRows = providerKey === 'agnes' ? (data.keyPool || []).map(k => {
        const tail = k.fingerprint.slice(-4);
        const cooling = k.status === 'COOLING' && k.cooldownRemainingSec > 0;
        return `<div class="ai-key-row"><span class="mono">…${tail}</span><span class="${cooling ? 'text-danger' : 'text-ok'}">${cooling ? `冷却 ${Math.ceil(k.cooldownRemainingSec / 60)} 分钟` : k.status === 'ACTIVE' ? '可用' : k.status}</span></div>`;
    }).join('') : '';
    return `
    <div class="card ai-provider-card" data-provider="${providerKey}">
        <div class="card-header">
            <span class="card-title"><span class="health-dot ${s.level === 'ok' ? 'ok' : s.level === 'fail' ? 'fail' : 'warn'}"></span>${meta.label}</span>
            <span class="ai-probe-note">${s.lastProbe ? `最近探测 ${formatTime(s.lastProbe.created_at * 1000)}` : '未探测'}</span>
        </div>
        <div class="health-row"><span>${AI_HEALTH_STATE.window} 请求量</span><span>${s.total}</span></div>
        <div class="health-row"><span>成功率</span><span class="${s.successRate != null && s.successRate < 0.8 ? 'text-danger' : ''}">${s.successRate != null ? `${Math.round(s.successRate * 100)}%` : '—'}</span></div>
        <div class="health-row"><span>平均耗时</span><span>${avgMs != null ? `${avgMs} ms` : '—'}</span></div>
        <div class="health-row"><span>最近错误</span><span class="${lastErr ? 'text-danger' : 'text-ok'}">${lastErr ? `${formatTime(lastErr.created_at * 1000)} ${lastErr.http_status ?? ''} ${lastErr.error_code || ''}` : '无'}</span></div>
        ${keyPoolRows ? `<div class="ai-key-pool"><div class="ai-key-title">Key 池状态</div>${keyPoolRows}</div>` : ''}
        <div class="ai-card-actions">
            <select class="ai-probe-model" aria-label="探测模型">
                ${meta.models.map(m => `<option value="${m}">${m}</option>`).join('')}
            </select>
            <button type="button" class="btn btn-ghost btn-sm js-ai-probe-one">探测</button>
            <a class="btn btn-ghost btn-sm" href="${meta.consoleUrl}" target="_blank" rel="noopener noreferrer">前往控制台 ↗</a>
        </div>
    </div>`;
}

function bindAiCardActions(container) {
    container.querySelectorAll('.ai-provider-card .js-ai-probe-one').forEach(btn => {
        btn.addEventListener('click', async () => {
            const card = btn.closest('.ai-provider-card');
            const provider = card.dataset.provider;
            const model = card.querySelector('.ai-probe-model').value;
            btn.disabled = true;
            btn.textContent = '探测中…';
            try {
                const result = await API.probeAiHealth({ provider, model });
                const r = result.results?.[0];
                if (r?.outcome === 'success') toast.success(`${provider} 探测成功（${r.durationMs} ms）`);
                else toast.error(`${provider} 探测失败：${r?.errorCode || '未知'}${r?.httpStatus ? ` HTTP ${r.httpStatus}` : ''}`);
                await loadAiHealth(container, state.renderToken);
            } catch (error) {
                toast.error(`探测失败：${error.message}`);
                btn.disabled = false;
                btn.textContent = '探测';
            }
        });
    });
}

async function runAiProbeAll(container) {
    const btn = container.querySelector('#aiProbeAllBtn');
    if (!btn || btn.disabled) return;
    btn.disabled = true;
    btn.textContent = '探测中…';
    try {
        const result = await API.probeAiHealth({ all: true });
        const failCount = (result.results || []).filter(r => r.outcome !== 'success').length;
        if (failCount === 0) toast.success('三家探测全部成功');
        else toast.error(`${failCount} 家探测失败，见卡片详情`);
        await loadAiHealth(container, state.renderToken);
    } catch (error) {
        toast.error(`探测失败：${error.message}`);
    } finally {
        btn.disabled = false;
        btn.textContent = '全部探测';
    }
}

function aiEventTableHtml(data) {
    const rows = data.recent || [];
    if (rows.length === 0) {
        return '<div class="empty-state"><div class="empty-title">窗口内无事件</div><div class="empty-desc">尚无真实 AI 流量或探针记录</div></div>';
    }
    return `<div class="table-scroll"><table><thead><tr>
        <th>时间</th><th>来源</th><th>路由</th><th>供应商</th><th>模型</th><th>耗时</th><th>结果</th>
    </tr></thead><tbody>${rows.map(r => `
        <tr>
            <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${formatTime(r.created_at * 1000)}</td>
            <td>${r.source === 'probe' ? '探针' : '流量'}</td>
            <td>${escapeHtml(r.route || '—')}</td>
            <td>${escapeHtml(r.provider)}</td>
            <td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(r.model)}</td>
            <td>${r.duration_ms != null ? `${r.duration_ms} ms` : '—'}</td>
            <td class="${r.outcome === 'success' ? 'text-ok' : 'text-danger'}">${r.outcome === 'success' ? '成功' : `${r.outcome === 'invalid_output' ? '输出不合格' : '上游错误'}${r.error_code ? ` · ${escapeHtml(r.error_code)}` : ''}${r.http_status ? ` · HTTP ${r.http_status}` : ''}`}</td>
        </tr>`).join('')}</tbody></table></div>`;
}
```

实现注记：
- `toast.success/error`、`formatTime`、`escapeHtml`、`state`、`API_BASE`、`activeRequestControllers` 均为 app.js 现有工具（实现前 grep 确认实际名称，如 toast 对象方法名可能不同——按现有调用习惯改写，不新造轮子）
- 状态灯阈值：🔴 成功率<80% 或 24h 内有 upstream_error；🟡 有 invalid_output 或 key 冷却；🟢 其余（与 spec ③ 一致）
- `formatTime(r.created_at * 1000)`：D1 存的是秒，前端 formatTime 若吃毫秒需乘 1000（实现时核对 formatTime 签名）

- [ ] **Step 4: styles.css**

文件尾追加：

```css
/* ===== AI 健康 ===== */
.ai-health-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin: 16px 0; }
.segmented { display: inline-flex; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.segmented button { background: transparent; border: 0; color: var(--text-dim); padding: 8px 14px; cursor: pointer; font-size: 13px; }
.segmented button.active { background: var(--surface-variant); color: var(--text); }
.ai-provider-card .health-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; }
.ai-key-pool { margin-top: 10px; padding: 10px; border: 1px dashed var(--border); border-radius: 10px; }
.ai-key-title { font-size: 12px; color: var(--text-dim); margin-bottom: 6px; }
.ai-key-row { display: flex; justify-content: space-between; font-size: 12px; padding: 3px 0; }
.ai-card-actions { display: flex; gap: 8px; margin-top: 14px; align-items: center; }
.ai-probe-model { background: var(--surface-variant); color: var(--text); border: 1px solid var(--border); border-radius: 8px; padding: 6px 8px; font-size: 12px; }
.ai-probe-note { font-size: 12px; color: var(--text-dim); }
.text-ok { color: var(--success); }
.text-danger { color: var(--danger); }
.mono { font-family: var(--font-mono); }
```

（实现时核对 `--surface-variant`/`--success`/`--danger` 等 CSS 变量名与现有 styles.css 一致，不一致则以现有为准。）

- [ ] **Step 5: 手核 + Commit**

本地起 preview（`npx wrangler pages dev app-config/public` 或项目现有 dev 惯例）核对：导航可达、24h/7d 切换、三家卡片渲染、探测按钮交互、外链新标签页。无自动化前端测试，人工核对即可。

```bash
git add app-config/public/admin/index.html app-config/public/admin/app.js app-config/public/admin/styles.css
git commit -m "feat(ai-health): 后台新增 AI 供应商健康页与控制台外链"
```

---

### Task 6: 部署与真场景验收

**Files:**
- 无代码改动；远端操作 + 文档沉淀

**Interfaces:**
- Consumes: Task 1-5 全部产出
- Produces: 线上可用的 AI 健康页 + agentmemory 沉淀

- [ ] **Step 1: 应用迁移**

```bash
cd auth-worker && npx wrangler d1 execute auth-db --remote --file migrations/0018_ai_health_events.sql
```

Expected: 执行成功（空表创建，无数据风险）

- [ ] **Step 2: 部署 worker 与 Pages**

```bash
cd auth-worker && npx wrangler deploy
```

Pages（app-config）按既有部署方式（wrangler pages deploy 或 git 集成，以仓库现状为准）。

- [ ] **Step 3: 真场景验收**

1. 浏览器打开后台 → AI 健康 → 点「全部探测」
2. 预期：zhipu 🟢（glm-5.3-flash 成功）；mimo 🔴 HTTP 402 AI_UPSTREAM_ERROR（欠费未充，红灯是**正确**结果）；agnes 视 key 池状态 🟢/🔴
3. 事件明细表出现 3 条「探针」来源记录
4. 点「前往控制台」三家外链各开新标签页且域名正确
5. 24h/7d 切换正常刷新

- [ ] **Step 4: 真实流量回归**

真机触发一次出题（现有流程），回到 AI 健康页确认出现 `traffic` 来源记录，验证被动链路。

- [ ] **Step 5: 沉淀记忆**

```javascript
// agentmemory memory_save
// content: 后台 AI 健康监控上线：D1 ai_health_events（30 天保留）、/admin/ai/health* 三端点、
// 探针直调三家底层函数不走轮替（避免掩盖目标家失败）、AppError 第 4 参 upstreamStatus 透传原始
// HTTP 状态码、后台 #/ai-health 页含三家卡片/状态灯/KeyPool 快照/控制台外链
// tags: ai-fallback, cloudflare-deploy
```

---

## Self-Review 结论

- **Spec 覆盖**：被动记录（Task 3）、主动探测（Task 4）、聚合+KeyPool（Task 4）、cron 30 天清理（Task 4 Step 4）、前端页+外链（Task 5）、验收（Task 6）——全覆盖；summary 端点为 spec 标注「可选」，纳入 Task 4 顺手实现（3 行 SQL）
- **占位符扫描**：无 TBD；Task 5 有两处「实现时核对现有工具名」属防御性说明（escapeHtml/toast/formatTime 确为 app.js 已有，风格变量名同理），不阻塞执行
- **类型一致性**：`recordHealthEvent(env, ctx, source, provider, model, outcome, error?, durationMs?)` 在 Task 2 定义、Task 3/4 消费一致；`upstreamStatus` Task 1 定义、Task 3/4 消费一致；`HealthRouteContext.route` 为 string（Task 2），Task 3 传入 `LlmRequestContext.route`（字符串联合，兼容）；`handleAiHealthList/Probe` 命名 Task 4 内一致
