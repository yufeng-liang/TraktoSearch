// display_id 工具：生成与解析反馈显示 ID
// 格式：{前缀}{3位补零序号}，如 BUG001、FEAT042、UX005、OTH001
// 序号超过 999 时自然扩展，如 BUG1000

// 类型 → 前缀映射（全大写三字母）
export const TYPE_PREFIX_MAP: Record<string, string> = {
    FEATURE: 'FEAT',
    BUG: 'BUG',
    UX: 'UX',
    OTHER: 'OTH',
};

// 前缀 → 类型反向映射
const PREFIX_TYPE_MAP: Record<string, string> = {
    FEAT: 'FEATURE',
    BUG: 'BUG',
    UX: 'UX',
    OTH: 'OTHER',
};

/**
 * 生成 display_id。
 * @param type 反馈类型（FEATURE / BUG / UX / OTHER）
 * @param seq 序号（从 1 开始）
 * @returns 如 BUG001
 */
export function generateDisplayId(type: string, seq: number): string {
    const prefix = TYPE_PREFIX_MAP[type];
    if (!prefix) {
        throw new Error(`Unknown feedback type: ${type}`);
    }
    if (!Number.isInteger(seq) || seq < 1) {
        throw new Error(`seq must be positive integer: ${seq}`);
    }
    // 3 位补零，超出自然扩展
    const padded = seq < 1000 ? String(seq).padStart(3, '0') : String(seq);
    return `${prefix}${padded}`;
}

/**
 * 解析 display_id。
 * @param displayId 如 BUG001
 * @returns { type, seq } 或 null（无效格式）
 */
export function parseDisplayId(displayId: string): { type: string; seq: number } | null {
    if (typeof displayId !== 'string' || displayId.length < 4) return null;
    // 前缀固定 3 字母（UX 是 2 字母，特殊处理）
    const prefixes = Object.keys(PREFIX_TYPE_MAP);
    const prefix = prefixes.find(p => displayId.startsWith(p));
    if (!prefix) return null;
    const seqStr = displayId.slice(prefix.length);
    if (!/^\d+$/.test(seqStr)) return null;
    const seq = parseInt(seqStr, 10);
    if (seq < 1) return null;
    return { type: PREFIX_TYPE_MAP[prefix], seq };
}
