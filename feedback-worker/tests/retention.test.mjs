import test from 'node:test';
import assert from 'node:assert/strict';
import {
    handleCleanupScreenshots,
    SCREENSHOT_MAX_AGE_DAYS,
    FEEDBACK_RETENTION_DAYS,
} from '../src/cron/cleanup-screenshots.ts';

test('retention policy keeps screenshots for 90 days and closed feedback for 180 days', () => {
    assert.equal(SCREENSHOT_MAX_AGE_DAYS, 90);
    assert.equal(FEEDBACK_RETENTION_DAYS, 180);
});

test('cleanup removes old closed feedback records after removing conversations', async () => {
    const prepared = [];
    const batches = [];
    const env = {
        SCREENSHOTS: {
            async list() { return { objects: [], truncated: false }; },
        },
        DB: {
            prepare(sql) {
                prepared.push(sql);
                return {
                    bind() { return this; },
                    async run() { return { meta: { changes: 0 } }; },
                };
            },
            async batch(statements) {
                batches.push(statements);
                return statements.map(() => ({ meta: { changes: 0 } }));
            },
        },
    };

    await handleCleanupScreenshots(env);

    assert.equal(batches.length, 1);
    assert.match(prepared.join('\n'), /DELETE FROM feedback_conversations/);
    assert.match(prepared.join('\n'), /DELETE FROM feedback_replies/);
    assert.match(prepared.join('\n'), /DELETE FROM feedbacks/);
    assert.match(prepared.join('\n'), /closed_at < \?/);
});
