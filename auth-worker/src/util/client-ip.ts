// 客户端 IP / 地理信息提取
//
// 背景：gateway-pages 通过 service binding 转发请求时，Cloudflare 会用边缘节点
// 出口 IP 覆盖 CF-Connecting-IP，request.cf 也会反映 gateway 的边缘节点而非原始
// 客户端。因此 gateway 在转发前把原始 CF-Connecting-IP 写入 X-Real-IP，并把
// request.cf 的地理字段序列化到 X-Client-Geo。
//
// 安全约束：X-Real-IP / X-Client-Geo 只有来自 gateway.internal 的 service binding
// 请求才可信任。auth-worker 的 workers.dev / 自定义域名是公开入口，客户端可以自带
// 这两个头，公开入口必须回退到 Cloudflare 注入的 CF-Connecting-IP / request.cf。

const GATEWAY_HOSTNAME = 'gateway.internal';

export interface ClientGeo {
    country?: string;
    region?: string;
    city?: string;
    latitude?: string;
    longitude?: string;
    asOrganization?: string;
}

// 请求是否来自可信的 gateway service binding
function isTrustedGatewayRequest(request: Request): boolean {
    try {
        return new URL(request.url).hostname === GATEWAY_HOSTNAME;
    } catch {
        return false;
    }
}

// 取客户端 IP；取不到返回空串，调用方自行决定兜底值。
// 直接公网访问时忽略客户端自带的 X-Real-IP，避免伪造。
export function clientIp(request: Request): string {
    const forwardedIp = isTrustedGatewayRequest(request) ? request.headers.get('X-Real-IP') : null;
    return forwardedIp || request.headers.get('CF-Connecting-IP') || '';
}

// 取客户端地理信息；不可用时返回 null。
// X-Client-Geo 同样只在 gateway 转发路径下采信，解析失败回退 request.cf。
export function clientGeo(request: Request): ClientGeo | null {
    if (isTrustedGatewayRequest(request)) {
        const raw = request.headers.get('X-Client-Geo');
        if (raw) {
            try {
                const parsed = JSON.parse(raw) as Record<string, unknown>;
                return {
                    country: readString(parsed.country),
                    region: readString(parsed.region),
                    city: readString(parsed.city),
                    latitude: readString(parsed.latitude),
                    longitude: readString(parsed.longitude),
                    asOrganization: readString(parsed.asOrganization),
                };
            } catch {
                // 头损坏时回退到 request.cf
            }
        }
    }
    return (request as { cf?: ClientGeo | null }).cf ?? null;
}

function readString(value: unknown): string | undefined {
    return typeof value === 'string' && value.length > 0 ? value : undefined;
}
