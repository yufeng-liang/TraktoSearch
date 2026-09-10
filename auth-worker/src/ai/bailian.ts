// 阿里云百炼（Model Studio 新加坡地域）OpenAI 兼容提供商。
//
// 为什么接它：账号级吞吐天花板是这套出题链路的墙——智谱同一模型是账号级串行队列，实测
// 稳定在 ~40 字符/秒（并发拉到 8 也一样），一套 13 题要 5~9 分钟；百炼的 flash 档实测
// 20.4k 字符 / 105 秒 ≈ 217 字符/秒，同一套流水线整套跑完 87~129 秒，且 4 套全部真生成、
// 降级格 0。两家配额独立，因此这条是真正的跨供应商车道，不是同一条队列里插队。
//
// 免费额度按模型独立发放（每模型 100 万 token、约 90 天，输入输出共用），用尽后上游返回
// 403 + `AllocationQuota.FreeTierOnly`。所以「换模型」= 换一个 model id 继续用免费额度，
// 梯队的轮转交给 handler 的 MODEL_FALLBACKS_BY_PROVIDER（与智谱同一套机制）。
//
// 两个参数缺一不可（都实测过语义）：
// - enable_thinking: false：qwen3.6-flash 这类默认「始终思考」，一次调用烧 1000+ reasoning
//   token（寒暄一句 13.5s）；关掉后同问 1.2s。思考还计入 max_tokens，放任它跑会有
//   finish_reason=length 且正文为空的风险（同智谱 glm-5.3-flash）。
// - response_format: {type:'json_object'}：本区不支持 json_schema（官方明确「新加坡地域
//   暂不支持」，实测传了会被静默忽略、输出完全不守 schema）；json_object 要求提示词里
//   出现 "JSON" 字样，出题与每日知识的提示词本来就写着「只返回 JSON」。
import { AppError } from '../util/errors.ts';
import { readOpenAiSseStream } from './openai-stream.ts';

/** 默认 base URL：业务空间专属域名（与北京区不同，换成 cn-beijing 域名即为北京区）。 */
export const BAILIAN_DEFAULT_BASE_URL = 'https://ws-k1ig9eukvaiwq6yr.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1';

/**
 * 可用模型白名单：全部实测在「关思考 + json_object + 流式」下返回合法 JSON。
 * 只作请求校验用，不等于梯队顺序——梯队见 handler 的 MODEL_FALLBACKS_BY_PROVIDER.bailian。
 */
export const BAILIAN_MODELS = [
    'qwen3.6-flash',
    'qwen3.7-flash',
    'qwen3.8-flash',
    'qwen3.5-flash',
    'qwen-flash',
    'qwen3.6-plus',
    'qwen3.7-plus',
    'qwen3.6-max-preview',
    'qwen3-max',
    'qwen-max',
    'qwen-plus',
    'qwen-turbo',
    'qwen3.8-max',
    'qwen3.7-max',
    'deepseek-v4-flash',
    'deepseek-v4-pro',
    'deepseek-v4-pro-0813',
    'glm-5.2',
    'glm-5.1',
    'kimi-k3',
] as const;
export type BailianModel = typeof BAILIAN_MODELS[number];

export type BailianMessage = {
    role: 'system' | 'user' | 'assistant';
    content: unknown;
};

export interface BailianEnvironment {
    BAILIAN_API_KEY?: string;
    BAILIAN_BASE_URL?: string;
    AI_TEST_MODE?: boolean | string;
}

export interface BailianOptions extends Record<string, unknown> {
    maxCompletionTokens?: number;
    temperature?: number;
    /** 默认发 response_format json_object；显式 false 时不发（留给不需要 JSON 的调用方）。 */
    responseFormat?: boolean;
    timeoutMs?: number;
    stream?: boolean;
    onProgress?: (chars: number) => void;
}

