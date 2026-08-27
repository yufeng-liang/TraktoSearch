/**
 * Crash Logs API
 * POST /api/crash-logs — Receive crash log from app
 * GET  /api/crash-logs — List crash logs with filter + pagination + stats
 * GET  /api/crash-logs/:id — Get single crash log detail（见 [id].js）
 */

/** 单条 stackTrace 上限（KV 值上限 25MB，堆栈超此长度基本是异常数据） */
const MAX_STACK_TRACE_LENGTH = 500 * 1024;
/** 单条 recentActions 上限 */
const MAX_RECENT_ACTIONS_LENGTH = 100 * 1024;

export async function onRequestPost(context) {
    const { request, env } = context;

    try {
        const body = await request.json();
        const { timestamp, appVersion, androidVersion, device, currentPage, recentActions, stackTrace } = body;

        if (!stackTrace) {
            return new Response(JSON.stringify({ error: 'stackTrace is required' }), {
                status: 400,
                headers: { 'Content-Type': 'application/json' }
            });
        }
        if (stackTrace.length > MAX_STACK_TRACE_LENGTH) {
            return new Response(JSON.stringify({ error: 'stackTrace too large' }), {
                status: 413,
                headers: { 'Content-Type': 'application/json' }
            });
        }
        if (typeof recentActions === 'string' && recentActions.length > MAX_RECENT_ACTIONS_LENGTH) {
            return new Response(JSON.stringify({ error: 'recentActions too large' }), {
                status: 413,
                headers: { 'Content-Type': 'application/json' }
            });
        }

        const id = `crash_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
        const entry = {
            id,
            timestamp: timestamp || new Date().toISOString(),
            appVersion: appVersion || '',
            androidVersion: androidVersion || '',
            device: device || '',
            currentPage: currentPage || '',
            recentActions: recentActions || '',
            stackTrace,
            status: 'open',
        };

        await env.CRASH_LOGS.put(id, JSON.stringify(entry));

        return new Response(JSON.stringify({ ok: true, id }), {
            status: 201,
            headers: { 'Content-Type': 'application/json' }
        });
    } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' }
        });
    }
}

/** 受控并发执行：每批最多 batchSize 个在途 Promise，避免上千并发 KV get 触发限流 */
async function mapWithConcurrency(items, batchSize, fn) {
    const results = new Array(items.length);
    let index = 0;
    async function worker() {
        while (index < items.length) {
            const i = index++;
            results[i] = await fn(items[i], i);
        }
    }
    const workers = [];
    for (let i = 0; i < Math.min(batchSize, items.length); i++) {
        workers.push(worker());
    }
    await Promise.all(workers);
    return results;
}

export async function onRequestGet(context) {
    const { env, request } = context;
    const url = new URL(request.url);

    // GET /api/crash-logs — list
    // 崩溃日志为低频数据（仅崩溃时写入），量级小：全量拉取 KV → 内存过滤/排序 → offset 分页，
    // 与审计日志的 { limit, offset, total, hasMore } 模式一致，同时一次请求带回统计信息。
    try {
        const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '20', 10) || 20, 1), 50);
        const offset = Math.max(parseInt(url.searchParams.get('offset') || '0', 10) || 0, 0);
        const search = (url.searchParams.get('search') || '').trim().toLowerCase();
        const status = url.searchParams.get('status') || 'all'; // all | open | fixed

        // 全量拉取 crash_ 前缀键（KV list 每页最多 1000 条，用 cursor 翻完）
        const keys = [];
        let cursor;
        do {
            const page = await env.CRASH_LOGS.list({ prefix: 'crash_', limit: 1000, cursor });
            keys.push(...page.keys);
            cursor = page.cursor;
        } while (cursor);

        // 受控并发读值（每批 20，避免大批量同时 get 触发 KV 限流）
        const rawList = await mapWithConcurrency(keys, 20, k =>
            env.CRASH_LOGS.get(k.name, { type: 'json' }).catch(() => null)
        );

        // 归一化 + 旧数据兼容（无 status 视为 open）
        let logs = rawList.filter(Boolean).map(v => ({
            id: v.id || '',
            timestamp: v.timestamp || '',
            appVersion: v.appVersion || '',
            androidVersion: v.androidVersion || '',
            device: v.device || '',
            currentPage: v.currentPage || '',
            recentActions: v.recentActions || '',
            stackTrace: v.stackTrace || '',
            status: v.status === 'fixed' ? 'fixed' : 'open',
        }));

        // 按时间倒序（新在前）
        logs.sort((a, b) => new Date(b.timestamp || 0).getTime() - new Date(a.timestamp || 0).getTime());

        // 统计（基于全量，不受筛选影响；未来时间戳视为无效不计入近 7 天）
        const now = Date.now();
        const stats = {
            total: logs.length,
            last7d: logs.filter(l => {
                const ts = new Date(l.timestamp || 0).getTime();
                return Number.isFinite(ts) && ts <= now && now - ts < 7 * 86400000;
            }).length,
            open: logs.filter(l => l.status === 'open').length,
            devices: new Set(logs.map(l => l.device).filter(Boolean)).size,
        };

        // 关键词筛选：匹配堆栈 / 设备 / 版本 / 页面
        if (search) {
            logs = logs.filter(l =>
                l.stackTrace.toLowerCase().includes(search) ||
                l.device.toLowerCase().includes(search) ||
                l.appVersion.toLowerCase().includes(search) ||
                l.androidVersion.toLowerCase().includes(search) ||
                l.currentPage.toLowerCase().includes(search)
            );
        }
        // 状态筛选
        if (status === 'open' || status === 'fixed') {
            logs = logs.filter(l => l.status === status);
        }

        const total = logs.length;
        const pageItems = logs.slice(offset, offset + limit).map(l => ({
            id: l.id,
            timestamp: l.timestamp,
            appVersion: l.appVersion,
            androidVersion: l.androidVersion,
            device: l.device,
            currentPage: l.currentPage,
            status: l.status,
            stackTracePreview: l.stackTrace.split('\n').slice(0, 3).join('\n'),
        }));

        return new Response(JSON.stringify({
            entries: pageItems,
            total,
            hasMore: offset + pageItems.length < total,
            stats,
        }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
        });
    } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' }
        });
    }
}
