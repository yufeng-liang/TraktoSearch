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
    // verification_token_hash 仅为兼容既有表结构的簿记写入：验证邮件流程已下线，明文 token 即弃，不存在对应链接。
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
            subject: '欢迎加入 TraktoSearch，你的取票码已送达',
            ...buildInvitationEmail({
                nickname: request.nickname,
                inviteCode,
                siteUrl: env.PUBLIC_SITE_ORIGIN,
            }),
        });
        await env.DB.prepare(`
            UPDATE invite_requests SET email_sent_at = ?, updated_at = ? WHERE id = ?
        `).bind(currentTime, currentTime, request.id).run();
    } catch (error) {
        console.error('Public invitation email failed', error);
        // 回滚顺序有硬约束：invite_requests.friend_id / invite_id 有外键指向
        // friends / invites（D1 默认 foreign_keys=1，batch 又是事务性的），
        // 必须先清空这两个指针再删父行；反过来整批会被 FOREIGN KEY constraint
        // failed 拒绝，名额泄漏、请求卡在 ISSUED、用户拿到的是原始 SQL 错误。
        await env.DB.batch([
            env.DB.prepare(`
                UPDATE invite_requests
                SET status = 'EMAIL_FAILED', friend_id = NULL, invite_id = NULL, updated_at = ?
                WHERE id = ?
            `).bind(now(), request.id),
            env.DB.prepare(`DELETE FROM invites WHERE id = ?`).bind(inviteId),
            env.DB.prepare(`DELETE FROM friends WHERE id = ?`).bind(friendId),
            // 邮件发送失败回滚时，前一步已插入的 SUCCESS 审计行成为悬挂记录
            // （显示签发成功但邀请已删），标记为 FAILURE——result 列有
            // CHECK(result IN ('SUCCESS','FAILURE')) 约束，只能取这两个值。
            // request_id 列存的是 HTTP 请求 ID（见上面的 INSERT），不是
            // invite_requests.id，用后者匹配会静默命中 0 行。
            env.DB.prepare(`
                UPDATE audit_logs
                SET result = 'FAILURE', detail = detail || ';email_send_failed_rolled_back'
                WHERE event_type = 'PUBLIC_INVITE_ISSUE' AND friend_id = ? AND request_id = ? AND result = 'SUCCESS'
            `).bind(friendId, requestId),
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
               r.updated_at AS request_updated_at,
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
        request_updated_at: number;
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
            subject: '欢迎加入 TraktoSearch，你的取票码已送达',
            ...buildInvitationEmail({
                nickname: current.nickname,
                inviteCode,
                siteUrl: env.PUBLIC_SITE_ORIGIN,
            }),
        });
    } catch (error) {
        console.error('Public invitation resend failed', error);
        // 上面那批语句已经提交，发信却失败了。不回滚的话，用户手上/收件箱里的
        // 旧码已失效、新码从未送达、email_sent_at 还被刷新（60 秒内连重试都不行），
        // 审计日志却记着 SUCCESS。这里把状态逐项还原成重发前的样子。
        //
        // 顺序同样是硬约束：invite_requests.invite_id 此刻指向新码，必须先指回
        // 旧码，否则 DELETE 新码会撞上外键。
        await env.DB.batch([
            env.DB.prepare(`
                UPDATE invite_requests
                SET invite_id = ?, email_sent_at = ?, updated_at = ?
                WHERE id = ? AND invite_id = ?
            `).bind(
                current.invite_id,
                current.email_sent_at,
                current.request_updated_at,
                current.request_id,
                inviteId,
            ),
            // revoked_at = ? 是并发护栏：只有仍是本次撤销打上的时间戳才还原
            env.DB.prepare(`
                UPDATE invites
                SET revoked_at = NULL
                WHERE id = ? AND friend_id = ? AND revoked_at = ?
            `).bind(current.invite_id, current.friend_id, currentTime),
            env.DB.prepare(`DELETE FROM invites WHERE id = ?`).bind(inviteId),
            env.DB.prepare(`
                UPDATE audit_logs
                SET result = 'FAILURE', detail = detail || ';email_send_failed_rolled_back'
                WHERE event_type = 'PUBLIC_INVITE_RESEND' AND friend_id = ? AND request_id = ? AND result = 'SUCCESS'
            `).bind(current.friend_id, requestId),
        ]);
        throw new AppError('EMAIL_SEND_FAILED', 'Unable to send invitation email', 503);
    }

    return { inviteId, inviteCode, expiresAt };
}

