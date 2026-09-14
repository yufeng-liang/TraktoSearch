// POST /feedback-api/upload-screenshot — 上传截图到 R2，返回 URL

import { AppError, successResponse } from '../util/errors';
import { generateSecureToken } from '../util/crypto';
import { checkRateLimit } from '../util/rate-limit';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
    SCREENSHOTS: R2Bucket;
}

interface JWTPayload {
    sub: string;
    device: string;
}

const MAX_SIZE = 5 * 1024 * 1024; // 5 MB
const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export async function handleUploadScreenshot(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    // 限流：每分钟 5 张
    const key = `feedback:upload:${payload.sub}:minute`;
    if (!await checkRateLimit(env, key, 5, 60)) {
        throw new AppError('RATE_LIMITED', 'Too many uploads, try again later', 429);
    }

    const contentType = request.headers.get('Content-Type') || '';
    if (!contentType.startsWith('multipart/form-data')) {
        throw new AppError('INVALID_REQUEST', 'Expected multipart/form-data', 400);
    }

    const formData = await request.formData();
    const file = formData.get('file');
    if (!(file instanceof File)) {
        throw new AppError('INVALID_REQUEST', 'Missing file field', 400);
    }
    if (file.size > MAX_SIZE) {
        throw new AppError('INVALID_REQUEST', 'File too large (max 5MB)', 400);
    }
    if (!ALLOWED_TYPES.has(file.type)) {
        throw new AppError('INVALID_REQUEST', 'Only JPEG/PNG/WebP allowed', 400);
    }

    // 路径：{friendId}/{timestamp}-{token}.{ext}
    const ext = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg';
    const timestamp = Math.floor(Date.now() / 1000);
    const token = generateSecureToken(8);
    const objectKey = `${payload.sub}/${timestamp}-${token}.${ext}`;

    await env.SCREENSHOTS.put(objectKey, file.stream(), {
        httpMetadata: { contentType: file.type },
    });

    // 返回相对路径，App 拼接完整 URL
    return successResponse({ key: objectKey }, requestId);
}
