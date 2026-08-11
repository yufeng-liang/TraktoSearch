// 音色设计 TTS 服务：负责 MP3 缓存、短时签名播放地址和同实例并发去重。

import { AppError } from '../util/errors.ts';
import { sha256 } from '../util/crypto.ts';
import { signAccessToken, verifyAccessToken } from '../util/jwt.ts';
import { callMimoAudio, type MimoEnvironment } from './mimo.ts';
import type { TtsScene } from './characters.ts';

export const TTS_CACHE_VERSION = 'tts-vd-v1';
export const TTS_PROMPT_VERSION = 'characters-v1';
export const TTS_AUDIO_TTL_SECONDS = 30 * 24 * 60 * 60;
export const TTS_AUDIO_URL_TTL_SECONDS = 10 * 60;

export interface TtsEnvironment extends MimoEnvironment {
    AI_AUDIO_CACHE?: R2Bucket;
    JWT_SIGNING_KEY?: string;
}

export interface TtsSynthesisInput {
    characterId: string;
    scene: TtsScene;
    text: string;
    voicePrompt: string;
    origin: string;
}

export interface TtsPublicAudio {
    audioDataUrl: string | null;
    audioUrl: string | null;
    mimeType: 'audio/mpeg';
    durationMs: number | null;
    cacheKey: string;
    transcript: string | null;
}

interface TtsResolution {
    cacheKey: string;
    audioDataUrl: string | null;
    transcript: string | null;
    stored: boolean;
}

const inFlight = new Map<string, Promise<TtsResolution | null>>();

export async function ttsCacheKey(input: Pick<TtsSynthesisInput, 'characterId' | 'scene' | 'text' | 'voicePrompt'>): Promise<string> {
    const normalizedText = normalizeSpokenText(input.text).normalize('NFKC');
    const digest = await sha256(JSON.stringify({
        version: TTS_CACHE_VERSION,
        promptVersion: TTS_PROMPT_VERSION,
        characterId: input.characterId,
        scene: input.scene,
        text: normalizedText,
        format: 'mp3',
        voicePrompt: input.voicePrompt,
    }));
    return `${TTS_CACHE_VERSION}/${digest}.mp3`;
}

export async function readCachedTtsAudio(
    env: TtsEnvironment,
    input: TtsSynthesisInput,
): Promise<TtsPublicAudio | null> {
    const bucket = env.AI_AUDIO_CACHE;
    if (!bucket) return null;
    const cacheKey = await ttsCacheKey(input);
    let object: R2Object | null = null;
    try {
        object = await bucket.head(cacheKey);
    } catch {
        return null;
    }
    if (!object) return null;
    return await publicAudioFromResolution(env, input, {
        cacheKey,
        audioDataUrl: null,
        transcript: readTranscript(object.customMetadata),
        stored: true,
    });
}

export async function synthesizeTtsAudio(
    env: TtsEnvironment,
    input: TtsSynthesisInput,
): Promise<TtsPublicAudio | null> {
    const cacheKey = await ttsCacheKey(input);
    const cached = await readCachedTtsAudio(env, input);
    if (cached) return cached;

    const existing = inFlight.get(cacheKey);
    if (existing) {
        return toPublicAudio(await existing, env, input);
    }

    const promise = synthesizeUncached(env, input, cacheKey);
    inFlight.set(cacheKey, promise);
    try {
        return toPublicAudio(await promise, env, input);
    } finally {
        if (inFlight.get(cacheKey) === promise) inFlight.delete(cacheKey);
    }
}

export async function handleAiAudio(
    request: Request,
    env: TtsEnvironment,
    token: string,
): Promise<Response> {
    if (request.method !== 'GET') {
        throw new AppError('NOT_FOUND', 'Not found', 404);
    }
    const secret = typeof env.JWT_SIGNING_KEY === 'string' ? env.JWT_SIGNING_KEY.trim() : '';
    const payload = secret ? await verifyAccessToken(secret, token) : null;
    if (
        !payload
        || payload.sub !== 'audio'
        || !Array.isArray(payload.scope)
        || !payload.scope.includes('audio')
        || typeof payload.device !== 'string'
        || !/^tts-vd-v1\/[a-f0-9]{64}\.mp3$/.test(payload.device)
    ) {
        throw new AppError('INVALID_AUDIO_TOKEN', 'Invalid or expired audio token', 403);
    }

    const bucket = env.AI_AUDIO_CACHE;
    if (!bucket) throw new AppError('AUDIO_NOT_FOUND', 'Audio is not available', 404);
    let object: R2ObjectBody | null = null;
    try {
        object = await bucket.get(payload.device);
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'Audio storage is unavailable', 503);
    }
    if (!object || !object.body) throw new AppError('AUDIO_NOT_FOUND', 'Audio is not available', 404);

    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set('Content-Type', 'audio/mpeg');
    headers.set('Cache-Control', `private, max-age=${TTS_AUDIO_URL_TTL_SECONDS}`);
    headers.set('ETag', object.httpEtag);
    return new Response(object.body, { status: 200, headers });
}

