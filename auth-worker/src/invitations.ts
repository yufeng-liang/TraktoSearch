// 公开邀请码申请：邮箱验证后原子分配名额，并通过 Cloudflare Email Service 发信。

import { AppError, errorResponse, now, successResponse } from './util/errors.ts';
import { generateId, generateInviteCode, generateSecureToken, sha256 } from './util/crypto.ts';

export const PUBLIC_INVITE_LIMIT = 200;
export const VERIFICATION_TTL_SECONDS = 30 * 60;
export const PUBLIC_INVITE_RESERVATION_TTL_SECONDS = 72 * 60 * 60;
const PUBLIC_REQUEST_RATE_LIMIT = 3;

export interface PublicInviteEnv {
    DB: D1Database;
    EMAIL?: SendEmail;
    EMAIL_FROM?: string;
    EMAIL_REPLY_TO?: string;
    PUBLIC_SITE_ORIGIN: string;
    KV?: KVNamespace;
}

export interface InviteRequestInput {
    nickname: string;
    email: string;
}

interface InviteRequestRecord {
    id: string;
    nickname: string;
    email: string;
    email_normalized: string;
    verification_token_hash: string;
    status: string;
    friend_id: string | null;
    invite_id: string | null;
    verification_expires_at: number;
}

export function normalizeInviteRequest(input: unknown): InviteRequestInput {
    const body = input && typeof input === 'object' ? input as Partial<InviteRequestInput> : {};
    const nickname = typeof body.nickname === 'string' ? body.nickname.trim() : '';
    const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';

    if (!nickname || nickname.length > 32 || /[\u0000-\u001f\u007f]/.test(nickname)) {
        throw new AppError('INVALID_REQUEST', 'nickname is required and must be at most 32 characters', 400);
    }
    if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        throw new AppError('INVALID_REQUEST', 'email is invalid', 400);
    }

    return { nickname, email };
}

export async function handleInviteRequest(
    request: Request,
    env: PublicInviteEnv,
    requestId: string,
): Promise<Response> {
    let body: unknown;
    try {
        body = await request.json();
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid JSON body', 400);
    }

    const input = normalizeInviteRequest(body);
    await enforcePublicRateLimit(env, request);
    await releaseExpiredPublicInvitations(env);

    const existing = await env.DB.prepare(`
        SELECT id FROM friends WHERE LOWER(email) = ? LIMIT 1
    `).bind(input.email).first<{ id: string }>();
    if (existing) {
        return successResponse({ status: 'VERIFICATION_SENT' }, requestId);
    }

    const currentTime = now();
    const requestIdValue = generateId();
    const verificationToken = generateSecureToken(32);
    const verificationTokenHash = await sha256(verificationToken);
    const expiresAt = currentTime + VERIFICATION_TTL_SECONDS;

    await env.DB.prepare(`
        UPDATE invite_requests
        SET status = 'REPLACED', updated_at = ?
        WHERE email_normalized = ? AND status = 'VERIFICATION_SENT'
    `).bind(currentTime, input.email).run();

    await env.DB.prepare(`
        INSERT INTO invite_requests (
            id, nickname, email, email_normalized, verification_token_hash,
            status, verification_expires_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'VERIFICATION_SENT', ?, ?, ?)
    `).bind(
        requestIdValue,
        input.nickname,
        input.email,
        input.email,
        verificationTokenHash,
        expiresAt,
        currentTime,
        currentTime,
    ).run();

    try {
        await sendEmail(env, {
            to: input.email,
            subject: '请验证邮箱，领取 TraktoSearch 邀请码',
            ...buildVerificationEmail({
                nickname: input.nickname,
                verificationUrl: buildVerificationUrl(env, verificationToken),
                expiresAt,
            }),
        });
        await env.DB.prepare(`
            UPDATE invite_requests SET email_sent_at = ?, updated_at = ? WHERE id = ?
        `).bind(currentTime, currentTime, requestIdValue).run();
    } catch (error) {
        await env.DB.prepare(`
            UPDATE invite_requests SET status = 'EMAIL_FAILED', updated_at = ? WHERE id = ?
        `).bind(now(), requestIdValue).run();
        console.error('Public invite verification email failed', error);
        throw new AppError('EMAIL_SEND_FAILED', 'Unable to send verification email', 503);
    }

    return successResponse({ status: 'VERIFICATION_SENT' }, requestId);
}

