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

// 邀请码长度（6 位纯数字，便于口述与数字键盘输入）
export const INVITE_CODE_LENGTH = 6;

// 生成邀请码（6 位纯数字）
//
// 拒绝采样：只采用 0-249（250 是 10 的整数倍），落在 250-255 的字节丢弃重抽。
// 直接 byte % 10 会让数字 0-5 各多出 1/256 概率，把 10^6 的有效空间再削一截；
// 原 12 位字母表长度 32 整除 256 所以没有这个问题，改成 10 之后必须显式处理。
export function generateInviteCode(): string {
    const digits: string[] = [];
    while (digits.length < INVITE_CODE_LENGTH) {
        const bytes = new Uint8Array(INVITE_CODE_LENGTH - digits.length);
        crypto.getRandomValues(bytes);
        for (const byte of bytes) {
            if (byte < 250) digits.push(String(byte % 10));
        }
    }
    return digits.join('');
}

// 邀请码掩码：落库到 invites.code_mask 与审计日志 detail 的脱敏串。
//
// 6 位纯数字空间只有 10^6，露出任意一位都会把可暴破空间压掉一个数量级，
// 而按 12 位写法取首尾 4 位（slice(0,4) + slice(-4)）在 6 位码上会拼出完整明文，
// 等于把码原样写进数据库和审计日志。因此短码整体掩掉，只保留长度形状；
// 历史 12 位字母数字码仍保留首尾 4 位，方便人工对账旧记录。
export function maskInviteCode(code: string): string {
    if (code.length >= 12) return `${code.slice(0, 4)}****${code.slice(-4)}`;
    return '*'.repeat(Math.max(code.length, 4));
}

// 生成设备/会话 ID
export function generateId(): string {
    return crypto.randomUUID();
}

// 常量时间字符串比较（防时序攻击）
// 不在长度不同时提前返回，避免泄露长度信息
export function timingSafeEqual(a: string, b: string): boolean {
    const aBuf = new TextEncoder().encode(a);
    const bBuf = new TextEncoder().encode(b);
    const minLen = Math.min(aBuf.length, bBuf.length);
    let result = aBuf.length ^ bBuf.length;
    for (let i = 0; i < minLen; i++) {
        result |= aBuf[i] ^ bBuf[i];
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
