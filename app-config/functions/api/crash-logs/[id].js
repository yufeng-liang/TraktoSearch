/**
 * Crash Logs detail API
 * GET   /api/crash-logs/:id — Get single crash log detail
 * PATCH /api/crash-logs/:id — Update status（open | fixed）
 */

async function readEntry(env, id) {
    const fullKey = id.startsWith('crash_') ? id : `crash_${id}`;
    let val = await env.CRASH_LOGS.get(fullKey, { type: 'json' });
    if (!val) {
        val = await env.CRASH_LOGS.get(id, { type: 'json' });
    }
    return { key: val ? fullKey : null, value: val };
}

export async function onRequestGet(context) {
    const { env, params } = context;
    const id = params.id;

    try {
        const { value: val } = await readEntry(env, id);
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

export async function onRequestPatch(context) {
    const { env, params, request } = context;
    const id = params.id;

    try {
        const body = await request.json();
        const status = body?.status;
        if (status !== 'open' && status !== 'fixed') {
            return new Response(JSON.stringify({ error: 'status must be "open" or "fixed"' }), {
                status: 400,
                headers: { 'Content-Type': 'application/json' }
            });
        }

        const { key, value: val } = await readEntry(env, id);
        if (!val) {
            return new Response(JSON.stringify({ error: 'Not found' }), {
                status: 404,
                headers: { 'Content-Type': 'application/json' }
            });
        }

        // 旧数据无 status 字段时补齐
        val.status = status;
        await env.CRASH_LOGS.put(key, JSON.stringify(val));

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
