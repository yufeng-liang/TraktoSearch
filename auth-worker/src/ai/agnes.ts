// Agnes AI 提供商：OpenAI 兼容的 Chat Completions 接口。
// 多 key 容灾通过 util/key-pool.ts 的 KeyPool + fetchWithKeyRotation 实现：
// 单个 key 触发 429/401/403 时自动轮换到下一个可用 key；共享限流池下的 429 会标记全部 key 冷却后上抛稳定错误。
import { AppError } from '../util/errors.ts';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool.ts';

export const AGNES_BASE_URL = 'https://apihub.agnes-ai.com/v1';
export const AGNES_MODELS = ['agnes-2.5-flash'] as const;
export type AgnesModel = typeof AGNES_MODELS[number];

export type AgnesMessage = {
    role: 'system' | 'user' | 'assistant';
    content: unknown;
};

export interface AgnesEnvironment {
    AGNES_API_KEYS?: string;
    KV?: KVNamespace;
    AI_TEST_MODE?: boolean | string;
}

export interface AgnesOptions extends Record<string, unknown> {
    maxCompletionTokens?: number;
    temperature?: number;
    // Agnes 文档未确认 response_format；默认由提示词约束 JSON，仅显式开启时发送以保留兼容性。
    responseFormat?: boolean;
}

// 触发自动轮换的状态：限流/鉴权错误 + 网关级 5xx（共享基础设施抖动时逐 key 重试）。
const ROTATE_ON = [429, 401, 403, 500, 502, 503, 504];

export function validateAgnesModel(value: unknown): AgnesModel {
    if (typeof value !== 'string' || !(AGNES_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as AgnesModel;
}

function isTestFallback(env: AgnesEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export async function callAgnesJson(
    env: AgnesEnvironment,
    model: unknown,
    messages: AgnesMessage[],
    options: AgnesOptions = {},
): Promise<unknown | null> {
    validateAgnesModel(model);
    const body: Record<string, unknown> = {
        model,
        messages,
        max_tokens: options.maxCompletionTokens ?? 1024,
        temperature: options.temperature ?? 0.7,
        stream: false,
    };
    // Agnes 2.5 Flash 文档列出的请求参数不包含 response_format。
    // 默认依赖提示词要求合法 JSON，避免因未确认的参数导致整个文本请求失败。
    if (options.responseFormat === true) {
        body.response_format = { type: 'json_object' };
    }
    return callAgnesPayload(env, body);
}

async function callAgnesPayload(env: AgnesEnvironment, body: Record<string, unknown>): Promise<unknown | null> {
    const apiKeys = env.AGNES_API_KEYS?.trim();
    if (!apiKeys) {
        // 测试模式下无 key 直接返回 null，由调用方走离线兜底，保证路由测试不依赖真实 key。
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'Agnes provider is not configured', 503);
    }

    const kv = env.KV;
    if (!kv) {
        // 无 KV 绑定时退化成单 key 直连（不启用冷却状态）。
        return requestOnce(apiKeys.split(/[\s,]+/)[0].trim(), body);
    }

    const pool = new KeyPool('agnes', env.AGNES_API_KEYS, kv);
    const response = await fetchWithKeyRotation(
        pool,
        (key) =>
            fetch(`${AGNES_BASE_URL}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${key}`,
                },
                body: JSON.stringify(body),
            }),
        ROTATE_ON,
    );

    if (response.ok) {
        return response.json();
    }

    // 所有 key 轮流失败（含共享池 429）。上抛稳定错误，由调用方决定是否回退其它供应商或离线兜底，
    // 避免把 key 级错误伪装成模型结构错误。
    throw new AppError('AI_UPSTREAM_ERROR', 'Agnes provider request failed', 502);
}

async function requestOnce(key: string, body: Record<string, unknown>): Promise<unknown> {
    const response = await fetch(`${AGNES_BASE_URL}/chat/completions`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${key}`,
        },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        throw new AppError('AI_UPSTREAM_ERROR', 'Agnes provider request failed', 502);
    }
    return response.json();
}
