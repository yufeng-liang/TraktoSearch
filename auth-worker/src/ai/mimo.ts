// MiMo 上游封装：只允许固定模型，隐藏供应商错误细节，并统一重试一次。

import { AppError } from '../util/errors.ts';

export const MIMO_BASE_URL = 'https://api.xiaomimimo.com/v1';

export const MIMO_MODELS = [
    'mimo-v2.5',
    'mimo-v2.5-pro',
    'mimo-v2.5-asr',
    'mimo-v2.5-tts',
    'mimo-v2.5-tts-voicedesign',
    'mimo-v2.5-tts-voiceclone',
] as const;

export type MimoModel = typeof MIMO_MODELS[number];

export interface MimoEnvironment {
    MIMO_API_KEY?: string;
    AI_TEST_MODE?: boolean | string;
}

export interface MimoMessage {
    role: 'system' | 'user' | 'assistant';
    content: unknown;
}

export interface MimoAudioResult {
    data: string;
    mimeType: string;
    transcript: string | null;
}

export function isTestFallback(env: MimoEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export function validateMimoModel(value: unknown): MimoModel {
    if (typeof value !== 'string' || !MIMO_MODELS.includes(value as MimoModel)) {
        throw new AppError('INVALID_MODEL', 'Unsupported AI model', 400);
    }
    return value as MimoModel;
}

export async function callMimoJson(
    env: MimoEnvironment,
    model: MimoModel,
    messages: MimoMessage[],
    options: Record<string, unknown> = {},
): Promise<unknown | null> {
    validateMimoModel(model);
    // 与 agnes/zhipu 对齐：文本生成默认 90s 主动断，调用方可通过 options.timeoutMs 收紧。
    const timeoutMs = typeof options.timeoutMs === 'number' && options.timeoutMs > 0 ? options.timeoutMs : 90_000;
    const body: Record<string, unknown> = {
        model,
        messages,
        // 输出 token 上限：默认 1024，测验这类长 JSON 必须显式给足，否则输出被截断导致 JSON 解析失败
        max_completion_tokens: options.maxCompletionTokens ?? 1024,
        temperature: options.temperature ?? 0.7,
        stream: false,
    };
    if (options.responseFormat !== false) body.response_format = { type: 'json_object' };
    if (options.asrOptions) body.asr_options = options.asrOptions;
    return callMimoPayload(env, body, timeoutMs);
}

export async function callMimoAudio(
    env: MimoEnvironment,
    model: Extract<MimoModel, 'mimo-v2.5-tts' | 'mimo-v2.5-tts-voicedesign' | 'mimo-v2.5-tts-voiceclone'>,
    messages: MimoMessage[],
    audio: { format: 'wav' | 'mp3'; voice?: string; optimizeTextPreview?: boolean },
): Promise<MimoAudioResult | null> {
    validateMimoModel(model);
    const audioPayload: Record<string, unknown> = { format: audio.format };
    if (model !== 'mimo-v2.5-tts-voicedesign' && audio.voice) {
        audioPayload.voice = audio.voice;
    }
    if (audio.optimizeTextPreview !== undefined) {
        audioPayload.optimize_text_preview = audio.optimizeTextPreview;
    }
    const payload = await callMimoPayload(env, {
        model,
        messages,
        audio: audioPayload,
        stream: false,
    });
    if (!payload) return null;

    const message = getAssistantMessage(payload);
    const audioValue = isRecord(message?.audio) ? message.audio : null;
    const data = audioValue && typeof audioValue.data === 'string' ? audioValue.data : '';
    if (!data || data.length > 20 * 1024 * 1024) {
        throw new AppError('INVALID_AI_OUTPUT', 'AI audio response is invalid', 502);
    }
    return {
        data,
        mimeType: audio.format === 'mp3' ? 'audio/mpeg' : 'audio/wav',
        transcript: audioValue && typeof audioValue.transcript === 'string' ? audioValue.transcript : null,
    };
}

export function extractAssistantText(payload: unknown): string {
    const message = getAssistantMessage(payload);
    const content = message?.content;
    if (typeof content === 'string') return content.trim();
    if (Array.isArray(content)) {
        return content
            .filter(item => isRecord(item) && typeof item.text === 'string')
            .map(item => String(item.text))
            .join('')
            .trim();
    }
    throw new AppError('INVALID_AI_OUTPUT', 'AI text response is invalid', 502);
}

/**
 * 容忍模型输出的三类 JSON 缺陷，且只做「能重新解析成功」的修复，解析不了就原样放弃：
 *
 * 1. 值缺起始引号：`"learningTakeaway":异质性群体在共同目标"`——键与结尾引号都在，
 *    只有值的起始引号被写漏。实测占出题槽位判废的大头，且正文完全可用。
 * 2. 收尾引号多打反斜杠：中文正文里模型把字符串结束引号写成 `\"`（实测
 *    `…支撑了本题的对比。\"\n  }\n}`），正文完整但解析失败。
 * 3. 尾部被上游截断：字符串没闭合、括号没配平。补上收尾引号与缺失的闭合括号。
 *
 * 三步按顺序叠加，每一步之后都试一次解析，能解开就返回。
 */
export function repairAssistantJson(text: string): string | null {
    const steps: Array<(value: string) => string> = [quoteBareStringValues, unescapeStructuralQuotes, closeTruncatedJson];
    let candidate = text;
    for (const step of steps) {
        const next = step(candidate);
        if (next === candidate) continue;
        candidate = next;
        try {
            JSON.parse(candidate);
            return candidate;
        } catch {
            // 还有别的缺陷，继续叠加下一步
        }
    }
    return null;
}

/**
 * `"键":裸值"` → `"键":"裸值"`。
 *
 * 只在值既不是引号串、也不是对象/数组/数字/字面量开头时补引号——合法 JSON 的冒号
 * 后面不会出现这些字符，所以这条改写对正常输出是空操作。
 */
function quoteBareStringValues(text: string): string {
    return text.replace(/("[A-Za-z_][A-Za-z0-9_]*")\s*:\s*([^"{[\s\d\-.tfn\]}][^"]*?)"/gu, '$1:"$2"');
}

/** 结构分隔符（, } ]）前的多余反斜杠引号：正文内部的合法转义引号后面不会紧跟这些字符。 */
function unescapeStructuralQuotes(text: string): string {
    return text.replace(/\\(["\\])(\s*[,}\]])/gu, '$1$2');
}

/** 尾部截断：补上未闭合的字符串与括号；补不回来的半截键值整体丢弃。 */
function closeTruncatedJson(text: string): string {
    const start = text.indexOf('{');
    if (start < 0) return text;
    const body = text.slice(start);
    const stack: string[] = [];
    let inString = false;
    let escaped = false;
    for (const char of body) {
        if (inString) {
            if (escaped) escaped = false;
            else if (char === '\\') escaped = true;
            else if (char === '"') inString = false;
            continue;
        }
        if (char === '"') inString = true;
        else if (char === '{' || char === '[') stack.push(char);
        else if (char === '}' || char === ']') stack.pop();
    }
    if (!inString && stack.length === 0) return text;
    // 截断点落在键名或冒号后面时那个半截元素补不回来：退到最后一个完整元素再补括号
    let head = body;
    if (/(?:,\s*)?"[^"]*"\s*:\s*$/u.test(body)) {
        const cut = Math.max(body.lastIndexOf(','), body.lastIndexOf('{'));
        if (cut > 0) head = body.slice(0, cut);
    }
    let repaired = head;
    if (inString && !head.endsWith('"')) repaired += '"';
    for (let index = stack.length - 1; index >= 0; index -= 1) {
        repaired += stack[index] === '{' ? '}' : ']';
    }
    return repaired;
}

export function parseAssistantJson<T>(payload: unknown): T {
    const text = extractAssistantText(payload)
        .replace(/^```(?:json)?\s*/i, '')
        .replace(/\s*```$/i, '')
        .trim();
    try {
        return JSON.parse(text) as T;
    } catch {
        const repaired = repairAssistantJson(text);
        if (repaired !== null) {
            try {
                return JSON.parse(repaired) as T;
            } catch {
                // 修复结果仍不可解析：按原始失败处理
            }
        }
        throw new AppError('INVALID_AI_OUTPUT', 'AI JSON response is invalid', 502);
    }
}

async function callMimoPayload(
    env: MimoEnvironment,
    body: Record<string, unknown>,
    timeoutMs = 90_000,
): Promise<unknown | null> {
    const apiKey = typeof env.MIMO_API_KEY === 'string' ? env.MIMO_API_KEY.trim() : '';
    if (!apiKey) {
        if (isTestFallback(env)) return null;
        throw new AppError('AI_NOT_CONFIGURED', 'AI service is not configured', 503);
    }

    for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
            const response = await fetch(`${MIMO_BASE_URL}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'api-key': apiKey,
                },
                // 同 Agnes：上游可能长时间不响应，主动断避免挂死整场出题
                signal: AbortSignal.timeout(timeoutMs),
                body: JSON.stringify(body),
            });
            // 仅在网络失败或服务端 5xx 时重试；4xx（含限流 429）属确定性错误，重试只会放大供应商费用
            if (!response.ok) {
                if (response.status >= 500 && attempt === 0) {
                    await sleep(400);
                    continue;
                }
                throw new AppError('AI_UPSTREAM_ERROR', 'AI provider request failed', 502, response.status);
            }
            const payload: unknown = await response.json();
            return payload;
        } catch (error) {
            // 网络异常：做一次有限重试；业务错误（AppError）直接上抛
            if (error instanceof AppError) throw error;
            if (attempt === 0) {
                await sleep(400);
                continue;
            }
            throw new AppError('AI_UPSTREAM_ERROR', 'AI provider request failed', 502);
        }
    }

    throw new AppError('AI_UPSTREAM_ERROR', 'AI provider request failed', 502);
}

function sleep(milliseconds: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function getAssistantMessage(payload: unknown): Record<string, unknown> | null {
    if (!isRecord(payload) || !Array.isArray(payload.choices) || payload.choices.length === 0) return null;
    const firstChoice = payload.choices[0];
    if (!isRecord(firstChoice) || !isRecord(firstChoice.message)) return null;
    return firstChoice.message;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
