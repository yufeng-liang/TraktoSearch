// 云端配置代理：服务端解密后返回明文 JSON
//
// 客户端原从 Pages 直接拉取加密配置(config.json.enc)，再用 BuildConfig.CONFIG_AES_KEY
// 在本地 AES-256-GCM 解密。密钥嵌入 APK 可被反编译提取。
//
// 现改为走网关 /api/config，由 worker 用 Secret 注入的 CONFIG_AES_KEY 解密后返回明文，
// 密钥不再编译进 APK。
//
// 缓存策略：worker 侧用 KV 缓存解密后的明文配置(5 分钟 TTL)，减少 Pages 拉取和解密开销。

import { Env } from '../index';
import { AppError } from '../util/errors';

const KV_CACHE_KEY = 'config:plaintext';
const KV_CACHE_TTL = 300; // 5 分钟
const GCM_IV_LENGTH = 12;
const GCM_TAG_LENGTH = 16;

export async function handleConfigProxy(
    request: Request,
    env: Env,
    requestId: string
): Promise<Response> {
    if (request.method !== 'GET') {
        throw new AppError('METHOD_NOT_ALLOWED', 'Only GET is supported', 405);
    }

    if (!env.CONFIG_AES_KEY || !env.CONFIG_BASE_URL) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Config proxy not configured', 503);
    }

    // 1. 尝试从 KV 缓存读取解密后的明文
    const cached = await env.KV.get(KV_CACHE_KEY);
    if (cached) {
        return new Response(cached, {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    }

    // 2. 从 Pages 拉取加密配置
    const configUrl = env.CONFIG_BASE_URL.replace(/\/$/, '') + '/config.json.enc';
    const fetchResp = await fetch(configUrl);
    if (!fetchResp.ok) {
        throw new AppError('UPSTREAM_ERROR', `Failed to fetch config: ${fetchResp.status}`, 502);
    }

    const encryptedBase64 = (await fetchResp.text()).trim();

    // 3. AES-256-GCM 解密（使用 Web Crypto API）
    const plaintext = await decryptAesGcm(encryptedBase64, env.CONFIG_AES_KEY);
    if (!plaintext) {
        throw new AppError('DECRYPT_FAILED', 'Config decryption failed', 500);
    }

    // 4. 写入 KV 缓存
    try {
        await env.KV.put(KV_CACHE_KEY, plaintext, { expirationTtl: KV_CACHE_TTL });
    } catch { /* KV 写入失败不影响响应 */ }

    return new Response(plaintext, {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

/**
 * AES-256-GCM 解密
 *
 * 密文格式: base64(iv(12) || ciphertext || tag(16))
 * 与客户端原 RemoteConfigManager.decrypt 逻辑一致
 */
async function decryptAesGcm(encryptedBase64: string, hexKey: string): Promise<string | null> {
    try {
        const combined = base64ToBytes(encryptedBase64);
        if (combined.length < GCM_IV_LENGTH + GCM_TAG_LENGTH) return null;

        const iv = combined.slice(0, GCM_IV_LENGTH);
        const cipherPayload = combined.slice(GCM_IV_LENGTH); // ciphertext || tag

        const keyBytes = hexToBytes(hexKey);
        if (keyBytes.length !== 32) return null;

        // 导入密钥
        const cryptoKey = await crypto.subtle.importKey(
            'raw',
            keyBytes,
            { name: 'AES-GCM' },
            false,
            ['decrypt']
        );

        // 解密
        const decrypted = await crypto.subtle.decrypt(
            {
                name: 'AES-GCM',
                iv: iv,
                tagLength: GCM_TAG_LENGTH * 8, // 128 bits
            },
            cryptoKey,
            cipherPayload
        );

        return new TextDecoder().decode(decrypted);
    } catch {
        return null;
    }
}

/** hex 字符串转 Uint8Array */
function hexToBytes(hex: string): Uint8Array {
    const len = hex.length;
    const data = new Uint8Array(len / 2);
    for (let i = 0; i < len; i += 2) {
        data[i / 2] = parseInt(hex.substr(i, 2), 16);
    }
    return data;
}

/** base64 字符串转 Uint8Array */
function base64ToBytes(base64: string): Uint8Array {
    const binaryString = atob(base64);
    const len = binaryString.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes;
}
