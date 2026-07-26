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
            <button class="palette-swatch${key === this.current ? ' active' : ''}" data-palette="${key}">
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
            el.classList.toggle('active', el.dataset.palette === this.current);
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
            note: source.note,
            status: source.status,
            devices: source.active_devices,
            maxDevices: source.max_devices,
            expiresAt: source.expires_at,
            lastSeen: source.last_seen,
            devicesList: (data.devices || []).map((d) => ({
            id: d.id,
            name: d.device_name,
            appVersion: d.app_version,
            lastSeen: d.last_seen_at,
            status: d.status,
            activatedAt: d.activated_at,
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
        MIGRATION_DEVICE_MISMATCH: '该设备不属于此朋友，无法迁移',
        DEVICE_ALREADY_BOUND: '此设备已绑定，请改用激活邀请码或先撤销原绑定',
        MAX_DEVICES_BELOW_ACTIVE: '设备上限不能低于当前活跃设备数',
        REQUEST_TIMEOUT: '请求超时，请检查网络后重试',
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
    const label = { ACTIVE: '活跃', DISABLED: '已禁用', REVOKED: '已撤销', AVAILABLE: '可用', USED: '已使用', EXPIRED: '已过期', SUCCESS: '成功', FAILURE: '失败' };
    return `<span class="badge ${map[status] || ''}"><span class="badge-dot"></span>${escapeHtml(label[status] || status)}</span>`;
}

function inviteKindLabel(kind) {
    return kind === 'MIGRATION' ? '迁移' : kind === 'ACTIVATION' ? '激活' : String(kind || '—');
}

function eventLabel(type) {
    const map = {
        ACTIVATE: '激活', MIGRATE: '迁移', REFRESH: '刷新令牌', REFRESH_REPLAY: '重放检测',
        DEVICE_REVOKE: '撤销设备', FRIEND_DISABLE: '禁用朋友', FRIEND_CREATE: '创建朋友', FRIEND_UPDATE: '更新朋友',
        INVITE_CREATE: '生成邀请码', INVITE_REVOKE: '撤销邀请码',
    };
    return escapeHtml(map[type] || type);
}

function auditDetail(log) {
    const detail = log.detail || '';
    const details = {
        'invite_kind:ACTIVATION': '邀请码类型：激活',
        'invite_kind:MIGRATION': '邀请码类型：迁移',
        invite_revoked: '邀请码已撤销',
        friend_created: '朋友已创建',
        friend_disabled: '朋友已禁用',
        device_revoked: '设备已撤销',
        'refresh:rotated': '刷新成功，旧刷新令牌已轮换',
        'refresh:invalid_signature': '客户端签名校验失败',
        'refresh:invalid_challenge': '刷新挑战已过期或与设备不匹配',
        'refresh:device_or_friend_revoked': '设备或朋友已撤销',
        'refresh:friend_expired': '朋友有效期已到，设备授权已失效',
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
        const fields = detail.slice(7).split(',').map((field) => labels[field] || field);
        return `已更新：${fields.join('、')}`;
    }
    return '—';
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
    document.querySelectorAll('.nav-link, .sidebar-link').forEach(el => {
        const active = el.dataset.route === state.route;
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
    const [route, id] = hash.slice(1).split('/');
    state.route = route || 'dashboard';
    state.params = id ? { id } : {};
}

// ===== Sidebar toggle =====
const menuToggle = document.getElementById('menuToggle');
const sidebar = document.getElementById('sidebar');
const sidebarScrim = document.getElementById('sidebarScrim');
function closeSidebar({ restoreFocus = true } = {}) {
    sidebar.classList.remove('open');
    sidebarScrim.hidden = true;
    menuToggle.setAttribute('aria-expanded', 'false');
    if (restoreFocus) menuToggle.focus();
}
function openSidebar() {
    sidebar.classList.add('open');
    sidebarScrim.hidden = false;
    menuToggle.setAttribute('aria-expanded', 'true');
}
menuToggle.addEventListener('click', () => {
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
            case 'audit': renderAudit(main, renderToken); break;
            default: renderDashboard(main, renderToken);
        }
        main.style.opacity = '1';
    }, 100);
}

// ===== Dashboard =====
function renderDashboard(container, renderToken) {
    const title = document.createElement('div');
    title.innerHTML = `<h1 class="section-title">仪表盘</h1><p class="section-subtitle">授权系统概览</p>`;
    container.appendChild(title);

    const stats = document.createElement('div');
    stats.className = 'stat-grid';
    stats.innerHTML = [1,2,3,4].map(() => `<div class="stat-card"><div class="loading-skeleton" style="height:90px"></div></div>`).join('');
    container.appendChild(stats);

    const grid = document.createElement('div');
    grid.className = 'detail-grid';
    grid.innerHTML = `
        <div class="card">
            <div class="card-header"><span class="card-title">最近授权失败</span></div>
            <div class="loading-skeleton" style="height:200px"></div>
        </div>
        <div class="card">
            <div class="card-header"><span class="card-title">网关健康</span></div>
            <div class="loading-skeleton" style="height:200px"></div>
        </div>
    `;
    container.appendChild(grid);

    Promise.allSettled([API.getStats(), API.getAuditLogs({ limit: 50, offset: 0 })]).then(([statsResult, auditResult]) => {
        if (renderToken !== state.renderToken || !container.isConnected) return;

        if (statsResult.status === 'fulfilled') {
            const s = statsResult.value;
            stats.innerHTML = `
                <div class="stat-card"><div class="stat-label">活跃朋友</div><div class="stat-value stat-accent">${s.totalFriends}</div><div class="stat-hint">当前可用</div></div>
                <div class="stat-card"><div class="stat-label">活跃设备</div><div class="stat-value stat-success">${s.activeDevices}</div><div class="stat-hint">在线设备</div></div>
                <div class="stat-card"><div class="stat-label">24h 失败</div><div class="stat-value ${s.recentFailures > 0 ? 'stat-danger' : ''}">${s.recentFailures}</div><div class="stat-hint">需关注</div></div>
                <div class="stat-card"><div class="stat-label">管理服务</div><div class="stat-value" style="font-size:20px">${s.gatewayHealth === 'ok' ? '🟢 正常' : '🔴 异常'}</div><div class="stat-hint">D1 与统计查询</div></div>
            `;
            grid.innerHTML = dashboardHealthCard(s);
        } else {
            stats.innerHTML = dashboardErrorCard('统计加载失败', statsResult.reason, container, renderToken);
        }

        if (auditResult.status === 'fulfilled') {
            const failures = auditResult.value.logs.filter(l => l.result === 'FAILURE').slice(0, 5);
            grid.insertAdjacentHTML('afterbegin', dashboardFailuresCard(failures));
        } else {
            grid.insertAdjacentHTML('afterbegin', dashboardErrorCard('失败记录加载失败', auditResult.reason, container, renderToken));
        }
    });
}

function dashboardHealthCard(stats) {
    const healthy = stats.gatewayHealth === 'ok';
    return `<div class="card">
        <div class="card-header"><span class="card-title">管理服务健康</span></div>
        <div class="health-indicator"><span class="health-dot ${healthy ? 'ok' : 'fail'}"></span><span>${healthy ? 'D1 与统计查询正常' : '管理服务异常'}</span></div>
        <div style="margin-top:16px">
            <div class="health-row"><span>D1 数据库</span><span style="color:${healthy ? 'var(--success)' : 'var(--danger)'}">${healthy ? '● 正常' : '● 异常'}</span></div>
            <div class="health-row"><span>上游代理</span><span style="color:var(--text-dim)">未在此页探测</span></div>
        </div>
    </div>`;
}

function dashboardFailuresCard(failures) {
    return `<div class="card">
        <div class="card-header"><span class="card-title">最近授权失败</span><a href="#/audit" class="btn btn-ghost btn-sm">查看全部</a></div>
        ${failures.length === 0 ? '<div class="empty-state"><div class="empty-title">无失败记录</div><div class="empty-desc">系统运行正常</div></div>' :
        `<div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>朋友</th><th>错误</th></tr></thead><tbody>${failures.map(l => `<tr><td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatTime(l.createdAt)}</td><td>${eventLabel(l.eventType)}</td><td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(l.friendId)}</td><td style="color:var(--danger)">${escapeHtml(l.errorCode || '—')}</td></tr>`).join('')}</tbody></table></div>`}
    </div>`;
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
    return `<div class="card"><div class="error-banner"><span class="error-text">${escapeHtml(title)}：${escapeHtml(errorMessage(error))}</span><button class="btn btn-sm btn-ghost" id="${retryId}">重试</button></div></div>`;
}

// ===== Friends list =====
function renderFriends(container, renderToken) {
    const header = document.createElement('div');
    header.innerHTML = `<h1 class="section-title">朋友</h1><p class="section-subtitle">管理朋友白名单和设备</p>`;
    container.appendChild(header);

    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <div class="toolbar-search"><input type="search" class="form-input" id="searchInput" aria-label="搜索朋友" autocomplete="off" placeholder="搜索昵称或备注..."></div>
        <div class="toolbar-filters">
            <select class="form-select" id="statusFilter" aria-label="朋友状态筛选" style="width:auto">
                <option value="">全部状态</option>
                <option value="ACTIVE">活跃</option>
                <option value="DISABLED">已禁用</option>
            </select>
            <button class="btn btn-primary" id="createFriendBtn">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                创建朋友
            </button>
        </div>
    `;
    container.appendChild(toolbar);

    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap';
    tableWrap.innerHTML = `<div class="table-scroll"><table><thead><tr><th>昵称</th><th>状态</th><th>设备</th><th>上限</th><th>有效期至</th><th>最近活动</th><th>操作</th></tr></thead><tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div><div class="invite-pagination friends-pagination"></div>`;
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
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-icon">👥</div><div class="empty-title">${friendsPage.total === 0 && !query && !filter ? '还没有朋友' : '没有匹配结果'}</div><div class="empty-desc">${friendsPage.total === 0 && !query && !filter ? '点击右上角按钮创建第一个朋友' : '尝试调整搜索或筛选条件'}</div>${friendsPage.total === 0 && !query && !filter ? '<button class="btn btn-primary js-create-friend" style="margin-top:8px">+ 创建朋友</button>' : ''}</div></td></tr>`;
                tbody.querySelector('.js-create-friend')?.addEventListener('click', showCreateFriendModal);
            } else {
                tbody.innerHTML = friends.map(f => `
                    <tr>
                        <td><div style="font-weight:600">${escapeHtml(f.nickname)}</div>${f.note ? `<div style="font-size:12px;color:var(--text-dim)">${escapeHtml(f.note)}</div>` : ''}</td>
                        <td>${statusBadge(f.status)}</td>
                        <td>${f.devices}/${f.maxDevices}</td>
                        <td>${f.maxDevices}</td>
                        <td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatDate(f.expiresAt)}</td>
                        <td style="color:var(--text-dim);font-size:12px">${formatTime(f.lastSeen)}</td>
                        <td><button class="btn btn-ghost btn-sm js-friend-detail" data-id="${escapeHtml(f.id)}">详情</button></td>
                    </tr>
                `).join('');
                tbody.querySelectorAll('.js-friend-detail').forEach(button => {
                    button.addEventListener('click', () => navigate('friend-detail', { id: button.dataset.id }));
                });
            }
            const end = Math.min(friendsPage.offset + friends.length, friendsPage.total);
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
    title.innerHTML = `<div class="detail-heading-copy"><h1 class="section-title">朋友详情</h1><p class="section-subtitle" id="detailSubtitle">加载中...</p></div><div class="detail-heading-actions"><button class="btn btn-ghost btn-sm" id="editBtn" hidden><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></svg>编辑信息</button><button class="btn btn-danger btn-sm detail-disable-btn" id="disableBtn" hidden>禁用朋友</button></div>`;
    container.appendChild(title);

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
        disableButton.textContent = f.status === 'DISABLED' ? '重新启用朋友' : '禁用朋友';
        content.innerHTML = `
                <div class="card detail-card">
                    <div class="card-header"><span class="card-title">基本信息</span><span>${statusBadge(f.status)}</span></div>
                    <div style="display:grid;gap:14px;font-size:13px">
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">昵称</span><span style="font-weight:500">${escapeHtml(f.nickname)}</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">备注</span><span>${escapeHtml(f.note || '—')}</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">设备上限</span><span>${f.maxDevices} 台</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">有效期至</span><span>${formatDate(f.expiresAt)}</span></div>
                    </div>
                </div>
                <div class="card">
                    <div class="card-header"><span class="card-title">设备列表</span><button class="btn btn-primary btn-sm" id="createInviteBtn" ${f.status !== 'ACTIVE' ? 'disabled' : ''}>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        生成邀请码
                    </button></div>
                    <div class="table-scroll"><table><thead><tr><th>设备名称</th><th>App 版本</th><th>状态</th><th>最后活动</th><th>操作</th></tr></thead><tbody>
                        ${f.devicesList.map(d => `
                            <tr>
                                <td>${escapeHtml(d.name || '—')}</td>
                                <td style="font-family:var(--font-mono);font-size:12px">${escapeHtml(d.appVersion || '—')}</td>
                                <td>${statusBadge(d.status)}</td>
                                <td style="color:var(--text-dim);font-size:12px">${formatTime(d.lastSeen)}</td>
                                <td>${d.status === 'ACTIVE' ? `<button class="btn btn-danger btn-sm js-revoke-device" data-device-id="${escapeHtml(d.id)}" data-device-name="${escapeHtml(d.name || '—')}">撤销</button>` : '—'}</td>
                            </tr>
                        `).join('')}
                    </tbody></table></div>
                </div>
        `;
        editButton.addEventListener('click', () => showEditFriendModal(f));
        content.querySelector('#createInviteBtn')?.addEventListener('click', () => showCreateInviteModal(f.id, f.nickname, f.devices));
        disableButton.addEventListener('click', () => {
            if (f.status === 'DISABLED') showEnableFriendModal(f.id, f.nickname);
            else showDisableFriendModal(f.id, f.nickname, f.devices);
        });
        content.querySelectorAll('.js-revoke-device').forEach(button => {
            button.addEventListener('click', () => showRevokeDeviceModal(button.dataset.deviceId, button.dataset.deviceName));
        });
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
            '<div class="invite-pagination"><span>第 ' + (invites.length ? offset + 1 : 0) + ' - ' + (offset + invites.length) + ' 条</span>' +
                '<div><button class="btn btn-ghost btn-sm invite-prev" ' + (offset === 0 ? 'disabled' : '') + '>上一页</button>' +
                '<button class="btn btn-ghost btn-sm invite-next" ' + (data.hasMore ? '' : 'disabled') + '>下一页</button></div></div>';
        section.querySelector('.invite-status-filter').value = status;
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
            <option value="FRIEND_DISABLE">禁用朋友</option>
            <option value="FRIEND_ENABLE">启用朋友</option>
            <option value="FRIEND_CREATE">创建朋友</option>
            <option value="FRIEND_UPDATE">更新朋友</option>
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
    tableWrap.innerHTML = `<div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>详情</th><th>朋友</th><th>设备</th><th>结果</th><th>错误码</th></tr></thead><tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div><div class="invite-pagination audit-pagination"></div>`;
    container.appendChild(tableWrap);

    const eventFilter = toolbar.querySelector('#eventFilter');
    const resultFilter = toolbar.querySelector('#resultFilter');
    const timeFilter = toolbar.querySelector('#timeFilter');

    function loadAndRender(offset = 0) {
        const eFilter = eventFilter.value;
        const rFilter = resultFilter.value;
        const tFilter = timeFilter.value;
        const now = Date.now();
        const cutoff = tFilter === '24h' ? now - 86400000 : tFilter === '7d' ? now - 86400000 * 7 : tFilter === '30d' ? now - 86400000 * 30 : now - 86400000 * 90;
        const params = { limit: 50, offset, from: Math.floor(cutoff / 1000) };
        if (eFilter) params.eventType = eFilter;
        if (rFilter) params.result = rFilter;

        API.getAuditLogs(params).then(page => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
            const logs = page.logs;

            const tbody = tableWrap.querySelector('tbody');
            if (logs.length === 0) {
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-title">无记录</div><div class="empty-desc">当前筛选条件下没有审计日志</div></div></td></tr>`;
            } else {
                tbody.innerHTML = logs.map(l => `
                    <tr>
                        <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${new Date(l.createdAt).toLocaleString('zh-CN')}</td>
                        <td>${eventLabel(l.eventType)}</td>
                        <td>${escapeHtml(auditDetail(l))}</td>
                        <td>${entityCell(l.friendName, l.friendId, '未知朋友')}</td>
                        <td>${entityCell(l.deviceName, l.deviceId, '未知设备')}</td>
                        <td>${statusBadge(l.result)}</td>
                        <td style="color:var(--danger);font-size:12px">${escapeHtml(l.errorCode || '—')}</td>
                    </tr>
                `).join('');
            }
            const pagination = tableWrap.querySelector('.audit-pagination');
            const end = Math.min(page.offset + page.logs.length, page.total);
            pagination.innerHTML = `<span>共 ${page.total} 条，${page.total ? `第 ${page.offset + 1} - ${end} 条` : '暂无记录'}</span><div><button class="btn btn-ghost btn-sm audit-prev" ${page.offset === 0 ? 'disabled' : ''}>上一页</button><button class="btn btn-ghost btn-sm audit-next" ${page.hasMore ? '' : 'disabled'}>下一页</button></div>`;
            pagination.querySelector('.audit-prev')?.addEventListener('click', () => loadAndRender(Math.max(0, page.offset - page.limit)));
            pagination.querySelector('.audit-next')?.addEventListener('click', () => loadAndRender(page.offset + page.limit));
        }).catch(err => {
            if (renderToken !== state.renderToken || !container.isConnected) return;
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
        <p class="form-hint">朋友长期有效；邀请码单独设置有效期。</p>
    `;

    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary';
    submitBtn.type = 'submit';
    submitBtn.textContent = '创建';
    bindSubmitButton(form, submitBtn);

    modal.open({
        title: '创建朋友',
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
        const note = form.note.value.trim();
        const maxDevices = Number.parseInt(form.maxDevices.value, 10);
        if (!nickname) {
            showFormError(form, '请输入昵称。');
            form.nickname.focus();
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
            note,
            maxDevices,
        };
        try {
            const friend = await API.createFriend(data);
            modal.close();
            showToast(`已创建朋友 "${friend.nickname}"`);
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
        title: `编辑朋友 · ${friend.nickname}`,
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
        const note = form.note.value.trim();
        const maxDevices = Number.parseInt(form.maxDevices.value, 10);
        if (!nickname) {
            showFormError(form, '请输入昵称。');
            form.nickname.focus();
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
            await API.updateFriend(friend.id, { nickname, note, maxDevices, expiresAt });
            modal.close();
            showToast(`朋友「${nickname}」已更新`);
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
                <option value="ACTIVATION">激活（新朋友）</option>
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
        <div class="invite-warning">⚠️ 此邀请码只显示一次，关闭此窗口后无法再次查看。请立即复制并发送给朋友。</div>
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

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = '撤销中...';
        try {
            await API.revokeDevice(deviceId);
            modal.close();
            showToast('设备已撤销');
            navigate('friend-detail', { id: state.params.id });
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认撤销';
            showToast('撤销失败: ' + errorMessage(err), 'error');
        }
    });
}

function showDisableFriendModal(friendId, friendName, deviceCount) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要禁用朋友 <strong>${escapeHtml(friendName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">⚠️ 禁用后该朋友下全部 ${Number(deviceCount) || 0} 台设备和会话立即失效。需要手动启用才能恢复。</div>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-danger';
    confirmBtn.textContent = '确认禁用';

    modal.open({
        title: '禁用朋友',
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
            showToast('朋友已禁用');
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
        <div class="confirm-danger-text">确定要重新启用朋友 <strong>${escapeHtml(friendName)}</strong> 吗？</div>
        <div class="confirm-danger-impact">启用只恢复朋友状态，不会恢复已撤销设备和会话。需要重新生成邀请码并重新绑定设备。</div>
    `;

    const confirmBtn = document.createElement('button');
    confirmBtn.className = 'btn btn-primary';
    confirmBtn.textContent = '确认启用';

    modal.open({
        title: '重新启用朋友',
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
            showToast('朋友已重新启用');
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

// ===== Init =====
Theme.init();
Palettes.init();
window.addEventListener('hashchange', () => { parseHash(); updateActiveNav(); render(); });
parseHash();
updateActiveNav();
render();
