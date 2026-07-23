// 稳定错误码（ViewModel 内部用英文，UI 通过 stringResource 映射）
export const ErrorCode = {
    // 激活相关
    INVALID_INVITE: 'INVALID_INVITE',
    INVITE_EXPIRED: 'INVITE_EXPIRED',
    INVITE_REVOKED: 'INVITE_REVOKED',
    INVITE_ALREADY_USED: 'INVITE_ALREADY_USED',
    DEVICE_LIMIT_REACHED: 'DEVICE_LIMIT_REACHED',
    DEVICE_ALREADY_BOUND: 'DEVICE_ALREADY_BOUND',
    MIGRATION_DEVICE_NOT_FOUND: 'MIGRATION_DEVICE_NOT_FOUND',
    MIGRATION_DEVICE_MISMATCH: 'MIGRATION_DEVICE_MISMATCH',
    FRIEND_DISABLED: 'FRIEND_DISABLED',

    // 令牌相关
    INVALID_TOKEN: 'INVALID_TOKEN',
    TOKEN_EXPIRED: 'TOKEN_EXPIRED',
    TOKEN_REVOKED: 'TOKEN_REVOKED',
    REFRESH_REPLAY: 'REFRESH_REPLAY',
    INVALID_SIGNATURE: 'INVALID_SIGNATURE',

    // 设备相关
    DEVICE_REVOKED: 'DEVICE_REVOKED',
    DEVICE_NOT_FOUND: 'DEVICE_NOT_FOUND',

    // 权限相关
    UNAUTHORIZED: 'UNAUTHORIZED',
    FORBIDDEN: 'FORBIDDEN',

    // 通用
    INTERNAL_ERROR: 'INTERNAL_ERROR',
    RATE_LIMITED: 'RATE_LIMITED',
} as const;

export class AppError extends Error {
    constructor(
        public code: string,
        message: string,
        public statusCode: number = 400
    ) {
        super(message);
        this.name = 'AppError';
    }
}

// 统一响应格式
export interface ApiResponse<T = unknown> {
    code: string;
    message: string;
    requestId: string;
    data?: T;
}

export function successResponse<T>(data: T, requestId: string): Response {
    const body: ApiResponse<T> = {
        code: 'SUCCESS',
        message: 'OK',
        requestId,
        data,
    };
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

export function errorResponse(error: AppError, requestId: string): Response {
    const body: ApiResponse = {
        code: error.code,
        message: error.message,
        requestId,
    };
    return new Response(JSON.stringify(body), {
        status: error.statusCode,
        headers: { 'Content-Type': 'application/json' },
    });
}

// 生成请求追踪 ID
export function generateRequestId(): string {
    return crypto.randomUUID();
}

// 当前时间（Unix 秒）
export function now(): number {
    return Math.floor(Date.now() / 1000);
}
