// 智谱 GLM 提供商：OpenAI 兼容接口（https://docs.bigmodel.cn）。
// 作为文本生成的第二梯队：agnes 主路径失败（常见：共享池 429 全 key 冷却）后先回退 GLM，
// 再落 MiMo；三家都失败才交业务确定性兜底。
// GLM-4.7 是思考型模型：响应 message.reasoning_content 是思考链，正文在 message.content；
// response_format json_object 实测有效（content 为纯 JSON）。
import { AppError } from '../util/errors.ts';
import { readOpenAiSseStream } from './openai-stream.ts';

export const ZHIPU_BASE_URL = 'https://open.bigmodel.cn/api/paas/v4';
// 内部优先级（2026-09 全矩阵实测）：
// - glm-5.3-flash ~3-5s，但「始终思考」不支持关闭，只接受 thinking:{level:"low"}（~103 tok）
// - glm-4.7 / glm-4.6v / glm-4.5-air 接受 thinking:{type:"disabled"}：
//   4.7 关后 7s→2s/191→24 tok；4.6v 关后 12.6s 空正文→1s 正常；4.5-air 4.9s→1.2s
//   （出题是结构化任务，质量靠提示词约束而非思考链，正文 JSON 均不受影响）
// - glm-4.7-flash 高峰期常态 1305 限频（HTTP 200 包业务错误码），仅作末位备选
// thinking 参数发错形态直接 400（code 1210），因此按模型白名单分级，绝不统一发一种。
export const ZHIPU_MODELS = ['glm-5.3-flash', 'glm-4.7', 'glm-4.7-flash', 'glm-4.6v', 'glm-4.5-air'] as const;
export type ZhipuModel = typeof ZHIPU_MODELS[number];


/** GLM 限频错误码 1305「该模型当前访问量过大」：上游瞬时限流，轮替下一家即可。 */
const RATE_LIMIT_CODE = '1305';

export type ZhipuMessage = {
    role: 'system' | 'user' | 'assistant';
    content: unknown;
};

export interface ZhipuEnvironment {
    ZHIPU_API_KEY?: string;
    AI_TEST_MODE?: boolean | string;
}

export interface ZhipuOptions extends Record<string, unknown> {
    maxCompletionTokens?: number;
    temperature?: number;
    // 与 agnes/mimo 保持同形：默认发 response_format json_object（GLM 实测支持）。
    responseFormat?: boolean;
    // 上游请求超时（毫秒）：出题等长链路需要按客户端读超时预留轮替余量时由调用方收紧。
    timeoutMs?: number;
    // 流式模式：GLM 非流式时最后一 token 前无任何传输，流式可提前拿到 TTFB 并把
    // 已生成字符数回调给调用方（出题链路用它向前端推送真实进度）。
    stream?: boolean;
    // 流式正文进度回调：累积正文字符数。回调频率等于上游 SSE 频率（数十毫秒一次），
    // 节流由调用方负责。
    onProgress?: (chars: number) => void;
}

export function validateZhipuModel(value: unknown): ZhipuModel {
    if (typeof value !== 'string' || !(ZHIPU_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as ZhipuModel;
}

function isTestFallback(env: ZhipuEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export async function callZhipuJson(
    env: ZhipuEnvironment,
    model: unknown,
    messages: ZhipuMessage[],
    options: ZhipuOptions = {},
): Promise<unknown | null> {
    validateZhipuModel(model);
    const apiKey = typeof env.ZHIPU_API_KEY === 'string' ? env.ZHIPU_API_KEY.trim() : '';
    if (!apiKey) {
        // 测试模式下无 key 直接返回 null，由调用方走各自的离线兜底或失败状态。
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'Zhipu provider is not configured', 503);
    }

    // 按模型分级控制思考：5.3-flash 只认 {level:"low"}；其余支持关闭的发 {type:"disabled"}。
    // reasoning 计入 max_tokens 且极耗时，结构化 JSON 任务不需要思考链。
    const thinking = model === 'glm-5.3-flash'
        ? { level: 'low' }
        : { type: 'disabled' };
    const body: Record<string, unknown> = {
        model,
        messages,
        max_tokens: options.maxCompletionTokens ?? 1024,
        temperature: options.temperature ?? 0.7,
        stream: options.stream === true,
        thinking,
        // GLM 实测支持 json_object（关/低思考后 content 为纯 JSON）
        response_format: { type: 'json_object' },
    };
    if (options.responseFormat === false) delete body.response_format;

    // 限频 1305 是 HTTP 200 包裹的业务错误码：探测响应体并折算成上游错误，
    // 让 callLlmJson 立刻轮替下一家（实测 flash 高峰期偶发，退避重试会拖死出题）。
    try {
        for (let attempt = 0; attempt < 2; attempt += 1) {
            const response = await fetch(`${ZHIPU_BASE_URL}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${apiKey}`,
                },
                // 与 agnes/mimo 对齐：文本生成默认 90s 主动断，避免 Workers fetch 挂死整场出题；
                // 调用方可通过 options.timeoutMs 收紧以给轮替留预算。
                signal: AbortSignal.timeout(
                    typeof options.timeoutMs === 'number' && options.timeoutMs > 0 ? options.timeoutMs : 90_000,
                ),
                body: JSON.stringify(body),
            });
            if (response.ok) {
                const payload: unknown = options.stream === true
                    ? await readOpenAiSseStream(response, options.onProgress)
                    : await response.json();
                if (isZhipuRateLimited(payload)) {
                    throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider rate limited', 502, response.status);
                }
                return payload;
            }
            // 网络/网关级 5xx 做一次短退避重试；4xx 是确定性错误直接上抛
            if (response.status >= 500 && attempt === 0) {
                await new Promise(resolve => setTimeout(resolve, 400));
                continue;
            }
            throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider request failed', 502, response.status);
        }
        throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider request failed', 502);
    } catch (error) {
        if (error instanceof AppError) throw error;
        // AbortError 等网络异常统一折算成上游错误，由 callLlmJson 决定是否轮到下一家
        throw new AppError('AI_UPSTREAM_ERROR', 'Zhipu provider request failed', 502);
    }
}

function isZhipuRateLimited(payload: unknown): boolean {    if (typeof payload !== 'object' || payload === null) return false;
    const error = (payload as Record<string, unknown>).error;
    if (typeof error !== 'object' || error === null) return false;
    return (error as Record<string, unknown>).code === RATE_LIMIT_CODE;
}

export function extractZhipuText(payload: unknown): string {
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