export async function handleInviteVerification(
    request: Request,
    env: PublicInviteEnv,
    requestId: string,
): Promise<Response> {
    const token = new URL(request.url).searchParams.get('token') || '';
    if (!/^[a-f0-9]{64}$/.test(token)) {
        return verificationResponse(request, requestId, '链接无效', '这条邮箱验证链接无效，请重新提交申请。', 400, undefined, 'INVALID_VERIFICATION_TOKEN');
    }

    const tokenHash = await sha256(token);
    const record = await env.DB.prepare(`
        SELECT id, nickname, email, email_normalized, verification_token_hash, status,
               friend_id, invite_id, verification_expires_at
        FROM invite_requests
        WHERE verification_token_hash = ?
        LIMIT 1
    `).bind(tokenHash).first<InviteRequestRecord>();

    if (!record) {
        return verificationResponse(request, requestId, '链接无效', '这条邮箱验证链接不存在或已经被替换。', 400, undefined, 'INVALID_VERIFICATION_TOKEN');
    }
    if (record.status === 'ISSUED') {
        return verificationResponse(request, requestId, '邀请码已发送', '这个申请已经处理过了，请回到邮箱查看邀请码。', 200);
    }
    if (record.verification_expires_at < now() || record.status === 'EXPIRED' || record.status === 'REPLACED') {
        await env.DB.prepare(`
            UPDATE invite_requests SET status = 'EXPIRED', updated_at = ?
            WHERE id = ? AND status != 'ISSUED'
        `).bind(now(), record.id).run();
        return verificationResponse(request, requestId, '验证链接已过期', '请回到官网重新填写用户名和邮箱。', 400, undefined, 'INVITE_VERIFICATION_EXPIRED');
    }

    try {
        const issued = await issueInvitation(env, {
            id: record.id,
            nickname: record.nickname,
            email: record.email,
            verificationToken: token,
            verificationTokenHash: record.verification_token_hash,
        }, requestId, now());
        return verificationResponse(
            request,
            requestId,
            '邀请码已送达',
            `欢迎你，${record.nickname}！邀请码已经发送到 ${record.email}。`,
            200,
            issued.inviteCode,
        );
    } catch (error) {
        if (error instanceof AppError && error.code === 'PUBLIC_INVITE_LIMIT_REACHED') {
            return verificationResponse(request, requestId, '本期名额已满', '本期 200 个体验名额已经发完，感谢你的关注。', 409, undefined, 'PUBLIC_INVITE_LIMIT_REACHED');
        }
        if (error instanceof AppError && error.code === 'INVITE_ALREADY_ISSUED') {
            return verificationResponse(request, requestId, '邀请码已发送', '这个申请已经处理过了，请回到邮箱查看邀请码。', 200);
        }
        throw error;
    }
}

export async function releaseExpiredPublicInvitations(
    env: Pick<PublicInviteEnv, 'DB'>,
    currentTime: number = now(),
): Promise<number> {
    const { results } = await env.DB.prepare(`
        SELECT id, friend_id, invite_id
        FROM invite_requests
        WHERE status = 'ISSUED'
          AND invite_id IS NOT NULL
          AND friend_id IS NOT NULL
          AND invite_id IN (
              SELECT id
              FROM invites
              WHERE used_at IS NULL
                AND revoked_at IS NULL
                AND expires_at < ?
          )
    `).bind(currentTime).all<{ id: string; friend_id: string; invite_id: string }>();

    const expired = (results || []).filter(row => row.id && row.friend_id && row.invite_id);
    let released = 0;
    for (let offset = 0; offset < expired.length; offset += 50) {
        const chunk = expired.slice(offset, offset + 50);
        const requestSlots = chunk.map(() => '?').join(', ');
        const inviteSlots = chunk.map(() => '?').join(', ');
        const friendSlots = chunk.map(() => '?').join(', ');
        const requestIds = chunk.map(row => row.id);
        const inviteIds = chunk.map(row => row.invite_id);
        const friendIds = chunk.map(row => row.friend_id);

        const batchResults = await env.DB.batch([
            env.DB.prepare(`
                UPDATE invite_requests
                SET status = 'EXPIRED', friend_id = NULL, invite_id = NULL, updated_at = ?
                WHERE id IN (${requestSlots}) AND status = 'ISSUED'
            `).bind(currentTime, ...requestIds),
            env.DB.prepare(`
                DELETE FROM invites
                WHERE id IN (${inviteSlots})
                  AND used_at IS NULL
                  AND revoked_at IS NULL
            `).bind(...inviteIds),
            env.DB.prepare(`
                DELETE FROM friends
                WHERE id IN (${friendSlots})
                  AND NOT EXISTS (
                      SELECT 1 FROM devices d
                      WHERE d.friend_id = friends.id AND d.deleted_at IS NULL
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM invites i
                      WHERE i.friend_id = friends.id
                  )
            `).bind(...friendIds),
        ]);
        released += Number(batchResults?.[0]?.meta?.changes || 0);
    }
    return released;
}

