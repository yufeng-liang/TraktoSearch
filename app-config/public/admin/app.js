// ===== Theme management =====
const Theme = {
    toggle() {
        const html = document.documentElement;
        const current = html.dataset.theme;
        const next = current === 'dark' ? 'light' : 'dark';
        html.dataset.theme = next;
        localStorage.setItem('tts-theme', next);
        this.updateIcon(next);
    },

    init() {
        const saved = localStorage.getItem('tts-theme');
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        const theme = saved || (prefersDark ? 'dark' : 'light');
        document.documentElement.dataset.theme = theme;
        this.updateIcon(theme);

        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
            if (!localStorage.getItem('tts-theme')) {
                const t = e.matches ? 'dark' : 'light';
                document.documentElement.dataset.theme = t;
                this.updateIcon(t);
            }
        });
    },

    updateIcon(theme) {
        const sun = document.querySelector('.theme-icon-sun');
        const moon = document.querySelector('.theme-icon-moon');
        if (theme === 'dark') { sun.hidden = false; moon.hidden = true; }
        else { sun.hidden = true; moon.hidden = false; }
        // 手机状态栏/浏览器工具栏跟着主题走，避免深色页面配浅色状态栏
        const meta = document.getElementById('themeColorMeta');
        if (meta) meta.setAttribute('content', theme === 'light' ? '#e4e3e0' : '#141821');
    }
};

// ===== Palette system =====
const Palettes = {
    presets: {
        indigo: {
            name: '靛蓝',
            accent: '#7c8cf8',
            accentHover: '#a0abfa',
            accentSoft: 'rgba(124, 140, 248, 0.15)',
            ambient1: '#6366f1',
            ambient2: '#8b5cf6',
            ambient3: '#a78bfa',
        },
        emerald: {
            name: '翠绿',
            accent: '#34d399',
            accentHover: '#6ee7b7',
            accentSoft: 'rgba(52, 211, 153, 0.15)',
            ambient1: '#10b981',
            ambient2: '#34d399',
            ambient3: '#6ee7b7',
        },
        rose: {
            name: '玫瑰',
            accent: '#fb7185',
            accentHover: '#fda4af',
            accentSoft: 'rgba(251, 113, 133, 0.15)',
            ambient1: '#f43f5e',
            ambient2: '#fb7185',
            ambient3: '#fda4af',
        },
        amber: {
            name: '琥珀',
            accent: '#fbbf24',
            accentHover: '#fcd34d',
            accentSoft: 'rgba(251, 191, 36, 0.15)',
            ambient1: '#f59e0b',
            ambient2: '#fbbf24',
            ambient3: '#fcd34d',
        },
        // 莫奈印象派系列（中等饱和）
        'monet-lilies': {
            name: '睡莲',
            accent: '#d88890',
            accentHover: '#e8a8b0',
            accentSoft: 'rgba(216, 136, 144, 0.18)',
            ambient1: '#7bc4a0',
            ambient2: '#e0b8d0',
            ambient3: '#98d4d4',
        },
        'monet-sunrise': {
            name: '日出',
            accent: '#e08058',
            accentHover: '#f0a080',
            accentSoft: 'rgba(224, 128, 88, 0.18)',
            ambient1: '#e87850',
            ambient2: '#e8b080',
            ambient3: '#b8a0d0',
        },
        'monet-garden': {
            name: '花园',
            accent: '#d88898',
            accentHover: '#e8a8b8',
            accentSoft: 'rgba(216, 136, 152, 0.18)',
            ambient1: '#d07888',
            ambient2: '#a0c880',
            ambient3: '#e8d4b0',
        },
        'monet-haystack': {
            name: '干草堆',
            accent: '#d8b068',
            accentHover: '#e8c888',
            accentSoft: 'rgba(216, 176, 104, 0.18)',
            ambient1: '#d8b870',
            ambient2: '#c0a0b0',
            ambient3: '#e8d8a0',
        },
        'monet-bridge': {
            name: '日本桥',
            accent: '#78a888',
            accentHover: '#98c4a8',
            accentSoft: 'rgba(120, 168, 136, 0.18)',
            ambient1: '#689878',
            ambient2: '#b0a0c8',
            ambient3: '#c8b090',
        },
    },

    current: 'indigo',

    init() {
        const saved = localStorage.getItem('tts-palette');
        if (saved && this.presets[saved]) this.current = saved;
        this.apply(this.current);
        this.renderPanel();
        this.bindEvents();
    },

    apply(key) {
        const p = this.presets[key];
        if (!p) return;
        const root = document.documentElement;
        root.style.setProperty('--accent', p.accent);
        root.style.setProperty('--accent-hover', p.accentHover);
        root.style.setProperty('--accent-soft', p.accentSoft);
        root.style.setProperty('--ambient-1', p.ambient1);
        root.style.setProperty('--ambient-2', p.ambient2);
        root.style.setProperty('--ambient-3', p.ambient3);
        this.current = key;
        localStorage.setItem('tts-palette', key);
        this.updatePanelActive();
        this.updateCurrentSwatch();
    },

    updateCurrentSwatch() {
        const el = document.getElementById('paletteCurrent');
        if (el) el.style.background = this.presets[this.current].accent;
    },

    renderPanel() {
        const panel = document.getElementById('palettePanel');
        if (!panel) return;

        const basicKeys = ['indigo', 'emerald', 'rose', 'amber'];
        const monetKeys = Object.keys(this.presets).filter(k => k.startsWith('monet-'));

        const renderSwatch = (key, p) => `
            <button class="palette-swatch${key === this.current ? ' active' : ''}" data-palette="${key}" aria-pressed="${key === this.current}">
                <div class="palette-swatch-colors">
                    <span class="palette-swatch-dot" style="background:${p.accent}"></span>
                    <span class="palette-swatch-dot" style="background:${p.ambient1}"></span>
                    <span class="palette-swatch-dot" style="background:${p.ambient2}"></span>
                </div>
                <span class="palette-swatch-name">${p.name}</span>
            </button>
        `;

        panel.innerHTML = `
            <div class="palette-title">基础配色</div>
            <div class="palette-grid">
                ${basicKeys.map(k => renderSwatch(k, this.presets[k])).join('')}
            </div>
            <div class="palette-divider"></div>
            <div class="palette-title">莫奈印象</div>
            <div class="palette-grid">
                ${monetKeys.map(k => renderSwatch(k, this.presets[k])).join('')}
            </div>
        `;
    },

    updatePanelActive() {
        document.querySelectorAll('.palette-swatch').forEach(el => {
            const active = el.dataset.palette === this.current;
            el.classList.toggle('active', active);
            el.setAttribute('aria-pressed', String(active));
        });
    },

    bindEvents() {
        const btn = document.getElementById('paletteBtn');
        const panel = document.getElementById('palettePanel');
        if (!btn || !panel) return;

        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            panel.hidden = !panel.hidden;
            btn.setAttribute('aria-expanded', String(!panel.hidden));
        });

        panel.addEventListener('click', (e) => {
            const swatch = e.target.closest('.palette-swatch');
            if (swatch) {
                this.apply(swatch.dataset.palette);
                panel.hidden = true;
                btn.setAttribute('aria-expanded', 'false');
            }
        });

        document.addEventListener('click', (e) => {
            if (!panel.contains(e.target) && !btn.contains(e.target)) {
                panel.hidden = true;
                btn.setAttribute('aria-expanded', 'false');
            }
        });
    },
};

// ===== API Client =====
// API 根域名（部署时替换为实际 Worker 域名）
const API_BASE = `${window.location.origin}/admin-api`;
const FEEDBACK_SCREENSHOT_BASE_URL = `${API_BASE}/fb/feedback-api/screenshot`;
const REQUEST_TIMEOUT_MS = 15000;
const activeRequestControllers = new Set();

function cancelPendingRequests() {
    activeRequestControllers.forEach((controller) => controller.abort());
    activeRequestControllers.clear();
}

const API = {
    // Access JWT 优先读取当前域名 Cookie，避免登录切换后复用旧令牌。
    getToken() {
        const match = document.cookie.match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
        if (match) {
            const token = decodeURIComponent(match[1]);
            localStorage.setItem('tts-access-token', token);
            return token;
        }
        return localStorage.getItem('tts-access-token');
    },

    // 通用请求
    async request(method, path, body = null) {
        const token = this.getToken();
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const controller = new AbortController();
        activeRequestControllers.add(controller);
        let timedOut = false;
        const timeoutId = setTimeout(() => {
            timedOut = true;
            controller.abort();
        }, REQUEST_TIMEOUT_MS);
        let response;
        try {
            response = await fetch(`${API_BASE}${path}`, {
                method,
                headers,
                body: body ? JSON.stringify(body) : null,
                signal: controller.signal,
            });
        } catch (error) {
            if (error?.name === 'AbortError') {
                if (!timedOut) {
                    const cancelledError = new Error('Request cancelled');
                    cancelledError.code = 'REQUEST_CANCELLED';
                    throw cancelledError;
                }
                const timeoutError = new Error('Request timed out');
                timeoutError.code = 'REQUEST_TIMEOUT';
                throw timeoutError;
            }
            throw error;
        } finally {
            clearTimeout(timeoutId);
            activeRequestControllers.delete(controller);
        }

        // 401 → Access 登录失效，跳转登录
        if (response.status === 401) {
            localStorage.removeItem('tts-access-token');
            window.location.href = this.getAccessLoginUrl();
            throw new Error('UNAUTHORIZED');
        }
        if (response.redirected && response.url.includes('/cdn-cgi/access/login')) {
            localStorage.removeItem('tts-access-token');
            window.location.href = this.getAccessLoginUrl();
            throw new Error('UNAUTHORIZED');
        }

        const contentType = response.headers.get('content-type') || '';
        const data = contentType.includes('application/json') ? await response.json() : null;

        if (!response.ok) {
            const error = new Error(data?.message || `Request failed (${response.status})`);
            error.code = data?.code;
            throw error;
        }
        if (!data || !Object.prototype.hasOwnProperty.call(data, 'data')) {
            throw new Error('Invalid server response');
        }

        return data.data;
    },

    get(path) { return this.request('GET', path); },
    post(path, body) { return this.request('POST', path, body); },
    patch(path, body) { return this.request('PATCH', path, body); },

    // 上传截图（multipart/form-data，不走通用 request）
    async uploadScreenshot(path, file) {
        const token = this.getToken();
        const headers = {};
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const controller = new AbortController();
        activeRequestControllers.add(controller);
        const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

        try {
            const formData = new FormData();
            formData.append('file', file);
            const response = await fetch(`${API_BASE}${path}`, {
                method: 'POST',
                headers,
                body: formData,
                signal: controller.signal,
            });
            if (response.status === 401) {
                localStorage.removeItem('tts-access-token');
                window.location.href = this.getAccessLoginUrl();
                throw new Error('UNAUTHORIZED');
            }
            const data = await response.json();
            if (!response.ok) {
                throw new Error(data?.message || `Upload failed (${response.status})`);
            }
            return data.data;  // { key: 'admin/xxx.jpg' }
        } finally {
            clearTimeout(timeoutId);
            activeRequestControllers.delete(controller);
        }
    },

    // Access 登录 URL（替换为实际 team domain）
    getAccessLoginUrl() {
        const teamDomain = localStorage.getItem('tts-access-team') || 'douban-movie-api-peak';
        return `https://${teamDomain}.cloudflareaccess.com/cdn-cgi/access/login`;
    },

    // === Admin API ===

    async getStats() {
        const health = await this.get('/admin/health');
        return {
            totalFriends: health.stats?.active_friends || 0,
            activeDevices: health.stats?.active_devices || 0,
            recentFailures: health.stats?.recent_failures || 0,
            gatewayHealth: health.status === 'ok' ? 'ok' : 'degraded',
        };
    },

    async getAiHealth(window = '24h') {
        return this.get(`/admin/ai/health?window=${encodeURIComponent(window)}`);
    },

    async probeAiHealth(body) {
        // 探针可能比常规请求慢（最长 10s 超时），用更长超时直连 fetch
        const token = this.getToken();
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;
        const controller = new AbortController();
        let timedOut = false;
        const timeoutId = setTimeout(() => {
            timedOut = true;
            controller.abort();
        }, 20000);
        activeRequestControllers.add(controller);
        try {
            const response = await fetch(`${API_BASE}/admin/ai/health/probe`, {
                method: 'POST', headers,
                body: JSON.stringify(body), signal: controller.signal,
            });
            if (response.status === 401) {
                localStorage.removeItem('tts-access-token');
                window.location.href = this.getAccessLoginUrl();
                throw new Error('UNAUTHORIZED');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data?.message || `Probe failed (${response.status})`);
            return data.data;
        } catch (error) {
            if (error?.name === 'AbortError') {
                // AbortError 无 code，转成带 code 的错误供 errorMessage 中文化
                const abortError = new Error(timedOut ? 'Request timed out' : 'Request cancelled');
                abortError.code = timedOut ? 'REQUEST_TIMEOUT' : 'REQUEST_CANCELLED';
                throw abortError;
            }
            throw error;
        } finally {
            clearTimeout(timeoutId);
            activeRequestControllers.delete(controller);
        }
    },

    async getFriends(params = {}) {
        const query = new URLSearchParams({
            q: params.q || '',
            status: params.status || '',
            limit: String(params.limit || 50),
            offset: String(params.offset || 0),
        });
        const data = await this.get('/admin/friends?' + query);
        return {
            friends: (data.friends || []).map((f) => ({
                id: f.id,
                nickname: f.nickname,
                email: f.email,
                note: f.note,
                status: f.status,
                devices: f.active_devices,
                maxDevices: f.max_devices,
                expiresAt: f.expires_at,
                lastSeen: f.last_seen,
            })),
            limit: data.limit || Number(params.limit) || 50,
            offset: data.offset || Number(params.offset) || 0,
            total: data.total || 0,
            hasMore: Boolean(data.hasMore),
        };
    },

    async getFriend(id) {
        const data = await this.get(`/admin/friends/${id}/detail`);
        const source = data.friend;
        if (!source) throw new Error('NOT_FOUND');
        const f = {
            id: source.id,
            nickname: source.nickname,
            email: source.email,
            note: source.note,
            status: source.status,
            devices: source.active_devices,
            maxDevices: source.max_devices,
            totalDevices: Number(source.total_devices || 0),
            activeDevices: Number(source.active_devices || 0),
            revokedDevices: Number(source.revoked_devices || 0),
            recoveryCount: Number(source.recovery_count || 0),
            lastRecoveryAt: source.last_recovery_at,
            expiresAt: source.expires_at,
            lastSeen: source.last_seen,
            lastIp: source.last_ip,
            lastIpGeo: source.last_ip_geo,
            ipUpdatedAt: source.ip_updated_at,
            devicesList: (data.devices || []).map((d) => ({
            id: d.id,
            name: d.device_name,
            appVersion: d.app_version,
            lastSeen: d.last_seen_at,
            status: d.status,
            activatedAt: d.activated_at,
            hasRecoveryIdentity: Boolean(d.has_recovery_identity),
            lastRecoveryAt: d.last_recovery_at,
            possibleDuplicate: Boolean(d.possible_duplicate),
            })),
        };
        return f;
    },

    async getAuditLogs(params = {}) {
        const qs = new URLSearchParams(params).toString();
        const data = await this.get(`/admin/audit-logs${qs ? '?' + qs : ''}`);
        return {
            logs: (data.logs || []).map((l) => ({
            id: l.id,
            eventType: l.event_type,
            friendId: l.friend_id,
            friendName: l.friend_nickname,
            deviceId: l.device_id,
            deviceName: l.device_name,
            result: l.result,
            errorCode: l.error_code,
            detail: l.detail,
            createdAt: l.created_at * 1000, // Unix 秒 → 毫秒
            })),
            limit: data.limit || Number(params.limit) || 50,
            offset: data.offset || Number(params.offset) || 0,
            total: data.total || 0,
            hasMore: Boolean(data.hasMore),
        };
    },

    async createFriend(data) {
        return this.post('/admin/friends', data);
    },

    async updateFriend(friendId, data) {
        return this.patch(`/admin/friends/${friendId}`, data);
    },

    async createInvite(friendId, kind, expiresInDays) {
        return this.post(`/admin/friends/${friendId}/invites`, { kind, expiresInDays });
    },

    async getInvites(friendId, params = {}) {
        const query = new URLSearchParams({
            status: params.status || 'ALL',
            limit: String(params.limit || 50),
            offset: String(params.offset || 0),
        });
        const data = await this.get('/admin/friends/' + friendId + '/invites?' + query);
        return {
            ...data,
            invites: data.invites || [],
            summary: data.summary || { total: 0, available: 0, used: 0, expired: 0, revoked: 0 },
        };
    },

    async revokeInvite(inviteId) {
        return this.post('/admin/invites/' + inviteId + '/revoke', {});
    },

    async revokeDevice(deviceId) {
        return this.post(`/admin/devices/${deviceId}/revoke`, {});
    },

    async deleteDevice(deviceId) {
        return this.request('DELETE', `/admin/devices/${deviceId}`);
    },

    async disableFriend(friendId) {
        return this.post(`/admin/friends/${friendId}/disable`, {});
    },

    async enableFriend(friendId) {
        return this.post(`/admin/friends/${friendId}/enable`, {});
    },
};

