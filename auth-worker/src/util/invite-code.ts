// 邀请码分配：生成前先确认码位没被占用。
//
// invites.code_hash 是全表 UNIQUE，且 retention.ts 不清理 invites 行，已用/已撤销/
// 已过期的码会永久占位。12 位字母数字码空间 32^12 ≈ 1.2e18，碰撞可以忽略；改成
// 6 位纯数字后空间只有 10^6，签发量累积到几千条时单次碰撞概率就到千分之几，
// 而裸 INSERT 撞上 UNIQUE 会让整个 batch 失败并抛 500，用户侧表现为随机激活失败。
// 所以签发前查一次空位，撞了就重抽。

import { generateInviteCode, sha256 } from './crypto.ts';
import { AppError } from './errors.ts';

const MAX_CODE_ATTEMPTS = 8;

export interface ReservedInviteCode {
    code: string;
    codeHash: string;
}

// 返回一个当前未被占用的邀请码及其哈希。
//
// 查空位与后续 INSERT 之间仍有竞态窗口：两个并发签发可能选中同一个空码，
// 由 UNIQUE 约束兜底（落败方整批失败，退化为改动前的行为）。窗口只有一次
// SELECT 到 INSERT 的时间，概率是「碰撞概率 × 并发重叠概率」，可以接受；
// 真正的兜底是 UNIQUE 约束本身，这里只把常态碰撞率压回可忽略。
export async function reserveInviteCode(db: D1Database): Promise<ReservedInviteCode> {
    for (let attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
        const code = generateInviteCode();
        const codeHash = await sha256(code);
        const taken = await db.prepare(`
            SELECT 1 AS taken FROM invites WHERE code_hash = ?
        `).bind(codeHash).first<{ taken: number }>();
        if (!taken) return { code, codeHash };
    }
    // 连续 8 次全撞说明可用码位已接近耗尽，此时继续签发只会把 UNIQUE 冲突
    // 推给下游变成 500。直接报 503，让运维看到「该扩容码长了」而不是零散失败。
    throw new AppError(
        'INVITE_CODE_EXHAUSTED',
        'Could not allocate a unique invite code',
        503,
    );
}
