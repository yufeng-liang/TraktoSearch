// AI 供应商健康监控端点：聚合查询 + 手动探针。
// 鉴权由 index.ts 的 handleAdminApi 统一 verifyAccessJWT，本模块不重复校验。
// 安全边界与 health.ts 一致：只落库路由/供应商/模型/错误码/状态码/耗时，不含 prompt 与响应正文。

import { AppError, errorResponse, now, successResponse } from '../util/errors.ts';
import { recordHealthEvent, readKeyPoolSnapshot } from '../ai/health.ts';
import { callZhipuJson, ZHIPU_MODELS, type ZhipuEnvironment } from '../ai/zhipu.ts';
import { callAgnesJson, AGNES_MODELS, type AgnesEnvironment } from '../ai/agnes.ts';
import { callMimoJson, MIMO_MODELS, type MimoEnvironment, type MimoModel } from '../ai/mimo.ts';
import { callBailianJson, BAILIAN_MODELS, type BailianEnvironment } from '../ai/bailian.ts';
import { DEFAULT_MODEL_BY_PROVIDER } from '../ai/handler.ts';

const WINDOW_SECONDS: Record<string, number> = { '24h': 86_400, '7d': 7 * 86_400 };
const PROBE_TIMEOUT_MS = 10_000;

// 探针固定消息与参数：不随业务提示词变化，保证跨时间可比性
const PROBE_MESSAGES = [{ role: 'user' as const, content: '回复 OK 两个字，不要输出其他内容。' }];
const PROBE_OPTIONS = { maxCompletionTokens: 16, temperature: 0, responseFormat: false, timeoutMs: PROBE_TIMEOUT_MS };

// 默认模型直接取 handler 那一份，不再维护同值副本——主供应商换人（如 zhipu→bailian）时
// 探针漏掉新家就等于健康页看不到主力到底通不通。
type ProbeProvider = 'zhipu' | 'agnes' | 'mimo' | 'bailian';

const PROBE_DEFAULT_MODEL: Record<ProbeProvider, string> = {
    zhipu: DEFAULT_MODEL_BY_PROVIDER.zhipu,
    agnes: DEFAULT_MODEL_BY_PROVIDER.agnes,
    mimo: DEFAULT_MODEL_BY_PROVIDER.mimo,
    bailian: DEFAULT_MODEL_BY_PROVIDER.bailian,
};

// 显式数组判定，避免 `in` 运算符把原型链上的属性（constructor / hasOwnProperty 等）当成合法值
const PROBE_PROVIDERS: readonly ProbeProvider[] = ['zhipu', 'bailian', 'agnes', 'mimo'];

const VALID_MODELS: Record<string, readonly string[]> = {
    zhipu: ZHIPU_MODELS,
    agnes: AGNES_MODELS,
    mimo: MIMO_MODELS,
    bailian: BAILIAN_MODELS,
};

// 探针要读四家密钥；DB/KV 均可选，与 AiHealthEnvironment 的尽力而为语义一致
interface ProbeEnvironment extends ZhipuEnvironment, AgnesEnvironment, MimoEnvironment, BailianEnvironment {
    DB?: D1Database;
    KV?: KVNamespace;
}

interface ProbeResult {
    provider: string;
    model: string;
    outcome: 'success' | 'upstream_error';
    errorCode: string | null;
    httpStatus: number | null;
    durationMs: number;
}

// 单家探测：直调底层供应商函数（不走 callLlmJson 轮替，避免别家成功掩盖目标家失败）。
// 供应商内部的一次 5xx 退避重试与 agnes 的 key 轮换属可接受的重试语义，不算跨家轮替。
// ctx 来自 handleAdminApi（index.ts），落库经 waitUntil 延寿；仅测试直调时为 undefined，退化为 fire-and-forget。
async function probeOne(
    env: ProbeEnvironment,
    provider: string,
    model: string,
    requestId: string,
    ctx: ExecutionContext | undefined,
): Promise<ProbeResult> {
    const startedAtMs = Date.now();
    try {
        if (provider === 'zhipu') {
            await callZhipuJson(env, model, PROBE_MESSAGES, PROBE_OPTIONS);
        } else if (provider === 'bailian') {
            await callBailianJson(env, model, PROBE_MESSAGES, PROBE_OPTIONS);
        } else if (provider === 'agnes') {
            await callAgnesJson(env, model, PROBE_MESSAGES, PROBE_OPTIONS);
        } else {
            // 模型已在 handleAiHealthProbe 里过 VALID_MODELS 白名单，这里窄化仅为通过类型检查
            await callMimoJson(env, model as MimoModel, PROBE_MESSAGES, PROBE_OPTIONS);
        }
        const durationMs = Date.now() - startedAtMs;
        recordHealthEvent(env, { route: 'probe', requestId, background: ctx }, 'probe', provider, model, 'success', undefined, durationMs);
        return { provider, model, outcome: 'success', errorCode: null, httpStatus: null, durationMs };
    } catch (error) {
        // 未配置（AI_NOT_CONFIGURED，503）同样计入不健康：配置缺失就是可用性问题
        const appError = error instanceof AppError
            ? error
            : new AppError('AI_UPSTREAM_ERROR', 'AI provider probe failed', 502);
        const durationMs = Date.now() - startedAtMs;
        recordHealthEvent(env, { route: 'probe', requestId, background: ctx }, 'probe', provider, model, 'upstream_error', appError, durationMs);
        return {
            provider,
            model,
            outcome: 'upstream_error',
            errorCode: appError.code,
            httpStatus: appError.upstreamStatus,
            durationMs,
        };
    }
}

