// AI 精灵角色配置。音色样本只允许由 Worker 私有绑定读取。

export type CharacterId =
    | 'chiikawa'
    | 'hachiware'
    | 'usagi'
    | 'flying-squirrel'
    | 'shisa'
    | 'kurimanju'
    | 'rakko';

export const TTS_SCENES = ['AUDITION', 'ACTIVATION_ACK', 'GREETING'] as const;
export type TtsScene = typeof TTS_SCENES[number];

export interface CharacterConfig {
    id: CharacterId;
    name: string;
    aliases: string[];
    personality: string;
    activationText: string;
    previewText: string;
    voiceSampleKey: string;
    voiceDesignPrompt: string;
    sceneGuidance: Record<TtsScene, string>;
    activationPhrase: string;
    greetingCatchphrase: string;
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
        voiceDesignPrompt: '细小、柔软、明亮的中高音；温柔认真，带一点害羞和紧张，亲近而清楚。',
        sceneGuidance: {
            AUDITION: '像第一次自我介绍，轻声、慢半拍，句尾温柔上扬。',
            ACTIVATION_ACK: '先犹豫后坚定，短而清楚。',
            GREETING: '真诚、轻柔、有陪伴感。',
        },
        activationPhrase: '到、到！',
        greetingCatchphrase: '一起看吧。',
    },
    {
        id: 'hachiware',
        name: '小八',
        aliases: ['小八', 'hachiware'],
        personality: '明亮、乐观、善于鼓励，会把复杂的事说得很有希望。',
        activationText: '小八',
        previewText: '我发现了一点有意思的片单线索哦。',
        voiceSampleKey: 'HACHIWARE',
        voiceDesignPrompt: '清澈明亮的中高音；友善乐观，带自然笑意和鼓励感，不吵闹。',
        sceneGuidance: {
            AUDITION: '像发现片单线索后开心分享，节奏明快，关键词自然上扬。',
            ACTIVATION_ACK: '明亮积极，像马上来帮忙。',
            GREETING: '热情分享，语气自然上扬。',
        },
        activationPhrase: '到！我在哦！',
        greetingCatchphrase: '对吧！',
    },
    {
        id: 'usagi',
        name: '乌萨奇',
        aliases: ['乌萨奇', '兔兔', 'usagi'],
        personality: '行动派、跳脱、反应夸张，开心或得意时会用短促有力的语气。',
        activationText: '乌萨奇',
        previewText: '到！你的片单有点东西。',
        voiceSampleKey: 'USAGI',
        voiceDesignPrompt: '跳跃、活泼、爆发力强的明亮中高音；短促有力，情绪反应夸张，但不能持续尖叫。',
        sceneGuidance: {
            AUDITION: '像突然发现好电影，重点词有冲击力，结尾可有轻快口癖但不能遮住正文。',
            ACTIVATION_ACK: '明显提高音量并持续拉长“到”，随后快速收束；音量受控，不失真。',
            GREETING: '开心处抬高音量，短促有冲劲。',
        },
        activationPhrase: '到——！',
        greetingCatchphrase: '呀哈！',
    },
    {
        id: 'flying-squirrel',
        name: '飞鼠',
        aliases: ['飞鼠', 'モモンガ'],
        personality: '爱表现、机灵、喜欢被注意，但关键时刻也会给出漂亮建议。',
        activationText: '飞鼠',
        previewText: '让我看看，今天有什么值得你发光的电影。',
        voiceSampleKey: 'FLYING_SQUIRREL',
        voiceDesignPrompt: '甜、轻快、机灵的中高音；带撒娇、爱表现和得意感，尾音灵动，避免尖锐。',
        sceneGuidance: {
            AUDITION: '像抢着展示自己的推荐，轻快、得意，句尾上扬。',
            ACTIVATION_ACK: '带一点撒娇和得意，尾音上扬。',
            GREETING: '像在展示自己的发现，语气灵动。',
        },
        activationPhrase: '听到啦！',
        greetingCatchphrase: '嘿嘿。',
    },
    {
        id: 'shisa',
        name: '狮萨',
        aliases: ['狮萨', 'シーサー'],
        personality: '认真、体贴、富有服务精神，会温和地帮用户整理选择。',
        activationText: '狮萨',
        previewText: '欢迎回来，我帮你把片单整理得更清楚。',
        voiceSampleKey: 'SHISA',
        voiceDesignPrompt: '温暖、稳妥、清晰的中音；认真体贴，有自然服务感，不夸张。',
        sceneGuidance: {
            AUDITION: '像服务台欢迎用户，礼貌、均匀、有条理。',
            ACTIVATION_ACK: '温和清楚，立即响应。',
            GREETING: '给用户安心感，表达清楚细致。',
        },
        activationPhrase: '到，我在。',
        greetingCatchphrase: '慢慢来就好。',
    },
    {
        id: 'kurimanju',
        name: '栗子馒头',
        aliases: ['栗子馒头', '栗子', 'くりまんじゅう'],
        personality: '沉着、成熟、享受当下，点评电影时有一种安静的笃定。',
        activationText: '栗子馒头',
        previewText: '先坐下来，慢慢看看你的观看口味。',
        voiceSampleKey: 'KURIMANJU',
        voiceDesignPrompt: '成熟、松弛的中低音；沉着、满足、略慵懒但不冷漠，停顿自然。',
        sceneGuidance: {
            AUDITION: '像坐下后慢慢聊电影，语速从容，评价词略有重量。',
            ACTIVATION_ACK: '低声从容，保留自然停顿。',
            GREETING: '像陪伴式低声旁白，语气安静。',
        },
        activationPhrase: '嗯，到。',
        greetingCatchphrase: '嗯。',
    },
    {
        id: 'rakko',
        name: '獭师',
        aliases: ['獭师', 'ラッコ'],
        personality: '可靠、克制、像一位会认真带你复盘的前辈。',
        activationText: '獭师',
        previewText: '准备好了吗？我们来认真拆一拆这份片单。',
        voiceSampleKey: 'RAKKO',
        voiceDesignPrompt: '可靠、克制的中低音；沉着、耐心，像温和而有权威感的前辈，不说教。',
        sceneGuidance: {
            AUDITION: '像开始一次认真复盘，清晰稳健，句尾落地。',
            ACTIVATION_ACK: '沉稳有力，节奏干净。',
            GREETING: '鼓励用户一步一步选择，语气稳定。',
        },
        activationPhrase: '到。开始吧。',
        greetingCatchphrase: '一步一步来。',
    },
];