export async function issueInvitation(
    env: PublicInviteEnv,
    request: {
        id: string;
        nickname: string;
        email: string;
        verificationToken?: string;
        verificationTokenHash?: string;
    },
    requestId: string,
    currentTime: number = now(),
): Promise<{ friendId: string; inviteId: string; inviteCode: string; expiresAt: number }> {
    if (!env.EMAIL || !env.EMAIL_FROM) {
        throw new AppError('EMAIL_NOT_CONFIGURED', 'Invitation email is not configured', 503);
    }
    await releaseExpiredPublicInvitations(env, currentTime);

    const friendId = generateId();
    const inviteId = generateId();
    const inviteCode = generateInviteCode();
    const codeHash = await sha256(inviteCode);
    const codeMask = `${inviteCode.slice(0, 4)}****${inviteCode.slice(-4)}`;
    const expiresAt = currentTime + PUBLIC_INVITE_RESERVATION_TTL_SECONDS;

    const statements = [
        env.DB.prepare(`
            UPDATE invite_requests
            SET status = 'ISSUED', friend_id = ?, invite_id = ?, verified_at = ?, updated_at = ?
            WHERE id = ?
              AND status IN ('VERIFICATION_SENT', 'EMAIL_FAILED')
              AND verification_expires_at >= ?
              AND (SELECT COUNT(*) FROM invite_requests WHERE status = 'ISSUED') < ?
        `).bind(friendId, inviteId, currentTime, currentTime, request.id, currentTime, PUBLIC_INVITE_LIMIT),
        env.DB.prepare(`
            INSERT INTO friends (
                id, nickname, email, signup_request_id, note, status,
                max_devices, expires_at, created_at, updated_at
            )
            SELECT ?, ?, ?, ?, NULL, 'ACTIVE', 2, NULL, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM invite_requests
                WHERE id = ? AND status = 'ISSUED' AND friend_id = ? AND invite_id = ?
            )
        `).bind(friendId, request.nickname, request.email, request.id, currentTime, currentTime, request.id, friendId, inviteId),
        env.DB.prepare(`
            INSERT INTO invites (
                id, friend_id, kind, code_hash, code_mask, device_id,
                expires_at, created_at
            )
            SELECT ?, ?, 'ACTIVATION', ?, ?, NULL, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM invite_requests
                WHERE id = ? AND status = 'ISSUED' AND friend_id = ? AND invite_id = ?
            )
        `).bind(inviteId, friendId, codeHash, codeMask, expiresAt, currentTime, request.id, friendId, inviteId),
        env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
            SELECT 'PUBLIC_INVITE_ISSUE', ?, ?, 'SUCCESS', ?, ?
            WHERE EXISTS (
                SELECT 1 FROM invite_requests
                WHERE id = ? AND status = 'ISSUED' AND friend_id = ? AND invite_id = ?
            )
        `).bind(friendId, requestId, `invite_id:${inviteId};invite_mask:${codeMask}`, currentTime, request.id, friendId, inviteId),
    ];

    const results = await env.DB.batch(statements);
    if (Number(results?.[0]?.meta?.changes || 0) !== 1) {
        const latest = await env.DB.prepare(`
            SELECT status FROM invite_requests WHERE id = ?
        `).bind(request.id).first<{ status: string }>();
        if (latest?.status === 'ISSUED') {
            throw new AppError('INVITE_ALREADY_ISSUED', 'Invitation already issued', 409);
        }
        throw new AppError('PUBLIC_INVITE_LIMIT_REACHED', 'Invitation limit reached', 409);
    }
    if (results.length < 4 || results.slice(1, 4).some(result => Number(result?.meta?.changes || 0) !== 1)) {
        throw new AppError('INVITE_ISSUE_INCOMPLETE', 'Invitation could not be issued', 500);
    }

    try {
        await sendEmail(env, {
            to: request.email,
            subject: '欢迎加入 TraktoSearch，你的邀请码已送达',
            ...buildInvitationEmail({
                nickname: request.nickname,
                inviteCode,
                verificationUrl: buildVerificationUrl(env, request.verificationToken || ''),
                expiresAt,
            }),
        });
        await env.DB.prepare(`
            UPDATE invite_requests SET email_sent_at = ?, updated_at = ? WHERE id = ?
        `).bind(currentTime, currentTime, request.id).run();
    } catch (error) {
        console.error('Public invitation email failed', error);
        await env.DB.batch([
            env.DB.prepare(`DELETE FROM invites WHERE id = ?`).bind(inviteId),
            env.DB.prepare(`DELETE FROM friends WHERE id = ?`).bind(friendId),
            env.DB.prepare(`
                UPDATE invite_requests
                SET status = 'EMAIL_FAILED', friend_id = NULL, invite_id = NULL, updated_at = ?
                WHERE id = ?
            `).bind(now(), request.id),
        ]);
        throw new AppError('EMAIL_SEND_FAILED', 'Unable to send invitation email', 503);
    }

    return { friendId, inviteId, inviteCode, expiresAt };
}

export function buildVerificationEmail(input: {
    nickname: string;
    verificationUrl: string;
    expiresAt: number;
}): { html: string; text: string } {
    const name = escapeHtml(input.nickname);
    const url = escapeHtml(input.verificationUrl);
    const expiry = formatDate(input.expiresAt);
    return {
        html: emailLayout(`
            <p style="margin:0 0 18px;color:#6D685F;">你好，${name}：</p>
            <h1 style="margin:0 0 16px;font:600 30px/1.15 Georgia,serif;color:#1D1C19;">欢迎加入 TraktoSearch 的初期体验。</h1>
            <p style="margin:0 0 24px;color:#6D685F;line-height:1.8;">感谢你愿意下载和体验 TraktoSearch。请点击下面的按钮验证邮箱，验证完成后我们会把专属邀请码发送到你的收件箱。</p>
            <p style="margin:0 0 24px;"><a href="${url}" style="display:inline-block;padding:13px 20px;background:#D95532;color:#fff;text-decoration:none;border-radius:2px;font-weight:600;">验证邮箱并领取邀请码</a></p>
            <p style="margin:0;color:#9B9588;font-size:13px;line-height:1.7;">链接将在 ${expiry} 后失效。如果这不是你的操作，可以忽略这封邮件。</p>
        `),
        text: `你好，${input.nickname}：\n\n欢迎加入 TraktoSearch 的初期体验，感谢你愿意下载和体验。请打开下面的链接验证邮箱，验证完成后我们会把邀请码发送到你的收件箱：\n\n${input.verificationUrl}\n\n链接将在 ${expiry} 后失效。`,
    };
}

export function buildInvitationEmail(input: {
    nickname: string;
    inviteCode: string;
    verificationUrl: string;
    expiresAt: number;
}): { html: string; text: string } {
    const name = escapeHtml(input.nickname);
    const code = escapeHtml(input.inviteCode);
    const url = escapeHtml(input.verificationUrl);
    const expiry = formatDate(input.expiresAt);
    return {
        html: emailLayout(`
            <p style="margin:0 0 18px;color:#6D685F;">你好，${name}：</p>
            <h1 style="margin:0 0 16px;font:600 30px/1.15 Georgia,serif;color:#1D1C19;">欢迎来到 TraktoSearch。</h1>
            <p style="margin:0 0 22px;color:#6D685F;line-height:1.8;">感谢你下载并体验 TraktoSearch。下面是你的初期体验邀请码，请在 App 激活页面输入。</p>
            <div style="margin:0 0 24px;padding:18px 20px;background:#E9E2D4;border-left:4px solid #D95532;text-align:center;">
                <div style="margin-bottom:8px;color:#6D685F;font-size:12px;letter-spacing:.16em;text-transform:uppercase;">INVITATION CODE</div>
                <div style="font:700 24px/1.2 'Courier New',monospace;letter-spacing:.12em;color:#1D1C19;">${code}</div>
            </div>
            <p style="margin:0 0 12px;color:#6D685F;line-height:1.7;">邀请码有效期至 ${expiry}，只能使用一次，请不要转发给他人。</p>
            <p style="margin:0 0 24px;color:#6D685F;line-height:1.7;">如果你愿意，欢迎把使用体验、反馈和建议提交到 <a href="https://github.com/yufeng-liang/TrackToSearch" style="color:#D95532;">GitHub 仓库</a>，这会直接帮助我们改进后续版本。</p>
            <p style="margin:0;color:#9B9588;font-size:13px;line-height:1.7;">也可以打开 <a href="${url}" style="color:#D95532;">TraktoSearch 官网</a>，查看最新说明和下载入口。</p>
        `),
        text: `你好，${input.nickname}：\n\n欢迎来到 TraktoSearch，感谢你下载并体验。你的初期体验邀请码是：\n\n${input.inviteCode}\n\n邀请码有效期至 ${expiry}，只能使用一次，请不要转发给他人。欢迎把使用体验、反馈和建议提交到 GitHub：https://github.com/yufeng-liang/TrackToSearch\n\n官网：${input.verificationUrl}`,
    };
}

