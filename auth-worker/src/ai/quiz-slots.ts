// 出题槽位表：把「一次让模型写 4 个单元 + 13 道题」拆成「每一格各自生成、哪一格不合格只补哪一格」。
// 本文件只放静态规则与纯函数，不做任何 IO —— 纯函数才能被单测覆盖，也才能让本地闭环统计
// 「单元一次通过率 / 题位一次通过率 / 修复后通过率」这些指标。
//
// 为什么需要它：整包生成时任意一处违规都会让 13 题全部作废（实测 5 次真实生成 0 次通过）。
// 拆成槽位后，违规的代价从「整包重来」降到「只补这一格」。

/** 单元槽位数量：固定 4，让 13 个题位能稳定分配（每概念 3-4 题），学科覆盖要求也天然满足。 */
export const QUIZ_UNIT_SLOT_COUNT = 4;

/** 题位槽位数量：与产品定义的 13 题一一对应。 */
export const QUIZ_QUESTION_SLOT_COUNT = 13;

/** 角度类型：同一批单元之间、同一概念下的题位之间都不得重复，用来防同质化。 */
export const QUIZ_ANGLE_TYPES = ['象征', '因果', '对比', '应用', '机制解释', '证据辨识'] as const;
export type QuizAngleType = (typeof QUIZ_ANGLE_TYPES)[number];

/**
 * 可选学科组：只有这 5 个「不需要外部来源」的组。强证据学科所在的
 * science_and_nature / technology_and_future / life_and_career 一律不入表，
 * society_and_institution 也按同样口径剔除了法学。
 */
const QUIZ_UNIT_GROUPS: ReadonlyArray<{ subjectGroup: string; allowedSubjects: readonly string[] }> = [
    {
        subjectGroup: 'film_expression',
        allowedSubjects: ['电影学', '叙事学', '摄影与视觉设计', '剪辑与声音', '表演与戏剧'],
    },
    {
        subjectGroup: 'people_and_mind',
        allowedSubjects: ['心理学', '认知科学', '发展心理学', '教育学'],
    },
    {
        subjectGroup: 'society_and_institution',
        allowedSubjects: ['社会学', '人类学', '传播学', '政治学', '经济学', '犯罪学'],
    },
    {
        subjectGroup: 'history_and_culture',
        allowedSubjects: ['历史', '文化研究', '语言学与符号学', '宗教神话与民俗', '音乐与艺术史'],
    },
    {
        subjectGroup: 'philosophy_and_ethics',
        allowedSubjects: ['哲学与伦理学', '马克思主义哲学'],
    },
];

/**
 * FNV-1a 32 位稳定哈希：纯函数、不碰随机数，同一 seed 恒得同一偏移量，
 * 「按 (用户, 日期, 套序号) 复算槽位表」才成立。
 */