// ===== State =====
const state = {
    route: 'dashboard',
    params: {},
    renderToken: 0,
};

// ===== Utils =====
function toDateMs(value) {
    const timestamp = Number(value);
    if (!Number.isFinite(timestamp) || timestamp <= 0) return null;
    return timestamp < 1e12 ? timestamp * 1000 : timestamp;
}

function formatTime(ts) {
    if (!ts) return '—';
    const dateMs = toDateMs(ts);
    if (!dateMs) return '无';
    const diff = Date.now() - dateMs;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + ' 天前';
    return new Date(dateMs).toLocaleDateString('zh-CN');
}

function formatDate(ts) {
    const dateMs = toDateMs(ts);
    if (!ts) return '长期有效';
    return dateMs ? new Date(dateMs).toLocaleDateString('zh-CN') : '长期有效';
}

function formatDateTimeLocal(ts) {
    const dateMs = toDateMs(ts);
    if (!dateMs) return '';
    const date = new Date(dateMs);
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatDateTime(ts, empty = '—') {
    const dateMs = toDateMs(ts);
    if (!ts || !dateMs) return empty;
    return new Date(dateMs).toLocaleString('zh-CN', {
        hour12: false,
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function maskIdentifier(value) {
    const id = String(value ?? '').trim();
    if (!id) return '';
    return id.length > 12 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}

function entityCell(name, id, fallbackLabel) {
    const label = String(name ?? '').trim() || fallbackLabel;
    const identifier = String(id ?? '').trim();
    const maskedId = maskIdentifier(identifier);
    const title = identifier ? `title="${escapeHtml(identifier)}"` : '';
    return `<div class="entity-cell" ${title}>
        <div class="entity-name">${escapeHtml(label)}</div>
        ${maskedId ? `<div class="entity-id">${escapeHtml(maskedId)}</div>` : ''}
    </div>`;
}

function legacyErrorMessage(error) {
    return error instanceof Error && error.message ? error.message : '请求失败';
}

function errorMessage(error) {
    const codeMessages = {
        MIGRATION_DEVICE_NOT_FOUND: '迁移邀请码需要已有绑定设备，请改选激活',
        MIGRATION_DEVICE_MISMATCH: '该设备不属于此用户，无法迁移',
        DEVICE_ALREADY_BOUND: '此设备已绑定，请改用激活邀请码或先撤销原绑定',
        MAX_DEVICES_BELOW_ACTIVE: '设备上限不能低于当前活跃设备数',
        REQUEST_TIMEOUT: '请求超时，请检查网络后重试',
        REQUEST_CANCELLED: '请求已取消',
    };
    if (error?.code && codeMessages[error.code]) return codeMessages[error.code];
    return legacyErrorMessage(error);
}

function showFormError(form, message) {
    let error = form.querySelector('.form-error');
    if (!error) {
        error = document.createElement('p');
        error.className = 'form-error';
        form.prepend(error);
    }
    error.id = `${form.id}-error`;
    error.setAttribute('role', 'alert');
    form.setAttribute('aria-describedby', error.id);
    error.textContent = message;
}

function clearFormError(form) {
    form.querySelector('.form-error')?.remove();
    form.removeAttribute('aria-describedby');
}

let modalFormId = 0;

function bindSubmitButton(form, button) {
    form.id = `modal-form-${++modalFormId}`;
    button.setAttribute('form', form.id);
}

function statusBadge(status) {
    const map = {
        ACTIVE: 'badge-active', DISABLED: 'badge-disabled', REVOKED: 'badge-revoked',
        AVAILABLE: 'badge-active', USED: 'badge-success', EXPIRED: 'badge-disabled',
        SUCCESS: 'badge-success', FAILURE: 'badge-failure',
    };
    const label = { ACTIVE: '有效', DISABLED: '已禁用', REVOKED: '已撤销', AVAILABLE: '可用', USED: '已使用', EXPIRED: '已过期', SUCCESS: '成功', FAILURE: '失败' };
    return `<span class="badge ${map[status] || ''}"><span class="badge-dot"></span>${escapeHtml(label[status] || status)}</span>`;
}

function inviteKindLabel(kind) {
    return kind === 'MIGRATION' ? '迁移' : kind === 'ACTIVATION' ? '激活' : String(kind || '—');
}

function eventLabel(type) {
    const map = {
        REINSTALL_RECOVER: '卸载重装恢复',
        ACTIVATE: '激活', MIGRATE: '迁移', REFRESH: '刷新令牌', REFRESH_REPLAY: '重放检测',
        DEVICE_REVOKE: '撤销设备', DEVICE_DELETE: '删除设备记录', FRIEND_DISABLE: '禁用用户', FRIEND_CREATE: '创建用户', FRIEND_UPDATE: '更新用户',
        INVITE_CREATE: '生成邀请码', INVITE_REVOKE: '撤销邀请码',
    };
    return escapeHtml(map[type] || type);
}

function auditDetail(log) {
    const detail = log.detail || '';
    const details = {
        recovery_id_version: '已更新设备连续性身份并恢复授权',
        'invite_kind:ACTIVATION': '邀请码类型：激活',
        'invite_kind:MIGRATION': '邀请码类型：迁移',
        invite_revoked: '邀请码已撤销',
        friend_created: '用户已创建',
        friend_disabled: '用户已禁用',
        device_revoked: '设备已撤销',
        device_deleted: '设备记录已软删除',
        'refresh:rotated': '刷新成功，旧刷新令牌已轮换',
        'refresh:invalid_signature': '客户端签名校验失败',
        'refresh:invalid_challenge': '刷新挑战已过期或与设备不匹配',
        'refresh:device_or_friend_revoked': '设备或用户已撤销',
        'refresh:friend_expired': '用户有效期已到，设备授权已失效',
        'refresh:expired': '刷新令牌已过期',
        'refresh:token_not_found': '刷新令牌不存在或已失效',
        'refresh:replay_revoked_all': '检测到令牌重放，已撤销该设备全部刷新会话',
    };
    if (details[detail]) return details[detail];
    if (detail.startsWith('invite_kind:')) {
        const kind = detail.match(/^invite_kind:(ACTIVATION|MIGRATION)/)?.[1];
        const mask = detail.match(/;invite_mask:([^;]*)/)?.[1];
        const label = kind === 'MIGRATION' ? '迁移' : '激活';
        return `邀请码类型：${label}${mask ? `（${escapeHtml(mask)}）` : ''}`;
    }
    if (log.eventType === 'ACTIVATE') return '邀请码类型：激活（历史记录）';
    if (log.eventType === 'MIGRATE') return '邀请码类型：迁移（历史记录）';
    if (log.eventType === 'REFRESH' && log.result === 'SUCCESS') return '刷新成功，旧刷新令牌已轮换';
    if (log.eventType === 'REFRESH_REPLAY' && log.errorCode === 'TOKEN_REPLAY') return '检测到令牌重放，已撤销该设备全部刷新会话';
    if (log.eventType === 'REFRESH_REPLAY' && log.errorCode === 'TOKEN_NOT_FOUND') return '刷新令牌不存在或已失效';
    if (log.eventType === 'FRIEND_UPDATE' && detail.startsWith('fields:')) {
        const labels = { nickname: '昵称', note: '备注', max_devices: '设备上限', expires_at: '有效期至' };
        const fields = detail.slice(7).split(',').map((field) => labels[field] || escapeHtml(field));
        return `已更新：${fields.join('、')}`;
    }
    return '—';
}

// ===== 截图大图预览 =====
// 不用 window.open：手机上会新开标签丢掉当前页面状态，且容易被弹窗拦截
function showImageLightbox(src, alt) {
    const box = document.createElement('div');
    box.className = 'lightbox';
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-label', alt || '截图预览');
    const img = document.createElement('img');
    img.src = src;
    img.alt = alt || '截图';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'lightbox-close';
    close.setAttribute('aria-label', '关闭预览');
    close.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>';
    const onKey = (event) => { if (event.key === 'Escape') dismiss(); };
    function dismiss() {
        document.removeEventListener('keydown', onKey);
        box.remove();
    }
    box.append(img, close);
    close.addEventListener('click', dismiss);
    box.addEventListener('click', (event) => { if (event.target === box) dismiss(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(box);
    close.focus();
}

// ===== Toast =====
function showToast(message, type = 'success') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    const icon = document.createElement('span');
    icon.textContent = type === 'success' ? '✓' : '✕';
    const text = document.createElement('span');
    text.textContent = message;
    toast.append(icon, text);
    container.appendChild(toast);
    setTimeout(() => {
        toast.classList.add('hiding');
        setTimeout(() => toast.remove(), 200);
    }, 3000);
}

// ===== Modal =====
const modal = {
    overlay: document.getElementById('modalOverlay'),
    title: document.getElementById('modalTitle'),
    body: document.getElementById('modalBody'),
    footer: document.getElementById('modalFooter'),
    onClose: null,
    _focusHandler: null,

    open({ title, body, footer, onClose }) {
        this.title.textContent = title;
        this.body.innerHTML = '';
        if (typeof body === 'string') this.body.innerHTML = body;
        else this.body.appendChild(body);
        this.footer.innerHTML = '';
        footer.forEach(b => this.footer.appendChild(b));
        this.onClose = onClose;
        this.overlay.hidden = false;
        this._prevFocus = document.activeElement;
        setTimeout(() => this.footer.querySelector('button')?.focus(), 50);
        this._trapFocus();
    },

    close() {
        this.overlay.hidden = true;
        if (this._focusHandler) {
            this.overlay.removeEventListener('keydown', this._focusHandler);
            this._focusHandler = null;
        }
        if (this.onClose) this.onClose();
        this.onClose = null;
        this._prevFocus?.focus();
    },

    _trapFocus() {
        const focusables = this.overlay.querySelectorAll('button, input, select, [href], [tabindex]:not([tabindex="-1"])');
        if (!focusables.length) return;
        const first = focusables[0], last = focusables[focusables.length - 1];
        if (this._focusHandler) this.overlay.removeEventListener('keydown', this._focusHandler);
        this._focusHandler = (e) => {
            if (e.key === 'Tab') {
                if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
            }
            if (e.key === 'Escape') modal.close();
        };
        this.overlay.addEventListener('keydown', this._focusHandler);
    }
};

document.getElementById('modalClose').addEventListener('click', () => modal.close());
document.getElementById('modalOverlay').addEventListener('click', (e) => {
    if (e.target === e.currentTarget) modal.close();
});

// ===== Navigation =====
function navigate(route, params = {}) {
    state.route = route;
    state.params = params;
    const nextHash = '#/' + route + (params.id ? '/' + params.id : '');
    if (window.location.hash === nextHash) {
        updateActiveNav();
        render();
        return;
    }
    window.location.hash = nextHash;
    updateActiveNav();
}

function updateActiveNav() {
    document.querySelectorAll('.sidebar-link, .tab-link[data-route]').forEach(el => {
        const active = el.dataset.route === state.route
            || (el.dataset.route === 'crash-logs' && state.route === 'crash-log-detail');
        el.classList.toggle('active', active);
        if (active) {
            el.setAttribute('aria-current', 'page');
        } else {
            el.removeAttribute('aria-current');
        }
    });
}

function parseHash() {
    const hash = window.location.hash.slice(1) || '/dashboard';
    const [route, id] = hash.split('/').filter(Boolean);
    state.route = route || 'dashboard';
    state.params = id ? { id } : {};
}

// ===== Sidebar toggle =====
const menuToggle = document.getElementById('menuToggle');
const tabMore = document.getElementById('tabMore');
const sidebar = document.getElementById('sidebar');
const sidebarScrim = document.getElementById('sidebarScrim');
// 手机上汉堡被底部「更多」标签取代，两者共用同一个抽屉
function drawerTrigger() {
    return menuToggle.offsetParent ? menuToggle : tabMore;
}
function setDrawerState(open) {
    sidebar.classList.toggle('open', open);
    sidebarScrim.hidden = !open;
    menuToggle.setAttribute('aria-expanded', String(open));
    tabMore.setAttribute('aria-expanded', String(open));
}
function closeSidebar({ restoreFocus = true } = {}) {
    setDrawerState(false);
    if (restoreFocus) drawerTrigger().focus();
}
function openSidebar() {
    setDrawerState(true);
}
menuToggle.addEventListener('click', () => {
    if (sidebar.classList.contains('open')) closeSidebar({ restoreFocus: false });
    else openSidebar();
});
tabMore.addEventListener('click', () => {
    if (sidebar.classList.contains('open')) closeSidebar({ restoreFocus: false });
    else openSidebar();
});
sidebarScrim.addEventListener('click', () => closeSidebar());
document.getElementById('sidebar').addEventListener('click', (e) => {
    if (e.target.closest('.sidebar-link')) {
        closeSidebar({ restoreFocus: false });
    }
});
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && sidebar.classList.contains('open')) closeSidebar();
});

// ===== Mobile touch gestures =====
(function initTouchGestures() {
    let touchStartX = 0;
    let touchStartY = 0;
    let touchCurrentX = 0;
    let isSwiping = false;
    const swipeThreshold = 60;
    const sideEdgeThreshold = 30;

    sidebar.addEventListener('touchstart', (e) => {
        if (!sidebar.classList.contains('open')) return;
        const touch = e.touches[0];
        touchStartX = touch.clientX;
        touchStartY = touch.clientY;
        touchCurrentX = touchStartX;
        isSwiping = false;
    }, { passive: true });

    sidebar.addEventListener('touchmove', (e) => {
        if (!sidebar.classList.contains('open')) return;
        const touch = e.touches[0];
        touchCurrentX = touch.clientX;
        const deltaX = touchCurrentX - touchStartX;
        const deltaY = touch.clientY - touchStartY;

        if (deltaX < -10 && Math.abs(deltaX) > Math.abs(deltaY) * 1.5) {
            isSwiping = true;
            sidebar.style.transition = 'none';
            sidebar.style.transform = `translateX(${Math.min(0, deltaX)}px)`;
            sidebarScrim.style.opacity = String(Math.max(0, 1 + deltaX / 200));
        }
    }, { passive: true });

    sidebar.addEventListener('touchend', () => {
        if (!sidebar.classList.contains('open')) {
            sidebar.style.transition = '';
            sidebar.style.transform = '';
            return;
        }
        sidebar.style.transition = '';
        sidebar.style.transform = '';
        sidebarScrim.style.opacity = '';

        if (isSwiping && touchStartX - touchCurrentX > swipeThreshold) {
            closeSidebar({ restoreFocus: false });
        }
        isSwiping = false;
    }, { passive: true });

    document.addEventListener('touchstart', (e) => {
        if (sidebar.classList.contains('open')) return;
        const touch = e.touches[0];
        if (touch.clientX <= sideEdgeThreshold) {
            touchStartX = touch.clientX;
            touchStartY = touch.clientY;
            isSwiping = false;
        }
    }, { passive: true });

    document.addEventListener('touchmove', (e) => {
        if (sidebar.classList.contains('open')) return;
        if (touchStartX > sideEdgeThreshold) return;
        const touch = e.touches[0];
        const deltaX = touch.clientX - touchStartX;
        const deltaY = touch.clientY - touchStartY;

        if (deltaX > 10 && Math.abs(deltaX) > Math.abs(deltaY) * 1.5) {
            isSwiping = true;
        }
    }, { passive: true });

    document.addEventListener('touchend', () => {
        if (!sidebar.classList.contains('open') && isSwiping && touchCurrentX - touchStartX > swipeThreshold * 1.5) {
            openSidebar();
        }
        touchStartX = 999;
        isSwiping = false;
    }, { passive: true });
})();

// ===== Theme toggle =====
document.getElementById('themeToggle').addEventListener('click', () => Theme.toggle());

// ===== Logout =====
document.getElementById('logoutBtn').addEventListener('click', () => {
    localStorage.removeItem('tts-access-token');
    localStorage.removeItem('tts-access-team');
    localStorage.removeItem('tts-api-base');
    document.getElementById('adminEmail').textContent = '未登录';
    // 由 Access 清除当前 Pages 域名的授权 Cookie。
    window.location.assign(`${window.location.origin}/cdn-cgi/access/logout`);
});

const adminEmail = document.getElementById('adminEmail');
if (window.__ADMIN_EMAIL__) {
    adminEmail.textContent = window.__ADMIN_EMAIL__;
}

// ===== Render router =====
function render() {
    const main = document.getElementById('mainContent');
    cancelPendingRequests();
    const renderToken = ++state.renderToken;
    main.style.opacity = '0';
    setTimeout(() => {
        if (renderToken !== state.renderToken) return;
        main.innerHTML = '';
        switch (state.route) {
            case 'dashboard': renderDashboard(main, renderToken); break;
            case 'friends': renderFriends(main, renderToken); break;
            case 'friend-detail': renderFriendDetail(main, renderToken); break;
            case 'crash-logs': renderCrashLogs(main, renderToken); break;
            case 'crash-log-detail':
                if (state.params.id) showCrashLogDetail(state.params.id, main, renderToken);
                else renderCrashLogs(main, renderToken);
                break;
            case 'audit': renderAudit(main, renderToken); break;
            case 'feedback':
                // 支持 #/feedback/{id} 直达详情：邮件提醒里的深链依赖它
                if (state.params.id) showFeedbackDetail(state.params.id, main, renderToken);
                else renderFeedback(main, renderToken);
                break;
            case 'ai-health': renderAiHealth(main, renderToken); break;
            default: renderDashboard(main, renderToken);
        }
        main.style.opacity = '1';
    }, 100);
}

// ===== Dashboard =====
function renderDashboard(container, renderToken) {
    const header = document.createElement('div');
    header.className = 'page-header';
    header.innerHTML = `
        <h1 class="section-title">仪表盘</h1>
        <p class="section-subtitle">全站概览 · 授权 / AI / 待办</p>
        <div class="dashboard-meta">
            <span class="dashboard-updated" id="dashboardUpdated"></span>
            <button type="button" class="btn btn-ghost btn-sm" id="dashboardRefresh">刷新</button>
        </div>
    `;
    container.appendChild(header);

    const stats = document.createElement('div');
    stats.className = 'stat-grid';
    stats.id = 'dashboardStats';
    stats.innerHTML = [1,2,3,4,5,6].map(() => `<div class="stat-card"><div class="loading-skeleton" style="height:90px"></div></div>`).join('');
    container.appendChild(stats);

    const grid = document.createElement('div');
    grid.className = 'detail-grid';
    grid.innerHTML = `
        <div class="card">
            <div class="card-header"><span class="card-title">最近授权失败</span></div>
            <div class="loading-skeleton" style="height:200px"></div>
        </div>
        <div class="card">
            <div class="card-header"><span class="card-title">服务健康</span></div>
            <div class="loading-skeleton" style="height:200px"></div>
        </div>
    `;
    container.appendChild(grid);

    header.querySelector('#dashboardRefresh').addEventListener('click', () => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        const refreshBtn = header.querySelector('#dashboardRefresh');
        refreshBtn.disabled = true;
        refreshBtn.textContent = '刷新中...';
        loadDashboard(container, renderToken, stats, grid).finally(() => {
            if (renderToken === state.renderToken && container.isConnected) {
                refreshBtn.disabled = false;
                refreshBtn.textContent = '刷新';
            }
        });
    });

    loadDashboard(container, renderToken, stats, grid);
}

async function loadDashboard(container, renderToken, stats, grid) {
    // 五路数据独立降级：任一失败只影响对应卡片，不阻塞其余渲染
    const [statsRes, auditRes, aiRes, crashRes, feedbackRes] = await Promise.allSettled([
        API.getStats(),
        API.getAuditLogs({ limit: 50, offset: 0 }),
        API.get('/admin/ai/health/summary'),
        crashFetch('/api/crash-logs?limit=1').then(res => {
            if (!res.ok) throw new Error(`Request failed (${res.status})`);
            return res.json();
        }),
        API.post('/fb/admin/list', { status: 'PENDING', limit: 1, offset: 0 }),
    ]);
    if (renderToken !== state.renderToken || !container.isConnected) return;

    const s = statsRes.status === 'fulfilled' ? statsRes.value : null;
    const aiSummary = aiRes.status === 'fulfilled' ? aiRes.value : null;
    const crashStats = crashRes.status === 'fulfilled' ? crashRes.value?.stats || null : null;
    const pendingFeedback = feedbackRes.status === 'fulfilled' ? Number(feedbackRes.value?.total || 0) : null;

    // AI 供应商 24h 状态：有调用且在跑的为正常；成功率 <80% 视为需关注
    const aiProviders = aiSummary?.byProvider || [];
    const aiActive = aiProviders.filter(p => Number(p.total) > 0);
    const aiBad = aiActive.filter(p => Number(p.success) / Number(p.total) < 0.8);
    let aiValue = '—', aiValueClass = '', aiHint = '24h 无调用';
    if (aiRes.status !== 'fulfilled') { aiHint = '加载失败'; }
    else if (aiActive.length > 0) {
        aiValue = aiBad.length === 0 ? '全部正常' : `${aiBad.length} 家异常`;
        aiValueClass = aiBad.length === 0 ? 'stat-success' : 'stat-danger';
        aiHint = `${aiActive.length} 家 24h 有调用`;
    }

    const card = (label, value, valueClass, hint, href) => `
        <${href ? `a href="${href}"` : 'div'} class="stat-card stat-card-link">
            <div class="stat-label">${label}</div>
            <div class="stat-value ${valueClass}">${value}</div>
            <div class="stat-hint">${hint}</div>
        </${href ? 'a' : 'div'}>`;

    if (s) {
        stats.innerHTML = [
            card('活跃用户', s.totalFriends, 'stat-accent', '当前可用', '#/friends'),
            card('活跃设备', s.activeDevices, 'stat-success', '在线设备', '#/friends'),
            card('24h 授权失败', s.recentFailures, s.recentFailures > 0 ? 'stat-danger' : '', '需关注', '#/audit'),
            card('待处理反馈', pendingFeedback == null ? '—' : pendingFeedback, pendingFeedback > 0 ? 'stat-danger' : 'stat-success', pendingFeedback == null ? '加载失败' : '未回复', '#/feedback'),
            card('待修复崩溃', crashStats ? crashStats.open : '—', crashStats && crashStats.open > 0 ? 'stat-danger' : 'stat-success', crashStats ? `近 7 天 ${crashStats.last7d} 条` : '加载失败', '#/crash-logs'),
            card('AI 供应商', aiValue, aiValueClass, aiHint, '#/ai-health'),
        ].join('');
    } else {
        stats.innerHTML = dashboardErrorCard('统计加载失败', statsRes.reason, container, renderToken);
    }

    if (s) {
        grid.innerHTML = dashboardHealthCard(s, aiSummary);
    } else {
        // stats 失败时替换骨架，避免两张骨架卡常驻
        grid.innerHTML = dashboardErrorCard('统计加载失败', statsRes.reason, container, renderToken);
    }

    if (auditRes.status === 'fulfilled') {
        const failures = auditRes.value.logs.filter(l => l.result === 'FAILURE').slice(0, 5);
        grid.insertAdjacentHTML('afterbegin', dashboardFailuresCard(failures));
        labelizeTables(grid);
        grid.querySelector('.js-view-all-failures')?.addEventListener('click', showAllFailuresModal);
    } else {
        grid.insertAdjacentHTML('afterbegin', dashboardErrorCard('失败记录加载失败', auditRes.reason, container, renderToken));
    }

    const updatedEl = document.getElementById('dashboardUpdated');
    if (updatedEl) updatedEl.textContent = `更新于 ${formatDateTime(Date.now())}`;
}

function dashboardHealthCard(stats, aiSummary) {
    const healthy = stats.gatewayHealth === 'ok';
    const providers = aiSummary?.byProvider || [];
    const providerRows = providers.map(p => {
        const total = Number(p.total) || 0;
        const success = Number(p.success) || 0;
        const rate = total > 0 ? Math.round((success / total) * 100) : null;
        const bad = rate != null && rate < 80;
        const dotClass = rate == null ? 'warn' : bad ? 'fail' : 'ok';
        const valueText = rate == null ? '无调用' : `${success}/${total} · ${rate}%`;
        const label = AI_PROVIDER_META[p.provider]?.label || p.provider;
        return `<div class="health-row"><span><span class="health-dot ${dotClass}" style="margin-right:8px"></span>${escapeHtml(label)}</span><span style="color:${bad ? 'var(--danger)' : rate == null ? 'var(--text-dim)' : 'var(--success)'}">${valueText}</span></div>`;
    }).join('');
    return `<div class="card js-services-card">
        <div class="card-header"><span class="card-title">服务健康</span></div>
        <div class="health-indicator"><span class="health-dot ${healthy ? 'ok' : 'fail'}"></span><span>${healthy ? 'D1 与统计查询正常' : '管理服务异常'}</span></div>
        <div style="margin-top:16px">
            <div class="health-row"><span>D1 数据库</span><span style="color:${healthy ? 'var(--success)' : 'var(--danger)'}">${healthy ? '● 正常' : '● 异常'}</span></div>
        </div>
        <div class="health-row" style="border-bottom:none;padding-bottom:4px"><span class="dashboard-section-label">AI 供应商 · 24h</span></div>
        ${providerRows || '<div class="health-row"><span style="color:var(--text-dim)">暂无供应商数据</span></div>'}
        <div style="margin-top:14px"><a class="btn btn-ghost btn-sm" href="#/ai-health">查看 AI 健康详情 →</a></div>
    </div>`;
}

function dashboardFailuresCard(failures) {
    return `<div class="card">
        <div class="card-header"><span class="card-title">最近授权失败</span><button type="button" class="btn btn-ghost btn-sm js-view-all-failures">查看全部</button></div>
        ${failures.length === 0 ? '<div class="empty-state"><div class="empty-title">无失败记录</div><div class="empty-desc">系统运行正常</div></div>' :
        `<div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>用户</th><th>错误</th></tr></thead><tbody>${failures.map(l => `<tr><td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatTime(l.createdAt)}</td><td>${eventLabel(l.eventType)}</td><td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(l.friendId)}</td><td style="color:var(--danger)">${escapeHtml(l.errorCode || '—')}</td></tr>`).join('')}</tbody></table></div>`}
    </div>`;
}

function showAllFailuresModal() {
    const limit = 10;
    const modalPanel = modal.overlay.querySelector('.modal');
    modalPanel?.classList.add('failure-log-modal');
    const body = document.createElement('div');
    const closeButton = Object.assign(document.createElement('button'), {
        className: 'btn btn-ghost',
        textContent: '关闭',
        type: 'button',
    });
    modal.open({
        title: '全部授权失败',
        body,
        footer: [closeButton],
        onClose: () => modalPanel?.classList.remove('failure-log-modal'),
    });
    closeButton.addEventListener('click', () => modal.close());

    const renderPage = (page) => {
        const rows = page.logs.length === 0
            ? '<tr><td colspan="4"><div class="empty-state"><div class="empty-title">暂无失败记录</div><div class="empty-desc">审计保留期内没有授权失败</div></div></td></tr>'
            : page.logs.map(log => `
                <tr>
                    <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${formatTime(log.createdAt)}</td>
                    <td>${eventLabel(log.eventType)}</td>
                    <td>${entityCell(log.friendName, log.friendId, '未知用户')}</td>
                    <td style="color:var(--danger);font-size:12px">${escapeHtml(log.errorCode || '—')}</td>
                </tr>
            `).join('');
        const end = Math.min(page.offset + page.logs.length, page.total);
        body.innerHTML = `
            <div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>用户</th><th>错误</th></tr></thead><tbody>${rows}</tbody></table></div>
            <div class="invite-pagination failure-modal-pagination"><span>共 ${page.total} 条，${page.total ? `第 ${page.offset + 1} - ${end} 条` : '暂无记录'}</span><div><button type="button" class="btn btn-ghost btn-sm failure-prev" ${page.offset === 0 ? 'disabled' : ''}>上一页</button><button type="button" class="btn btn-ghost btn-sm failure-next" ${page.hasMore ? '' : 'disabled'}>下一页</button></div></div>
        `;
        body.querySelector('.failure-prev')?.addEventListener('click', () => loadPage(Math.max(0, page.offset - limit)));
        body.querySelector('.failure-next')?.addEventListener('click', () => loadPage(page.offset + limit));
        labelizeTables(body);
    };
    const loadPage = (offset) => {
        body.innerHTML = '<div class="loading-skeleton" style="height:240px"></div>';
        API.getAuditLogs({ result: 'FAILURE', limit, offset }).then(renderPage).catch(error => {
            body.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(error))}</span><button type="button" class="btn btn-sm btn-ghost failure-retry">重试</button></div>`;
            body.querySelector('.failure-retry')?.addEventListener('click', () => loadPage(offset));
        });
    };
    loadPage(0);
}

function dashboardErrorCard(title, error, container, renderToken) {
    const retryId = `dashboard-retry-${Math.random().toString(36).slice(2)}`;
    setTimeout(() => {
        const retry = document.getElementById(retryId);
        retry?.addEventListener('click', () => {
            if (renderToken !== state.renderToken || !container?.isConnected) return;
            container.innerHTML = '';
            renderDashboard(container, renderToken);
        });
    }, 0);
    return `<div class="card dashboard-error-card"><div class="error-banner"><span class="error-text">${escapeHtml(title)}：${escapeHtml(errorMessage(error))}</span><button class="btn btn-sm btn-ghost" id="${retryId}">重试</button></div></div>`;
}

// ===== Friends list =====
function renderFriends(container, renderToken) {
    const header = document.createElement('div');
    header.innerHTML = `<h1 class="section-title">用户</h1><p class="section-subtitle">管理用户白名单和设备</p>`;
    container.appendChild(header);

    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <div class="toolbar-search"><input type="search" class="form-input" id="searchInput" aria-label="搜索用户" autocomplete="off" placeholder="搜索昵称或备注..."></div>
        <div class="toolbar-filters">
            <select class="form-select" id="statusFilter" aria-label="用户状态筛选" style="width:auto">
                <option value="">全部状态</option>
                <option value="ACTIVE">有效</option>
                <option value="DISABLED">已禁用</option>
            </select>
            <button class="btn btn-primary" id="createFriendBtn">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                创建用户
            </button>
        </div>
    `;
    container.appendChild(toolbar);

    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap';
    tableWrap.innerHTML = `<div class="table-scroll"><table data-card-wide="2" data-card-hide="7"><thead><tr><th>昵称</th><th>邮箱</th><th>状态</th><th>设备</th><th>有效期至</th><th>最近活动</th><th>操作</th></tr></thead><tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div><div class="invite-pagination friends-pagination"></div>`;
    container.appendChild(tableWrap);

    const searchInput = toolbar.querySelector('#searchInput');
    const statusFilter = toolbar.querySelector('#statusFilter');
    const pagination = tableWrap.querySelector('.friends-pagination');
    const tbody = tableWrap.querySelector('tbody');
    const limit = 50;
    let offset = 0;
    let loadSequence = 0;
    toolbar.querySelector('#createFriendBtn').addEventListener('click', showCreateFriendModal);

    function loadAndRender() {
        const query = searchInput.value.trim();
        const filter = statusFilter.value;
        const sequence = ++loadSequence;
        pagination.innerHTML = '';
        API.getFriends({ q: query, status: filter, limit, offset }).then(friendsPage => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;
            const friends = friendsPage.friends;

            if (friends.length === 0) {
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-icon">👥</div><div class="empty-title">${friendsPage.total === 0 && !query && !filter ? '还没有用户' : '没有匹配结果'}</div><div class="empty-desc">${friendsPage.total === 0 && !query && !filter ? '点击右上角按钮创建第一个用户' : '尝试调整搜索或筛选条件'}</div>${friendsPage.total === 0 && !query && !filter ? '<button class="btn btn-primary js-create-friend" style="margin-top:8px">+ 创建用户</button>' : ''}</div></td></tr>`;
                tbody.querySelector('.js-create-friend')?.addEventListener('click', showCreateFriendModal);
            } else {
                tbody.innerHTML = friends.map(f => `
                    <tr class="friend-row" data-id="${escapeHtml(f.id)}" style="cursor:pointer">
                        <td><div style="font-weight:600">${escapeHtml(f.nickname)}</div>${f.note ? `<div style="font-size:12px;color:var(--text-dim)">${escapeHtml(f.note)}</div>` : ''}</td>
                        <td style="font-size:12px;color:var(--text-dim);overflow-wrap:anywhere">${escapeHtml(f.email || '—')}</td>
                        <td>${statusBadge(f.status)}</td>
                        <td>${f.devices}/${f.maxDevices}</td>
                        <td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatDate(f.expiresAt)}</td>
                        <td style="color:var(--text-dim);font-size:12px">${formatTime(f.lastSeen)}</td>
                        <td><button class="btn btn-ghost btn-sm js-friend-detail" data-id="${escapeHtml(f.id)}">详情</button></td>
                    </tr>
                `).join('');
                // 整行可点：手机卡片排版收掉了「详情」按钮后，行本身就是入口
                tbody.querySelectorAll('.friend-row').forEach(row => {
                    row.addEventListener('click', () => navigate('friend-detail', { id: row.dataset.id }));
                });
                tbody.querySelectorAll('.js-friend-detail').forEach(button => {
                    button.addEventListener('click', (event) => {
                        event.stopPropagation();
                        navigate('friend-detail', { id: button.dataset.id });
                    });
                });
            }
            const end = Math.min(friendsPage.offset + friends.length, friendsPage.total);
            labelizeTables(tableWrap);
            pagination.innerHTML = `<span>共 ${friendsPage.total} 条${friendsPage.total ? `，第 ${friendsPage.offset + 1} - ${end} 条` : ''}</span><div><button class="btn btn-ghost btn-sm friends-prev" ${friendsPage.offset === 0 ? 'disabled' : ''}>上一页</button><button class="btn btn-ghost btn-sm friends-next" ${friendsPage.hasMore ? '' : 'disabled'}>下一页</button></div>`;
            pagination.querySelector('.friends-prev')?.addEventListener('click', () => { offset = Math.max(0, friendsPage.offset - friendsPage.limit); loadAndRender(); });
            pagination.querySelector('.friends-next')?.addEventListener('click', () => { if (friendsPage.hasMore) { offset = friendsPage.offset + friendsPage.limit; loadAndRender(); } });
        }).catch(err => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;
            tbody.innerHTML = `<tr><td colspan="7"><div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(err))}</span><button class="btn btn-sm btn-ghost" id="friendsRetry">重试</button></div></td></tr>`;
            tbody.querySelector('#friendsRetry')?.addEventListener('click', loadAndRender);
        });
    }

    searchInput.addEventListener('input', debounce(() => { offset = 0; loadAndRender(); }, 300));
    statusFilter.addEventListener('change', () => { offset = 0; loadAndRender(); });
    loadAndRender();
}

// ===== Friend detail =====
function renderFriendDetail(container, renderToken) {
    const id = state.params.id;
    const title = document.createElement('div');
    title.className = 'detail-heading';
    title.innerHTML = `<div style="display:flex;align-items:flex-start;gap:12px;min-width:0"><button class="back-btn" id="friend-back"><svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>返回</button><div class="detail-heading-copy"><h1 class="section-title">用户详情</h1><p class="section-subtitle" id="detailSubtitle">加载中...</p></div></div><div class="detail-heading-actions"><button class="btn btn-ghost btn-sm" id="editBtn" hidden><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></svg>编辑信息</button><button class="btn btn-danger btn-sm detail-disable-btn" id="disableBtn" hidden>禁用用户</button></div>`;
    container.appendChild(title);

    // 返回用户列表
    title.querySelector('#friend-back').addEventListener('click', () => navigate('friends'));

    const content = document.createElement('div');
    content.className = 'detail-grid friend-detail-grid';
    content.innerHTML = `<div class="card"><div class="loading-skeleton" style="height:460px"></div></div>`;
    container.appendChild(content);

    API.getFriend(id).then(f => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        title.querySelector('#detailSubtitle').textContent = `${f.nickname} · ${f.devices} 台设备`;
        const editButton = title.querySelector('#editBtn');
        const disableButton = title.querySelector('#disableBtn');
        editButton.hidden = false;
        disableButton.hidden = false;
        disableButton.disabled = false;
        disableButton.className = `btn btn-sm detail-disable-btn ${f.status === 'DISABLED' ? 'btn-primary' : 'btn-danger'}`;
        disableButton.textContent = f.status === 'DISABLED' ? '重新启用用户' : '禁用用户';
        content.innerHTML = `
                <div class="card detail-card">
                    <div class="card-header"><span class="card-title">基本信息</span><span>${statusBadge(f.status)}</span></div>
                    <div style="display:grid;gap:14px;font-size:13px">
                        <div class="kv-row"><span style="color:var(--text-dim)">昵称</span><span style="font-weight:500">${escapeHtml(f.nickname)}</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">备注</span><span>${escapeHtml(f.note || '—')}</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">设备上限</span><span>${f.maxDevices} 台</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">有效期至</span><span>${formatDate(f.expiresAt)}</span></div>
                    </div>
                </div>
                <div class="card">
                    <div class="card-header"><span class="card-title">设备列表</span><button class="btn btn-primary btn-sm" id="createInviteBtn" ${f.status !== 'ACTIVE' ? 'disabled' : ''}>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        生成邀请码
                    </button></div>
                    <div class="device-summary-grid">
                        <div><span>设备总数</span><strong>${f.totalDevices}</strong></div>
                        <div><span>活跃设备</span><strong>${f.activeDevices}</strong></div>
                        <div><span>已失效</span><strong>${f.revokedDevices}</strong></div>
                        <div><span>恢复次数</span><strong>${f.recoveryCount}</strong></div>
                    </div>
                    <div class="table-scroll"><table><thead><tr><th>设备名称</th><th>App 版本</th><th>状态</th><th>最后活动</th><th>操作</th></tr></thead><tbody>
                        ${f.devicesList.map(d => `
                            <tr>
                                <td>${escapeHtml(d.name || '—')}</td>
                                <td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(d.appVersion || '—')}</td>
                                <td>${statusBadge(d.status)}</td>
                                <td style="color:var(--text-dim);font-size:12px">${formatTime(d.lastSeen)}</td>
                                <td>${d.status === 'ACTIVE' ? `<button class="btn btn-danger btn-sm js-revoke-device" data-device-id="${escapeHtml(d.id)}" data-device-name="${escapeHtml(d.name || '—')}">撤销</button>` : `<button class="btn btn-danger btn-sm js-delete-device" data-device-id="${escapeHtml(d.id)}" data-device-name="${escapeHtml(d.name || '—')}">删除记录</button>`}</td>
                            </tr>
                        `).join('')}
                    </tbody></table></div>
                </div>
        `;
        const basicInfo = content.querySelector('.detail-card > div:last-child');
        labelizeTables(content);
        basicInfo?.insertAdjacentHTML('beforeend', `<div class="kv-row"><span style="color:var(--text-dim)">邮箱</span><span>${escapeHtml(f.email || '—')}</span></div>`);
        const deviceRows = content.querySelectorAll('tbody tr');
        deviceRows.forEach((row, index) => {
            const device = f.devicesList[index];
            if (!device) return;
            const nameCell = row.querySelector('td');
            if (device.possibleDuplicate && nameCell) {
                const marker = document.createElement('span');
                marker.className = 'device-possible-duplicate';
                marker.textContent = '可能重复';
                nameCell.appendChild(marker);
            }
            const statusCell = row.querySelector('td:nth-child(3)');
            if (device.hasRecoveryIdentity && device.lastRecoveryAt && statusCell) {
                const recovery = document.createElement('div');
                recovery.className = 'device-recovery-meta';
                recovery.textContent = `最近恢复 ${formatTime(device.lastRecoveryAt)}`;
                statusCell.appendChild(recovery);
            }
        });
        editButton.addEventListener('click', () => showEditFriendModal(f));
        content.querySelector('#createInviteBtn')?.addEventListener('click', () => showCreateInviteModal(f.id, f.nickname, f.devices));
        disableButton.addEventListener('click', () => {
            if (f.status === 'DISABLED') showEnableFriendModal(f.id, f.nickname);
            else showDisableFriendModal(f.id, f.nickname, f.devices);
        });
        content.querySelectorAll('.js-revoke-device').forEach(button => {
            button.addEventListener('click', () => showRevokeDeviceModal(button.dataset.deviceId, button.dataset.deviceName));
        });
        content.querySelectorAll('.js-delete-device').forEach(button => {
            button.addEventListener('click', () => showDeleteDeviceModal(button.dataset.deviceId, button.dataset.deviceName));
        });

        // IP 历史卡片
        const ipCard = document.createElement('div');
        ipCard.className = 'card';
        ipCard.innerHTML = `
            <div class="card-header"><span class="card-title">最近活动 IP</span></div>
            ${f.lastIp ? `
                <div style="display:grid;gap:10px;font-size:13px">
                    <div class="kv-row"><span style="color:var(--text-dim)">IP 地址</span><span style="font-family:var(--font-mono)">${escapeHtml(f.lastIp)}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">地理位置</span><span>${escapeHtml(f.lastIpGeo || '未知')}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">更新时间</span><span>${formatTime(f.ipUpdatedAt)}</span></div>
                </div>
                <button class="btn btn-ghost btn-sm" id="show-ip-logs" style="margin-top:12px">查看 IP 历史</button>
                <div id="ip-logs-list" hidden style="margin-top:12px"></div>
            ` : '<div class="empty-state" style="text-align:center;padding:16px;color:var(--text-dim)">暂无 IP 记录</div>'}
        `;
        content.appendChild(ipCard);
        const showIpLogsBtn = ipCard.querySelector('#show-ip-logs');
        if (showIpLogsBtn) {
            showIpLogsBtn.addEventListener('click', () => {
                const logsDiv = ipCard.querySelector('#ip-logs-list');
                if (!logsDiv.hasAttribute('hidden')) {
                    logsDiv.setAttribute('hidden', '');
                    showIpLogsBtn.textContent = '查看 IP 历史';
                    return;
                }
                showIpLogsBtn.textContent = '加载中...';
                showIpLogsBtn.disabled = true;
                API.get(`/admin/friends/${id}/ip-logs`).then(logs => {
                    showIpLogsBtn.disabled = false;
                    showIpLogsBtn.textContent = '收起 IP 历史';
                    logsDiv.removeAttribute('hidden');
                    const ipLogs = Array.isArray(logs) ? logs : (logs.logs || []);
                    if (ipLogs.length === 0) {
                        logsDiv.innerHTML = '<div style="text-align:center;padding:12px;color:var(--text-dim)">暂无历史记录</div>';
                    } else {
                        logsDiv.innerHTML = `<div class="table-scroll"><table><thead><tr><th>IP</th><th>地理位置</th><th>ISP</th><th>时间</th></tr></thead><tbody>${ipLogs.map(l => {
                            const geo = [l.country, l.region, l.city].filter(Boolean).join(' ') || '未知';
                            return `<tr><td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(l.ip || '—')}</td><td>${escapeHtml(geo)}</td><td style="font-size:12px;color:var(--text-dim)">${escapeHtml(l.isp || '—')}</td><td style="color:var(--text-dim);font-size:12px">${formatTime(l.created_at)}</td></tr>`;
                        }).join('')}</tbody></table></div>`;
                        labelizeTables(logsDiv);
                    }
                }).catch(err => {
                    showIpLogsBtn.disabled = false;
                    showIpLogsBtn.textContent = '查看 IP 历史';
                    logsDiv.removeAttribute('hidden');
                    logsDiv.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(err.message || '')}</span></div>`;
                });
            });
        }

        const inviteSection = document.createElement('div');
        inviteSection.id = 'inviteSection';
        inviteSection.className = 'card invite-management-card';
        content.appendChild(inviteSection);
        loadInvitesSection(f.id, inviteSection, renderToken);
    }).catch(err => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        content.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(err))}</span><button class="btn btn-sm btn-ghost" id="friendDetailRetry">重试</button></div>`;
        content.querySelector('#friendDetailRetry')?.addEventListener('click', () => navigate('friend-detail', { id: state.params.id }));
    });
}

function loadInvitesSection(friendId, section, renderToken) {
    let status = 'ALL';
    let offset = 0;
    const limit = 50;

    const renderLoading = () => {
        section.innerHTML = '<div class="card-header"><span class="card-title">邀请码</span></div><div class="loading-skeleton" style="height:180px"></div>';
    };

    const render = (data) => {
        if (renderToken !== state.renderToken || !section.isConnected) return;
        const summary = data.summary || {};
        const invites = data.invites || [];
        const rows = invites.length === 0
            ? '<tr><td colspan="7"><div class="empty-state"><div class="empty-title">暂无邀请码记录</div><div class="empty-desc">创建邀请码后会显示在这里</div></div></td></tr>'
            : invites.map(invite => '<tr>' +
                '<td class="invite-code-masked">' + escapeHtml(invite.code_mask || invite.codeMask || '—') + '</td>' +
                '<td>' + escapeHtml(inviteKindLabel(invite.kind)) + '</td>' +
                '<td>' + statusBadge(invite.status) + '</td>' +
                '<td class="invite-date">' + formatDateTime(invite.created_at) + '</td>' +
                '<td class="invite-date">' + formatDateTime(invite.expires_at, '长期有效') + '</td>' +
                '<td class="invite-date">' + formatDateTime(invite.used_at, '—') + '</td>' +
                '<td>' + (invite.status === 'AVAILABLE'
                    ? '<button class="btn btn-danger btn-sm js-revoke-invite" data-invite-id="' + escapeHtml(invite.id) + '">撤销</button>'
                    : '—') + '</td></tr>').join('');
        section.innerHTML =
            '<div class="card-header"><span class="card-title">邀请码</span>' +
                '<select class="form-select invite-status-filter" aria-label="邀请码状态筛选">' +
                    '<option value="ALL">全部</option><option value="AVAILABLE">可用</option>' +
                    '<option value="USED">已使用</option><option value="EXPIRED">已过期</option>' +
                    '<option value="REVOKED">已撤销</option>' +
                '</select></div>' +
            '<div class="invite-summary-grid">' +
                '<div><span>总数</span><strong>' + Number(summary.total || 0) + '</strong></div>' +
                '<div><span>可用</span><strong>' + Number(summary.available || 0) + '</strong></div>' +
                '<div><span>已使用</span><strong>' + Number(summary.used || 0) + '</strong></div>' +
                '<div><span>已失效</span><strong>' + (Number(summary.expired || 0) + Number(summary.revoked || 0)) + '</strong></div>' +
            '</div><div class="table-scroll"><table><thead><tr><th>邀请码</th><th>类型</th><th>状态</th>' +
                '<th>创建时间</th><th>有效期至</th><th>使用时间</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table></div>' +
            '<div class="invite-pagination"><span>' + (invites.length ? '第 ' + (offset + 1) + ' - ' + (offset + invites.length) + ' 条' : '暂无记录') + '</span>' +
                '<div><button class="btn btn-ghost btn-sm invite-prev" ' + (offset === 0 ? 'disabled' : '') + '>上一页</button>' +
                '<button class="btn btn-ghost btn-sm invite-next" ' + (data.hasMore ? '' : 'disabled') + '>下一页</button></div></div>';
        section.querySelector('.invite-status-filter').value = status;
        labelizeTables(section);
        section.querySelector('.invite-status-filter').addEventListener('change', (event) => {
            status = event.target.value;
            offset = 0;
            load();
        });
        section.querySelector('.invite-prev').addEventListener('click', () => {
            offset = Math.max(0, offset - limit);
            load();
        });
        section.querySelector('.invite-next').addEventListener('click', () => {
            if (data.hasMore) {
                offset += limit;
                load();
            }
        });
        section.querySelectorAll('.js-revoke-invite').forEach(button => {
            button.addEventListener('click', () => showRevokeInviteModal(button.dataset.inviteId, load));
        });
    };

    const renderError = (err) => {
        if (renderToken !== state.renderToken || !section.isConnected) return;
        section.innerHTML = '<div class="card-header"><span class="card-title">邀请码</span></div><div class="error-banner"><span class="error-text">加载失败：' +
            escapeHtml(errorMessage(err)) + '</span><button class="btn btn-sm btn-ghost invite-retry">重试</button></div>';
        section.querySelector('.invite-retry').addEventListener('click', load);
    };

    const load = () => {
        if (renderToken !== state.renderToken || !section.isConnected) return;
        renderLoading();
        API.getInvites(friendId, { status, limit, offset }).then(render).catch(renderError);
    };
    load();
}

function showRevokeInviteModal(inviteId, reload) {
    const body = document.createElement('div');
    body.innerHTML = '<div class="confirm-danger-text">确定要撤销这个邀请码吗？</div><div class="confirm-danger-impact">撤销后邀请码立即失效，操作不可恢复；已使用或已过期的邀请码不可撤销。</div>';
    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-danger';
    confirmBtn.textContent = '确认撤销';
    modal.open({
        title: '撤销邀请码',
        body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            confirmBtn,
        ],
    });
    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '撤销中...';
        try {
            await API.revokeInvite(inviteId);
            modal.close();
            showToast('邀请码已撤销');
            reload();
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认撤销';
            showToast('撤销失败: ' + errorMessage(err), 'error');
        }
    });
}

