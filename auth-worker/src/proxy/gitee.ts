// Gitee API 代理（Worker Secret 注入鉴权 + 云端同步数据服务端加密）
//
// 客户端原直连 https://gitee.com/api/v5/ 并自带 Bearer token，
// 现改为走网关 /api/gitee/* ，由 worker 注入 GITEE_ACCESS_TOKEN，
// 避免密钥编译进 APK 被反编译泄露。
//
// 云端同步加密：details_pool/、failures/、personal/ 路径下的文件内容
// 原由客户端 AesCrypto（硬编码 key）加密/解密，现改由 worker 用
// CLOUD_SYNC_AES_KEY Secret 在服务端透明加密/解密，密钥不再编译进 APK。
//
// 透明转发：路径、query、请求体保持原语义，仅替换鉴权头 + 加解密 content 字段。

import { Env } from '../index';
import { AppError } from '../util/errors';

const GITEE_BASE_URL = 'https://gitee.com/api/v5';
const GITEE_PREFIX = '/api/gitee/';

// 需要服务端加解密的云同步路径前缀
const CLOUD_SYNC_PATH_PREFIXES = ['details_pool/', 'failures/', 'personal/'];

export async function handleGiteeProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    if (!env.GITEE_ACCESS_TOKEN) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Gitee proxy not configured', 503);
    }

    const giteePath = path.replace(GITEE_PREFIX, '');
    const clientUrl = new URL(request.url);
    const upstreamUrl = new URL(`${GITEE_BASE_URL}/${giteePath}`);
    // 透传 query 参数（如 ref、per_page、direction、page）
    upstreamUrl.search = clientUrl.search;

    // 判断是否为云同步路径（需要加解密）
    const isCloudSync = isCloudSyncPath(giteePath);
    // 判断是否为 Contents API 的文件读写操作
    const isContentsApi = giteePath.includes('/contents/');

    const headers = new Headers();
    headers.set('Authorization', `token ${env.GITEE_ACCESS_TOKEN}`);
    headers.set('Accept', 'application/json');
    headers.set('User-Agent', 'TrackToSearch-Worker/3.0');
    const clientContentType = request.headers.get('Content-Type');
    if (clientContentType) headers.set('Content-Type', clientContentType);

    // 处理请求体：POST/PUT 时加密 content 字段
    let body: ArrayBuffer | undefined;
    if (request.method !== 'GET' && request.method !== 'HEAD') {
        body = await request.arrayBuffer();
        if (isCloudSync && isContentsApi && env.CLOUD_SYNC_AES_KEY && body.byteLength > 0) {
            body = await encryptContentField(body, env.CLOUD_SYNC_AES_KEY);
        }
    }

    const upstream = await fetch(upstreamUrl.toString(), {
        method: request.method,
        headers,
        body,
    });

    // 处理响应体：GET 时解密 content 字段
    if (isCloudSync && isContentsApi && upstream.ok && env.CLOUD_SYNC_AES_KEY) {
        return await decryptContentInResponse(upstream, env.CLOUD_SYNC_AES_KEY);
    }

    return proxyResponse(upstream);
}

/** 判断 Gitee API 路径是否为云同步路径 */
function isCloudSyncPath(giteePath: string): boolean {
    // 路径格式: repos/{owner}/{repo}/contents/{path}
    // 提取 contents/ 后的文件路径
    const contentsMatch = giteePath.match(/\/contents\/(.+)$/);
    if (!contentsMatch) return false;
    const filePath = decodeURIComponent(contentsMatch[1]);
    return CLOUD_SYNC_PATH_PREFIXES.some(prefix => filePath.startsWith(prefix));
}

/**
 * 加密请求体中的 content 字段。
 * 输入: {"content": "base64(plaintext)", ...}
 * 输出: {"content": "base64(IV + ciphertext)", ...}
 */
