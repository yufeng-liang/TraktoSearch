// 公开邀请码申请：直接原子分配名额，并通过事务邮件发信。

import { AppError, errorResponse, now, successResponse } from './util/errors.ts';
import { generateId, generateSecureToken, maskInviteCode, sha256, timingSafeEqual } from './util/crypto.ts';
import { reserveInviteCode } from './util/invite-code.ts';
import { clientIp } from './util/client-ip.ts';
import { consumeRateLimit } from './util/rate-limit.ts';

export const PUBLIC_INVITE_LIMIT = 200;
export const VERIFICATION_TTL_SECONDS = 30 * 60;
export const PUBLIC_INVITE_RESERVATION_TTL_SECONDS = 72 * 60 * 60;
export const PUBLIC_INVITE_RESEND_COOLDOWN_SECONDS = 60;
const PUBLIC_REQUEST_RATE_LIMIT = 3;

export interface PublicInviteEnv {
    DB: D1Database;
    EMAIL?: SendEmail;
    BREVO_API_KEY?: string;
    EMAIL_FROM?: string;
    EMAIL_REPLY_TO?: string;
    PUBLIC_SITE_ORIGIN: string;
    KV?: KVNamespace;
    INVITE_TEST_BYPASS_KEY?: string;
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

function normalizeInviteEmail(input: unknown): string {
    const body = input && typeof input === 'object' ? input as Partial<InviteRequestInput> : {};
    const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
    if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        throw new AppError('INVALID_REQUEST', 'email is invalid', 400);
    }
    return email;
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
        return successResponse({ status: 'INVITE_SENT' }, requestId);
    }

    const currentTime = now();
    const requestIdValue = generateId();
    const verificationTokenHash = await sha256(generateSecureToken(32));
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
        await issueInvitation(env, {
            id: requestIdValue,
            nickname: input.nickname,
            email: input.email,
            verificationTokenHash,
        }, requestId, currentTime);
    } catch (error) {
        if (error instanceof AppError && error.code === 'PUBLIC_INVITE_LIMIT_REACHED') {
            await env.DB.prepare(`
                UPDATE invite_requests SET status = 'EXPIRED', updated_at = ?
                WHERE id = ? AND status = 'VERIFICATION_SENT'
            `).bind(now(), requestIdValue).run();
        }
        throw error;
    }

    return successResponse({ status: 'INVITE_SENT' }, requestId);
}