// ===== Audit logs =====
function renderAudit(container, renderToken) {
    const title = document.createElement('div');
    title.innerHTML = `<h1 class="section-title">审计日志</h1><p class="section-subtitle">安全事件记录（保留 90 天）</p>`;
    container.appendChild(title);

    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <select class="form-select" id="eventFilter" aria-label="事件类型筛选" style="width:auto">
            <option value="">全部事件</option>
            <option value="ACTIVATE">激活</option>
            <option value="MIGRATE">迁移</option>
            <option value="REFRESH">刷新令牌</option>
            <option value="REFRESH_REPLAY">重放检测</option>
            <option value="DEVICE_REVOKE">撤销设备</option>
            <option value="FRIEND_DISABLE">禁用用户</option>
            <option value="FRIEND_ENABLE">启用用户</option>
            <option value="FRIEND_CREATE">创建用户</option>
            <option value="FRIEND_UPDATE">更新用户</option>
            <option value="INVITE_CREATE">生成邀请码</option>
            <option value="INVITE_REVOKE">撤销邀请码</option>
        </select>
        <select class="form-select" id="resultFilter" aria-label="结果筛选" style="width:auto">
            <option value="">全部结果</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILURE">失败</option>
        </select>
        <select class="form-select" id="timeFilter" aria-label="时间范围筛选" style="width:auto">
            <option value="24h">最近 24 小时</option>
            <option value="7d">最近 7 天</option>
            <option value="30d">最近 30 天</option>
            <option value="90d">最近 90 天</option>
        </select>
    `;
    container.appendChild(toolbar);

    const help = document.createElement('p');
    help.className = 'form-hint';
    help.textContent = '刷新令牌：访问令牌过期后轮换刷新令牌；重放检测：检测到旧刷新令牌再次使用，会撤销该设备的全部刷新会话。';
    container.appendChild(help);

    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap';
    tableWrap.innerHTML = `<div class="table-scroll"><table data-card-wide="3"><thead><tr><th>时间</th><th>事件</th><th>详情</th><th>用户</th><th>设备</th><th>结果</th><th>错误码</th></tr></thead><tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div><div class="invite-pagination audit-pagination"></div>`;
    container.appendChild(tableWrap);

    const eventFilter = toolbar.querySelector('#eventFilter');
    const resultFilter = toolbar.querySelector('#resultFilter');
    const timeFilter = toolbar.querySelector('#timeFilter');
    eventFilter.insertAdjacentHTML('beforeend', '<option value="REINSTALL_RECOVER">卸载重装恢复</option>');
    let loadSequence = 0;

    function loadAndRender(offset = 0) {
        const eFilter = eventFilter.value;
        const rFilter = resultFilter.value;
        const tFilter = timeFilter.value;
        const now = Date.now();
        const cutoff = tFilter === '24h' ? now - 86400000 : tFilter === '7d' ? now - 86400000 * 7 : tFilter === '30d' ? now - 86400000 * 30 : now - 86400000 * 90;
        const params = { limit: 50, offset, from: Math.floor(cutoff / 1000) };
        if (eFilter) params.eventType = eFilter;
        if (rFilter) params.result = rFilter;
        const sequence = ++loadSequence;

        API.getAuditLogs(params).then(page => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;
            const logs = page.logs;

            const tbody = tableWrap.querySelector('tbody');
            if (logs.length === 0) {
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-title">无记录</div><div class="empty-desc">当前筛选条件下没有审计日志</div></div></td></tr>`;
            } else {
                tbody.innerHTML = logs.map(l => `
                    <tr>
                        <td class="cell-nowrap" style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${new Date(l.createdAt).toLocaleString('zh-CN')}</td>
                        <td>${eventLabel(l.eventType)}</td>
                        <td>${auditDetail(l)}</td>
                        <td>${entityCell(l.friendName, l.friendId, '未知用户')}</td>
                        <td>${entityCell(l.deviceName, l.deviceId, '未知设备')}</td>
                        <td>${statusBadge(l.result)}</td>
                        <td style="color:var(--danger);font-size:12px">${escapeHtml(l.errorCode || '—')}</td>
                    </tr>
                `).join('');
            }
            const pagination = tableWrap.querySelector('.audit-pagination');
            labelizeTables(tableWrap);
            const end = Math.min(page.offset + page.logs.length, page.total);
            pagination.innerHTML = `<span>共 ${page.total} 条，${page.total ? `第 ${page.offset + 1} - ${end} 条` : '暂无记录'}</span><div><button class="btn btn-ghost btn-sm audit-prev" ${page.offset === 0 ? 'disabled' : ''}>上一页</button><button class="btn btn-ghost btn-sm audit-next" ${page.hasMore ? '' : 'disabled'}>下一页</button></div>`;
            pagination.querySelector('.audit-prev')?.addEventListener('click', () => loadAndRender(Math.max(0, page.offset - page.limit)));
            pagination.querySelector('.audit-next')?.addEventListener('click', () => loadAndRender(page.offset + page.limit));
        }).catch(err => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;
            const tbody = tableWrap.querySelector('tbody');
            tbody.innerHTML = `<tr><td colspan="7"><div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(err))}</span><button class="btn btn-sm btn-ghost" id="auditRetry">重试</button></div></td></tr>`;
            tbody.querySelector('#auditRetry')?.addEventListener('click', () => loadAndRender(0));
        });
    }

    eventFilter.addEventListener('change', () => loadAndRender(0));
    resultFilter.addEventListener('change', () => loadAndRender(0));
    timeFilter.addEventListener('change', () => loadAndRender(0));
    loadAndRender();
}

