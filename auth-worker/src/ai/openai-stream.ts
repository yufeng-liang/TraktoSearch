// OpenAI 兼容流式 SSE 的公共读取逻辑。
//
// 智谱（open.bigmodel.cn）与百炼（Model Studio 兼容模式）用的是同一套 SSE 形状：
// 每行 `data: {json}`，增量在 choices[0].delta.content，末行 `data: [DONE]`；上游在 200
// 响应体里包业务错误码（如智谱 1305 限频）时原样返回该对象，由调用方判定后决定轮替。
// 两家各写一份容易出现「一处修了、另一处还坏着」的分叉，因此抽到这里共用。
import { AppError } from '../util/errors.ts';

/**
 * 读流式 SSE，拼回与非流式同形的 payload（choices[0].message.content），
 * 让 parseAssistantJson 等下游无需感知流式。正文每累积一次就回调 onProgress。
 */
export async function readOpenAiSseStream(
    response: Response,
    onProgress?: (chars: number) => void,
): Promise<unknown> {
    const reader = response.body?.getReader();
    if (!reader) throw new AppError('AI_UPSTREAM_ERROR', 'upstream stream body missing', 502);
    const decoder = new TextDecoder();
    let buffer = '';
    let content = '';
    let finishReason: string | null = null;
    let upstreamError: unknown = null;
    try {
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            // SSE 以行为单位：最后一段可能被截断，留在 buffer 里等下一块
            const lines = buffer.split('\n');
            buffer = lines.pop() ?? '';
            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed.startsWith('data:')) continue;
                const data = trimmed.slice(5).trim();
                if (!data || data === '[DONE]') continue;
                let chunk: unknown;
                try {
                    chunk = JSON.parse(data);
                } catch {
                    continue; // 心跳/空行等非 JSON 行
                }
                const record = chunk as Record<string, unknown> | null;
                if (record && typeof record === 'object' && record.error) {
                    upstreamError = record;
                    continue;
                }
                const choices = record?.choices;
                if (!Array.isArray(choices) || choices.length === 0) continue;
                const first = choices[0] as Record<string, unknown> | null;
                // 只取 content：思考型模型的 delta.reasoning_content 不能混进正文，
                // 否则 JSON.parse 会被思考链前缀污染。
                const delta = (first?.delta as Record<string, unknown> | undefined)?.content;
                if (typeof delta === 'string' && delta.length > 0) {
                    content += delta;
                    onProgress?.(content.length);
                }
                if (typeof first?.finish_reason === 'string') finishReason = first.finish_reason;
            }
        }
    } finally {
        try {
            await reader.cancel();
        } catch {
            // 流已结束或已被上游中断，忽略
        }
    }
    if (upstreamError) return upstreamError;
    return { choices: [{ message: { content }, finish_reason: finishReason }] };
}
