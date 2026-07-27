// 加密工具：SHA-256 哈希 + 安全随机数

// SHA-256 哈希（用于邀请码/令牌/刷新令牌）
export async function sha256(input: string): Promise<string> {
    const encoder = new TextEncoder();
    const data = encoder.encode(input);
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

// 使用 Worker Secret 生成设备连续性指纹，原始标识不落库。
export async function hmacDeviceContinuityId(deviceId: string, secret: string): Promise<string> {
    if (!deviceId || !secret) throw new Error('Device continuity HMAC is not configured');
    const key = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(secret),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign'],
    );
    const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(deviceId));
    return Array.from(new Uint8Array(signature), byte => byte.toString(16).padStart(2, '0')).join('');
}

// 生成安全随机令牌（32 字节，hex 编码）
export function generateSecureToken(length: number = 32): string {
    const bytes = new Uint8Array(length);
    crypto.getRandomValues(bytes);
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
}

// 生成邀请码（12 位，去混淆字符）
export function generateInviteCode(): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 不含 0/O/I/1/L
    const bytes = new Uint8Array(12);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, b => chars[b % chars.length]).join('');
}

// 生成设备/会话 ID
export function generateId(): string {
    return crypto.randomUUID();
}

// 常量时间字符串比较（防时序攻击）
export function timingSafeEqual(a: string, b: string): boolean {
    if (a.length !== b.length) return false;
    let result = 0;
    for (let i = 0; i < a.length; i++) {
        result |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return result === 0;
}

/** 使用 Worker Secret 派生 AES-GCM 密钥，凭据密文包含独立随机 IV。 */
export async function encryptSecret(plaintext: string, secret: string): Promise<string> {
    const key = await importEncryptionKey(secret);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encrypted = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        new TextEncoder().encode(plaintext)
    );
    return `${toBase64(iv)}.${toBase64(new Uint8Array(encrypted))}`;
}

export async function decryptSecret(ciphertext: string, secret: string): Promise<string> {
    const [ivPart, dataPart] = ciphertext.split('.');
    if (!ivPart || !dataPart) throw new Error('Invalid encrypted credential');
    const key = await importEncryptionKey(secret);
    const decrypted = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: fromBase64(ivPart) },
        key,
        fromBase64(dataPart)
    );
    return new TextDecoder().decode(decrypted);
}

async function importEncryptionKey(secret: string): Promise<CryptoKey> {
    const keyMaterial = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret));
    return crypto.subtle.importKey('raw', keyMaterial, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

function toBase64(bytes: Uint8Array): string {
    let binary = '';
    bytes.forEach(byte => { binary += String.fromCharCode(byte); });
    return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
    const binary = atob(value);
    return Uint8Array.from(binary, char => char.charCodeAt(0));
}
