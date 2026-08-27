/**
 * Crash Logs API
 * POST /api/crash-logs — Receive crash log from app
 * GET  /api/crash-logs — List crash logs with filter + pagination + stats
 * GET  /api/crash-logs/:id — Get single crash log detail（见 [id].js）
 */
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

export async function onRequestGet(context) {
    const { env, request } = context;
    const url = new URL(request.url);
    const pathSegments = url.pathname.replace('/api/crash-logs/', '').replace('/api/crash-logs', '').split('/').filter(Boolean);

    // GET /api/crash-logs/:id — single detail
    if (pathSegments.length > 0) {
        const id = pathSegments[0];
        try {
            const fullKey = id.startsWith('crash_') ? id : `crash_${id}`;
            let val = await env.CRASH_LOGS.get(fullKey, { type: 'json' });
            if (!val) {
                val = await env.CRASH_LOGS.get(id, { type: 'json' });
            }
            if (!val) {
                return new Response(JSON.stringify({ error: 'Not found' }), {
                    status: 404,
                    headers: { 'Content-Type': 'application/json' }
                });
            }
            return new Response(JSON.stringify(val), {
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

        // 并发读值
        const rawList = await Promise.all(
            keys.map(k => env.CRASH_LOGS.get(k.name, { type: 'json' }).catch(() => null))
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

        // 统计（基于全量，不受筛选影响）
        const now = Date.now();
        const stats = {
            total: logs.length,
            last7d: logs.filter(l => now - new Date(l.timestamp || 0).getTime() < 7 * 86400000).length,
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