// ===== Modals =====
function showCreateFriendModal() {
    const form = document.createElement('form');
    form.noValidate = true;
    form.innerHTML = `
        <div class="form-group"><label class="form-label" for="create-friend-nickname">昵称 *</label><input class="form-input" id="create-friend-nickname" name="nickname" maxlength="32" autocomplete="nickname" placeholder="例如：小明"></div>
        <div class="form-group"><label class="form-label" for="create-friend-note">备注</label><input class="form-input" id="create-friend-note" name="note" maxlength="64" autocomplete="off" placeholder="可选，如：大学同学"></div>
        <div class="form-group"><label class="form-label" for="create-friend-max-devices">设备上限</label><input class="form-input" id="create-friend-max-devices" name="maxDevices" type="number" min="1" max="10" value="2" autocomplete="off"></div>
        <p class="form-hint">用户长期有效；邀请码单独设置有效期。</p>
    `;

    const createEmailGroup = document.createElement('div');
    createEmailGroup.className = 'form-group';
    createEmailGroup.innerHTML = '<label class="form-label" for="create-friend-email">邮箱</label><input class="form-input" id="create-friend-email" name="email" type="email" maxlength="254" autocomplete="email" placeholder="user@example.com">';
    form.querySelector('#create-friend-nickname')?.closest('.form-group')?.after(createEmailGroup);

    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary';
    submitBtn.type = 'submit';
    submitBtn.textContent = '创建';
    bindSubmitButton(form, submitBtn);

    modal.open({
        title: '创建用户',
        body: form,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            submitBtn
        ]
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearFormError(form);
        const nickname = form.nickname.value.trim();
        const email = form.email.value.trim();
        const note = form.note.value.trim();
        const maxDevices = Number.parseInt(form.maxDevices.value, 10);
        if (!nickname) {
            showFormError(form, '请输入昵称。');
            form.nickname.focus();
            return;
        }
        if (email && (!email.includes('@') || email.length > 254)) {
            showFormError(form, '请输入有效的邮箱，或留空');
            form.email.focus();
            return;
        }
        if (nickname.length > 32 || note.length > 64) {
            showFormError(form, '昵称最多 32 个字符，备注最多 64 个字符。');
            return;
        }
        if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 10) {
            showFormError(form, '设备上限必须是 1 到 10 之间的整数。');
            form.maxDevices.focus();
            return;
        }
        submitBtn.disabled = true;
        submitBtn.textContent = '创建中...';
        const data = {
            nickname,
            email: email || null,
            note,
            maxDevices,
        };
        try {
            const friend = await API.createFriend(data);
            modal.close();
            showToast(`已创建用户 "${friend.nickname}"`);
            navigate('friend-detail', { id: friend.id });
        } catch (err) {
            submitBtn.disabled = false;
            submitBtn.textContent = '创建';
            showToast('创建失败: ' + errorMessage(err), 'error');
        }
    });
}

