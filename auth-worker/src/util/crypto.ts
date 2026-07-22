// 加密工具：SHA-256 哈希 + 安全随机数

// SHA-256 哈希（用于邀请码/令牌/刷新令牌）
export async function sha256(input: string): Promise<string> {
    const encoder = new TextEncoder();
    const data = encoder.encode(input);
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
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
