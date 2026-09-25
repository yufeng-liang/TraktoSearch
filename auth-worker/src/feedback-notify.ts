// POST /internal/feedback-notify — feedback-worker 提交新反馈后触发开发者邮件提醒。
//
// 只对内部 Service Binding 开放：调用方用两 worker 共享的 JWT_SIGNING_KEY 签一枚
// 短时 token（scope 固定为 internal:feedback-notify），这里验签 + 验 scope。
// 不复用 /api/ 的 scope:['api'] 校验——App 端 token 也带该 scope，若共用则任何
// 登录用户都能拿自己的 token 触发通知邮件。

import { AppError, successResponse } from './util/errors.ts';
import { verifyAccessToken } from './util/jwt.ts';
import { sendEmail, emailLayout, escapeHtml, type PublicInviteEnv } from './invitations.ts';

export const FEEDBACK_NOTIFY_SCOPE = 'internal:feedback-notify';

// DB / PUBLIC_SITE_ORIGIN 只为满足 sendEmail 的入参类型（PublicInviteEnv），本模块并不使用
export interface FeedbackNotifyEnv extends Pick<
    PublicInviteEnv,
    'DB' | 'PUBLIC_SITE_ORIGIN' | 'EMAIL' | 'BREVO_API_KEY' | 'EMAIL_FROM' | 'EMAIL_REPLY_TO'
> {
    JWT_SIGNING_KEY: string;
    ADMIN_EMAIL?: string;
    ADMIN_UI_ORIGIN?: string;
}