function showEditFriendModal(friend) {
    const form = document.createElement('form');
    form.noValidate = true;
    form.innerHTML = `
        <div class="form-group"><label class="form-label" for="edit-friend-nickname">昵称 *</label><input class="form-input" id="edit-friend-nickname" name="nickname" maxlength="32" autocomplete="nickname" value="${escapeHtml(friend.nickname)}"></div>
        <div class="form-group"><label class="form-label" for="edit-friend-note">备注</label><input class="form-input" id="edit-friend-note" name="note" maxlength="64" autocomplete="off" value="${escapeHtml(friend.note || '')}"></div>
        <div class="form-group"><label class="form-label" for="edit-friend-max-devices">设备上限</label><input class="form-input" id="edit-friend-max-devices" name="maxDevices" type="number" min="1" max="10" value="${Number(friend.maxDevices) || 1}" autocomplete="off"><p class="form-hint">不能低于当前活跃设备数（${Number(friend.devices) || 0} 台）。</p></div>
        <div class="form-group"><label class="form-label" for="edit-friend-expires-at">有效期至</label><div style="display:flex;align-items:center;gap:10px"><input class="form-input" id="edit-friend-expires-at" name="expiresAt" type="datetime-local" autocomplete="off" style="flex:1"><label for="edit-friend-no-expiry" style="display:flex;align-items:center;gap:6px;white-space:nowrap;font-size:12px;color:var(--text-muted)"><input id="edit-friend-no-expiry" name="noExpiry" type="checkbox">长期有效</label></div></div>
    `;

    const editEmailGroup = document.createElement('div');
    editEmailGroup.className = 'form-group';
    editEmailGroup.innerHTML = `<label class="form-label" for="edit-friend-email">邮箱</label><input class="form-input" id="edit-friend-email" name="email" type="email" maxlength="254" autocomplete="email" value="${escapeHtml(friend.email || '')}">`;
    form.querySelector('#edit-friend-nickname')?.closest('.form-group')?.after(editEmailGroup);

    const expiresInput = form.expiresAt;
    const noExpiryInput = form.noExpiry;
    noExpiryInput.checked = !friend.expiresAt;
    expiresInput.value = formatDateTimeLocal(friend.expiresAt);
    expiresInput.disabled = noExpiryInput.checked;
    noExpiryInput.addEventListener('change', () => {
        expiresInput.disabled = noExpiryInput.checked;
        if (noExpiryInput.checked) expiresInput.value = '';
    });

    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary';
    submitBtn.type = 'submit';
    submitBtn.textContent = '保存修改';
    bindSubmitButton(form, submitBtn);

    modal.open({
        title: `编辑用户 · ${friend.nickname}`,
        body: form,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            submitBtn,
        ],
    });
    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        clearFormError(form);
        const nickname = form.nickname.value.trim();
        const email = form.email.value.trim();
        const note = form.note.value.trim();
        const maxDevices = Number.parseInt(form.maxDevices.value, 10);
        if (!nickname) {
            showFormError(form, '请输入昵称。');
            form.nickname.focus();
            return;
        }
        if (email && (!email.includes('@') || email.length > 254)) {
            showFormError(form, '请输入有效的邮箱，或留空');
            form.email.focus();
            return;
        }
        if (nickname.length > 32 || note.length > 64) {
            showFormError(form, '昵称最多 32 个字符，备注最多 64 个字符。');
            return;
        }
        if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 10) {
            showFormError(form, '设备上限必须是 1 到 10 之间的整数。');
            form.maxDevices.focus();
            return;
        }
        if (maxDevices < Number(friend.devices) || maxDevices < 1) {
            showFormError(form, `设备上限不能低于当前活跃设备数（${Number(friend.devices) || 0} 台）。`);
            form.maxDevices.focus();
            return;
        }

        let expiresAt = null;
        if (!noExpiryInput.checked) {
            const timestamp = new Date(expiresInput.value).getTime();
            if (!expiresInput.value || !Number.isFinite(timestamp) || timestamp <= 0) {
                showFormError(form, '请选择有效期至时间，或勾选长期有效。');
                expiresInput.focus();
                return;
            }
            expiresAt = Math.floor(timestamp / 1000);
        }

        submitBtn.disabled = true;
        submitBtn.textContent = '保存中...';
        try {
            await API.updateFriend(friend.id, { nickname, email: email || null, note, maxDevices, expiresAt });
            modal.close();
            showToast(`用户「${nickname}」已更新`);
            navigate('friend-detail', { id: friend.id });
        } catch (err) {
            submitBtn.disabled = false;
            submitBtn.textContent = '保存修改';
            showFormError(form, errorMessage(err));
        }
    });
}

function showCreateInviteModal(friendId, friendName, activeDeviceCount = 0) {
    const form = document.createElement('form');
    form.innerHTML = `
        <div class="form-group"><label class="form-label" for="invite-kind">邀请类型</label>
            <select class="form-select" id="invite-kind" name="kind">
                <option value="ACTIVATION">激活（新用户）</option>
                <option value="MIGRATION">迁移（现有设备）</option>
            </select>
        </div>
        <div class="form-group"><label class="form-label" for="invite-expires-in">有效期</label>
            <select class="form-select" id="invite-expires-in" name="expiresIn">
                <option value="1">1 天</option>
                <option value="7" selected>7 天</option>
                <option value="30">30 天</option>
            </select>
        </div>
    `;

    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary';
    submitBtn.type = 'submit';
    submitBtn.textContent = '生成邀请码';
    bindSubmitButton(form, submitBtn);

    const updateInviteKindState = () => {
        const isMigration = form.kind.value === 'MIGRATION';
        const blocked = isMigration && Number(activeDeviceCount) < 1;
        if (blocked) {
            showFormError(form, '迁移邀请码需要已有绑定设备，请改选激活');
        } else {
            clearFormError(form);
        }
        submitBtn.disabled = blocked;
    };

    modal.open({
        title: `生成邀请码 · ${friendName}`,
        body: form,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            submitBtn
        ]
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    form.kind.addEventListener('change', updateInviteKindState);
    updateInviteKindState();

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (form.kind.value === 'MIGRATION' && Number(activeDeviceCount) < 1) {
            updateInviteKindState();
            return;
        }
        submitBtn.disabled = true;
        submitBtn.textContent = '生成中...';
        const kind = form.kind.value;
        const days = parseInt(form.expiresIn.value);
        try {
            const invite = await API.createInvite(friendId, kind, days);
            const code = invite.inviteCode || invite.code;
            if (!code) throw new Error('服务器未返回邀请码');
            showInviteCodeResult(code, invite.expiresAt);
        } catch (err) {
            submitBtn.textContent = '生成邀请码';
            updateInviteKindState();
            showToast('生成失败: ' + errorMessage(err), 'error');
        }
    });
}

async function copyText(text) {
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return;
        } catch {
            // 非安全上下文或浏览器权限拒绝时继续使用传统复制方式。
        }
    }

    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    let copied = false;
    try {
        copied = document.execCommand('copy');
    } finally {
        textarea.remove();
    }
    if (!copied) throw new Error('Clipboard unavailable');
}