export async function handleInviteResend(
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

    const email = normalizeInviteEmail(body);
    const isTestRequest = isInviteTestRequest(env, request);
    await enforcePublicRateLimit(env, request);
    await releaseExpiredPublicInvitations(env);
    await resendInvitation(env, { email }, requestId, now(), { bypassCooldown: isTestRequest });
    return successResponse({ status: 'INVITE_RESENT', cooldownSeconds: PUBLIC_INVITE_RESEND_COOLDOWN_SECONDS }, requestId);
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
    if ((!env.BREVO_API_KEY && !env.EMAIL) || !env.EMAIL_FROM) {
        throw new AppError('EMAIL_NOT_CONFIGURED', 'Invitation email is not configured', 503);
    }
    await releaseExpiredPublicInvitations(env, currentTime);

    const friendId = generateId();
    const inviteId = generateId();
    const { code: inviteCode, codeHash } = await reserveInviteCode(env.DB);
    const codeMask = maskInviteCode(inviteCode);
    const expiresAt = currentTime + PUBLIC_INVITE_RESERVATION_TTL_SECONDS;

    const statements = [
        env.DB.prepare(`
            INSERT INTO friends (
                id, nickname, email, signup_request_id, note, status,
                max_devices, expires_at, created_at, updated_at
            )
            SELECT ?, ?, ?, ?, NULL, 'ACTIVE', 2, NULL, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM invite_requests
                WHERE id = ?
                  AND status IN ('VERIFICATION_SENT', 'EMAIL_FAILED')
                  AND verification_expires_at >= ?
            )
              AND (SELECT COUNT(*) FROM invite_requests WHERE status = 'ISSUED') < ?
              AND NOT EXISTS (
                  SELECT 1 FROM friends WHERE signup_request_id = ?
              )
        `).bind(
            friendId,
            request.nickname,
            request.email,
            request.id,
            currentTime,
            currentTime,
            request.id,
            currentTime,
            PUBLIC_INVITE_LIMIT,
            request.id,
        ),
        env.DB.prepare(`
            INSERT INTO invites (
                id, friend_id, kind, code_hash, code_mask, device_id,
                expires_at, created_at
            )
            SELECT ?, ?, 'ACTIVATION', ?, ?, NULL, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM friends
                WHERE id = ? AND signup_request_id = ? AND status = 'ACTIVE'
            )
        `).bind(inviteId, friendId, codeHash, codeMask, expiresAt, currentTime, friendId, request.id),
        env.DB.prepare(`
            UPDATE invite_requests
            SET status = 'ISSUED', friend_id = ?, invite_id = ?, verified_at = ?, updated_at = ?
            WHERE id = ?
              AND status IN ('VERIFICATION_SENT', 'EMAIL_FAILED')
              AND verification_expires_at >= ?
              AND friend_id IS NULL
              AND invite_id IS NULL
              AND EXISTS (
                  SELECT 1 FROM friends
                  WHERE id = ? AND signup_request_id = ? AND status = 'ACTIVE'
              )
              AND EXISTS (
                  SELECT 1 FROM invites
                  WHERE id = ? AND friend_id = ? AND used_at IS NULL AND revoked_at IS NULL
              )
        `).bind(
            friendId,
            inviteId,
            currentTime,
            currentTime,
            request.id,
            currentTime,
            friendId,
            request.id,
            inviteId,
            friendId,
        ),
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
        const issuedCount = await env.DB.prepare(`
            SELECT COUNT(*) AS count FROM invite_requests WHERE status = 'ISSUED'
        `).first<{ count: number }>();
        if (Number(issuedCount?.count || 0) >= PUBLIC_INVITE_LIMIT) {
            throw new AppError('PUBLIC_INVITE_LIMIT_REACHED', 'Invitation limit reached', 409);
        }
        throw new AppError('INVITE_ISSUE_INCOMPLETE', 'Invitation could not be reserved', 500);
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
                siteUrl: env.PUBLIC_SITE_ORIGIN,
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
            // 邮件发送失败回滚时，前一步已插入的 SUCCESS 审计行成为悬挂记录
            // （显示签发成功但邀请已删），在此标记为回滚，避免误导审计排查
            env.DB.prepare(`
                UPDATE audit_logs
                SET result = 'ROLLED_BACK', detail = detail || ';email_send_failed'
                WHERE event_type = 'PUBLIC_INVITE_ISSUE' AND friend_id = ? AND request_id = ? AND result = 'SUCCESS'
            `).bind(friendId, request.id),
        ]);
        throw new AppError('EMAIL_SEND_FAILED', 'Unable to send invitation email', 503);
    }

    return { friendId, inviteId, inviteCode, expiresAt };
}