async function encryptContentField(body: ArrayBuffer, keyString: string): Promise<ArrayBuffer> {
    try {
        const bodyStr = new TextDecoder().decode(body);
        const bodyJson = JSON.parse(bodyStr);
        if (!bodyJson.content) return body;

        // 解码客户端发来的 base64 明文
        const plaintext = atob(bodyJson.content);

        // 加密
        const encrypted = await aesEncrypt(plaintext, keyString);
        bodyJson.content = encrypted;

        const encoded = new TextEncoder().encode(JSON.stringify(bodyJson));
        return encoded.buffer as ArrayBuffer;
    } catch {
        return body;
    }
}

/**
 * 解密响应体中的 content 字段。
 * 输入: {"content": "base64(IV + ciphertext)", ...}
 * 输出: {"content": "base64(plaintext)", ...}
 */
async function decryptContentInResponse(response: Response, keyString: string): Promise<Response> {
    try {
        const text = await response.text();
        // Gitee 在文件不存在时可能返回 `[]`（空数组），直接透传
        const trimmed = text.trim();
        if (trimmed.startsWith('[')) {
            return proxyResponse(new Response(text, {
                status: response.status,
                headers: response.headers,
            }));
        }

        const bodyJson = JSON.parse(text);
        if (!bodyJson.content) {
            return proxyResponse(new Response(text, {
                status: response.status,
                headers: response.headers,
            }));
        }

        // 解密
        const plaintext = await aesDecrypt(bodyJson.content, keyString);
        if (plaintext === null) {
            // 解密失败，返回原始响应（兼容旧数据或非加密数据）
            return proxyResponse(new Response(text, {
                status: response.status,
                headers: response.headers,
            }));
        }

        // 用明文的 base64 替换 content 字段
        bodyJson.content = btoa(plaintext);
        const newBody = JSON.stringify(bodyJson);

        return proxyResponse(new Response(newBody, {
            status: response.status,
            headers: response.headers,
        }));
    } catch {
        // 解析失败，返回原始响应
        return proxyResponse(response);
    }
}

// ==================== AES-256-CBC 加解密 ====================
// 与客户端原 AesCrypto 逻辑一致：
// - Key: SHA-256(keyString) → 32 字节
// - IV: 16 字节随机
// - 格式: base64(IV + ciphertext)
// - Padding: PKCS7（Web Crypto AES-CBC 默认）

async function deriveKey(keyString: string): Promise<CryptoKey> {
    const encoder = new TextEncoder();
    const keyData = encoder.encode(keyString);
    const hashBuffer = await crypto.subtle.digest('SHA-256', keyData);
    return crypto.subtle.importKey(
        'raw',
        hashBuffer,
        { name: 'AES-CBC' },
        false,
        ['encrypt', 'decrypt']
    );
}

async function aesEncrypt(plaintext: string, keyString: string): Promise<string> {
    const key = await deriveKey(keyString);
    const iv = crypto.getRandomValues(new Uint8Array(16));
    const encoder = new TextEncoder();
    const plaintextBytes = encoder.encode(plaintext);

    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-CBC', iv },
        key,
        plaintextBytes
    );

    // 拼接 IV + ciphertext
    const combined = new Uint8Array(iv.length + ciphertext.byteLength);
    combined.set(iv, 0);
    combined.set(new Uint8Array(ciphertext), iv.length);

    // base64 编码
    return bytesToBase64(combined);
}

async function aesDecrypt(encryptedBase64: string, keyString: string): Promise<string | null> {
    try {
        const combined = base64ToBytes(encryptedBase64);
        if (combined.length <= 16) return null;

        const iv = combined.slice(0, 16);
        const ciphertext = combined.slice(16);

        const key = await deriveKey(keyString);
        const decrypted = await crypto.subtle.decrypt(
            { name: 'AES-CBC', iv },
            key,
            ciphertext
        );

        return new TextDecoder().decode(decrypted);
    } catch {
        return null;
    }
}

// ==================== 工具函数 ====================

function bytesToBase64(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}

function base64ToBytes(base64: string): Uint8Array {
    const binaryString = atob(base64);
    const len = binaryString.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes;
}

function proxyResponse(response: Response): Response {
    const headers = new Headers(response.headers);
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, {
        status: response.status,
        headers,
    });
}
