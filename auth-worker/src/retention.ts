// 按公开数据政策清理已不再需要的申请正文和对应安全审计记录。

import { now } from './util/errors.ts';
import { AI_TTS_RATE_WINDOW_SECONDS } from './ai/store.ts';

export const INCOMPLETE_INVITE_RETENTION_SECONDS = 30 * 24 * 60 * 60;
export const CLOSED_LEGAL_REQUEST_RETENTION_SECONDS = 180 * 24 * 60 * 60;
// AI 健康事件只用于近期可用性观察，30 天足够定位回溯问题，也控制 D1 体积
export const AI_HEALTH_EVENT_RETENTION_SECONDS = 30 * 24 * 60 * 60;

export async function cleanupRetention(env: { DB: D1Database }): Promise<void> {
    const currentTime = now();
    const incompleteInviteCutoff = currentTime - INCOMPLETE_INVITE_RETENTION_SECONDS;
    const closedLegalRequestCutoff = currentTime - CLOSED_LEGAL_REQUEST_RETENTION_SECONDS;
    const aiHealthEventCutoff = currentTime - AI_HEALTH_EVENT_RETENTION_SECONDS;

    // 无界增长表补清理：
    // - auth_challenges：10 分钟过期的一次性 nonce，永不删则无限膨胀
    // - rate_limits：匿名 IP×操作桶（activate/recover/challenge/invite/legal），跨窗口即死行
    // - ai_cache：过期行只靠读侧 expires_at 过滤，行本身永不删
    // - friend_ip_logs：每次 check 插入，只用于近期观察
    const expiredChallengesCutoff = currentTime;
    const rateLimitCutoff = currentTime - 2 * 24 * 60 * 60;
    const expiredAiCacheCutoff = currentTime;
    const friendIpLogCutoff = currentTime - 30 * 24 * 60 * 60;
    // 题库用量按天记账，覆盖跨天边界保留 2 天即可
    const quizBankUsageCutoffDate = new Date((currentTime - 2 * 24 * 60 * 60) * 1000)
        .toISOString().slice(0, 10);

    const [inviteResult, legalResult, auditResult, ttsRateLimitResult, healthResult,
        challengesResult, rateLimitResult, aiCacheResult, friendIpLogResult, quizBankResult] = await Promise.all([
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
        env.DB.prepare(`
            DELETE FROM ai_health_events
            WHERE created_at < ?
        `).bind(aiHealthEventCutoff).run(),
        env.DB.prepare(`
            DELETE FROM auth_challenges
            WHERE expires_at < ?
        `).bind(expiredChallengesCutoff).run(),
        env.DB.prepare(`
            DELETE FROM rate_limits
            WHERE updated_at < ?
        `).bind(rateLimitCutoff).run(),
        env.DB.prepare(`
            DELETE FROM ai_cache
            WHERE expires_at < ?
        `).bind(expiredAiCacheCutoff).run(),
        env.DB.prepare(`
            DELETE FROM friend_ip_logs
            WHERE created_at < ?
        `).bind(friendIpLogCutoff).run(),
        env.DB.prepare(`
            DELETE FROM quiz_bank_usage
            WHERE usage_date < ?
        `).bind(quizBankUsageCutoffDate).run(),
    ]);

    console.log(JSON.stringify({
        event: 'retention_cleanup_completed',
        incompleteInvites: Number(inviteResult.meta.changes || 0),
        closedLegalRequests: Number(legalResult.meta.changes || 0),
        legalAuditLogs: Number(auditResult.meta.changes || 0),
        expiredTtsRateLimits: Number(ttsRateLimitResult.meta.changes || 0),
        expiredHealthEvents: Number(healthResult.meta.changes || 0),
        expiredAuthChallenges: Number(challengesResult.meta.changes || 0),
        staleRateLimits: Number(rateLimitResult.meta.changes || 0),
        expiredAiCacheRows: Number(aiCacheResult.meta.changes || 0),
        staleFriendIpLogs: Number(friendIpLogResult.meta.changes || 0),
        staleQuizBankUsage: Number(quizBankResult.meta.changes || 0),
    }));
}
