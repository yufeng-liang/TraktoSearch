// GET /feedback-api/screenshot/{key} — 从 R2 读取截图

interface Env {
    SCREENSHOTS: R2Bucket;
}

export async function handleScreenshot(
    request: Request,
    env: Env,
    key: string
): Promise<Response> {
    if (!key || key.length > 256 || key.includes('..')) {
        return new Response('Bad request', { status: 400 });
    }

    const object = await env.SCREENSHOTS.get(key);
    if (!object) {
        return new Response('Not found', { status: 404 });
    }

    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set('Cache-Control', 'public, max-age=2592000'); // 30 天
    headers.set('Access-Control-Allow-Origin', '*');
    return new Response(object.body, { headers });
}
