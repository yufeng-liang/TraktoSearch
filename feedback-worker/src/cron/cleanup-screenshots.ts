// Cron：清理 30 天前的 R2 截图

interface Env {
    SCREENSHOTS: R2Bucket;
}

const MAX_AGE_DAYS = 30;
const MAX_AGE_MS = MAX_AGE_DAYS * 24 * 60 * 60 * 1000;

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

    console.log(`[cleanup] deleted ${deleted} screenshots older than ${MAX_AGE_DAYS} days`);
}