export function validateBailianModel(value: unknown): BailianModel {
    if (typeof value !== 'string' || !(BAILIAN_MODELS as readonly string[]).includes(value)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as BailianModel;
}

function isTestFallback(env: BailianEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export function bailianBaseUrl(env: BailianEnvironment): string {
    const configured = typeof env.BAILIAN_BASE_URL === 'string' ? env.BAILIAN_BASE_URL.trim() : '';
    return (configured === '' ? BAILIAN_DEFAULT_BASE_URL : configured).replace(/\/+$/u, '');
}

/** 免费额度耗尽（403 AllocationQuota.FreeTierOnly）单列：它决定「换下一个模型」而不是「这家挂了」。 */
export function isFreeQuotaExhausted(payload: unknown): boolean {
    if (typeof payload !== 'object' || payload === null) return false;
    const error = (payload as Record<string, unknown>).error;
    if (typeof error !== 'object' || error === null) return false;
    const record = error as Record<string, unknown>;
    const code = typeof record.code === 'string' ? record.code : '';
    const message = typeof record.message === 'string' ? record.message : '';
    return code === 'AllocationQuota.FreeTierOnly' || /free quota/i.test(message);
}

export async function callBailianJson(
    env: BailianEnvironment,
    model: unknown,
    messages: BailianMessage[],
    options: BailianOptions = {},
): Promise<unknown | null> {
    validateBailianModel(model);
    const apiKey = typeof env.BAILIAN_API_KEY === 'string' ? env.BAILIAN_API_KEY.trim() : '';
    if (!apiKey) {
        // 测试模式下无 key 直接返回 null，由调用方轮下一家或走离线兜底。
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'Bailian provider is not configured', 503);
    }

    const body: Record<string, unknown> = {
        model,
        messages,
        max_tokens: options.maxCompletionTokens ?? 1024,
        temperature: options.temperature ?? 0.7,
        stream: options.stream === true,
        // 关思考：见文件头的实测数据，开着会让单次调用慢一个数量级
        enable_thinking: false,
    };
    if (options.responseFormat !== false) body.response_format = { type: 'json_object' };

    try {
        for (let attempt = 0; attempt < 2; attempt += 1) {
            const response = await fetch(`${bailianBaseUrl(env)}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${apiKey}`,
                },
                // 与 agnes/mimo/zhipu 对齐：默认 90s 主动断，调用方可用 options.timeoutMs 收紧
                signal: AbortSignal.timeout(
                    typeof options.timeoutMs === 'number' && options.timeoutMs > 0 ? options.timeoutMs : 90_000,
                ),
                body: JSON.stringify(body),
            });
            if (response.ok) {
                const payload: unknown = options.stream === true
                    ? await readOpenAiSseStream(response, options.onProgress)
                    : await response.json();
                // 兼容模式把业务错误包在 200 里时也要判出来（免费额度用尽通常走 403，但不排除包 200）
                if (isFreeQuotaExhausted(payload)) {
                    throw new AppError('AI_UPSTREAM_ERROR', 'Bailian free quota exhausted', 502, response.status);
                }
                return payload;
            }
            // 403 免费额度耗尽属于「换模型」，不是「这家挂了」：带上原文让上层诊断能看懂
            if (response.status === 403) {
                const text = await response.text().catch(() => '');
                throw new AppError('AI_UPSTREAM_ERROR', 'Bailian free quota exhausted: ' + text.slice(0, 200), 502, 403);
            }
            // 网关级 5xx 做一次短退避重试；4xx 是确定性错误直接上抛
            if (response.status >= 500 && attempt === 0) {
                await new Promise(resolve => setTimeout(resolve, 400));
                continue;
            }
            const detail = await response.text().catch(() => '');
            throw new AppError('AI_UPSTREAM_ERROR', 'Bailian provider request failed: ' + detail.slice(0, 200), 502, response.status);
        }
        throw new AppError('AI_UPSTREAM_ERROR', 'Bailian provider request failed', 502);
    } catch (error) {
        if (error instanceof AppError) throw error;
        // AbortError 等网络异常统一折算成上游错误，由 callLlmJson 决定是否轮下一家
        throw new AppError('AI_UPSTREAM_ERROR', 'Bailian provider request failed', 502);
    }
}

export function extractBailianText(payload: unknown): string {
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
