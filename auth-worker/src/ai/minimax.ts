// MiniMax 提供商：api.aiportx.com 网关的 OpenAI 兼容接口。
//
// 只接 MiniMax-M3（2026-09-12 用户拍板「只取里面的 M3」）。选它的实测依据（同题长输出、
// max_tokens 6000、3 轮）：无 reasoning_content、直出 JSON（偶带 ``` 包裹），361~393 字符/秒，
// 真链路出一套 13 题 134s / degradedSlots=0 —— 同批 8 档里唯一「快且不思考」的。
// 网关同批还有 M2/M2.1/M2.5/M2.7 及其 highspeed 档，都不接，理由：
// - M2.x 全系每次调用都返回 reasoning_content，且 reasoning 计入 max_tokens：97~168
//   （M2.5）/123~139（M2.5-highspeed）/81~116（M2.7-highspeed）字符/秒，M2.7 只有 23；
// - M2.1 系在 400 token 预算下思考就把额度烧光、正文为空（finish_reason=length），正是
//   handler 里「200 但没正文 → 当上游失败轮下一家」要防的那种输出；
// - 真链路实测 M2.7-highspeed 一套 509s 且 3 次产出非法、要靠 M3 接手（M2.5 也要 254s）；
// - 关思考的开关（enable_thinking / chat_template_kwargs.enable_thinking / reasoning.effort）
//   对这个网关全部无效，接进来就得忍受「思考吃预算」。
// 另外 response_format/json_schema 传了会被静默忽略（200 但回散文），所以与 agnes 同策略：
// 默认不发，靠提示词约束 JSON；options.responseFormat 显式开启时才带上。
import { AppError } from '../util/errors.ts';

/** 默认 base URL：aiportx 网关；可用 MINIMAX_BASE_URL 覆盖（换区域/换代理时不必改代码）。 */
export const MINIMAX_DEFAULT_BASE_URL = 'https://api.aiportx.com/v1';

/**
 * 可用模型白名单：只留 M3。请求里写别的 MiniMax 档（M2.x 等）一律 INVALID_MODEL，
 * 想放开得先按上面那几条实测理由重新评估。
 */
export const MINIMAX_MODELS = ['MiniMax-M3'] as const;
export type MinimaxModel = typeof MINIMAX_MODELS[number];

/**
 * MiniMax 首选模型：白名单里只有 M3，它就是无歧义的首选（handler 与探针都引用这里）。
 * 以后若放开更多档，改这一处即可让 DEFAULT_MODEL_BY_PROVIDER 与探针跟着走。
 */
export const MINIMAX_DEFAULT_MODEL: MinimaxModel = 'MiniMax-M3';

export type MinimaxMessage = {
    role: 'system' | 'user' | 'assistant';
    content: unknown;
};

export interface MinimaxEnvironment {
    MINIMAX_API_KEY?: string;
    MINIMAX_BASE_URL?: string;
    AI_TEST_MODE?: boolean | string;
}

export interface MinimaxOptions extends Record<string, unknown> {
    maxCompletionTokens?: number;
    temperature?: number;
    /** 网关会静默忽略 response_format，默认不发；只有显式 true 才带（留作以后网关支持时启用）。 */
    responseFormat?: boolean;
    timeoutMs?: number;
}

export function validateMinimaxModel(value: unknown): MinimaxModel {
    if (typeof value !== 'string' || !(MINIMAX_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as MinimaxModel;
}

function isTestFallback(env: MinimaxEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export function minimaxBaseUrl(env: MinimaxEnvironment): string {
    const configured = typeof env.MINIMAX_BASE_URL === 'string' ? env.MINIMAX_BASE_URL.trim() : '';
    return (configured === '' ? MINIMAX_DEFAULT_BASE_URL : configured).replace(/\/+$/u, '');
}

export async function callMinimaxJson(
    env: MinimaxEnvironment,
    model: unknown,
    messages: MinimaxMessage[],
    options: MinimaxOptions = {},
): Promise<unknown | null> {
    validateMinimaxModel(model);
    const apiKey = typeof env.MINIMAX_API_KEY === 'string' ? env.MINIMAX_API_KEY.trim() : '';
    if (!apiKey) {
        // 测试模式下无 key 直接返回 null，由调用方轮下一家或走离线兜底。
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'MiniMax provider is not configured', 503);
    }

    const body: Record<string, unknown> = {
        model,
        messages,
        max_tokens: options.maxCompletionTokens ?? 1024,
        temperature: options.temperature ?? 0.7,
        stream: false,
    };
    if (options.responseFormat === true) body.response_format = { type: 'json_object' };

    try {
        for (let attempt = 0; attempt < 2; attempt += 1) {
            const response = await fetch(`${minimaxBaseUrl(env)}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${apiKey}`,
                },
                // 与 agnes/mimo/zhipu/bailian 对齐：默认 90s 主动断，调用方可用 options.timeoutMs 收紧
                signal: AbortSignal.timeout(
                    typeof options.timeoutMs === 'number' && options.timeoutMs > 0 ? options.timeoutMs : 90_000,
                ),
                body: JSON.stringify(body),
            });
            if (response.ok) return response.json();
            // 网关级 5xx 做一次短退避重试；4xx 是确定性错误（模型名/额度/参数）直接上抛
            if (response.status >= 500 && attempt === 0) {
                await new Promise(resolve => setTimeout(resolve, 400));
                continue;
            }
            const detail = await response.text().catch(() => '');
            throw new AppError(
                'AI_UPSTREAM_ERROR',
                'MiniMax provider request failed: ' + detail.slice(0, 200),
                502,
                response.status,
            );
        }
        throw new AppError('AI_UPSTREAM_ERROR', 'MiniMax provider request failed', 502);
    } catch (error) {
        if (error instanceof AppError) throw error;
        // AbortError 等网络异常统一折算成上游错误，由 callLlmJson 决定是否轮下一家
        throw new AppError('AI_UPSTREAM_ERROR', 'MiniMax provider request failed', 502);
    }
}

export function extractMinimaxText(payload: unknown): string {
    if (typeof payload !== 'object' || payload === null || !Array.isArray((payload as Record<string, unknown>).choices)) {
        return '';
    }
    const choices = (payload as Record<string, unknown>).choices as Array<unknown>;
    if (choices.length === 0) return '';
    const first = choices[0];
    if (typeof first !== 'object' || first === null) return '';
    const message = (first as Record<string, unknown>).message;
    if (typeof message !== 'object' || message === null) return '';
    const content = (message as Record<string, unknown>).content;
    return typeof content === 'string' ? content.trim() : '';
}
