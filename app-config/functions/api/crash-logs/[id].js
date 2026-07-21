/**
 * GET /api/crash-logs/:id — Get single crash log detail
 */
export async function onRequestGet(context) {
    const { env, params } = context;
    const id = params.id;

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