export function findCharacter(id: unknown): CharacterConfig | null {
    if (typeof id !== 'string') return null;
    return CHARACTERS.find(character => character.id === id) || null;
}

export async function characterVoiceStatus(
    env: { AI_VOICE_SAMPLES?: R2Bucket } & Record<string, unknown>,
    character: CharacterConfig,
): Promise<'ready' | 'preparing'> {
    void character;
    if (isTestMode(env) && env.AI_TEST_VOICE_DESIGN_READY === true) return 'ready';
    return typeof env.MIMO_API_KEY === 'string' && env.MIMO_API_KEY.trim()
        ? 'ready'
        : 'preparing';
}

export async function characterCatalog(
    env: { AI_VOICE_SAMPLES?: R2Bucket } & Record<string, unknown>,
): Promise<Array<Record<string, unknown>>> {
    return Promise.all(CHARACTERS.map(async character => {
        const voiceStatus = await characterVoiceStatus(env, character);
        return {
            id: character.id,
            name: character.name,
            activationWord: character.activationText,
            aliases: character.aliases,
            available: voiceStatus === 'ready',
            personalityPrompt: character.personality,
            auditionText: character.previewText,
            personality: character.personality,
            activationName: character.activationText,
            previewText: character.previewText,
            voiceStatus,
        };
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
    const testSample = isTestMode(env)
        ? env[`AI_TEST_VOICE_SAMPLE_${character.voiceSampleKey}`]
        : undefined;
    if (isUsableVoiceSample(testSample)) return testSample;

    const bucket = env.AI_VOICE_SAMPLES;
    if (!bucket) return null;
    try {
        const object = await bucket.get(voiceObjectKey(character));
        if (!object) return null;
        const bytes = new Uint8Array(await object.arrayBuffer());
        if (bytes.byteLength === 0 || bytes.byteLength > 10 * 1024 * 1024) return null;
        return `data:audio/wav;base64,${toBase64(bytes)}`;
    } catch {
        return null;
    }
}

function voiceObjectKey(character: CharacterConfig): string {
    return `${character.voiceSampleKey.toLowerCase()}.wav`;
}

function isUsableVoiceSample(value: unknown): value is string {
    return typeof value === 'string' && /^data:audio\/[\w.+-]+;base64,[A-Za-z0-9+/=]+$/i.test(value);
}

function isTestMode(env: Record<string, unknown>): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

function toBase64(bytes: Uint8Array): string {
    let binary = '';
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
        binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
}
