/**
 * GET /api/crash-logs/:id — Get single crash log detail
 */
export async function onRequestGet(context) {
    const { env, params } = context;
    const id = params.id;

    if (!id || Array.isArray(id)) {
        return new Response(JSON.stringify({ error: 'Invalid ID' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
        });
    }

    try {
        const val = await env.CRASH_LOGS.get(`crash_${id}`, { type: 'json' });
        if (!val) {
            // Try full key
            const full = await env.CRASH_LOGS.get(id, { type: 'json' });
            if (!full) {
                return new Response(JSON.stringify({ error: 'Not found' }), {
                    status: 404,
                    headers: { 'Content-Type': 'application/json' }
                });
            }
            return new Response(JSON.stringify(full), {
                status: 200,
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