// POST /admin/ai/health/probe：body 为 { all: true } 或 { provider, model? }
export async function handleAiHealthProbe(
    request: Request,
    env: ProbeEnvironment,
    requestId: string,
    ctx?: ExecutionContext,
): Promise<Response> {
    let body: Record<string, unknown>;
    try {
        const parsed: unknown = await request.json();
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
            return errorResponse(new AppError('INVALID_REQUEST', 'Invalid JSON body', 400), requestId);
        }
        body = parsed as Record<string, unknown>;
    } catch {
        return errorResponse(new AppError('INVALID_REQUEST', 'Invalid JSON body', 400), requestId);
    }

    const targets: Array<{ provider: string; model: string }> = [];
    if (body.all === true) {
        for (const provider of PROBE_PROVIDERS) {
            targets.push({ provider, model: PROBE_DEFAULT_MODEL[provider] });
        }
    } else {
        const provider = typeof body.provider === 'string' ? body.provider : '';
        if (!(PROBE_PROVIDERS as readonly string[]).includes(provider)) {
            return errorResponse(new AppError('INVALID_REQUEST', 'Unknown provider', 400), requestId);
        }
        const model = body.model === undefined ? PROBE_DEFAULT_MODEL[provider as ProbeProvider] : body.model;
        if (typeof model !== 'string' || !VALID_MODELS[provider].includes(model)) {
            return errorResponse(new AppError('INVALID_REQUEST', 'Unknown model for provider', 400), requestId);
        }
        targets.push({ provider, model });
    }

    // 多家并行探测，各家独立落库；单家异常不影响其他家结果
    const results = await Promise.all(targets.map(target => probeOne(env, target.provider, target.model, requestId, ctx)));
    return successResponse({ results, probedAt: now() }, requestId);
}

interface AggregateRow {
    provider: string;
    model: string;
    total: number;
    success: number;
    avg_duration_ms: number | null;
    p95_duration_ms: number | null;
}

interface RecentRow {
    created_at: number;
    source: string;
    route: string | null;
    provider: string;
    model: string;
    outcome: string;
    error_code: string | null;
    http_status: number | null;
    duration_ms: number | null;
    request_id: string | null;
}

interface HealthListEnvironment {
    DB: D1Database;
    KV?: KVNamespace;
}

// GET /admin/ai/health?window=24h|7d：按供应商+模型聚合 + 最近事件 + key 池快照
export async function handleAiHealthList(request: Request, env: HealthListEnvironment, requestId: string): Promise<Response> {
    const url = new URL(request.url);
    const window = url.searchParams.get('window') ?? '24h';
    // hasOwnProperty 判定：`in` 会把原型链属性（如 constructor）当成合法窗口
    if (!Object.prototype.hasOwnProperty.call(WINDOW_SECONDS, window)) {
        return errorResponse(new AppError('INVALID_REQUEST', 'Invalid window', 400), requestId);
    }
    const since = now() - WINDOW_SECONDS[window];

    // p95 名义上是 95 分位耗时，但 D1（SQLite）免费层无 percentile 函数；
    // 这里用 MAX 近似（语义为窗口内最差耗时），字段名保持 p95_duration_ms 以稳定前端契约。
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
        aggregateStmt.all<AggregateRow>(),
        recentStmt.all<RecentRow>(),
        env.KV ? readKeyPoolSnapshot(env.KV) : Promise.resolve([]),
    ]);

    const aggregates = (aggregateRes.results ?? []).map(row => ({
        ...row,
        // success 由 SQL 求和返回，D1 可能给出 number；空窗口 total 为 0 时避免除零
        successRate: Number(row.total) > 0 ? Number(row.success) / Number(row.total) : 0,
    }));
    return successResponse({ window, aggregates, recent: recentRes.results ?? [], keyPool }, requestId);
}

interface SummaryRow {
    provider: string;
    total: number;
    success: number;
}

interface SummaryEnvironment {
    DB: D1Database;
}

// GET /admin/ai/health/summary：管理首页卡片用的 24h 精简聚合
export async function handleAiHealthSummary(env: SummaryEnvironment, requestId: string): Promise<Response> {
    const since = now() - 86_400;
    const res = await env.DB.prepare(`
        SELECT provider,
               COUNT(*) AS total,
               SUM(CASE WHEN outcome = 'success' THEN 1 ELSE 0 END) AS success
        FROM ai_health_events
        WHERE created_at >= ?
        GROUP BY provider
    `).bind(since).all<SummaryRow>();
    return successResponse({ window: '24h', byProvider: res.results ?? [] }, requestId);
}
