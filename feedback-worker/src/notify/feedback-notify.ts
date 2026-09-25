// 提交新反馈后触发开发者邮件提醒。
//
// 发信能力集中在 auth-worker（Brevo key 与邮件模板只有一份），这里只负责
// 用两 worker 共享的 JWT_SIGNING_KEY 签一枚短时 token，经 Service Binding
// 调 auth-worker 的内部端点。邮件是「尽快知道」的旁路提醒：失败只记日志，
// 不得影响反馈提交本身。

import { signAccessToken } from '../util/jwt.ts';

export const FEEDBACK_NOTIFY_SCOPE = 'internal:feedback-notify';

// 60 秒足够覆盖一次 binding 往返；签名密钥泄露时的可用窗口也小
const TOKEN_TTL_SECONDS = 60;
// 通知是旁路：超时后直接放弃，不能让 waitUntil 长期挂住
const REQUEST_TIMEOUT_MS = 10_000;

export interface FeedbackNotifyEnv {
    AUTH_WORKER?: Fetcher;
    JWT_SIGNING_KEY: string;
}

export interface FeedbackNotifyPayload {
    id: string;
    displayId: string;
    type: string;
    content: string;
    friendNickname: string;
    contact: string | null;
    traktUsername: string | null;
    doubanUsername: string | null;
    appVersion: string;
    osVersion: string;
    deviceModel: string;
    screenshotCount: number;
    createdAt: number;
}

/** 组装内部通知 payload：字段口径与 submit 落库一致，超长已在 submit 侧拦下。 */
export function buildNotifyPayload(input: {
    id: string;
    displayId: string;
    type: string;
    content: string;
    friendNickname: string;
    contact: string | null;
    traktUsername: string | null;
    doubanUsername: string | null;
    appVersion: string;
    osVersion: string;
    deviceModel: string;
    screenshotCount: number;
    createdAt: number;
}): FeedbackNotifyPayload {
    return { ...input };
}

/**
 * 触发邮件提醒。任何失败（未配置 binding、签发失败、上游非 2xx、超时）
 * 都只写日志返回，调用方不需要处理错误。
 */
export async function notifyNewFeedback(
    env: FeedbackNotifyEnv,
    payload: FeedbackNotifyPayload,
): Promise<void> {
    if (!env.AUTH_WORKER) {
        console.warn(`[feedback-notify] AUTH_WORKER binding missing, skip notify ${payload.displayId}`);
        return;
    }

    try {
        const token = await signAccessToken(
            env.JWT_SIGNING_KEY,
            'feedback-worker',
            'service-binding',
            [FEEDBACK_NOTIFY_SCOPE],
            TOKEN_TTL_SECONDS,
        );
        const response = await env.AUTH_WORKER.fetch('https://auth-worker.internal/internal/feedback-notify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
            },
            body: JSON.stringify(payload),
            signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        });
        if (!response.ok) {
            const detail = (await response.text()).slice(0, 300);
            console.error(`[feedback-notify] failed displayId=${payload.displayId} status=${response.status} ${detail}`);
        }
    } catch (err) {
        console.error(
            `[feedback-notify] failed displayId=${payload.displayId}`,
            err instanceof Error ? err.message : String(err),
        );
    }
}