function showInviteCodeResult(code, expiresAt) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="invite-warning">⚠️ 此邀请码只显示一次，关闭此窗口后无法再次查看。请立即复制并发送给用户。</div>
        <div class="invite-code-box"><div class="invite-code" id="inviteCodeDisplay">${escapeHtml(code)}</div></div>
        <p style="font-size:12px;color:var(--text-dim);text-align:center">有效期至 ${formatDate(expiresAt)}</p>
    `;

    const copyBtn = document.createElement('button');
    copyBtn.className = 'btn btn-primary';
    copyBtn.textContent = '复制邀请码';

    modal.open({
        title: '邀请码（仅显示一次）',
        body: body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '关闭', type: 'button' }),
            copyBtn
        ],
        onClose: () => { navigate('friend-detail', { id: state.params.id }); }
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    copyBtn.addEventListener('click', async () => {
        copyBtn.disabled = true;
        try {
            await copyText(code);
            copyBtn.textContent = '已复制 ✓';
            showToast('邀请码已复制');
            setTimeout(() => { copyBtn.textContent = '复制邀请码'; }, 2000);
        } catch {
            showToast('复制失败，请手动选择文本复制', 'error');
        } finally {
            copyBtn.disabled = false;
        }
    });
}

function showRevokeDeviceModal(deviceId, deviceName) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要撤销设备 <strong>${escapeHtml(deviceName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">⚠️ 撤销后该设备的所有会话立即失效，无法再访问任何敏感接口。此操作不可逆，需要重新生成邀请码才能再次绑定。</div>
        <label class="revoke-checkbox">
            <input type="checkbox" id="revokeDeleteRecord">
            <span class="revoke-checkbox-text">同时删除该设备记录<span class="revoke-checkbox-hint">删除后设备列表将不再显示此条记录</span></span>
        </label>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-danger';
    confirmBtn.textContent = '确认撤销';

    modal.open({
        title: '撤销设备',
        body: body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            confirmBtn
        ]
    });

    const deleteRecordCheckbox = body.querySelector('#revokeDeleteRecord');
    deleteRecordCheckbox.addEventListener('change', () => {
        confirmBtn.textContent = deleteRecordCheckbox.checked ? '撤销并删除记录' : '确认撤销';
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '撤销中...';
        try {
            await API.revokeDevice(deviceId);
            if (deleteRecordCheckbox.checked) {
                // 撤销成功后设备状态为 REVOKED，此时才可删除记录
                confirmBtn.textContent = '删除记录中...';
                try {
                    await API.deleteDevice(deviceId);
                    modal.close();
                    showToast('设备已撤销并删除记录');
                } catch (deleteErr) {
                    modal.close();
                    showToast('设备已撤销，但删除记录失败: ' + errorMessage(deleteErr), 'error');
                }
            } else {
                modal.close();
                showToast('设备已撤销');
            }
            navigate('friend-detail', { id: state.params.id });
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = deleteRecordCheckbox.checked ? '撤销并删除记录' : '确认撤销';
            showToast('撤销失败: ' + errorMessage(err), 'error');
        }
    });
}

function showDeleteDeviceModal(deviceId, deviceName) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要删除设备记录 <strong>${escapeHtml(deviceName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">删除后仅从设备列表隐藏，授权和审计记录不会恢复或清除。</div>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-danger';
    confirmBtn.textContent = '确认删除';
    modal.open({
        title: '删除设备记录',
        body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            confirmBtn,
        ],
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '删除中...';
        try {
            await API.deleteDevice(deviceId);
            modal.close();
            showToast('设备记录已删除');
            navigate('friend-detail', { id: state.params.id });
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认删除';
            showToast('删除失败: ' + errorMessage(err), 'error');
        }
    });
}

function showDisableFriendModal(friendId, friendName, deviceCount) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要禁用用户 <strong>${escapeHtml(friendName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">⚠️ 禁用后该用户下全部 ${Number(deviceCount) || 0} 台设备和会话立即失效。需要手动启用才能恢复。</div>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-danger';
    confirmBtn.textContent = '确认禁用';

    modal.open({
        title: '禁用用户',
        body: body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            confirmBtn
        ]
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '禁用中...';
        try {
            await API.disableFriend(friendId);
            modal.close();
            showToast('用户已禁用');
            navigate('friend-detail', { id: state.params.id });
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认禁用';
            showToast('禁用失败: ' + errorMessage(err), 'error');
        }
    });
}

function showEnableFriendModal(friendId, friendName) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要重新启用用户 <strong>${escapeHtml(friendName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">启用只恢复用户状态，不会恢复已撤销设备和会话。需要重新生成邀请码并重新绑定设备。</div>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-primary';
    confirmBtn.textContent = '确认启用';

    modal.open({
        title: '重新启用用户',
        body,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            confirmBtn,
        ],
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '启用中...';
        try {
            await API.enableFriend(friendId);
            modal.close();
            showToast('用户已重新启用');
            navigate('friend-detail', { id: state.params.id });
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认启用';
            showToast('启用失败: ' + errorMessage(err), 'error');
        }
    });
}

// ===== Helpers =====
function debounce(fn, ms) {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
}

/** 手机端把表格渲染成卡片时，字段名取自同一张表的 <th>，模板里不必维护第二份标签。
 *  <table> 上的 data-card-wide / data-card-hide 是 1-based 列下标，
 *  分别表示「内容长到要跨整行」与「手机上不显示的列」。 */
function labelizeTables(root) {
    (root || document).querySelectorAll('table').forEach((table) => {
        const labels = Array.from(table.querySelectorAll('thead th'), (th) => th.textContent.trim());
        if (labels.length === 0) return;
        const wide = parseColumnIndexes(table.dataset.cardWide);
        const hide = parseColumnIndexes(table.dataset.cardHide);
        table.querySelectorAll('tbody tr').forEach((row) => {
            Array.from(row.children).forEach((cell, index) => {
                if (cell.colSpan > 1) return; // 骨架 / 空态 / 错误横幅的整行占位不贴字段名
                const column = index + 1;
                if (labels[index]) cell.dataset.label = labels[index];
                if (wide.includes(column)) cell.dataset.cardWide = '';
                if (hide.includes(column)) cell.dataset.cardHide = '';
            });
        });
    });
}

function parseColumnIndexes(value) {
    return String(value || '')
        .split(',')
        .map((item) => Number.parseInt(item, 10))
        .filter(Number.isFinite);
}

/** 反馈列表的截图角标：列表接口给的是 JSON 字符串，坏数据按 0 张处理 */
function screenshotCount(raw) {
    if (!raw) return 0;
    try {
        const keys = JSON.parse(raw);
        return Array.isArray(keys) ? keys.length : 0;
    } catch {
        return 0;
    }
}

// ===== Feedback Management =====
function renderFeedback(container, renderToken) {
    const header = document.createElement('div');
    header.className = 'page-header';
    header.innerHTML = `
        <h1 class="section-title">反馈管理</h1>
        <p class="section-subtitle">查看和回复用户反馈</p>
        <div class="feedback-filters" style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px;align-items:center">
            <select class="form-select" id="fb-type-filter" style="width:auto">
                <option value="">全部类型</option>
                <option value="FEATURE">功能建议</option>
                <option value="BUG">Bug 报告</option>
                <option value="UX">体验问题</option>
                <option value="OTHER">其他</option>
            </select>
            <select class="form-select" id="fb-status-filter" style="width:auto">
                <option value="">全部状态</option>
                <option value="PENDING">待处理</option>
                <option value="REPLIED">已回复</option>
                <option value="CLOSED">已关闭</option>
            </select>
            <input type="text" class="form-input" id="fb-nickname" placeholder="用户名搜索" style="width:160px">
            <button class="btn btn-primary btn-sm" id="fb-search">搜索</button>
        </div>
    `;
    container.appendChild(header);

    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap feedback-table-wrap';
    tableWrap.innerHTML = '<div class="loading-skeleton" style="height:200px;margin:16px"></div>';
    container.appendChild(tableWrap);

    const pagination = document.createElement('div');
    pagination.className = 'invite-pagination fb-pagination';
    container.appendChild(pagination);

    let offset = 0;
    const limit = 20;
    let loadSequence = 0;

    function loadAndRender() {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        const type = document.getElementById('fb-type-filter').value;
        const status = document.getElementById('fb-status-filter').value;
        const nickname = document.getElementById('fb-nickname').value.trim();
        const sequence = ++loadSequence;
        tableWrap.innerHTML = '<div class="loading-skeleton" style="height:200px;margin:16px"></div>';
        pagination.innerHTML = '';

        API.post('/fb/admin/list', { type, status, friendNickname: nickname, limit, offset }).then(page => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;
            const feedbacks = page.feedbacks || [];
            const typeLabels = { FEATURE: '功能', BUG: 'Bug', UX: '体验', OTHER: '其他' };
            const typeColors = { FEATURE: '#34d399', BUG: '#fb7185', UX: '#fbbf24', OTHER: '#9ca3af' };
            const statusLabels = { PENDING: '待处理', REPLIED: '已回复', CLOSED: '已关闭' };
            const statusColors = { PENDING: '#fb923c', REPLIED: '#10b981', CLOSED: '#9ca3af' };

            if (feedbacks.length === 0) {
                tableWrap.innerHTML = '<div class="empty-state" style="text-align:center;padding:32px;color:var(--text-dim)">暂无反馈</div>';
            } else {
                const rows = feedbacks.map(f => {
                    const typeLabel = escapeHtml(typeLabels[f.type] || f.type);
                    const typeColor = typeColors[f.type] || '#9ca3af';
                    const statusLabel = escapeHtml(statusLabels[f.status] || f.status);
                    const statusColor = statusColors[f.status] || '#9ca3af';
                    const shots = screenshotCount(f.screenshots);
                    const screenshotBadge = shots ? `📷 ${shots} 张` : '—';
                    const contentPreview = `<span class="cell-clamp">${escapeHtml(f.content || '—')}</span>`;
                    const displayId = escapeHtml(f.displayId || f.display_id || '');
                    return `<tr data-id="${escapeHtml(f.id)}" class="fb-row" style="cursor:pointer" tabindex="0" role="button" aria-label="查看反馈详情">
                        <td><span class="fb-id-badge" style="background:${typeColor}33;color:${typeColor}">${displayId}</span></td>
                        <td><span class="badge" style="background:${typeColor};color:white">${typeLabel}</span></td>
                        <td><span class="cell-ellipsis">${escapeHtml(f.friend_nickname || '—')}</span></td>
                        <td>${contentPreview}</td>
                        <td class="cell-nowrap">${screenshotBadge}</td>
                        <td class="cell-nowrap"><span style="color:${statusColor};font-weight:500">${statusLabel}</span></td>
                        <td class="cell-nowrap" style="color:var(--text-dim);font-size:12px">${formatTime(f.created_at)}</td>
                    </tr>`;
                }).join('');
                tableWrap.innerHTML = `
                    <div class="table-scroll"><table class="fb-list-table" data-card-wide="4">
                        <thead><tr><th>ID</th><th>类型</th><th>用户</th><th>内容</th><th>截图</th><th>状态</th><th>时间</th></tr></thead>
                        <tbody>${rows}</tbody>
                    </table></div>
                `;
                tableWrap.querySelectorAll('.fb-row').forEach(row => {
                    row.addEventListener('click', () => showFeedbackDetail(row.dataset.id, container, renderToken));
                    row.addEventListener('keydown', (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            showFeedbackDetail(row.dataset.id, container, renderToken);
                        }
                    });
                });
            }

            const total = page.total || 0;
            labelizeTables(tableWrap);
            const end = Math.min(offset + feedbacks.length, total);
            pagination.innerHTML = `<span>共 ${total} 条${total ? `，第 ${offset + 1} - ${end} 条` : ''}</span><div>
                <button class="btn btn-ghost btn-sm fb-prev" ${offset === 0 ? 'disabled' : ''}>上一页</button>
                <button class="btn btn-ghost btn-sm fb-next" ${page.hasMore ? '' : 'disabled'}>下一页</button>
            </div>`;
            pagination.querySelector('.fb-prev')?.addEventListener('click', () => { offset = Math.max(0, offset - limit); loadAndRender(); });
            pagination.querySelector('.fb-next')?.addEventListener('click', () => { offset += limit; loadAndRender(); });
        }).catch(err => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            tableWrap.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(err.message || '')}</span><button class="btn btn-sm btn-ghost" id="fbRetry">重试</button></div>`;
            document.getElementById('fbRetry')?.addEventListener('click', loadAndRender);
        });
    }

    document.getElementById('fb-search').addEventListener('click', () => { offset = 0; loadAndRender(); });
    document.getElementById('fb-nickname').addEventListener('keypress', (e) => { if (e.key === 'Enter') { offset = 0; loadAndRender(); } });
    loadAndRender();
}

