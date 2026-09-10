// AI 供应商健康事件：被动记录真实调用与主动探针结果到 D1，供后台「AI 健康」页聚合展示。
// 安全边界与 logAiDiagnostic 一致：只记路由/供应商/模型/错误码/状态码，禁止 prompt、响应正文与 key。

import { AppError } from '../util/errors.ts';

export type HealthOutcome = 'success' | 'upstream_error' | 'invalid_output';
export type HealthSource = 'traffic' | 'probe';

export interface AiHealthEnvironment {
    // 尽力而为语义：handler 的 AiEnvironment.DB 是可选的（D1Database | undefined），
    // 无 DB（本地 dev/降级环境）时健康写入静默跳过，绝不能影响主链路。
    DB?: D1Database;
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
    ctx: HealthRouteContext | undefined,
    source: HealthSource,
    provider: string,
    model: string,
    outcome: HealthOutcome,
    error?: unknown,
    durationMs?: number,
): void {
    // 尽力而为：无 D1 绑定（本地 dev/降级环境）或无路由上下文时不写、不排后台任务
    const db = env.DB;
    if (!db || !ctx) return;
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
            await db.prepare(`
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
        // waitUntil 挂载本身可能抛（context 已释放等），绝不能让它把异常传播进主链路
        try {
            ctx.background.waitUntil(write);
        } catch {
            void write;
        }
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
