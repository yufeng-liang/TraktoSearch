// 按公开数据政策清理已不再需要的申请正文和对应安全审计记录。

import { now } from './util/errors.ts';
import { AI_TTS_RATE_WINDOW_SECONDS } from './ai/store.ts';

export const INCOMPLETE_INVITE_RETENTION_SECONDS = 30 * 24 * 60 * 60;
export const CLOSED_LEGAL_REQUEST_RETENTION_SECONDS = 180 * 24 * 60 * 60;

export async function cleanupRetention(env: { DB: D1Database }): Promise<void> {
    const currentTime = now();
    const incompleteInviteCutoff = currentTime - INCOMPLETE_INVITE_RETENTION_SECONDS;
    const closedLegalRequestCutoff = currentTime - CLOSED_LEGAL_REQUEST_RETENTION_SECONDS;

    const [inviteResult, legalResult, auditResult, ttsRateLimitResult] = await Promise.all([
        env.DB.prepare(`
            DELETE FROM invite_requests
            WHERE status IN ('VERIFICATION_SENT', 'REPLACED', 'EXPIRED', 'EMAIL_FAILED')
              AND updated_at < ?
        `).bind(incompleteInviteCutoff).run(),
        env.DB.prepare(`
            DELETE FROM legal_requests
            WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < ?
        `).bind(closedLegalRequestCutoff).run(),
        env.DB.prepare(`
            DELETE FROM audit_logs
            WHERE event_type IN ('LEGAL_REQUEST_SUBMIT', 'LEGAL_REQUEST_CLOSE')
              AND created_at < ?
        `).bind(closedLegalRequestCutoff).run(),
        env.DB.prepare(`
            DELETE FROM ai_tts_rate_limits
            WHERE updated_at < ?
        `).bind(currentTime - AI_TTS_RATE_WINDOW_SECONDS).run(),
    ]);

    console.log(JSON.stringify({
        event: 'retention_cleanup_completed',
        incompleteInvites: Number(inviteResult.meta.changes || 0),
        closedLegalRequests: Number(legalResult.meta.changes || 0),
        legalAuditLogs: Number(auditResult.meta.changes || 0),
        expiredTtsRateLimits: Number(ttsRateLimitResult.meta.changes || 0),
    }));
}