async function enforcePublicRateLimit(env: PublicInviteEnv, request: Request): Promise<void> {
    if (!env.KV) return;
    const ip = request.headers.get('CF-Connecting-IP') || request.headers.get('X-Real-IP') || 'unknown';
    const key = `public-invite:ip:${await sha256(ip)}`;
    const current = Number.parseInt(await env.KV.get(key) || '0', 10);
    if (current >= PUBLIC_REQUEST_RATE_LIMIT) {
        throw new AppError('RATE_LIMITED', 'Too many requests', 429);
    }
    await env.KV.put(key, String(current + 1), { expirationTtl: 3600 });
}

async function sendEmail(
    env: PublicInviteEnv,
    input: { to: string; subject: string; html: string; text: string },
): Promise<void> {
    if (!env.EMAIL || !env.EMAIL_FROM) {
        throw new AppError('EMAIL_NOT_CONFIGURED', 'Invitation email is not configured', 503);
    }
    await env.EMAIL.send({
        from: env.EMAIL_FROM,
        to: input.to,
        subject: input.subject,
        replyTo: env.EMAIL_REPLY_TO,
        html: input.html,
        text: input.text,
    });
}

function buildVerificationUrl(env: PublicInviteEnv, token: string): string {
    return `${env.PUBLIC_SITE_ORIGIN.replace(/\/$/, '')}/invite/verify/?token=${encodeURIComponent(token)}`;
}