export interface FeedbackNotifyInput {
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

const TYPE_LABELS: Record<string, string> = {
    FEATURE: '功能建议',
    BUG: '问题反馈',
    UX: '体验问题',
    OTHER: '其他',
};

const MAX_CONTENT_LENGTH = 2000;

/** 校验内部调用方 payload：网络边界，字段类型/长度全部收紧，避免畸形数据进邮件正文。 */
export function normalizeFeedbackNotify(input: unknown): FeedbackNotifyInput {
    const body = input && typeof input === 'object' ? input as Record<string, unknown> : {};
    const text = (value: unknown, max: number): string | null => {
        if (typeof value !== 'string') return null;
        const trimmed = value.trim();
        if (!trimmed || trimmed.length > max) return null;
        return trimmed;
    };

    const id = text(body.id, 64);
    const displayId = text(body.displayId, 32);
    const friendNickname = text(body.friendNickname, 64);
    const appVersion = text(body.appVersion, 64);
    const osVersion = text(body.osVersion, 64);
    const deviceModel = text(body.deviceModel, 128);
    if (!id || !displayId || !friendNickname || !appVersion || !osVersion || !deviceModel) {
        throw new AppError('INVALID_REQUEST', 'Missing required feedback fields', 400);
    }

    if (typeof body.type !== 'string' || !TYPE_LABELS[body.type]) {
        throw new AppError('INVALID_REQUEST', 'Invalid feedback type', 400);
    }
    if (typeof body.content !== 'string') {
        throw new AppError('INVALID_REQUEST', 'content must be a string', 400);
    }
    const content = body.content.trim().slice(0, MAX_CONTENT_LENGTH);
    if (!content) {
        throw new AppError('INVALID_REQUEST', 'content is required', 400);
    }

    const optional = (value: unknown, max: number): string | null => {
        if (value === undefined || value === null) return null;
        if (typeof value !== 'string') {
            throw new AppError('INVALID_REQUEST', 'optional field must be a string', 400);
        }
        const trimmed = value.trim();
        return trimmed ? trimmed.slice(0, max) : null;
    };

    const screenshotCount = typeof body.screenshotCount === 'number' && Number.isFinite(body.screenshotCount)
        ? Math.max(0, Math.min(5, Math.floor(body.screenshotCount)))
        : 0;

    return {
        id,
        displayId,
        type: body.type,
        content,
        friendNickname,
        contact: optional(body.contact, 128),
        traktUsername: optional(body.traktUsername, 64),
        doubanUsername: optional(body.doubanUsername, 64),
        appVersion,
        osVersion,
        deviceModel,
        screenshotCount,
        createdAt: typeof body.createdAt === 'number' && Number.isFinite(body.createdAt)
            ? Math.floor(body.createdAt)
            : Math.floor(Date.now() / 1000),
    };
}

function row(label: string, value: string): string {
    return `<tr><td style="padding:5px 0;color:#9B9588;font-size:13px;white-space:nowrap;vertical-align:top;">${escapeHtml(label)}</td><td style="padding:5px 0 5px 16px;color:#1D1C19;font-size:13px;line-height:1.7;">${value}</td></tr>`;
}

export function buildFeedbackNotifyEmail(
    input: FeedbackNotifyInput,
    adminOrigin: string,
): { subject: string; html: string; text: string } {
    const label = TYPE_LABELS[input.type];
    const detailUrl = `${adminOrigin.replace(/\/$/, '')}/admin/#/feedback/${encodeURIComponent(input.id)}`;
    const nickname = escapeHtml(input.friendNickname);

    // 正文里的换行必须转成 <br>：反馈内容是多行文本，直接塞进 <p> 会被压成一行
    const contentHtml = escapeHtml(input.content).replaceAll('\n', '<br>');

    const metaRows = [
        row('类型', escapeHtml(label)),
        row('用户', nickname),
        input.contact ? row('联系方式', escapeHtml(input.contact)) : '',
        input.traktUsername ? row('Trakt', escapeHtml(input.traktUsername)) : '',
        input.doubanUsername ? row('豆瓣', escapeHtml(input.doubanUsername)) : '',
        row('版本', escapeHtml(`${input.appVersion} · ${input.osVersion} · ${input.deviceModel}`)),
        row('截图', input.screenshotCount > 0 ? `${input.screenshotCount} 张（后台详情页查看）` : '无'),
    ].filter(Boolean).join('');

    const subject = `[TraktoSearch] 新反馈 ${input.displayId} · ${input.friendNickname}`;

    return {
        subject,
        html: emailLayout(`
            <div style="margin:0 0 6px;color:#D95532;font:600 12px/1.2 'Courier New',monospace;letter-spacing:.16em;text-transform:uppercase;">NEW FEEDBACK</div>
            <h1 style="margin:0 0 20px;font:600 26px/1.2 Georgia,serif;color:#1D1C19;">${escapeHtml(input.displayId)} · ${escapeHtml(label)}</h1>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;margin:0 0 20px;">${metaRows}</table>
            <div style="margin:0 0 8px;color:#9B9588;font-size:12px;letter-spacing:.16em;text-transform:uppercase;">内容</div>
            <div style="margin:0 0 22px;padding:16px 18px;background:#E9E2D4;border-left:4px solid #D95532;color:#1D1C19;font-size:14px;line-height:1.8;word-break:break-word;">${contentHtml}</div>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;margin:14px 0 8px;">
                <tr><td align="center" style="padding:0;">
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                        <tr><td style="background:#D95532;border-radius:6px;">
                            <a href="${escapeHtml(detailUrl)}" style="display:block;padding:13px 30px;font:600 15px/1.1 -apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;color:#FFFFFF;text-decoration:none;">打开后台详情页</a>
                        </td></tr>
                    </table>
                </td></tr>
            </table>
            <p style="margin:0 0 24px;color:#9B9588;font-size:12px;line-height:1.7;text-align:center;">详情页需通过 Cloudflare Access 登录后查看，可回复该反馈或将其关闭。</p>
        `, true, `新反馈 ${input.displayId} · ${input.friendNickname}：${input.content.slice(0, 60)}`),
        text: [
            `新反馈 ${input.displayId} · ${label}`,
            '',
            `用户：${input.friendNickname}`,
            input.contact ? `联系方式：${input.contact}` : '',
            input.traktUsername ? `Trakt：${input.traktUsername}` : '',
            input.doubanUsername ? `豆瓣：${input.doubanUsername}` : '',
            `版本：${input.appVersion} · ${input.osVersion} · ${input.deviceModel}`,
            `截图：${input.screenshotCount > 0 ? `${input.screenshotCount} 张（后台详情页查看）` : '无'}`,
            '',
            '内容：',
            input.content,
            '',
            `后台详情页：${detailUrl}`,
        ].filter(line => line !== '').join('\n'),
    };
}

/** 安全解析 JSON 请求体：畸形 JSON 归 400，避免冒泡成 500。 */
async function readJsonBody(request: Request): Promise<unknown> {
    try {
        const body = await request.json();
        if (!body || typeof body !== 'object' || Array.isArray(body)) {
            throw new AppError('INVALID_REQUEST', 'Body must be a JSON object', 400);
        }
        return body;
    } catch (err) {
        if (err instanceof AppError) throw err;
        throw new AppError('INVALID_REQUEST', 'Invalid JSON body', 400);
    }
}

export async function handleFeedbackNotify(
    request: Request,
    env: FeedbackNotifyEnv,
    requestId: string,
): Promise<Response> {
    const authHeader = request.headers.get('Authorization');
    if (!authHeader?.startsWith('Bearer ')) {
        throw new AppError('UNAUTHORIZED', 'Missing authorization header', 401);
    }
    const payload = await verifyAccessToken(env.JWT_SIGNING_KEY, authHeader.slice(7));
    if (!payload) {
        throw new AppError('INVALID_TOKEN', 'Invalid or expired token', 401);
    }
    if (!Array.isArray(payload.scope) || !payload.scope.includes(FEEDBACK_NOTIFY_SCOPE)) {
        throw new AppError('INVALID_TOKEN', 'Token scope does not permit feedback notifications', 403);
    }

    if (!env.ADMIN_EMAIL) {
        throw new AppError('EMAIL_NOT_CONFIGURED', 'ADMIN_EMAIL is not configured', 503);
    }

    const input = normalizeFeedbackNotify(await readJsonBody(request));
    const adminOrigin = env.ADMIN_UI_ORIGIN || 'https://app-config-1qe.pages.dev';
    const email = buildFeedbackNotifyEmail(input, adminOrigin);

    await sendEmail(env, {
        to: env.ADMIN_EMAIL,
        subject: email.subject,
        html: email.html,
        text: email.text,
    });

    return successResponse({ notified: true }, requestId);
}
