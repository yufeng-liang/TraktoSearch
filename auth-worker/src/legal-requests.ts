// 公开法律与隐私请求：只收处理请求所需的最少信息。

import { AppError, now, successResponse } from './util/errors.ts';
import { generateId, sha256 } from './util/crypto.ts';
import { sendEmail, type PublicInviteEnv } from './invitations.ts';
import { clientIp } from './util/client-ip.ts';

export const LEGAL_REQUEST_TYPES = [
    'PRIVACY_ACCESS',
    'PRIVACY_CORRECTION',
    'PRIVACY_DELETION',
    'COPYRIGHT_NOTICE',
    'OTHER',
] as const;

export type LegalRequestType = typeof LEGAL_REQUEST_TYPES[number];

const PUBLIC_LEGAL_IP_LIMIT = 3;
const PUBLIC_LEGAL_EMAIL_LIMIT = 5;

export interface LegalRequestEnv extends Pick<
    PublicInviteEnv,
    'DB' | 'KV' | 'EMAIL' | 'BREVO_API_KEY' | 'EMAIL_FROM' | 'EMAIL_REPLY_TO' | 'PUBLIC_SITE_ORIGIN'
> {
    ADMIN_EMAIL?: string;
}

export interface LegalRequestInput {
    type: LegalRequestType;
    email: string;
    accountReference: string;
    description: string;
}

export function normalizeLegalRequest(input: unknown): LegalRequestInput {
    const body = input && typeof input === 'object' ? input as Record<string, unknown> : {};
    if (body.acknowledged !== true) {
        throw new AppError('LEGAL_REQUEST_ACK_REQUIRED', 'Acknowledgement is required', 400);
    }

    const type = typeof body.type === 'string' ? body.type.trim().toUpperCase() : '';
    if (!LEGAL_REQUEST_TYPES.includes(type as LegalRequestType)) {
        throw new AppError('INVALID_REQUEST', 'Invalid legal request type', 400);
    }

    const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
    if (!email || email.length > 254 || /[\u0000-\u001f\u007f]/.test(email) || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        throw new AppError('INVALID_REQUEST', 'email is invalid', 400);
    }

    const accountReference = typeof body.accountReference === 'string'
        ? body.accountReference.trim()
        : '';
    if (accountReference.length > 64 || /[\u0000-\u001f\u007f]/.test(accountReference)) {
        throw new AppError('INVALID_REQUEST', 'account reference is invalid', 400);
    }

    const description = typeof body.description === 'string' ? body.description.trim() : '';
    if (description.length < 10 || description.length > 4000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(description)) {
        throw new AppError('INVALID_REQUEST', 'description must be 10-4000 characters', 400);
    }

    return {
        type: type as LegalRequestType,
        email,
        accountReference,
        description,
    };
}

export async function handleLegalRequest(
    request: Request,
    env: LegalRequestEnv,
    requestId: string,
    ctx?: Pick<ExecutionContext, 'waitUntil'>,
): Promise<Response> {
    let body: unknown;
    try {
        body = await request.json();
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid JSON body', 400);
    }

    const input = normalizeLegalRequest(body);
    await enforceLegalRateLimit(env, request, input.email);

    const id = generateId();
    const currentTime = now();
    await env.DB.prepare(`
        INSERT INTO legal_requests (
            id, request_type, email, email_normalized, account_reference,
            description, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
    `).bind(
        id,
        input.type,
        input.email,
        input.email,
        input.accountReference || null,
        input.description,
        currentTime,
        currentTime,
    ).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, request_id, result, detail, created_at)
        VALUES ('LEGAL_REQUEST_SUBMIT', ?, 'SUCCESS', ?, ?)
    `).bind(requestId, `legal_request_id:${id};type:${input.type}`, currentTime).run();

    if (ctx && canSendEmail(env)) {
        ctx.waitUntil(notifyLegalRequest(env, input, id));
    }

    return successResponse({ id, status: 'RECEIVED', receivedAt: currentTime }, requestId);
}

async function enforceLegalRateLimit(
    env: Pick<LegalRequestEnv, 'KV'>,
    request: Request,
    email: string,
): Promise<void> {
    if (!env.KV) return;

    // 同 invitations.ts：经 gateway 转发优先 X-Real-IP，公网直连只信 CF-Connecting-IP。
    const ip = clientIp(request) || 'unknown';
    await consumeRateLimit(env.KV, `public-legal:ip:${await sha256(ip)}`, PUBLIC_LEGAL_IP_LIMIT, 3600);
    await consumeRateLimit(env.KV, `public-legal:email:${await sha256(email)}`, PUBLIC_LEGAL_EMAIL_LIMIT, 86400);
}

async function consumeRateLimit(
    kv: KVNamespace,
    key: string,
    limit: number,
    ttl: number,
): Promise<void> {
    const current = Number.parseInt(await kv.get(key) || '0', 10);
    if (current >= limit) {
        throw new AppError('RATE_LIMITED', 'Too many requests', 429);
    }
    await kv.put(key, String(current + 1), { expirationTtl: ttl });
}

function canSendEmail(env: LegalRequestEnv): boolean {
    return Boolean(env.EMAIL_FROM && (env.BREVO_API_KEY || env.EMAIL));
}

async function notifyLegalRequest(
    env: LegalRequestEnv,
    input: LegalRequestInput,
    id: string,
): Promise<void> {
    const notifications: Promise<void>[] = [];
    if (env.ADMIN_EMAIL) {
        notifications.push(sendEmail(env, {
            to: env.ADMIN_EMAIL,
            subject: `[TraktoSearch] 法律/隐私请求 ${id}`,
            html: `<h1>新的法律/隐私请求</h1><p><strong>编号：</strong>${escapeHtml(id)}</p><p><strong>类型：</strong>${escapeHtml(input.type)}</p><p><strong>邮箱：</strong>${escapeHtml(input.email)}</p><p><strong>账号参考：</strong>${escapeHtml(input.accountReference || '未填写')}</p><p><strong>说明：</strong></p><p>${escapeHtml(input.description).replaceAll('\n', '<br>')}</p>`,
            text: `新的法律/隐私请求\n编号：${id}\n类型：${input.type}\n邮箱：${input.email}\n账号参考：${input.accountReference || '未填写'}\n说明：\n${input.description}`,
        }));
    }
    notifications.push(sendEmail(env, {
        to: input.email,
        subject: '已收到你的 TraktoSearch 法律/隐私请求',
        html: `<p>我们已收到你的请求。</p><p>请求编号：<strong>${escapeHtml(id)}</strong></p><p>我们会在需要时通过此邮箱联系你，并在完成必要的身份核验后处理请求。请不要通过邮件发送密码、Cookie、完整邀请码或身份证件。</p>`,
        text: `我们已收到你的请求。\n请求编号：${id}\n我们会在需要时通过此邮箱联系你，并在完成必要的身份核验后处理请求。请不要通过邮件发送密码、Cookie、完整邀请码或身份证件。`,
    }));

    const results = await Promise.allSettled(notifications);
    results.forEach((result, index) => {
        if (result.status === 'rejected') {
            console.error(JSON.stringify({
                event: 'legal_request_notification_failed',
                requestId: id,
                recipient: index === 0 && env.ADMIN_EMAIL ? 'admin' : 'requester',
                error: result.reason instanceof Error ? result.reason.message : String(result.reason),
            }));
        }
    });
}

function escapeHtml(value: string): string {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}
