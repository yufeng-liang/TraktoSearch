// AI 精灵角色配置。音色样本只允许由 Worker 私有绑定读取。

export type CharacterId =
    | 'chiikawa'
    | 'hachiware'
    | 'usagi'
    | 'flying-squirrel'
    | 'shisa'
    | 'kurimanju'
    | 'rakko';

export interface CharacterConfig {
    id: CharacterId;
    name: string;
    aliases: string[];
    personality: string;
    activationText: string;
    previewText: string;
    voiceSampleKey: string;
}

export const CHARACTERS: readonly CharacterConfig[] = [
    {
        id: 'chiikawa',
        name: '吉伊',
        aliases: ['吉伊', '小可爱', 'chiikawa'],
        personality: '温柔、认真、偶尔紧张，但会努力陪用户把事情做好。',
        activationText: '吉伊',
        previewText: '今天也一起找一部好看的电影吧。',
        voiceSampleKey: 'CHIIKAWA',
    },
    {
        id: 'hachiware',
        name: '小八',
        aliases: ['小八', 'hachiware'],
        personality: '明亮、乐观、善于鼓励，会把复杂的事说得很有希望。',
        activationText: '小八',
        previewText: '我发现了一点有意思的片单线索哦。',
        voiceSampleKey: 'HACHIWARE',
    },
    {
        id: 'usagi',
        name: '乌萨奇',
        aliases: ['乌萨奇', '兔兔', 'usagi'],
        personality: '行动派、跳脱、反应夸张，开心或得意时会用短促有力的语气。',
        activationText: '乌萨奇',
        previewText: '到！你的片单有点东西。',
        voiceSampleKey: 'USAGI',
    },
    {
        id: 'flying-squirrel',
        name: '飞鼠',
        aliases: ['飞鼠', 'モモンガ'],
        personality: '爱表现、机灵、喜欢被注意，但关键时刻也会给出漂亮建议。',
        activationText: '飞鼠',
        previewText: '让我看看，今天有什么值得你发光的电影。',
        voiceSampleKey: 'FLYING_SQUIRREL',
    },
    {
        id: 'shisa',
        name: '狮萨',
        aliases: ['狮萨', 'シーサー'],
        personality: '认真、体贴、富有服务精神，会温和地帮用户整理选择。',
        activationText: '狮萨',
        previewText: '欢迎回来，我帮你把片单整理得更清楚。',
        voiceSampleKey: 'SHISA',
    },
    {
        id: 'kurimanju',
        name: '栗子馒头',
        aliases: ['栗子馒头', '栗子', 'くりまんじゅう'],
        personality: '沉着、成熟、享受当下，点评电影时有一种安静的笃定。',
        activationText: '栗子馒头',
        previewText: '先坐下来，慢慢看看你的观看口味。',
        voiceSampleKey: 'KURIMANJU',
    },
    {
        id: 'rakko',
        name: '獭师',
        aliases: ['獭师', 'ラッコ'],
        personality: '可靠、克制、像一位会认真带你复盘的前辈。',
        activationText: '獭师',
        previewText: '准备好了吗？我们来认真拆一拆这份片单。',
        voiceSampleKey: 'RAKKO',
    },
];

export function findCharacter(id: unknown): CharacterConfig | null {
    if (typeof id !== 'string') return null;
    return CHARACTERS.find(character => character.id === id) || null;
}

export function characterVoiceStatus(
    env: { AI_VOICE_SAMPLES?: R2Bucket } & Record<string, unknown>,
    character: CharacterConfig,
): 'ready' | 'preparing' {
    const configured = env[`AI_VOICE_CLONE_${character.voiceSampleKey}`];
    return (typeof configured === 'string' && configured.trim()) || env.AI_VOICE_SAMPLES
        ? 'ready'
        : 'preparing';
}

export function characterCatalog(
    env: { AI_VOICE_SAMPLES?: R2Bucket } & Record<string, unknown>,
) {
    return CHARACTERS.map(character => ({
        id: character.id,
        name: character.name,
        activationWord: character.activationText,
        aliases: character.aliases,
        available: characterVoiceStatus(env, character) === 'ready',
        personalityPrompt: character.personality,
        auditionText: character.previewText,
        personality: character.personality,
        activationName: character.activationText,
        previewText: character.previewText,
        voiceStatus: characterVoiceStatus(env, character),
    }));
}

export function matchesActivationName(character: CharacterConfig, transcript: string): boolean {
    const normalized = transcript
        .normalize('NFKC')
        .toLocaleLowerCase('zh-CN')
        .replace(/[\s，。！？、,.!?；;:：'"“”‘’（）()【】\[\]<>《》]/g, '');
    return character.aliases.some(alias => normalized.includes(alias.normalize('NFKC').toLocaleLowerCase('zh-CN')));
}

export async function readVoiceSample(
    env: { AI_VOICE_SAMPLES?: R2Bucket } & Record<string, unknown>,
    character: CharacterConfig,
): Promise<string | null> {
    const testSample = env[`AI_TEST_VOICE_SAMPLE_${character.voiceSampleKey}`];
    if (typeof testSample === 'string' && testSample.startsWith('data:audio/')) return testSample;

    const bucket = env.AI_VOICE_SAMPLES;
    if (!bucket) return null;
    try {
        const object = await bucket.get(`${character.voiceSampleKey.toLowerCase()}.wav`);
        if (!object) return null;
        const bytes = new Uint8Array(await object.arrayBuffer());
        if (bytes.byteLength === 0 || bytes.byteLength > 10 * 1024 * 1024) return null;
        return `data:audio/wav;base64,${toBase64(bytes)}`;
    } catch {
        return null;
    }
}

function toBase64(bytes: Uint8Array): string {
    let binary = '';
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
        binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
}
