// Agnes AI 提供商：OpenAI 兼容接口。
// 多 key 容灾通过 util/key-pool.ts 的 KeyPool + fetchWithKeyRotation 实现：
// 单个 key 触发 429/401/403 时自动轮换到下一个可用 key；共享限流池下的 429 会标记全部 key 冷却后上抛稳定错误。
import { AppError } from '../util/errors.ts';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool.ts';

export const AGNES_BASE_URL = 'https://apihub.agnes-ai.com/v1';
export const AGNES_MODELS = ['agnes-2.5-flash'] as const;
export const AGNES_IMAGE_MODELS = ['agnes-image-2.5-flash'] as const;
export type AgnesModel = typeof AGNES_MODELS[number];
export type AgnesImageModel = typeof AGNES_IMAGE_MODELS[number];

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
    // 上游请求超时（毫秒）：出题等长链路需要按客户端读超时预留轮替余量时由调用方收紧。
    timeoutMs?: number;
}

// 触发自动轮换的状态：限流/鉴权错误 + 网关级 5xx（共享基础设施抖动时逐 key 重试）。
const ROTATE_ON = [429, 401, 403, 500, 502, 503, 504];

export function validateAgnesModel(value: unknown): AgnesModel {
    if (typeof value !== 'string' || !(AGNES_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as AgnesModel;
}

function validateAgnesImageModel(value: unknown): AgnesImageModel {
    if (typeof value !== 'string' || !(AGNES_IMAGE_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI image model', 400);
    }
    return value as AgnesImageModel;
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
    // 文本生成（尤其 quiz 两段长 JSON）上游可能长时间不响应；
    // Workers fetch 无超时会挂到平台上限，App 端 90s 读超时早已断开。
    // 默认 90s 主动断；调用方可通过 options.timeoutMs 收紧以给轮替留预算。
    return callAgnesPayload(env, body, '/chat/completions',
        typeof options.timeoutMs === 'number' && options.timeoutMs > 0 ? options.timeoutMs : 90_000);
}

/**
 * Agnes Image 2.5 Flash 适配：按官方文档调用 /images/generations。
 * 只请求 Base64 输出，避免把供应商临时图片 URL 当成长期资源或二次暴露给客户端。
 */
export async function callAgnesImage(
    env: AgnesEnvironment,
    model: unknown,
    prompt: string,
    timeoutMs = 120_000,
): Promise<Uint8Array> {
    validateAgnesImageModel(model);
    const body: Record<string, unknown> = {
        model,
        prompt,
        size: '1K',
        ratio: '16:9',
        return_base64: true,
        extra_body: { response_format: 'b64_json' },
    };
    const payload = await callAgnesPayload(env, body, '/images/generations', timeoutMs);
    if (payload === null) {
        throw new AppError('AI_NOT_CONFIGURED', 'Agnes image provider is not configured', 503);
    }
    const base64 = readAgnesImageBase64(payload);
    if (!base64) throw new AppError('INVALID_AI_OUTPUT', 'Agnes image response is invalid', 502);
    return decodeBase64Image(base64);
}

async function callAgnesPayload(
    env: AgnesEnvironment,
    body: Record<string, unknown>,
    endpoint: '/chat/completions' | '/images/generations',
    timeoutMs?: number,
): Promise<unknown | null> {
    const apiKeys = env.AGNES_API_KEYS?.trim();
    if (!apiKeys) {
        // 测试模式下无 key 直接返回 null，由调用方走各自的离线兜底或失败状态。
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'Agnes provider is not configured', 503);
    }

    const kv = env.KV;
    if (!kv) {
        // 无 KV 绑定时退化成单 key 直连（不启用冷却状态）。
        return requestOnce(apiKeys.split(/[\s,]+/)[0].trim(), body, endpoint, timeoutMs);
    }

    const pool = new KeyPool('agnes', env.AGNES_API_KEYS, kv);
    const response = await fetchWithKeyRotation(
        pool,
        (key) => fetch(`${AGNES_BASE_URL}${endpoint}`, buildRequestInit(key, body, timeoutMs)),
        ROTATE_ON,
    );

    if (response.ok) {
        return response.json();
    }

    // 所有 key 轮流失败（含共享池 429）。上抛稳定错误，由调用方决定降级方式，
    // 避免把 key 级错误伪装成模型结构错误。
    throw new AppError('AI_UPSTREAM_ERROR', 'Agnes provider request failed', 502);
}

function buildRequestInit(
    key: string,
    body: Record<string, unknown>,
    timeoutMs?: number,
): RequestInit {
    const init: RequestInit = {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${key}`,
        },
        body: JSON.stringify(body),
    };
    if (timeoutMs !== undefined) init.signal = AbortSignal.timeout(timeoutMs);
    return init;
}

async function requestOnce(
    key: string,
    body: Record<string, unknown>,
    endpoint: '/chat/completions' | '/images/generations',
    timeoutMs?: number,
): Promise<unknown> {
    const response = await fetch(`${AGNES_BASE_URL}${endpoint}`, buildRequestInit(key, body, timeoutMs));
    if (!response.ok) {
        throw new AppError('AI_UPSTREAM_ERROR', 'Agnes provider request failed', 502);
    }
    return response.json();
}

function readAgnesImageBase64(payload: unknown): string | null {
    if (!isRecord(payload) || !Array.isArray(payload.data) || payload.data.length === 0) return null;
    const first = payload.data[0];
    if (!isRecord(first) || typeof first.b64_json !== 'string' || !first.b64_json.trim()) return null;
    return first.b64_json.trim();
}

function decodeBase64Image(value: string): Uint8Array {
    if (value.length > 16 * 1024 * 1024) {
        throw new AppError('INVALID_AI_OUTPUT', 'Agnes image response is invalid', 502);
    }
    try {
        const binary = atob(value);
        return Uint8Array.from(binary, character => character.charCodeAt(0));
    } catch {
        throw new AppError('INVALID_AI_OUTPUT', 'Agnes image response is invalid', 502);
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
