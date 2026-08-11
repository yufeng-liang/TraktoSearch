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
    RECOVERY_NOT_FOUND: 'RECOVERY_NOT_FOUND',
    RECOVERY_AMBIGUOUS: 'RECOVERY_AMBIGUOUS',
    RECOVERY_REJECTED: 'RECOVERY_REJECTED',

    // 权限相关
    UNAUTHORIZED: 'UNAUTHORIZED',
    FORBIDDEN: 'FORBIDDEN',

    // 通用
    INTERNAL_ERROR: 'INTERNAL_ERROR',
    RATE_LIMITED: 'RATE_LIMITED',
} as const;

export class AppError extends Error {
    public readonly code: string;
    public readonly statusCode: number;

    constructor(
        code: string,
        message: string,
        statusCode: number = 400
    ) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.name = 'AppError';
    }
}

/**
 * Worker 在异步边界或模块边界捕获错误时，不能只依赖 instanceof。
 * 保留结构化检查，确保业务错误不会被误报成 INTERNAL_ERROR。
 */
export function isAppError(error: unknown): error is AppError {
    if (error instanceof AppError) return true;
    if (!error || typeof error !== 'object') return false;
    const candidate = error as Partial<AppError> & { name?: unknown };
    return candidate.name === 'AppError'
        && typeof candidate.code === 'string'
        && typeof candidate.statusCode === 'number';
}

// 统一响应格式
export interface ApiResponse<T = unknown> {
    code: string;
    message: string;
    requestId: string;
    data?: T;
}

export function successResponse<T>(data: T, requestId: string, quota?: unknown): Response {
    const body: ApiResponse<T> = {
        code: 'SUCCESS',
        message: 'OK',
        requestId,
        data,
        ...(quota !== undefined ? { quota } : {}),
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