function verificationHtmlResponse(title: string, message: string, status: number, inviteCode?: string): Response {
    const codeBlock = inviteCode
        ? `<div style="margin:24px 0;padding:18px;background:#E9E2D4;border-left:4px solid #D95532;text-align:center;font:700 24px/1.2 'Courier New',monospace;letter-spacing:.12em;">${escapeHtml(inviteCode)}</div>`
        : '';
    return new Response(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)} · TraktoSearch</title></head><body style="margin:0;background:#F6F2E9;color:#1D1C19;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;"><main style="max-width:620px;margin:10vh auto;padding:24px;"><div style="border-top:4px solid #D95532;padding:28px 0;"><div style="color:#D95532;font:600 12px/1.2 'Courier New',monospace;letter-spacing:.2em;text-transform:uppercase;">TraktoSearch</div><h1 style="font:600 38px/1.15 Georgia,serif;margin:22px 0 14px;">${escapeHtml(title)}</h1><p style="color:#6D685F;line-height:1.8;">${escapeHtml(message)}</p>${codeBlock}<p style="color:#9B9588;font-size:13px;line-height:1.7;">感谢你的关注，也欢迎提交反馈和建议。</p></div></main></body></html>`, {
        status,
        headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
    });
}

function verificationResponse(
    request: Request,
    requestId: string,
    title: string,
    message: string,
    status: number,
    inviteCode?: string,
    errorCode = 'INVITE_VERIFICATION_FAILED',
): Response {
    if ((request.headers.get('Accept') || '').includes('application/json')) {
        if (status >= 400) {
            return errorResponse(new AppError(errorCode, message, status), requestId);
        }
        return successResponse({ title, message, inviteCode: inviteCode || null }, requestId);
    }
    return verificationHtmlResponse(title, message, status, inviteCode);
}

function emailLayout(content: string): string {
    return `<!doctype html><html lang="zh-CN"><body style="margin:0;background:#F6F2E9;color:#1D1C19;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;"><div style="max-width:620px;margin:0 auto;padding:36px 20px;"><div style="border-top:4px solid #D95532;background:#F6F2E9;padding:26px 0 0;"><div style="color:#D95532;font:600 12px/1.2 'Courier New',monospace;letter-spacing:.2em;text-transform:uppercase;">TraktoSearch</div><div style="height:1px;background:#D8D0C1;margin:20px 0 28px;"></div>${content}<div style="height:1px;background:#D8D0C1;margin:30px 0 16px;"></div><p style="margin:16px 0 0;color:#9B9588;font-size:12px;line-height:1.6;">TraktoSearch · 从观影清单到网盘资源</p></div></div></body></html>`;
}

function formatDate(timestamp: number): string {
    return new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'Asia/Shanghai',
    }).format(new Date(timestamp * 1000));
}

function escapeHtml(value: string): string {
    return value.replace(/[&<>'"]/g, character => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;',
    })[character] || character);
}
