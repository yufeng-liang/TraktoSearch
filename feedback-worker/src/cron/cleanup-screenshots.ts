// Cron：清理过期截图和已关闭反馈

interface Env {
    SCREENSHOTS: R2Bucket;
    DB: D1Database;
}

export const SCREENSHOT_MAX_AGE_DAYS = 90;
export const FEEDBACK_RETENTION_DAYS = 180;
const MAX_AGE_MS = SCREENSHOT_MAX_AGE_DAYS * 24 * 60 * 60 * 1000;
const FEEDBACK_RETENTION_MS = FEEDBACK_RETENTION_DAYS * 24 * 60 * 60 * 1000;

export async function handleCleanupScreenshots(env: Env): Promise<void> {
    const cutoff = new Date(Date.now() - MAX_AGE_MS);
    let cursor: string | undefined = undefined;
    let deleted = 0;

    // R2 list 默认按 key 字典序，分页 1000 条
    while (true) {
        const listed = await env.SCREENSHOTS.list({ cursor, limit: 1000 });
        for (const obj of listed.objects) {
            if (obj.uploaded < cutoff) {
                await env.SCREENSHOTS.delete(obj.key);
                deleted++;
            }
        }
        if (!listed.truncated) break;
        cursor = listed.cursor;
    }

    const feedbackCutoff = Math.floor((Date.now() - FEEDBACK_RETENTION_MS) / 1000);
    const feedbackCleanup = await env.DB.batch([
        env.DB.prepare(`
            DELETE FROM feedback_conversations
            WHERE feedback_id IN (
                SELECT id FROM feedbacks
                WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < ?
            )
        `).bind(feedbackCutoff),
        env.DB.prepare(`
            DELETE FROM feedback_replies
            WHERE feedback_id IN (
                SELECT id FROM feedbacks
                WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < ?
            )
        `).bind(feedbackCutoff),
        env.DB.prepare(`
            DELETE FROM feedbacks
            WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < ?
        `).bind(feedbackCutoff),
    ]);
    const deletedFeedbacks = Number(feedbackCleanup[2]?.meta.changes || 0);

    console.log(JSON.stringify({
        event: 'feedback_retention_cleanup_completed',
        deletedScreenshots: deleted,
        screenshotRetentionDays: SCREENSHOT_MAX_AGE_DAYS,
        deletedFeedbacks,
        feedbackRetentionDays: FEEDBACK_RETENTION_DAYS,
    }));
}