function hashSeed(seed: string): number {
    let hash = 0x811c9dc5;
    for (let i = 0; i < seed.length; i += 1) {
        hash ^= seed.charCodeAt(i);
        // Math.imul 走 32 位整数乘法，避免 JS 浮点乘法丢低位
        hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash >>> 0;
}

/** 从 start 起在 list 上轮转取 count 个；count ≤ list.length 时两两不重复。 */
function rotateTake<T>(list: readonly T[], start: number, count: number): T[] {
    const picked: T[] = [];
    for (let i = 0; i < count; i += 1) {
        picked.push(list[(start + i) % list.length]);
    }
    return picked;
}

/** evidence 里简介行的前缀，与 handler 构造 evidence 时一致；决定这部片有没有可锚定的简介原文。 */
const SYNOPSIS_LINE_PREFIX = '简介：';

/**
 * 候选片优先级：按「材料厚度」降序排列，供 planUnitSlots 逐个槽位分配。
 *
 * 排序键依次是「有没有简介」「材料总字符数」「片名」：单元与题位的证据锚定都要求引用简介原文
 * （题位题干必须引用简介里 4 字以上连续片段），有简介的片子才拿得出可引用的材料；
 * 同分时按片名兜底，保证同一批候选片在任何环境下排出同一个顺序。
 *
 * @param movies 候选片（只需要片名与 evidence 两个字段）
 */
export function rankMovieTitlesForSlots(
    movies: ReadonlyArray<{ title: string; evidence?: readonly string[] }>,
): string[] {
    const seen = new Set<string>();
    const ranked: Array<{ title: string; synopsis: number; weight: number }> = [];
    for (const movie of movies) {
        const title = movie.title.trim();
        if (title === '' || seen.has(title)) continue;
        seen.add(title);
        const evidence = movie.evidence ?? [];
        ranked.push({
            title,
            synopsis: evidence.some(line => line.startsWith(SYNOPSIS_LINE_PREFIX)) ? 1 : 0,
            weight: evidence.reduce((sum, line) => sum + line.length, 0),
        });
    }
    ranked.sort((left, right) => (right.synopsis - left.synopsis)
        || (right.weight - left.weight)
        || (left.title < right.title ? -1 : left.title > right.title ? 1 : 0));
    return ranked.map(row => row.title);
}

/**
 * 单元槽位：生成前就把「这一格写哪个学科组、允许哪些学科、什么角度」钉死。
 *
 * 学科只用「不需要外部来源」的组与学科：强证据学科（物理/化学/生物与生态/医学与公共卫生/
 * 天文学/地理与气候/计算机与人工智能/数学与统计/工程与材料/建筑与城市规划/法学/
 * 军事学与战略/体育科学/食品科学）要求 external_fact 且 source.evidence 非空，
 * 实测是整包作废的高发点，槽位表从源头避开。
 */
export interface UnitSlot {
    /** 槽位序号 1..4 */
    index: number;
    /** 受控 subjectGroup */
    subjectGroup: string;
    /** 该组内允许的学科白名单（全部取自受控目录，且都不是强证据学科） */
    allowedSubjects: readonly string[];
    /** 本单元的角度类型 */
    angleType: QuizAngleType;
    /** 本槽位钉死的影片（片单为空时缺省，退回「单元自选影片」） */
    movieTitle?: string;
}

/** 题位槽位：题型、难度、归属单元、概念、角度全部预先分配，模型只负责把这一格写成题。 */
export interface QuestionSlot {
    /** 槽位序号 1..13 */
    index: number;
    type: 'single' | 'multiple' | 'short';
    difficulty: 'easy' | 'medium' | 'hard';
    /** 归属单元序号 1..4（按 surviving unit 的实际序号） */
    unitIndex: number;
    /** 该单元的概念：本格必须围绕它出题 */
    concept: string;
    /** 本格角度类型 */
    angleType: QuizAngleType;
    /** 同一概念其他题位的角度类型（喂给模型，明确「别人已经用了这些角度」） */
    siblingAngles: readonly QuizAngleType[];
    /** 本槽位相对所在单元的序号（第几个用这个概念），从 1 开始 */
    conceptOrdinal: number;
}

/**
 * 规划 4 个单元槽位。
 *
 * 规则：
 * - 4 个槽位取 4 个不同的 subjectGroup（从「不需要外部来源」的 5 个组里按 seed 轮转挑），
 *   保证学科覆盖 ≥3 的既有硬校验必然满足。
 * - 4 个角度类型互不重复（6 选 4，按 seed 轮转）。
 * - 传入 movieTitles 时第 i 个槽位钉死第 i 部片：13 个题位跟着单元走，单元固定在 4 部不同片上，
 *   一套必然覆盖 4 部片。片名不是 seed 派的（片单本身已按 seed 抽过一轮），换一套片单就换一批片。
 * - 同一 seed 必须得到完全相同的结果（确定性）：预生成按 (用户, 日期, 套序号) 复算槽位表，
 *   不需要把槽位表存库。
 *
 * @param seed 形如 `{friendId}:{date}:{setIndex}` 的确定性种子
 * @param movieTitles 候选片优先级顺序（见 rankMovieTitlesForSlots），缺省即不指定影片
 */
export function planUnitSlots(seed: string, movieTitles: readonly string[] = []): UnitSlot[] {
    const hash = hashSeed(seed);
    // 低位定组偏移，高位定角度偏移：5 与 6 互质，两个偏移各自独立轮转
    const groupOffset = hash % QUIZ_UNIT_GROUPS.length;
    const angleOffset = Math.floor(hash / QUIZ_UNIT_GROUPS.length) % QUIZ_ANGLE_TYPES.length;

    const groups = rotateTake(QUIZ_UNIT_GROUPS, groupOffset, QUIZ_UNIT_SLOT_COUNT);
    const angles = rotateTake(QUIZ_ANGLE_TYPES, angleOffset, QUIZ_UNIT_SLOT_COUNT);
    // 候选片不足 4 部时按位次轮转复用：宁可重复，也不能有空槽位拿不到片
    const picked: string[] = [];
    for (const title of movieTitles) {
        const trimmed = title.trim();
        if (trimmed === '' || picked.includes(trimmed)) continue;
        picked.push(trimmed);
        if (picked.length >= QUIZ_UNIT_SLOT_COUNT) break;
    }

    return groups.map((group, position) => ({
        index: position + 1,
        subjectGroup: group.subjectGroup,
        // 复制一份，避免调用方拿到表内数组后改动影响后续调用
        allowedSubjects: [...group.allowedSubjects],
        angleType: angles[position],
        ...(picked.length === 0 ? {} : { movieTitle: picked[position % picked.length] }),
    }));
}

/**
 * 题位形状表：题型与难度按 1..13 的位次写死，改这里等于改产品定义，测试会锁住计数。
 * 合计 10 single / 2 multiple / 1 short，4 easy / 5 medium / 4 hard。
 */
const QUESTION_SLOT_SHAPES: ReadonlyArray<Pick<QuestionSlot, 'type' | 'difficulty'>> = [
    { type: 'single', difficulty: 'easy' }, // 1
    { type: 'single', difficulty: 'easy' }, // 2
    { type: 'single', difficulty: 'easy' }, // 3
    { type: 'single', difficulty: 'easy' }, // 4
    { type: 'single', difficulty: 'medium' }, // 5
    { type: 'single', difficulty: 'medium' }, // 6
    { type: 'single', difficulty: 'medium' }, // 7
    { type: 'single', difficulty: 'medium' }, // 8
    { type: 'multiple', difficulty: 'medium' }, // 9
    { type: 'single', difficulty: 'hard' }, // 10
    { type: 'single', difficulty: 'hard' }, // 11
    { type: 'multiple', difficulty: 'hard' }, // 12
    { type: 'short', difficulty: 'hard' }, // 13
];

/**
 * 规划 13 个题位槽位。
 *
 * 题型与难度按位次写死（合计 10 single / 2 multiple / 1 short，4 easy / 5 medium / 4 hard）：
 * - 第 1-4 位：single / easy
 * - 第 5-9 位：single ×4 + multiple ×1 / medium
 * - 第 10-13 位：single ×2 + multiple ×1 + short ×1 / hard
 *
 * 概念按位次对给定单元轮转分配（单位元数 3 或 4 都要能跑）；同一概念下各题位的角度类型
 * 互不重复，并把它作为 siblingAngles 传给模型防撞车。
 *
 * @param units 已通过校验的单元（至少 3 个），只需 index 与 concept
 */
export function planQuestionSlots(
    units: ReadonlyArray<{ index: number; concept: string }>,
): QuestionSlot[] {
    if (units.length === 0) {
        throw new Error('planQuestionSlots 需要至少 3 个已通过校验的单元，收到的是空数组');
    }

    const angleCount = QUIZ_ANGLE_TYPES.length;
    // 概念首次在 units 中出现的位置 = 该概念角度轮转的起点：
    // 不同概念的起点因此各不相同，不会所有概念都从「象征」开始
    const conceptStart = new Map<string, number>();
    units.forEach((unit, position) => {
        if (!conceptStart.has(unit.concept)) conceptStart.set(unit.concept, position);
    });
    // 概念 -> 已排出的角度（按题位序号升序），同时用于回填 siblingAngles
    const anglesByConcept = new Map<string, QuizAngleType[]>();

    const slots: QuestionSlot[] = [];
    for (let position = 0; position < QUIZ_QUESTION_SLOT_COUNT; position += 1) {
        const unit = units[position % units.length];
        const shape = QUESTION_SLOT_SHAPES[position];
        const start = conceptStart.get(unit.concept) ?? 0;
        const used = anglesByConcept.get(unit.concept) ?? [];

        // 按起点依次取用，跳过该概念已用过的角度，保证同概念内互不重复
        let angle = QUIZ_ANGLE_TYPES[start % angleCount];
        for (let step = 0; step < angleCount; step += 1) {
            const candidate = QUIZ_ANGLE_TYPES[(start + step) % angleCount];
            if (!used.includes(candidate)) {
                angle = candidate;
                break;
            }
        }
        used.push(angle);
        anglesByConcept.set(unit.concept, used);

        slots.push({
            index: position + 1,
            type: shape.type,
            difficulty: shape.difficulty,
            unitIndex: unit.index,
            concept: unit.concept,
            angleType: angle,
            siblingAngles: [], // 占位，等全部角度定完后回填
            conceptOrdinal: used.length,
        });
    }

    // 回填同概念其他题位的角度：conceptOrdinal 就是本格在该概念角度表里的 1 基下标
    for (const slot of slots) {
        const used = anglesByConcept.get(slot.concept) ?? [];
        slot.siblingAngles = used.filter((_, position) => position !== slot.conceptOrdinal - 1);
    }

    return slots;
}