export async function resendInvitation(
    env: PublicInviteEnv,
    input: { email: string },
    requestId: string,
    currentTime: number = now(),
    options: { bypassCooldown?: boolean } = {},
): Promise<{ inviteId: string; inviteCode: string; expiresAt: number }> {
    if ((!env.BREVO_API_KEY && !env.EMAIL) || !env.EMAIL_FROM) {
        throw new AppError('EMAIL_NOT_CONFIGURED', 'Invitation email is not configured', 503);
    }

    const current = await env.DB.prepare(`
        SELECT f.id AS friend_id,
               f.nickname,
               f.email,
               r.id AS request_id,
               r.invite_id,
               r.email_sent_at,
               i.expires_at AS invite_expires_at
        FROM friends f
        JOIN invite_requests r
          ON r.id = f.signup_request_id
         AND r.status = 'ISSUED'
        JOIN invites i
          ON i.id = r.invite_id
         AND i.friend_id = f.id
        WHERE LOWER(f.email) = ?
          AND f.status = 'ACTIVE'
          AND i.kind = 'ACTIVATION'
          AND i.used_at IS NULL
          AND i.revoked_at IS NULL
        ORDER BY r.updated_at DESC
        LIMIT 1
    `).bind(input.email).first<{
        friend_id: string;
        nickname: string;
        email: string;
        request_id: string;
        invite_id: string;
        email_sent_at: number | null;
        invite_expires_at: number;
    }>();

    if (!current) {
        throw new AppError('INVITE_NOT_FOUND', 'No public invitation is available for this email', 404);
    }

    const lastSentAt = Number(current.email_sent_at || 0);
    const elapsed = Math.max(0, currentTime - lastSentAt);
    const remaining = PUBLIC_INVITE_RESEND_COOLDOWN_SECONDS - elapsed;
    if (remaining > 0 && !options.bypassCooldown) {
        throw new AppError('RESEND_COOLDOWN', `Please wait ${remaining} seconds before resending`, 429);
    }

    const inviteId = generateId();
    const { code: inviteCode, codeHash } = await reserveInviteCode(env.DB);
    const codeMask = maskInviteCode(inviteCode);
    const expiresAt = currentTime + PUBLIC_INVITE_RESERVATION_TTL_SECONDS;

    const results = await env.DB.batch([
        env.DB.prepare(`
            UPDATE invites
            SET revoked_at = ?
            WHERE id = ?
              AND friend_id = ?
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at >= ?
        `).bind(currentTime, current.invite_id, current.friend_id, currentTime),
        env.DB.prepare(`
            INSERT INTO invites (
                id, friend_id, kind, code_hash, code_mask, device_id,
                expires_at, created_at
            )
            SELECT ?, ?, 'ACTIVATION', ?, ?, NULL, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM invite_requests
                WHERE id = ?
                  AND friend_id = ?
                  AND invite_id = ?
                  AND status = 'ISSUED'
            )
        `).bind(inviteId, current.friend_id, codeHash, codeMask, expiresAt, currentTime, current.request_id, current.friend_id, current.invite_id),
        env.DB.prepare(`
            UPDATE invite_requests
            SET invite_id = ?, email_sent_at = ?, updated_at = ?
            WHERE id = ?
              AND status = 'ISSUED'
              AND invite_id = ?
        `).bind(inviteId, currentTime, currentTime, current.request_id, current.invite_id),
        env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
            VALUES ('PUBLIC_INVITE_RESEND', ?, ?, 'SUCCESS', ?, ?)
        `).bind(current.friend_id, requestId, `invite_id:${inviteId};previous_invite_id:${current.invite_id};invite_mask:${codeMask}`, currentTime),
    ]);

    if (results.length < 4 || results.some(result => Number(result?.meta?.changes || 0) !== 1)) {
        throw new AppError('RESEND_CONFLICT', 'Invitation changed while resending', 409);
    }

    try {
        await sendEmail(env, {
            to: current.email,
            subject: '欢迎加入 TraktoSearch，你的邀请码已送达',
            ...buildInvitationEmail({
                nickname: current.nickname,
                inviteCode,
                siteUrl: env.PUBLIC_SITE_ORIGIN,
                expiresAt,
            }),
        });
    } catch (error) {
        console.error('Public invitation resend failed', error);
        throw new AppError('EMAIL_SEND_FAILED', 'Unable to send invitation email', 503);
    }

    return { inviteId, inviteCode, expiresAt };
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
    siteUrl: string;
    expiresAt: number;
}): { html: string; text: string } {
    const name = escapeHtml(input.nickname);
    const code = escapeHtml(input.inviteCode);
    const siteUrl = input.siteUrl.replace(/\/$/, '');
    const url = escapeHtml(siteUrl);
    const imageUrl = escapeHtml(`${siteUrl}/assets/chiikawa/ai-three-watching-email.png`);
    const expiry = formatDate(input.expiresAt);
    return {
        html: emailLayout(`
            <p style="margin:0 0 18px;color:#6D685F;">你好，${name}：</p>
            <h1 style="margin:0 0 16px;font:600 30px/1.15 Georgia,serif;color:#1D1C19;">欢迎加入 TraktoSearch！</h1>
            <p style="margin:0 0 22px;color:#6D685F;line-height:1.8;">感谢你下载并体验 TraktoSearch，请在 App 激活页面输入下列激活码。</p>
            <div style="margin:0 0 8px;color:#6D685F;font-size:12px;letter-spacing:.16em;text-align:center;text-transform:uppercase;">INVITATION CODE</div>
            <div style="margin:0 0 12px;padding:18px 20px;background:#E9E2D4;border-left:4px solid #D95532;text-align:center;">
                <div style="font:700 24px/1.2 'Courier New',monospace;letter-spacing:.12em;color:#1D1C19;">${code}</div>
            </div>
            <div style="margin:0 0 12px;text-align:center;">
                <img src="${imageUrl}" alt="吉伊" width="180" style="display:block;width:180px;max-width:100%;height:auto;margin:0 auto;border:0;">
            </div>
            <p style="margin:0 0 12px;color:#6D685F;line-height:1.7;">邀请码有效期至 ${expiry}，只能使用一次，请不要转发给他人。</p>
            <p style="margin:0 0 24px;color:#6D685F;line-height:1.7;">如果你愿意，欢迎把使用体验、反馈和建议提交到 <a href="https://github.com/yufeng-liang/TraktoSearch" style="color:#D95532;">GitHub 仓库</a>，也可以通过 App 内的反馈与建议提交，这会直接帮助我改进后续版本。</p>
            <p style="margin:0;color:#9B9588;font-size:13px;line-height:1.7;">也可以打开 <a href="${url}" style="color:#D95532;">TraktoSearch 官网</a>，查看最新说明和下载入口。</p>
        `, false, 'TraktoSearch 激活码已准备好，请打开邮件查看。'),
        text: `你好，${input.nickname}：\n\n欢迎加入 TraktoSearch！感谢你下载并体验 TraktoSearch，请在 App 激活页面输入下列激活码。\n\n${input.inviteCode}\n\n邀请码有效期至 ${expiry}，只能使用一次，请不要转发给他人。欢迎把使用体验、反馈和建议提交到 GitHub：https://github.com/yufeng-liang/TraktoSearch，也可以通过 App 内的反馈与建议提交，这会直接帮助我改进后续版本。\n\n官网：${siteUrl}`,
    };
}

export async function enforcePublicRateLimit(env: PublicInviteEnv, request: Request): Promise<void> {
    if (isInviteTestRequest(env, request)) {
        return;
    }
    // 经 gateway 转发时 CF-Connecting-IP 已被覆盖为边缘出口 IP，须优先取 X-Real-IP；
    // 直接公网访问时反过来只信 CF-Connecting-IP，避免伪造。统一由 clientIp 处理。
    const ip = clientIp(request) || 'unknown';
    // D1 条件 UPSERT 原子限流，替代 KV 读改写（并发穿透 + TTL 滑动）
    const allowed = await consumeRateLimit(
        env.DB,
        `public-invite:ip:${await sha256(ip)}`,
        PUBLIC_REQUEST_RATE_LIMIT,
        3600,
    );
    if (!allowed) {
        throw new AppError('RATE_LIMITED', 'Too many requests', 429);
    }
}

export function isInviteTestRequest(env: PublicInviteEnv, request: Request): boolean {
    const testKey = request.headers.get('X-Invite-Test-Key');
    // 常量时间比较，避免 === 短路的时序侧信道（API key 基线）
    return Boolean(env.INVITE_TEST_BYPASS_KEY && testKey && timingSafeEqual(testKey, env.INVITE_TEST_BYPASS_KEY));
}

export async function sendEmail(
    env: PublicInviteEnv,
    input: { to: string; subject: string; html: string; text: string },
): Promise<void> {
    if (env.BREVO_API_KEY) {
        if (!env.EMAIL_FROM) {
            throw new AppError('EMAIL_NOT_CONFIGURED', 'Invitation email is not configured', 503);
        }

        const replyTo = env.EMAIL_REPLY_TO ? parseEmailAddress(env.EMAIL_REPLY_TO) : undefined;
        const payload = {
            sender: parseEmailAddress(env.EMAIL_FROM),
            to: [{ email: input.to }],
            subject: input.subject,
            htmlContent: input.html,
            textContent: input.text,
            ...(replyTo ? { replyTo } : {}),
        };
        const response = await fetch('https://api.brevo.com/v3/smtp/email', {
            method: 'POST',
            headers: {
                accept: 'application/json',
                'api-key': env.BREVO_API_KEY,
                'content-type': 'application/json',
            },
            body: JSON.stringify(payload),
        });
        if (!response.ok) {
            const detail = (await response.text()).slice(0, 500);
            console.error('Brevo transactional email failed', response.status, detail);
            throw new AppError('EMAIL_SEND_FAILED', 'Unable to send email through Brevo', 503);
        }
        return;
    }

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

function parseEmailAddress(value: string): { email: string; name?: string } {
    const displayAddress = value.match(/^\s*(.*?)\s*<([^<>\s]+@[^<>\s]+)>\s*$/);
    if (displayAddress) {
        return { name: displayAddress[1].trim(), email: displayAddress[2].trim() };
    }
    return { email: value.trim() };
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

function emailLayout(content: string, showBrand = true, preheader = ''): string {
    const shellStyle = showBrand ? 'border-top:4px solid #D95532;padding:26px 20px 0;' : 'padding:0 20px;';
    const brand = showBrand
        ? `<div style="color:#D95532;font:600 12px/1.2 'Courier New',monospace;letter-spacing:.2em;text-transform:uppercase;">TraktoSearch</div><div style="height:1px;background:#D8D0C1;margin:20px 0 28px;"></div>`
        : '';
    const hiddenPreheader = preheader
        ? `<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;font-size:1px;line-height:1px;">${escapeHtml(preheader)}&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;</div>`
        : '';
    return `<!doctype html><html lang="zh-CN"><body style="margin:0;padding:0;background:#F6F2E9;color:#1D1C19;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;">${hiddenPreheader}<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;margin:0;padding:0;background:#F6F2E9;"><tr><td align="center" style="padding:24px 16px;"><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;margin:0 auto;background:#F6F2E9;"><tr><td style="${shellStyle}">${brand}${content}<div style="height:1px;background:#D8D0C1;margin:30px 0 16px;"></div><p style="margin:16px 0 0;color:#9B9588;font-size:12px;line-height:1.6;">TraktoSearch · 从想看到找到，再到看过</p></td></tr></table></td></tr></table></body></html>`;
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
