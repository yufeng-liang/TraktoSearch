/**
 * Auth middleware for /api/*
 *
 * 两条鉴权路径：
 * 1. 后台管理读取（GET 列表/详情、PATCH 状态更新）：页面在 Cloudflare Access
 *    之后，校验 Access JWT（Cf-Access-Jwt-Assertion 头或 CF_Authorization
 *    cookie）——用 Access 公钥（JWKS）验证签名与过期，防伪造凭据直连读取。
 *    前端无法持有服务端 CRASH_LOG_TOKEN，用 Access 凭据替代。
 * 2. 旧版客户端直连上报（POST）：兼容历史客户端，验证 CRASH_LOG_TOKEN。
 *    当前客户端已统一走 gateway -> auth-worker -> CRASH_LOGS KV。
 */

/** Access 签名公钥缓存（JWKS 轮换频率低，5 分钟 TTL 足够） */
let jwksCache = { fetchedAt: 0, keys: null };
const JWKS_TTL_MS = 5 * 60 * 1000;
const DEFAULT_TEAM_DOMAIN = 'douban-movie-api-peak';

const B64URL_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const B64URL_LOOKUP = (() => {
    const table = new Uint8Array(256).fill(0xff);
    for (let i = 0; i < B64URL_CHARS.length; i++) {
        table[B64URL_CHARS.charCodeAt(i)] = i;
    }
    return table;
})();

/** base64url 解码（不依赖 atob，Workers 与 Node 通用） */
function base64UrlToBytes(input) {
    let buffer = 0;
    let bits = 0;
    const out = [];
    for (let i = 0; i < input.length; i++) {
        const value = B64URL_LOOKUP[input.charCodeAt(i)];
        if (value === 0xff) continue; // 忽略填充与非法字符
        buffer = (buffer << 6) | value;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push((buffer >> bits) & 0xff);
        }
    }
    return new Uint8Array(out);
}

function decodeJsonSegment(segment) {
    const bytes = base64UrlToBytes(segment);
    return JSON.parse(new TextDecoder().decode(bytes));
}

/** 拉取 Access 公钥 JWKS（带模块级 TTL 缓存；失败返回 null 走 fail-closed） */
async function loadJwks(env) {
    const now = Date.now();
    if (jwksCache.keys && now - jwksCache.fetchedAt < JWKS_TTL_MS) {
        return jwksCache.keys;
    }
    const teamDomain = env.ACCESS_TEAM_DOMAIN || DEFAULT_TEAM_DOMAIN;
    const jwksUrl = env.ACCESS_JWKS_URL || `https://${teamDomain}.cloudflareaccess.com/cdn-cgi/access/certs`;
    try {
        const res = await fetch(jwksUrl);
        if (!res.ok) return null;
        const data = await res.json();
        if (!Array.isArray(data.keys)) return null;
        jwksCache = { fetchedAt: now, keys: data };
        return data;
    } catch {
        return null;
    }
}

/**
 * 验证 Access JWT：结构 / 签名算法 / 过期 / 签名（RS256 via JWKS）。
 * 可选 env.ACCESS_ISS 校验 issuer，防跨团队 token 混用。
 */
async function verifyAccessJwt(token, env) {
    if (!token) return false;
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const [headerB64, payloadB64, signatureB64] = parts;

    let header;
    let payload;
    try {
        header = decodeJsonSegment(headerB64);
        payload = decodeJsonSegment(payloadB64);
    } catch {
        return false;
    }

    // Access 使用 RS256；缺 kid 无法匹配公钥
    if (header.alg !== 'RS256' || !header.kid) return false;

    // 过期检查（exp 为秒级 Unix 时间）
    if (typeof payload.exp !== 'number' || payload.exp * 1000 <= Date.now()) return false;

    // 可选 issuer 校验
    const expectedIss = env.ACCESS_ISS;
    if (expectedIss && payload.iss !== expectedIss) return false;

    const jwks = await loadJwks(env);
    if (!jwks) return false;
    const key = jwks.keys.find(k => k.kid === header.kid && k.kty === 'RSA' && k.n && k.e);
    if (!key) return false;

    try {
        const cryptoKey = await crypto.subtle.importKey(
            'jwk',
            { kty: 'RSA', n: key.n, e: key.e, alg: 'RS256' },
            { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
            false,
            ['verify']
        );
        return await crypto.subtle.verify(
            'RSASSA-PKCS1-v1_5',
            cryptoKey,
            base64UrlToBytes(signatureB64),
            new TextEncoder().encode(`${headerB64}.${payloadB64}`)
        );
    } catch {
        return false;
    }
}

function readCookieValue(cookieHeader) {
    const match = (cookieHeader || '').match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
    if (!match) return null;
    try {
        return decodeURIComponent(match[1]);
    } catch {
        return null;
    }
}

function unauthorizedResponse() {
    return new Response(JSON.stringify({ error: 'Unauthorized' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
    });
}

export async function onRequest(context) {
    const { request, env } = context;
    const url = new URL(request.url);

    // 精确匹配 /api/crash-logs 与 /api/crash-logs/:id，避免前缀误伤其他路径
    const isCrashLogsPath =
        url.pathname === '/api/crash-logs' ||
        url.pathname.startsWith('/api/crash-logs/');

    if (isCrashLogsPath && (request.method === 'GET' || request.method === 'PATCH')) {
        // Access 认证通过后注入 Cf-Access-Jwt-Assertion；cookie 作兜底
        const accessJwt = request.headers.get('Cf-Access-Jwt-Assertion');
        const cookieToken = readCookieValue(request.headers.get('Cookie'));
        const token = accessJwt || cookieToken;
        if (!token || !(await verifyAccessJwt(token, env))) {
            return unauthorizedResponse();
        }
        return context.next();
    }

    const authHeader = request.headers.get('Authorization') || '';
    const token = authHeader.replace('Bearer ', '').trim();

    // API key 用常量时间比较，避免 === 短路的时序侧信道（照 auth-worker crypto.ts）
    if (!token || !timingSafeEqual(token, env.CRASH_LOG_TOKEN || '')) {
        return unauthorizedResponse();
    }

    return context.next();
}

/** 常量时间字符串比较（TextEncoder 版，不依赖 node:crypto）。 */
function timingSafeEqual(a, b) {
    const aBuf = new TextEncoder().encode(a);
    const bBuf = new TextEncoder().encode(b);
    const minLen = Math.min(aBuf.length, bBuf.length);
    let result = aBuf.length ^ bBuf.length;
    for (let i = 0; i < minLen; i++) {
        result |= aBuf[i] ^ bBuf[i];
    }
    return result === 0;
}