export function buildInvitationEmail(input: {
    nickname: string;
    inviteCode: string;
    siteUrl: string;
}): { html: string; text: string } {
    const name = escapeHtml(input.nickname);
    const code = escapeHtml(input.inviteCode);
    const siteUrl = input.siteUrl.replace(/\/$/, '');
    const url = escapeHtml(siteUrl);
    const imageUrl = escapeHtml(`${siteUrl}/assets/chiikawa/ai-three-watching-email.png`);
    // 票码由官网页面申请，收件人此刻往往还没装上 App。latest.apk 是 Pages 侧的
    // 稳定别名，由 CI 移动 latest.json 指针决定指向，所以邮件里绝不写版本号——
    // 写死了就会把半年后翻出这封邮件的用户引向一个旧包。
    const apkUrl = escapeHtml(`${siteUrl}/dl/latest.apk`);
    const siteHost = escapeHtml(siteUrl.replace(/^https?:\/\//, ''));
    return {
        html: emailLayout(`
            <p style="margin:0 0 18px;color:#6D685F;">你好，${name}：</p>
            <h1 style="margin:0 0 16px;font:600 30px/1.15 Georgia,serif;color:#1D1C19;">欢迎加入 TraktoSearch！</h1>
            <p style="margin:0 0 22px;color:#6D685F;line-height:1.8;">请在 App 取票机页面输入下列取票码。</p>
            <div style="margin:0 0 8px;color:#6D685F;font-size:12px;letter-spacing:.16em;text-align:center;text-transform:uppercase;">TICKET CODE</div>
            <div style="margin:0 0 12px;padding:18px 20px;background:#E9E2D4;border-left:4px solid #D95532;text-align:center;">
                <div style="font:700 24px/1.2 'Courier New',monospace;letter-spacing:.12em;color:#1D1C19;">${code}</div>
            </div>
            <div style="margin:0 0 12px;text-align:center;">
                <img src="${imageUrl}" alt="吉伊" width="180" style="display:block;width:180px;max-width:100%;height:auto;margin:0 auto;border:0;">
            </div>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;margin:14px 0 8px;">
                <tr><td align="center" style="padding:0;">
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                        <tr><td style="background:#D95532;border-radius:6px;">
                            <a href="${apkUrl}" style="display:block;padding:13px 30px;font:600 15px/1.1 -apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;color:#FFFFFF;text-decoration:none;">下载 Android 安装包</a>
                        </td></tr>
                    </table>
                </td></tr>
            </table>
            <p style="margin:0 0 24px;color:#9B9588;font-size:12px;line-height:1.7;text-align:center;">安装包始终由 ${siteHost} 提供，不会经过任何第三方站点；下载后需在系统提示中允许安装未知来源应用。</p>
            <p style="margin:0 0 24px;color:#6D685F;line-height:1.7;">如果你愿意，欢迎把使用体验、反馈和建议提交到 <a href="https://github.com/yufeng-liang/TraktoSearch" style="color:#D95532;">GitHub 仓库</a>，也可以通过 App 内的反馈与建议提交，这会直接帮助我改进后续版本。</p>
            <p style="margin:0;color:#9B9588;font-size:13px;line-height:1.7;">也可以打开 <a href="${url}" style="color:#D95532;">TraktoSearch 官网</a>，查看最新说明。</p>
        `, false, 'TraktoSearch 取票码已准备好，请打开邮件查看。'),
        text: `你好，${input.nickname}：\n\n欢迎加入 TraktoSearch！请在 App 取票机页面输入下列取票码。\n\n${input.inviteCode}\n\nAndroid 安装包（始终最新版）：${siteUrl}/dl/latest.apk\n\n欢迎把使用体验、反馈和建议提交到 GitHub：https://github.com/yufeng-liang/TraktoSearch，也可以通过 App 内的反馈与建议提交，这会直接帮助我改进后续版本。\n\n官网：${siteUrl}`,
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

export function emailLayout(content: string, showBrand = true, preheader = ''): string {
    const shellStyle = showBrand ? 'border-top:4px solid #D95532;padding:26px 20px 0;' : 'padding:0 20px;';
    const brand = showBrand
        ? `<div style="color:#D95532;font:600 12px/1.2 'Courier New',monospace;letter-spacing:.2em;text-transform:uppercase;">TraktoSearch</div><div style="height:1px;background:#D8D0C1;margin:20px 0 28px;"></div>`
        : '';
    const hiddenPreheader = preheader
        ? `<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;font-size:1px;line-height:1px;">${escapeHtml(preheader)}&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;</div>`
        : '';
    // 深色模式加固：Gmail 移动端会剥离 <head>，因此配色声明必须同时内联在 html/body 上，
    // 否则整封邮件（含透明贴图露出的背景）会被强制反相压暗。
    return `<!doctype html><html lang="zh-CN" style="color-scheme:light only;supported-color-schemes:light only"><head><meta charset="utf-8"><meta name="color-scheme" content="light only"><meta name="supported-color-schemes" content="light only"><style>:root{color-scheme:light only;supported-color-schemes:light only}</style></head><body style="color-scheme:light only;supported-color-schemes:light only;margin:0;padding:0;background:#F6F2E9;color:#1D1C19;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;">${hiddenPreheader}<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;margin:0;padding:0;background:#F6F2E9;"><tr><td align="center" style="padding:24px 16px;"><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;margin:0 auto;background:#F6F2E9;"><tr><td style="${shellStyle}">${brand}${content}<div style="height:1px;background:#D8D0C1;margin:30px 0 16px;"></div><p style="margin:16px 0 0;color:#9B9588;font-size:12px;line-height:1.6;">TraktoSearch · 从想看到找到，再到看过</p></td></tr></table></td></tr></table></body></html>`;
}

export function escapeHtml(value: string): string {
    return value.replace(/[&<>'"]/g, character => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;',
    })[character] || character);
}