async function synthesizeUncached(
    env: TtsEnvironment,
    input: TtsSynthesisInput,
    cacheKey: string,
): Promise<TtsResolution | null> {
    const bucket = env.AI_AUDIO_CACHE;
    if (bucket) {
        try {
            const object = await bucket.head(cacheKey);
            if (object) {
                return {
                    cacheKey,
                    audioDataUrl: null,
                    transcript: readTranscript(object.customMetadata),
                    stored: true,
                };
            }
        } catch {
            // R2 读取异常时继续请求 MiMo，音频仍可通过 data URL 播放。
        }
    }

    const audio = await callMimoAudio(env, 'mimo-v2.5-tts-voicedesign', [
        { role: 'user', content: input.voicePrompt },
        { role: 'assistant', content: normalizeSpokenText(input.text) },
    ], { format: 'mp3', optimizeTextPreview: false });
    if (!audio) return null;

    const audioDataUrl = toAudioDataUrl(audio.data, audio.mimeType);
    const bytes = decodeAudioDataUrl(audioDataUrl);
    if (bucket) {
        try {
            const putResult = await bucket.put(cacheKey, bytes, {
                httpMetadata: {
                    contentType: 'audio/mpeg',
                    cacheControl: `private, max-age=${TTS_AUDIO_TTL_SECONDS}`,
                },
                customMetadata: audio.transcript ? { transcript: audio.transcript.slice(0, 1000) } : undefined,
            });
            if (putResult) {
                return {
                    cacheKey,
                    audioDataUrl: null,
                    transcript: audio.transcript,
                    stored: true,
                };
            }
        } catch {
            // 缓存故障不应阻断本次播放，回退到 MiMo 返回的数据 URL。
        }
    }
    return {
        cacheKey,
        audioDataUrl,
        transcript: audio.transcript,
        stored: false,
    };
}

async function toPublicAudio(
    resolution: TtsResolution | null,
    env: TtsEnvironment,
    input: TtsSynthesisInput,
): Promise<TtsPublicAudio | null> {
    if (!resolution) return null;
    return publicAudioFromResolution(env, input, resolution);
}

async function publicAudioFromResolution(
    env: TtsEnvironment,
    input: TtsSynthesisInput,
    resolution: TtsResolution,
): Promise<TtsPublicAudio> {
    if (resolution.stored && env.JWT_SIGNING_KEY) {
        const token = await signAccessToken(
            env.JWT_SIGNING_KEY,
            'audio',
            resolution.cacheKey,
            ['audio'],
            TTS_AUDIO_URL_TTL_SECONDS,
        );
        return {
            audioDataUrl: null,
            audioUrl: `${input.origin}/api/ai/audio/${token}`,
            mimeType: 'audio/mpeg',
            durationMs: null,
            cacheKey: resolution.cacheKey,
            transcript: resolution.transcript,
        };
    }
    if (resolution.stored && env.AI_AUDIO_CACHE) {
        try {
            const object = await env.AI_AUDIO_CACHE.get(resolution.cacheKey);
            if (object) {
                return {
                    audioDataUrl: toAudioDataUrl(await object.arrayBuffer(), 'audio/mpeg'),
                    audioUrl: null,
                    mimeType: 'audio/mpeg',
                    durationMs: null,
                    cacheKey: resolution.cacheKey,
                    transcript: resolution.transcript,
                };
            }
        } catch {
            // 继续返回空音频，由调用方决定是否展示文字或允许重试。
        }
    }
    return {
        audioDataUrl: resolution.audioDataUrl,
        audioUrl: null,
        mimeType: 'audio/mpeg',
        durationMs: null,
        cacheKey: resolution.cacheKey,
        transcript: resolution.transcript,
    };
}

function normalizeSpokenText(text: string): string {
    return text.trim().replace(/\s+/g, ' ');
}

function toAudioDataUrl(data: string | ArrayBuffer, mimeType: string): string {
    if (typeof data === 'string' && data.startsWith('data:')) return data;
    if (typeof data === 'string') return `data:${mimeType};base64,${data}`;
    return `data:${mimeType};base64,${toBase64(new Uint8Array(data))}`;
}

function decodeAudioDataUrl(dataUrl: string): Uint8Array {
    const comma = dataUrl.indexOf(',');
    if (comma <= 0 || !dataUrl.slice(0, comma).toLowerCase().includes(';base64')) {
        throw new AppError('INVALID_AI_OUTPUT', 'AI audio response is invalid', 502);
    }
    try {
        const binary = atob(dataUrl.slice(comma + 1));
        return Uint8Array.from(binary, character => character.charCodeAt(0));
    } catch {
        throw new AppError('INVALID_AI_OUTPUT', 'AI audio response is invalid', 502);
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

function readTranscript(metadata: Record<string, string> | undefined): string | null {
    const transcript = metadata?.transcript;
    return typeof transcript === 'string' && transcript.trim() ? transcript : null;
}
