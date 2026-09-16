// POST /admin/upload-screenshot — 后台开发者上传截图到 R2
// 与 App 端 upload-screenshot 类似，但用 Access JWT 鉴权，R2 key 前缀为 admin/

import { AppError, successResponse } from '../util/errors';
import { generateSecureToken } from '../util/crypto';
import { checkRateLimit } from '../util/rate-limit';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
    SCREENSHOTS: R2Bucket;
}

interface AdminPayload {
    email: string;
}

const MAX_SIZE = 5 * 1024 * 1024; // 5 MB
const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export async function handleAdminUploadScreenshot(
    request: Request,
    env: Env,
    requestId: string,
    payload: AdminPayload
): Promise<Response> {
    // 限流：每分钟 5 张（按管理员邮箱）
    const key = `feedback:admin-upload:${payload.email}:minute`;
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

    // 路径：admin/{timestamp}-{token}.{ext}
    const ext = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg';
    const timestamp = Math.floor(Date.now() / 1000);
    const token = generateSecureToken(8);
    const objectKey = `admin/${timestamp}-${token}.${ext}`;

    await env.SCREENSHOTS.put(objectKey, file.stream(), {
        httpMetadata: { contentType: file.type },
    });

    return successResponse({ key: objectKey }, requestId);
}