function showFeedbackDetail(id, container, renderToken) {
    container.innerHTML = '<div class="loading-skeleton" style="height:400px;margin:16px"></div>';

    API.get(`/fb/admin/detail/${id}`).then(data => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        const f = data.feedback || {};
        const replies = data.replies || [];
        const typeColors = { FEATURE: '#34d399', BUG: '#fb7185', UX: '#fbbf24', OTHER: '#9ca3af' };
        const statusLabels = { PENDING: '待处理', REPLIED: '已回复', CLOSED: '已关闭' };
        const statusColors = { PENDING: '#fb923c', REPLIED: '#10b981', CLOSED: '#9ca3af' };
        const typeColor = typeColors[f.type] || '#9ca3af';
        const displayId = escapeHtml(f.displayId || f.display_id || '');

        let screenshotsHtml = '';
        if (f.screenshots) {
            try {
                const keys = JSON.parse(f.screenshots);
                if (Array.isArray(keys) && keys.length > 0) {
                    screenshotsHtml = `<div class="fb-screenshots" style="margin-top:12px"><strong>截图（${keys.length} 张）：</strong><div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">${keys.map(k => { const src = `${FEEDBACK_SCREENSHOT_BASE_URL}/${encodeURIComponent(k)}`; return `<img src="${escapeHtml(src)}" alt="截图" style="width:120px;height:120px;object-fit:cover;border-radius:8px;cursor:pointer" class="fb-screenshot-img" data-key="${escapeHtml(src)}">`; }).join('')}</div></div>`;
                }
            } catch { /* screenshots 不是合法 JSON，忽略 */ }
        }

        const repliesHtml = replies.map(r => {
            const rawRole = r.authorRole || r.author_role || 'developer';
            const role = rawRole === 'user' ? 'user' : 'developer';
            const screenshotsArr = Array.isArray(r.screenshots) ? r.screenshots : [];
            const roleLabel = role === 'developer' ? '自己' : '用户';
            const screenshotsInner = screenshotsArr.length > 0
                ? `<div class="fb-bubble-screenshots">${screenshotsArr.map(k => {
                    const src = `${FEEDBACK_SCREENSHOT_BASE_URL}/${encodeURIComponent(k)}`;
                    return `<img src="${escapeHtml(src)}" alt="截图" class="fb-screenshot-img" data-key="${escapeHtml(src)}">`;
                  }).join('')}</div>`
                : '';
            return `
                <div class="fb-bubble-row ${role}">
                    <div class="fb-bubble-avatar ${role}">${role === 'developer' ? '我' : 'U'}</div>
                    <div class="fb-bubble-content">
                        <div class="fb-bubble-meta">${roleLabel} · ${formatTime(r.createdAt || r.created_at)}</div>
                        <div class="fb-bubble ${role}">${escapeHtml(r.content || '')}${screenshotsInner}</div>
                    </div>
                </div>
            `;
        }).join('');

        container.innerHTML = `
            <div class="detail-heading">
                <div style="display:flex;align-items:flex-start;gap:12px;min-width:0">
                    <button class="back-btn" id="fb-back"><svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>返回</button>
                    <div class="detail-heading-copy"><h1 class="section-title">反馈详情</h1><p class="section-subtitle feedback-detail-subtitle"><span class="fb-id-badge" style="background:${typeColor}33;color:${typeColor}">${displayId}</span><span>· ${escapeHtml(f.friend_nickname || '')}</span></p></div>
                </div>
            </div>
            <div class="detail-grid">
                <div class="card detail-card">
                    <div class="card-header"><span class="card-title">反馈内容</span><span style="color:${statusColors[f.status] || '#9ca3af'};font-weight:500">${escapeHtml(statusLabels[f.status] || f.status || '')}</span></div>
                    <div style="display:grid;gap:10px;font-size:13px;margin-bottom:16px">
                        <div class="kv-row"><span style="color:var(--text-dim)">用户</span><span>${escapeHtml(f.friend_nickname || '—')}</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">时间</span><span>${formatTime(f.created_at)}</span></div>
                        ${f.trakt_username ? `<div class="kv-row"><span style="color:var(--text-dim)">Trakt</span><span>${escapeHtml(f.trakt_username)}</span></div>` : ''}
                        ${f.douban_username ? `<div class="kv-row"><span style="color:var(--text-dim)">豆瓣</span><span>${escapeHtml(f.douban_username)}</span></div>` : ''}
                        ${f.contact ? `<div class="kv-row"><span style="color:var(--text-dim)">联系方式</span><span>${escapeHtml(f.contact)}</span></div>` : ''}
                    </div>
                    <div style="padding:12px;border-radius:8px;background:var(--surface-2);white-space:pre-wrap;word-break:break-word">${escapeHtml(f.content || '')}</div>
                    ${screenshotsHtml}
                </div>
                <div class="card">
                    <div class="card-header"><span class="card-title">对话（${replies.length}）</span></div>
                    <div id="replies-list" class="fb-conversation" style="margin-bottom:16px">${repliesHtml || '<div class="empty-state" style="text-align:center;padding:16px;color:var(--text-dim)">暂无对话</div>'}</div>
                    ${f.status !== 'CLOSED' ? `
                        <div class="fb-reply-dock">
                        <div class="fb-upload-area">
                            <div id="fb-upload-thumbs" class="fb-upload-thumbs"></div>
                            <div style="display:flex;gap:8px;align-items:center;margin-top:8px">
                                <input type="file" id="fb-file-input" accept="image/jpeg,image/png,image/webp" multiple style="display:none">
                                <button class="btn btn-ghost btn-sm" id="fb-add-screenshot">+ 添加截图</button>
                                <span style="color:var(--text-dim);font-size:11px">最多 5 张，每张 ≤ 5MB</span>
                            </div>
                        </div>
                        <textarea class="form-input" id="reply-content" placeholder="输入回复..." rows="4" style="width:100%;margin-top:8px;margin-bottom:8px;resize:vertical"></textarea>
                        <div style="display:flex;gap:8px">
                            <button class="btn btn-primary btn-sm" id="fb-reply">回复</button>
                            <button class="btn btn-ghost btn-sm" id="fb-close">关闭反馈</button>
                        </div>
                        </div>
                    ` : '<div class="empty-state" style="text-align:center;padding:8px;color:var(--text-dim)">此反馈已关闭</div>'}
                </div>
                <div class="card">
                    <div class="card-header"><span class="card-title">应用信息</span></div>
                    <div style="display:grid;gap:10px;font-size:13px">
                        <div class="kv-row"><span style="color:var(--text-dim)">App 版本</span><span>${escapeHtml(f.app_version || '—')}</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">系统版本</span><span>${escapeHtml(f.os_version || '—')}</span></div>
                        <div class="kv-row"><span style="color:var(--text-dim)">设备型号</span><span>${escapeHtml(f.device_model || '—')}</span></div>
                    </div>
                </div>
            </div>
        `;
        document.getElementById('fb-back').addEventListener('click', () => navigate('feedback'));

        // 截图上传逻辑
        const pendingScreenshots = [];
        const fileInput = document.getElementById('fb-file-input');
        const addScreenshotBtn = document.getElementById('fb-add-screenshot');
        if (addScreenshotBtn) {
            addScreenshotBtn.addEventListener('click', () => fileInput.click());
            fileInput.addEventListener('change', async () => {
                const files = Array.from(fileInput.files || []);
                if (files.length === 0) return;
                if (pendingScreenshots.length + files.length > 5) {
                    showToast('最多 5 张截图', 'error');
                    return;
                }
                addScreenshotBtn.disabled = true;
                addScreenshotBtn.textContent = '上传中...';
                try {
                    for (const file of files) {
                        if (file.size > 5 * 1024 * 1024) {
                            showToast(`${file.name} 超过 5MB`, 'error');
                            continue;
                        }
                        const result = await API.uploadScreenshot('/fb/admin/upload-screenshot', file);
                        pendingScreenshots.push(result.key);
                        const objectUrl = URL.createObjectURL(file);
                        const thumb = document.createElement('div');
                        thumb.className = 'fb-upload-thumb';
                        thumb.dataset.key = result.key;
                        thumb.innerHTML = `<img src="${objectUrl}" alt="待上传"><button class="fb-upload-thumb-remove" type="button">×</button>`;
                        thumb.querySelector('.fb-upload-thumb-remove').addEventListener('click', () => {
                            const idx = pendingScreenshots.indexOf(result.key);
                            if (idx >= 0) pendingScreenshots.splice(idx, 1);
                            URL.revokeObjectURL(objectUrl);
                            thumb.remove();
                        });
                        document.getElementById('fb-upload-thumbs').appendChild(thumb);
                    }
                } catch (err) {
                    showToast('截图上传失败：' + (err.message || ''), 'error');
                } finally {
                    addScreenshotBtn.disabled = false;
                    addScreenshotBtn.textContent = '+ 添加截图';
                    fileInput.value = '';
                }
            });
        }

        document.getElementById('fb-reply')?.addEventListener('click', () => {
            const content = document.getElementById('reply-content').value.trim();
            if (!content) return;
            const btn = document.getElementById('fb-reply');
            btn.disabled = true;
            btn.textContent = '回复中...';
            API.post('/fb/admin/reply', {
                feedbackId: id,
                content,
                screenshots: pendingScreenshots.slice(0, 5),
            }).then(() => {
                showToast('回复成功');
                showFeedbackDetail(id, container, renderToken);
            }).catch(err => {
                btn.disabled = false;
                btn.textContent = '回复';
                showToast('回复失败：' + (err.message || ''), 'error');
            });
        });
        document.getElementById('fb-close')?.addEventListener('click', () => {
            const body = document.createElement('div');
            body.innerHTML = `
                <div class="confirm-danger-text">确定要关闭此反馈吗？</div>
                <div class="confirm-danger-impact">关闭后用户仍可查看但不能再回复。</div>
            `;
            const confirmBtn = Object.assign(document.createElement('button'), {
                className: 'btn btn-danger', textContent: '确认关闭', type: 'button',
            });
            const cancelBtn = Object.assign(document.createElement('button'), {
                className: 'btn btn-ghost', textContent: '取消', type: 'button',
            });
            modal.open({ title: '关闭反馈', body, footer: [cancelBtn, confirmBtn] });
            cancelBtn.addEventListener('click', () => modal.close());
            confirmBtn.addEventListener('click', async () => {
                confirmBtn.disabled = true;
                confirmBtn.textContent = '关闭中...';
                try {
                    await API.post('/fb/admin/close', { feedbackId: id });
                    modal.close();
                    showToast('已关闭');
                    showFeedbackDetail(id, container, renderToken);
                } catch (err) {
                    confirmBtn.disabled = false;
                    confirmBtn.textContent = '确认关闭';
                    showToast('关闭失败：' + (err.message || ''), 'error');
                }
            });
        });
        container.querySelectorAll('.fb-screenshot-img').forEach(img => {
            img.addEventListener('click', () => showImageLightbox(img.dataset.key, img.alt));
        });
    }).catch(err => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        container.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(err.message || '')}</span><button class="btn btn-sm btn-ghost" id="fbDetailRetry">重试</button></div>`;
        document.getElementById('fbDetailRetry')?.addEventListener('click', () => showFeedbackDetail(id, container, renderToken));
    });
}

// ===== Crash Logs =====
/** 崩溃日志 API 请求：走 /api/* Pages Functions 路径，401/登录重定向时与 API.request 一致跳 Access 登录；带超时并支持路由切换时取消 */
function crashFetch(path, options) {
    const controller = new AbortController();
    activeRequestControllers.add(controller);
    let timedOut = false;
    const timeoutId = setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, REQUEST_TIMEOUT_MS);
    return fetch(path, { ...options, signal: controller.signal })
        .then(res => {
            if (res.status === 401 || (res.redirected && res.url.includes('/cdn-cgi/access/login'))) {
                localStorage.removeItem('tts-access-token');
                window.location.href = API.getAccessLoginUrl();
                throw new Error('UNAUTHORIZED');
            }
            return res;
        })
        .catch(error => {
            if (error?.name === 'AbortError') {
                if (!timedOut) {
                    const cancelledError = new Error('Request cancelled');
                    cancelledError.code = 'REQUEST_CANCELLED';
                    throw cancelledError;
                }
                const timeoutError = new Error('Request timed out');
                timeoutError.code = 'REQUEST_TIMEOUT';
                throw timeoutError;
            }
            throw error;
        })
        .finally(() => {
            clearTimeout(timeoutId);
            activeRequestControllers.delete(controller);
        });
}

function crashStatusBadge(status) {
    const fixed = status === 'fixed';
    return `<span class="badge ${fixed ? 'badge-success' : 'badge-failure'}"><span class="badge-dot"></span>${fixed ? '已修复' : '待修复'}</span>`;
}

/** 组装适合粘贴给 AI 的崩溃报告文本 */
function buildCrashReport(e) {
    const line = (label, value) => `${label}: ${String(value ?? '').trim() || '—'}`;
    return [
        '【崩溃日志】',
        line('崩溃时间', e.timestamp ? new Date(e.timestamp).toLocaleString('zh-CN', { hour12: false }) : ''),
        line('App 版本', e.appVersion),
        line('Android 版本', e.androidVersion),
        line('设备型号', e.device),
        line('崩溃页面', e.currentPage),
        line('状态', e.status === 'fixed' ? '已修复' : '待修复'),
        '',
        '【堆栈跟踪】',
        e.stackTrace || '（无）',
        e.recentActions ? `\n【最近操作】\n${e.recentActions}` : '',
    ].join('\n');
}

function renderCrashLogs(container, renderToken) {
    const header = document.createElement('div');
    header.className = 'page-header';
    header.innerHTML = `
        <h1 class="section-title">崩溃日志</h1>
        <p class="section-subtitle">查看应用崩溃堆栈记录（储存在 Cloudflare KV）</p>
    `;
    container.appendChild(header);

    // 统计卡片
    const statsGrid = document.createElement('div');
    statsGrid.className = 'stat-grid';
    statsGrid.innerHTML = [1, 2, 3, 4].map(() => `<div class="stat-card"><div class="loading-skeleton" style="height:90px"></div></div>`).join('');
    container.appendChild(statsGrid);

    // 工具栏：搜索 + 状态筛选 + 刷新
    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <input type="text" class="form-input" id="crash-search" placeholder="搜索堆栈 / 设备 / 版本..." style="width:220px" aria-label="搜索崩溃日志">
        <select class="form-select" id="crash-status-filter" aria-label="状态筛选" style="width:auto">
            <option value="all">全部状态</option>
            <option value="open">待修复</option>
            <option value="fixed">已修复</option>
        </select>
        <button class="btn btn-primary btn-sm" id="crash-refresh">刷新</button>
    `;
    container.appendChild(toolbar);

    const searchInput = toolbar.querySelector('#crash-search');
    const statusFilter = toolbar.querySelector('#crash-status-filter');

    // 表格 + 分页（复用审计日志结构）
    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap';
    tableWrap.innerHTML = `
        <div class="table-scroll"><table data-card-wide="7">
            <thead><tr><th>时间</th><th>设备</th><th>Android</th><th>App 版本</th><th>当前页面</th><th>状态</th><th>堆栈预览</th></tr></thead>
            <tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody>
        </table></div>
        <div class="invite-pagination crash-pagination"></div>
    `;
    container.appendChild(tableWrap);

    let offset = 0;
    const limit = 20;
    let loadSequence = 0;

    function loadAndRender() {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        const sequence = ++loadSequence;
        const tbody = tableWrap.querySelector('tbody');
        const pagination = tableWrap.querySelector('.crash-pagination');
        tbody.innerHTML = `<tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr>`;
        pagination.innerHTML = '';

        const params = new URLSearchParams({ limit, offset, status: statusFilter.value });
        const q = searchInput.value.trim();
        if (q) params.set('search', q);

        crashFetch(`/api/crash-logs?${params.toString()}`).then(res => {
            if (!res.ok) throw new Error(`Request failed (${res.status})`);
            return res.json();
        }).then(data => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            if (sequence !== loadSequence) return;

            // 统计卡片（全量口径，不受筛选影响）
            if (data.stats) {
                statsGrid.innerHTML = `
                    <div class="stat-card"><div class="stat-label">总崩溃</div><div class="stat-value stat-accent">${data.stats.total}</div><div class="stat-hint">累计记录</div></div>
                    <div class="stat-card"><div class="stat-label">近 7 天</div><div class="stat-value">${data.stats.last7d}</div><div class="stat-hint">7 天内崩溃</div></div>
                    <div class="stat-card"><div class="stat-label">待修复</div><div class="stat-value ${data.stats.open > 0 ? 'stat-danger' : 'stat-success'}">${data.stats.open}</div><div class="stat-hint">未处理</div></div>
                    <div class="stat-card"><div class="stat-label">涉及设备</div><div class="stat-value">${data.stats.devices}</div><div class="stat-hint">去重设备数</div></div>
                `;
            }

            const entries = data.entries || [];
            if (entries.length === 0) {
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-title">无记录</div><div class="empty-desc">当前条件下没有崩溃日志</div></div></td></tr>`;
            } else {
                tbody.innerHTML = entries.map(e => `
                    <tr class="crash-row" data-id="${escapeHtml(e.id)}" style="cursor:pointer" tabindex="0" role="button" aria-label="查看崩溃详情">
                        <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim);white-space:nowrap">${formatDateTime(e.timestamp)}</td>
                        <td>${entityCell(e.device, '', '未知设备')}</td>
                        <td style="font-size:12px">${escapeHtml(e.androidVersion || '—')}</td>
                        <td style="font-size:12px">${escapeHtml(e.appVersion || '—')}</td>
                        <td style="font-size:12px;overflow-wrap:anywhere">${escapeHtml(e.currentPage || '—')}</td>
                        <td>${crashStatusBadge(e.status)}</td>
                        <td style="font-family:var(--font-mono);font-size:11px;color:var(--text-dim);max-width:280px;white-space:pre-wrap;word-break:break-word">${escapeHtml(e.stackTracePreview || '')}</td>
                    </tr>
                `).join('');
                tableWrap.querySelectorAll('.crash-row').forEach(row => {
                    row.addEventListener('click', () => navigate('crash-log-detail', { id: row.dataset.id }));
                    row.addEventListener('keydown', (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            navigate('crash-log-detail', { id: row.dataset.id });
                        }
                    });
                });
            }

            const end = Math.min(offset + entries.length, data.total);
            labelizeTables(tableWrap);
            pagination.innerHTML = `<span>共 ${data.total} 条${data.total ? `，第 ${offset + 1} - ${end} 条` : ''}</span><div>
                <button class="btn btn-ghost btn-sm crash-prev" ${offset === 0 ? 'disabled' : ''}>上一页</button>
                <button class="btn btn-ghost btn-sm crash-next" ${data.hasMore ? '' : 'disabled'}>下一页</button>
            </div>`;
            pagination.querySelector('.crash-prev')?.addEventListener('click', () => { offset = Math.max(0, offset - limit); loadAndRender(); });
            pagination.querySelector('.crash-next')?.addEventListener('click', () => { offset += limit; loadAndRender(); });
        }).catch(err => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            const tbody = tableWrap.querySelector('tbody');
            tbody.innerHTML = `<tr><td colspan="7"><div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(err))}</span><button class="btn btn-sm btn-ghost" id="crashRetry">重试</button></div></td></tr>`;
            tbody.querySelector('#crashRetry')?.addEventListener('click', loadAndRender);
        });
    }

    searchInput.addEventListener('input', debounce(() => { offset = 0; loadAndRender(); }, 400));
    statusFilter.addEventListener('change', () => { offset = 0; loadAndRender(); });
    toolbar.querySelector('#crash-refresh').addEventListener('click', () => loadAndRender());
    loadAndRender();
}

