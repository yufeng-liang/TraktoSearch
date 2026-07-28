// crypto 工具

// 生成随机 ID（UUID v4）
export function generateId(): string {
    return crypto.randomUUID();
}

// 生成安全随机 token
export function generateSecureToken(bytes: number): string {
    const arr = new Uint8Array(bytes);
    crypto.getRandomValues(arr);
    return Array.from(arr, b => b.toString(16).padStart(2, '0')).join('');
}
