/**
 * Crash Logs API
 * POST /api/crash-logs — Receive crash log from app
 * GET  /api/crash-logs — List crash logs (summary)
 * GET  /api/crash-logs/:id — Get single crash log detail
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
            stackTrace
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
    try {
        const limit = Math.min(parseInt(url.searchParams.get('limit') || '20'), 50);
        const list = await env.CRASH_LOGS.list({
            limit,
            prefix: 'crash_',
            reverse: true
        });

        const entries = [];
        for (const key of list.keys) {
            const val = await env.CRASH_LOGS.get(key.name, { type: 'json' });
            if (val) {
                entries.push({
                    id: val.id,
                    timestamp: val.timestamp,
                    appVersion: val.appVersion,
                    device: val.device,
                    currentPage: val.currentPage,
                    stackTracePreview: val.stackTrace?.split('\n').slice(0, 3).join('\n')
                });
            }
        }

        return new Response(JSON.stringify({ entries, cursor: list.cursor }), {
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