/** 渲染崩溃日志详情视图（数据已就绪时调用；PATCH 后复用响应直接渲染避免整页重拉） */
function renderCrashLogDetailView(e, container, renderToken) {
    if (renderToken !== state.renderToken || !container.isConnected) return;
    const id = e.id || '';
    const fixed = e.status === 'fixed';
    const recentActions = String(e.recentActions || '').trim();
    container.innerHTML = `
        <div class="detail-heading">
            <div style="display:flex;align-items:flex-start;gap:12px;min-width:0">
                <button class="back-btn" id="crash-back"><svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>返回</button>
                <div class="detail-heading-copy"><h1 class="section-title">崩溃日志详情</h1><p class="section-subtitle">ID: <span style="font-family:var(--font-mono)">${escapeHtml(id || 'unknown')}</span></p></div>
            </div>
            <div class="detail-heading-actions">
                <button class="btn btn-ghost btn-sm" id="crash-copy">复制给 AI</button>
                <button class="btn ${fixed ? 'btn-ghost' : 'btn-primary'} btn-sm" id="crash-toggle-status">${fixed ? '重新打开' : '标记已修复'}</button>
            </div>
        </div>
        <div class="detail-grid">
            <div class="card">
                <div class="card-header"><span class="card-title">崩溃信息</span>${crashStatusBadge(e.status)}</div>
                <div style="display:grid;gap:10px;font-size:13px;margin-bottom:16px">
                    <div class="kv-row"><span style="color:var(--text-dim)">时间</span><span>${formatDateTime(e.timestamp)}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">App 版本</span><span>${escapeHtml(e.appVersion || '—')}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">Android 版本</span><span>${escapeHtml(e.androidVersion || '—')}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">设备型号</span><span>${escapeHtml(e.device || '—')}</span></div>
                    <div class="kv-row"><span style="color:var(--text-dim)">当前页面</span><span>${escapeHtml(e.currentPage || '—')}</span></div>
                </div>
                <div class="card-header" style="margin-top:12px"><span class="card-title">堆栈跟踪</span></div>
                <div style="background:var(--surface-2);padding:12px;border-radius:8px;white-space:pre-wrap;word-break:break-word;font-family:var(--font-mono);font-size:12px;line-height:1.6">${escapeHtml(e.stackTrace || '无堆栈信息')}</div>
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">最近操作</span></div>
                <div style="background:var(--surface-2);padding:12px;border-radius:8px;white-space:pre-wrap;word-break:break-word;font-family:var(--font-mono);font-size:12px;line-height:1.6">${escapeHtml(recentActions || '无记录')}</div>
            </div>
        </div>
    `;

    // 返回列表：走完整路由导航，render() 会清空 main 再重绘，避免内容叠加
    document.getElementById('crash-back')?.addEventListener('click', () => navigate('crash-logs'));

    // 复制 AI 友好报告
    document.getElementById('crash-copy')?.addEventListener('click', async () => {
        try {
            await copyText(buildCrashReport(e));
            showToast('已复制崩溃信息，可直接粘贴给 AI');
        } catch (err) {
            showToast('复制失败：' + (err.message || ''), 'error');
        }
    });

    // 标记已修复 / 重新打开：PATCH 成功后用响应直接重渲染，避免整页重拉
    document.getElementById('crash-toggle-status')?.addEventListener('click', () => {
        const btn = document.getElementById('crash-toggle-status');
        btn.disabled = true;
        const next = fixed ? 'open' : 'fixed';
        crashFetch(`/api/crash-logs/${id}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: next }),
        }).then(res => {
            if (!res.ok) throw new Error('更新失败');
            return res.json();
        }).then(updated => {
            showToast(fixed ? '已重新打开' : '已标记修复');
            renderCrashLogDetailView(updated, container, renderToken);
        }).catch(err => {
            btn.disabled = false;
            showToast('更新失败：' + (err.message || ''), 'error');
        });
    });
}

function showCrashLogDetail(id, container, renderToken) {
    container.innerHTML = '<div class="loading-skeleton" style="height:400px;margin:16px"></div>';

    crashFetch(`/api/crash-logs/${id}`).then(res => {
        if (!res.ok) throw new Error('未找到记录');
        return res.json();
    }).then(e => {
        renderCrashLogDetailView(e, container, renderToken);
    }).catch(err => {
        if (renderToken !== state.renderToken || !container.isConnected) return;
        container.innerHTML = `<div class="error-banner"><span class="error-text">加载失败：${escapeHtml(errorMessage(err))}</span><button class="btn btn-sm btn-ghost" id="crashDetailRetry">重试</button></div>`;
        document.getElementById('crashDetailRetry')?.addEventListener('click', () => showCrashLogDetail(id, container, renderToken));
    });
}

// ===== Init =====
Theme.init();
Palettes.init();
window.addEventListener('hashchange', () => { parseHash(); updateActiveNav(); render(); });
parseHash();
updateActiveNav();
render();

// 侧栏网关状态：启动与每次路由切换时探测，避免静态"在线"误导
function updateGatewayStatus() {
    const dot = document.getElementById('gatewayStatusDot');
    const text = document.getElementById('gatewayStatusText');
    if (!dot || !text) return;
    API.getStats().then(s => {
        const online = s.gatewayHealth === 'ok';
        dot.classList.toggle('status-dot-offline', !online);
        text.textContent = online ? '网关在线' : '网关异常';
    }).catch(() => {
        dot.classList.add('status-dot-offline');
        text.textContent = '网关离线';
    });
}
updateGatewayStatus();

// ===== AI 健康 =====
const AI_PROVIDER_META = {
    // 百炼是当前主力（额度按模型独立、吞吐不受智谱账号级限制），模型列表按 handler 的
    // 梯队顺序排（qwen3.6-flash 主模型在最前），用于额度耗尽后逐个探测换挡状态。
    bailian: {
        label: '阿里云百炼',
        consoleUrl: 'https://modelstudio.console.alibabacloud.com/ap-southeast-1/costing-balance/free-quota',
        models: [
            'qwen3.6-flash', 'qwen3.7-flash', 'deepseek-v4-flash', 'qwen-flash', 'qwen3.8-flash',
            'qwen3.5-flash', 'glm-5.2', 'glm-5.1', 'kimi-k3', 'qwen3.6-plus', 'qwen3.6-max-preview',
            'qwen3-max', 'qwen-max', 'qwen-plus', 'qwen-turbo', 'deepseek-v4-pro', 'deepseek-v4-pro-0813',
            'qwen3.7-plus', 'qwen3.8-max', 'qwen3.7-max',
        ],
    },
    zhipu: {
        label: '智谱 GLM',
        consoleUrl: 'https://open.bigmodel.cn/console/overview',
        models: ['glm-5.3-flash', 'glm-4.7', 'glm-4.7-flash', 'glm-4.6v', 'glm-4.5-air'],
    },
    // 与 bailian 同一规矩：按 handler 的链上顺序排（agnes-3.0-flash 首选、2.5-flash 同家降级），
    // 逐个探测就能看出当前换到了哪一档。
    agnes: { label: 'Agnes AI', consoleUrl: 'https://agnes-ai.com/', models: ['agnes-3.0-flash', 'agnes-2.5-flash'] },
    // MiniMax 走 aiportx 网关，只接 M3 一档（无 reasoning、361~393 字符/秒、一套 13 题 134s/0 降级格）。
    // 同批 M2.x 都带 reasoning 且计入 max_tokens（M2.7 只有 23 字符/秒），已按用户决定不接。
    // 探针列表必须与 handler 的白名单一致，否则探测会被 VALID_MODELS 挡成 400。
    minimax: {
        label: 'MiniMax（aiportx）',
        consoleUrl: 'https://platform.minimaxi.com/',
        models: ['MiniMax-M3'],
    },
    mimo: { label: '小米 MiMo', consoleUrl: 'https://platform.xiaomimimo.com/', models: ['mimo-v2.5-pro'] },
};
const AI_HEALTH_STATE = { window: '24h', data: null, providerFilter: 'all' };

function renderAiHealth(container, renderToken) {
    container.innerHTML = `
        <h1 class="section-title">AI 健康</h1>
        <p class="section-subtitle">供应商探针与真实流量健康度</p>
        <div class="ai-health-toolbar">
            <div class="segmented" id="aiHealthWindow">
                <button type="button" data-window="24h">24 小时</button>
                <button type="button" data-window="7d">7 天</button>
            </div>
            <button type="button" class="btn btn-sm" id="aiProbeAllBtn">全部探测</button>
        </div>
        <div class="ai-provider-grid" id="aiProviderCards"></div>
        <div class="card" style="margin-top:16px">
            <div class="card-header"><span class="card-title">事件明细（最近 50 条）</span>
                <select id="aiEventProvider" class="ai-probe-model" aria-label="供应商筛选">
                    <option value="all">全部供应商</option>
                    ${Object.entries(AI_PROVIDER_META).map(([key, meta]) => `<option value="${key}">${meta.label}</option>`).join('')}
                </select>
            </div>
            <div id="aiEventTable"></div>
        </div>
    `;
    container.querySelector('#aiHealthWindow').addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-window]');
        if (!btn) return;
        AI_HEALTH_STATE.window = btn.dataset.window;
        loadAiHealth(container, renderToken);
    });
    const providerFilterEl = container.querySelector('#aiEventProvider');
    if (providerFilterEl) {
        providerFilterEl.value = AI_HEALTH_STATE.providerFilter;
        providerFilterEl.addEventListener('change', () => {
            AI_HEALTH_STATE.providerFilter = providerFilterEl.value;
            loadAiHealth(container, renderToken);
        });
    }
    container.querySelector('#aiProbeAllBtn').addEventListener('click', () => runAiProbeAll(container));
    loadAiHealth(container, renderToken);
}

async function loadAiHealth(container, renderToken) {
    const cardsEl = container.querySelector('#aiProviderCards');
    const tableEl = container.querySelector('#aiEventTable');
    if (!cardsEl || !tableEl) return;
    container.querySelectorAll('#aiHealthWindow button').forEach(b => {
        const active = b.dataset.window === AI_HEALTH_STATE.window;
        b.classList.toggle('active', active);
        b.setAttribute('aria-pressed', String(active));
    });
    const providerFilterEl = container.querySelector('#aiEventProvider');
    if (providerFilterEl) providerFilterEl.value = AI_HEALTH_STATE.providerFilter;
    cardsEl.innerHTML = [1, 2, 3].map(() => '<div class="loading-skeleton" style="height:180px"></div>').join('');
    tableEl.innerHTML = '<div class="loading-skeleton" style="height:120px"></div>';
    try {
        const data = await API.getAiHealth(AI_HEALTH_STATE.window);
        if (renderToken !== state.renderToken || !container.isConnected) return;
        AI_HEALTH_STATE.data = data;
        cardsEl.innerHTML = Object.entries(AI_PROVIDER_META).map(([key, meta]) =>
            aiProviderCardHtml(key, meta, data)).join('');
        bindAiCardActions(container);
        tableEl.innerHTML = aiEventTableHtml(data);
        labelizeTables(tableEl);
    } catch (error) {
        if (renderToken !== state.renderToken) return;
        cardsEl.innerHTML = `<div class="card" style="grid-column:1/-1"><div class="empty-state"><div class="empty-title">加载失败</div><div class="empty-desc">${escapeHtml(errorMessage(error))}</div></div></div>`;
        tableEl.innerHTML = '';
    }
}

function aiHealthLight(providerKey, data) {
    const agg = (data.aggregates || []).filter(a => a.provider === providerKey);
    const total = agg.reduce((sum, a) => sum + Number(a.total || 0), 0);
    const success = agg.reduce((sum, a) => sum + Number(a.success || 0), 0);
    const recent = (data.recent || []).filter(r => r.provider === providerKey);
    const lastFail = recent.find(r => r.outcome !== 'success');
    const lastProbe = recent.find(r => r.source === 'probe');
    const hasInvalid = recent.some(r => r.outcome === 'invalid_output');
    const recentUpstreamError = recent.some(r => r.outcome === 'upstream_error' && (Date.now() / 1000 - r.created_at) < 86400);
    const coolingKeys = (data.keyPool || []).filter(k => providerKey === 'agnes' && k.status === 'COOLING' && k.cooldownRemainingSec > 0);
    let level = 'ok';
    if ((total > 0 && success / total < 0.8) || recentUpstreamError) level = 'fail';
    else if (hasInvalid || coolingKeys.length > 0) level = 'warn';
    return { level, total, successRate: total > 0 ? success / total : null, lastFail, lastProbe, hasInvalid };
}

function aiProviderCardHtml(providerKey, meta, data) {
    const s = aiHealthLight(providerKey, data);
    const aggRows = (data.aggregates || []).filter(a => a.provider === providerKey);
    const aggWithDuration = aggRows.filter(a => a.avg_duration_ms != null);
    const durationTotal = aggWithDuration.reduce((sum, a) => sum + Number(a.total || 0), 0);
    const avgMs = durationTotal > 0 ? Math.round(aggWithDuration.reduce((sum, a) => sum + Number(a.avg_duration_ms) * Number(a.total || 0), 0) / durationTotal) : null;
    const avgText = avgMs == null ? '—' : avgMs >= 1000 ? `${(avgMs / 1000).toFixed(1)}s` : `${avgMs} ms`;
    const ratePct = s.successRate != null ? Math.round(s.successRate * 100) : null;
    const rateLevel = ratePct == null ? 'none' : ratePct >= 80 ? 'ok' : ratePct >= 50 ? 'warn' : 'fail';
    const lastErr = s.lastFail;
    const lastErrText = lastErr
        ? `${formatTime(lastErr.created_at)}${lastErr.http_status && lastErr.http_status !== 200 ? ` HTTP ${lastErr.http_status}` : ''}${lastErr.error_code ? ` · ${escapeHtml(lastErr.error_code)}` : ''}`
        : '无';
    const keyPoolRows = providerKey === 'agnes' ? (data.keyPool || []).map(k => {
        const tail = escapeHtml(String(k.fingerprint || '').slice(-4));
        const cooling = k.status === 'COOLING' && k.cooldownRemainingSec > 0;
        const statusText = cooling
            ? `冷却 ${Math.ceil(k.cooldownRemainingSec / 60)}m`
            : k.status === 'ACTIVE' ? '可用'
            : k.status === 'INVALID' ? '已失效'
            : k.status === 'COOLING' ? '冷却中'
            : (k.status || '—');
        const chipClass = cooling ? 'cooling' : k.status === 'ACTIVE' ? 'active' : 'idle';
        return `<span class="ai-key-chip ${chipClass}"><span class="mono">…${tail}</span>${escapeHtml(statusText)}</span>`;
    }).join('') : '';
    return `
    <div class="card ai-provider-card" data-provider="${providerKey}" data-level="${s.level}">
        <span class="ai-status-strip" aria-hidden="true"></span>
        <div class="card-header">
            <span class="card-title"><span class="health-dot ${s.level === 'ok' ? 'ok' : s.level === 'fail' ? 'fail' : 'warn'}"></span>${meta.label}</span>
            <span class="ai-probe-note">${s.lastProbe ? `最近探测 ${formatTime(s.lastProbe.created_at)}` : '未探测'}</span>
        </div>
        <div class="ai-stat-row">
            <div class="ai-stat"><span class="ai-stat-value">${s.total}</span><span class="ai-stat-label">请求量</span></div>
            <div class="ai-stat"><span class="ai-stat-value ${rateLevel === 'fail' ? 'text-danger' : ''}">${ratePct != null ? `${ratePct}%` : '—'}</span><span class="ai-stat-label">成功率</span></div>
            <div class="ai-stat"><span class="ai-stat-value">${avgText}</span><span class="ai-stat-label">平均耗时</span></div>
        </div>
        <div class="ai-rate-bar"><span class="ai-rate-fill ${rateLevel}" style="width:${ratePct != null ? ratePct : 0}%"></span></div>
        <div class="ai-last-error ${lastErr ? 'is-error' : ''}">最近错误 · ${lastErrText}</div>
        ${keyPoolRows ? `<div class="ai-key-wrap"><span class="ai-key-label">Key 池</span><div class="ai-key-chips">${keyPoolRows}</div></div>` : ''}
        <div class="ai-card-actions">
            <select class="ai-probe-model" aria-label="探测模型">
                ${meta.models.map(m => `<option value="${m}">${m}</option>`).join('')}
            </select>
            <button type="button" class="btn btn-ghost btn-sm js-ai-probe-one">探测</button>
            <a class="btn btn-ghost btn-sm ai-console-link" href="${meta.consoleUrl}" target="_blank" rel="noopener noreferrer">控制台 ↗</a>
        </div>
    </div>`;
}

function bindAiCardActions(container) {
    container.querySelectorAll('.ai-provider-card .js-ai-probe-one').forEach(btn => {
        btn.addEventListener('click', async () => {
            const card = btn.closest('.ai-provider-card');
            const provider = card.dataset.provider;
            const model = card.querySelector('.ai-probe-model').value;
            btn.disabled = true;
            btn.textContent = '探测中…';
            try {
                const result = await API.probeAiHealth({ provider, model });
                const r = result.results?.[0];
                if (r?.outcome === 'success') showToast(`${provider} 探测成功（${r.durationMs} ms）`);
                else showToast(`${provider} 探测失败：${r?.errorCode || '未知'}${r?.httpStatus ? ` HTTP ${r.httpStatus}` : ''}`, 'error');
                await loadAiHealth(container, state.renderToken);
            } catch (error) {
                showToast(`探测失败：${errorMessage(error)}`, 'error');
                btn.disabled = false;
                btn.textContent = '探测';
            }
        });
    });
}

async function runAiProbeAll(container) {
    const btn = container.querySelector('#aiProbeAllBtn');
    if (!btn || btn.disabled) return;
    btn.disabled = true;
    btn.textContent = '探测中…';
    try {
        const result = await API.probeAiHealth({ all: true });
        const failCount = (result.results || []).filter(r => r.outcome !== 'success').length;
        if (failCount === 0) showToast('三家探测全部成功');
        else showToast(`${failCount} 家探测失败，见卡片详情`, 'error');
        await loadAiHealth(container, state.renderToken);
    } catch (error) {
        showToast(`探测失败：${errorMessage(error)}`, 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = '全部探测';
    }
}

function aiEventTableHtml(data) {
    const rows = (data.recent || []).filter(r =>
        AI_HEALTH_STATE.providerFilter === 'all' || r.provider === AI_HEALTH_STATE.providerFilter);
    if (rows.length === 0) {
        return '<div class="empty-state"><div class="empty-title">窗口内无事件</div><div class="empty-desc">尚无真实 AI 流量或探针记录</div></div>';
    }
    return `<div class="table-scroll ai-event-scroll"><table class="ai-event-table" data-card-wide="7"><thead><tr>
        <th>时间</th><th>来源</th><th>路由</th><th>供应商</th><th>模型</th><th>耗时</th><th>结果</th>
    </tr></thead><tbody>${rows.map(r => `
        <tr>
            <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${formatDateTime(r.created_at)}</td>
            <td>${r.source === 'probe' ? '探针' : '流量'}</td>
            <td>${escapeHtml(r.route || '—')}</td>
            <td>${escapeHtml(r.provider)}</td>
            <td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(r.model)}</td>
            <td>${r.duration_ms != null ? `${r.duration_ms} ms` : '—'}</td>
            <td class="${r.outcome === 'success' ? 'text-ok' : 'text-danger'}">${r.outcome === 'success' ? '成功' : `${r.outcome === 'invalid_output' ? '输出不合格' : '上游错误'}${r.error_code ? ` · ${escapeHtml(r.error_code)}` : ''}${r.http_status && r.http_status !== 200 ? ` · HTTP ${r.http_status}` : ''}`}</td>
        </tr>`).join('')}</tbody></table></div>`;
}
