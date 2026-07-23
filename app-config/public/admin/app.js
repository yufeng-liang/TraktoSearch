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
        });

        panel.addEventListener('click', (e) => {
            const swatch = e.target.closest('.palette-swatch');
            if (swatch) {
                this.apply(swatch.dataset.palette);
                panel.hidden = true;
            }
        });

        document.addEventListener('click', (e) => {
            if (!panel.contains(e.target) && !btn.contains(e.target)) {
                panel.hidden = true;
            }
        });
    },
};

// ===== API Client =====
// API 根域名（部署时替换为实际 Worker 域名）
const API_BASE = localStorage.getItem('tts-api-base') || 'https://auth-worker.douban-movie-api-peak.workers.dev';

const API = {
    // Access JWT（优先 localStorage，回退读取当前域名 Cookie）
    getToken() {
        const localToken = localStorage.getItem('tts-access-token');
        if (localToken) return localToken;

        const match = document.cookie.match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
        if (!match) return null;

        const token = decodeURIComponent(match[1]);
        localStorage.setItem('tts-access-token', token);
        return token;
    },

    // 通用请求
    async request(method, path, body = null) {
        const token = this.getToken();
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const response = await fetch(`${API_BASE}${path}`, {
            method,
            headers,
            body: body ? JSON.stringify(body) : null,
        });

        // 401 → Access 登录失效，跳转登录
        if (response.status === 401) {
            localStorage.removeItem('tts-access-token');
            window.location.href = this.getAccessLoginUrl();
            throw new Error('UNAUTHORIZED');
        }

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || 'Request failed');
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

    async getFriends() {
        const data = await this.get('/admin/friends');
        return data.friends.map((f) => ({
            id: f.id,
            nickname: f.nickname,
            note: f.note,
            status: f.status,
            devices: f.active_devices,
            maxDevices: f.max_devices,
            expiresAt: f.expires_at,
            lastSeen: f.last_seen,
        }));
    },

    async getFriend(id) {
        // 朋友详情 + 设备列表
        const friends = await this.getFriends();
        const f = friends.find((x) => x.id === id);
        if (!f) throw new Error('NOT_FOUND');

        const deviceData = await this.get(`/admin/friends/${id}`);
        f.devicesList = deviceData.devices.map((d) => ({
            id: d.id,
            name: d.device_name,
            appVersion: d.app_version,
            lastSeen: d.last_seen_at,
            status: d.status,
            activatedAt: d.activated_at,
        }));
        return f;
    },

    async getAuditLogs(params = {}) {
        const qs = new URLSearchParams(params).toString();
        const data = await this.get(`/admin/audit-logs${qs ? '?' + qs : ''}`);
        return data.logs.map((l) => ({
            id: l.id,
            eventType: l.event_type,
            friendId: l.friend_id,
            deviceId: l.device_id,
            result: l.result,
            errorCode: l.error_code,
            createdAt: l.created_at * 1000, // Unix 秒 → 毫秒
        }));
    },

    async createFriend(data) {
        return this.post('/admin/friends', data);
    },

    async createInvite(friendId, kind, expiresInDays) {
        return this.post(`/admin/friends/${friendId}/invites`, { kind, expiresInDays });
    },

    async revokeDevice(deviceId) {
        return this.post(`/admin/devices/${deviceId}/revoke`, {});
    },

    async disableFriend(friendId) {
        return this.post(`/admin/friends/${friendId}/disable`, {});
    },
};

// ===== State =====
const state = {
    route: 'dashboard',
    params: {},
};

// ===== Utils =====
function formatTime(ts) {
    if (!ts) return '—';
    const diff = Date.now() - ts;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + ' 天前';
    return new Date(ts).toLocaleDateString('zh-CN');
}

function formatDate(ts) {
    if (!ts) return '长期有效';
    return new Date(ts).toLocaleDateString('zh-CN');
}

function statusBadge(status) {
    const map = {
        ACTIVE: 'badge-active', DISABLED: 'badge-disabled', REVOKED: 'badge-revoked',
        SUCCESS: 'badge-success', FAILURE: 'badge-failure',
    };
    const label = { ACTIVE: '活跃', DISABLED: '已禁用', REVOKED: '已撤销', SUCCESS: '成功', FAILURE: '失败' };
    return `<span class="badge ${map[status] || ''}"><span class="badge-dot"></span>${label[status] || status}</span>`;
}

function eventLabel(type) {
    const map = { ACTIVATE: '激活', REFRESH: '刷新令牌', REFRESH_REPLAY: '重放检测', DEVICE_REVOKE: '撤销设备', FRIEND_DISABLE: '禁用朋友' };
    return map[type] || type;
}

// ===== Toast =====
function showToast(message, type = 'success') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `<span>${type === 'success' ? '✓' : '✕'}</span><span>${message}</span>`;
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
        if (this.onClose) this.onClose();
        this.onClose = null;
        this._prevFocus?.focus();
    },

    _trapFocus() {
        const focusables = this.overlay.querySelectorAll('button, input, select, [href], [tabindex]:not([tabindex="-1"])');
        if (!focusables.length) return;
        const first = focusables[0], last = focusables[focusables.length - 1];
        this.overlay.addEventListener('keydown', function(e) {
            if (e.key === 'Tab') {
                if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
            }
            if (e.key === 'Escape') modal.close();
        });
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
    window.location.hash = '#' + route + (params.id ? '/' + params.id : '');
    updateActiveNav();
    render();
}

function updateActiveNav() {
    document.querySelectorAll('.nav-link, .sidebar-link').forEach(el => {
        el.classList.toggle('active', el.dataset.route === state.route);
    });
}

function parseHash() {
    const hash = window.location.hash.slice(1) || '/dashboard';
    const [route, id] = hash.slice(1).split('/');
    state.route = route || 'dashboard';
    state.params = id ? { id } : {};
}

// ===== Sidebar toggle =====
document.getElementById('menuToggle').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('open');
});
document.getElementById('sidebar').addEventListener('click', (e) => {
    if (e.target.closest('.sidebar-link')) {
        document.getElementById('sidebar').classList.remove('open');
    }
});

// ===== Theme toggle =====
document.getElementById('themeToggle').addEventListener('click', () => Theme.toggle());

// ===== Logout =====
document.getElementById('logoutBtn').addEventListener('click', () => {
    localStorage.removeItem('tts-access-token');
    localStorage.removeItem('tts-access-team');
    localStorage.removeItem('tts-api-base');
    document.getElementById('adminEmail').textContent = '未登录';
    showToast('已退出登录');
    // 可选：跳转 Access 登录
    // window.location.href = API.getAccessLoginUrl();
});

// ===== Render router =====
function render() {
    const main = document.getElementById('mainContent');
    main.style.opacity = '0';
    setTimeout(() => {
        main.innerHTML = '';
        switch (state.route) {
            case 'dashboard': renderDashboard(main); break;
            case 'friends': renderFriends(main); break;
            case 'friend-detail': renderFriendDetail(main); break;
            case 'audit': renderAudit(main); break;
            default: renderDashboard(main);
        }
        main.style.opacity = '1';
    }, 100);
}

// ===== Dashboard =====
function renderDashboard(container) {
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

    API.getStats().then(s => {
        stats.innerHTML = `
            <div class="stat-card"><div class="stat-label">朋友总数</div><div class="stat-value stat-accent">${s.totalDevices}</div><div class="stat-hint">活跃朋友</div></div>
            <div class="stat-card"><div class="stat-label">活跃设备</div><div class="stat-value stat-success">${s.activeDevices}</div><div class="stat-hint">在线设备</div></div>
            <div class="stat-card"><div class="stat-label">24h 失败</div><div class="stat-value ${s.recentFailures > 0 ? 'stat-danger' : ''}">${s.recentFailures}</div><div class="stat-hint">需关注</div></div>
            <div class="stat-card"><div class="stat-label">网关状态</div><div class="stat-value" style="font-size:20px">${s.gatewayHealth === 'ok' ? '🟢 正常' : '🔴 异常'}</div><div class="stat-hint">所有服务</div></div>
        `;
    });

    Promise.all([API.getAuditLogs(), API.getStats()]).then(([logs, s]) => {
        const failures = logs.filter(l => l.result === 'FAILURE').slice(0, 5);
        grid.innerHTML = `
            <div class="card">
                <div class="card-header"><span class="card-title">最近授权失败</span><a href="#/audit" class="btn btn-ghost btn-sm">查看全部</a></div>
                ${failures.length === 0 ? '<div class="empty-state"><div class="empty-title">无失败记录</div><div class="empty-desc">系统运行正常</div></div>' :
                `<div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>朋友</th><th>错误</th></tr></thead><tbody>${failures.map(l => `<tr><td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatTime(l.createdAt)}</td><td>${eventLabel(l.eventType)}</td><td style="font-family:var(--font-mono);font-size:12px">${l.friendId}</td><td style="color:var(--danger)">${l.errorCode || '—'}</td></tr>`).join('')}</tbody></table></div>`}
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">网关健康</span></div>
                <div class="health-indicator"><span class="health-dot ${s.gatewayHealth === 'ok' ? 'ok' : 'fail'}"></span><span>${s.gatewayHealth === 'ok' ? '所有服务正常' : '检测到异常'}</span></div>
                <div style="margin-top:16px">
                    <div class="health-row"><span>授权网关</span><span style="color:var(--success)">● 正常</span></div>
                    <div class="health-row"><span>TMDB 代理</span><span style="color:var(--success)">● 正常</span></div>
                    <div class="health-row"><span>Trakt 代理</span><span style="color:var(--success)">● 正常</span></div>
                    <div class="health-row"><span>豆瓣代理</span><span style="color:var(--success)">● 正常</span></div>
                </div>
            </div>
        `;
    });
}

// ===== Friends list =====
function renderFriends(container) {
    const header = document.createElement('div');
    header.innerHTML = `<h1 class="section-title">朋友</h1><p class="section-subtitle">管理朋友白名单和设备</p>`;
    container.appendChild(header);

    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <div class="toolbar-search"><input type="text" class="form-input" id="searchInput" placeholder="搜索昵称或备注..."></div>
        <div class="toolbar-filters">
            <select class="form-select" id="statusFilter" style="width:auto">
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
    tableWrap.innerHTML = `<div class="table-scroll"><table><thead><tr><th>昵称</th><th>状态</th><th>设备</th><th>上限</th><th>到期</th><th>最近活动</th><th>操作</th></tr></thead><tbody><tr><td colspan="7"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div>`;
    container.appendChild(tableWrap);

    const searchInput = toolbar.querySelector('#searchInput');
    const statusFilter = toolbar.querySelector('#statusFilter');
    toolbar.querySelector('#createFriendBtn').addEventListener('click', showCreateFriendModal);

    function loadAndRender() {
        const query = searchInput.value.toLowerCase();
        const filter = statusFilter.value;
        API.getFriends().then(friends => {
            let filtered = friends;
            if (query) filtered = filtered.filter(f => f.nickname.toLowerCase().includes(query) || (f.note && f.note.toLowerCase().includes(query)));
            if (filter) filtered = filtered.filter(f => f.status === filter);

            const tbody = tableWrap.querySelector('tbody');
            if (filtered.length === 0) {
                tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="empty-icon">👥</div><div class="empty-title">${friends.length === 0 ? '还没有朋友' : '没有匹配结果'}</div><div class="empty-desc">${friends.length === 0 ? '点击右上角按钮创建第一个朋友' : '尝试调整搜索或筛选条件'}</div>${friends.length === 0 ? '<button class="btn btn-primary" onclick="showCreateFriendModal()" style="margin-top:8px">+ 创建朋友</button>' : ''}</div></td></tr>`;
                return;
            }
            tbody.innerHTML = filtered.map(f => `
                <tr>
                    <td><div style="font-weight:600">${f.nickname}</div>${f.note ? `<div style="font-size:12px;color:var(--text-dim)">${f.note}</div>` : ''}</td>
                    <td>${statusBadge(f.status)}</td>
                    <td>${f.devices}/${f.maxDevices}</td>
                    <td>${f.maxDevices}</td>
                    <td style="color:var(--text-dim);font-family:var(--font-mono);font-size:12px">${formatDate(f.expiresAt)}</td>
                    <td style="color:var(--text-dim);font-size:12px">${formatTime(f.lastSeen)}</td>
                    <td><button class="btn btn-ghost btn-sm" onclick="navigate('friend-detail',{id:'${f.id}'})">详情</button></td>
                </tr>
            `).join('');
        }).catch(err => {
            tbody.innerHTML = `<tr><td colspan="7"><div class="error-banner"><span class="error-text">加载失败: ${err.message}</span><button class="btn btn-sm btn-ghost" onclick="loadAndRender()">重试</button></div></td></tr>`;
        });
    }

    searchInput.addEventListener('input', debounce(loadAndRender, 300));
    statusFilter.addEventListener('change', loadAndRender);
    loadAndRender();
}

// ===== Friend detail =====
function renderFriendDetail(container) {
    const id = state.params.id;
    const title = document.createElement('div');
    title.innerHTML = `<h1 class="section-title">朋友详情</h1><p class="section-subtitle" id="detailSubtitle">加载中...</p>`;
    container.appendChild(title);

    const content = document.createElement('div');
    content.className = 'detail-grid';
    content.innerHTML = `<div class="card"><div class="loading-skeleton" style="height:300px"></div></div><div class="card"><div class="loading-skeleton" style="height:300px"></div></div>`;
    container.appendChild(content);

    API.getFriend(id).then(f => {
        title.querySelector('#detailSubtitle').textContent = `${f.nickname} · ${f.devices} 台设备`;
        content.innerHTML = `
            <div>
                <div class="card detail-card">
                    <div class="card-header"><span class="card-title">基本信息</span><span>${statusBadge(f.status)}</span></div>
                    <div style="display:grid;gap:14px;font-size:13px">
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">昵称</span><span style="font-weight:500">${f.nickname}</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">备注</span><span>${f.note || '—'}</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">设备上限</span><span>${f.maxDevices} 台</span></div>
                        <div style="display:flex;justify-content:space-between"><span style="color:var(--text-dim)">到期时间</span><span>${formatDate(f.expiresAt)}</span></div>
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
                                <td>${d.name}</td>
                                <td style="font-family:var(--font-mono);font-size:12px">${d.appVersion}</td>
                                <td>${statusBadge(d.status)}</td>
                                <td style="color:var(--text-dim);font-size:12px">${formatTime(d.lastSeen)}</td>
                                <td>${d.status === 'ACTIVE' ? `<button class="btn btn-ghost btn-sm" onclick="showRevokeDeviceModal('${d.id}','${d.name}')">撤销</button>` : '—'}</td>
                            </tr>
                        `).join('')}
                    </tbody></table></div>
                </div>
            </div>
            <div>
                <div class="card">
                    <div class="card-header"><span class="card-title">危险操作</span></div>
                    <div style="display:flex;flex-direction:column;gap:14px">
                        <button class="btn btn-danger" id="disableBtn" ${f.status === 'DISABLED' ? 'disabled' : ''}>${f.status === 'DISABLED' ? '已禁用' : '禁用该朋友'}</button>
                        <p style="font-size:12px;color:var(--text-dim)">禁用后该朋友下所有设备和会话立即失效。</p>
                    </div>
                </div>
            </div>
        `;
        content.querySelector('#createInviteBtn')?.addEventListener('click', () => showCreateInviteModal(f.id, f.nickname));
        content.querySelector('#disableBtn')?.addEventListener('click', () => showDisableFriendModal(f.id, f.nickname, f.devices));
    }).catch(err => {
        content.innerHTML = `<div class="error-banner"><span class="error-text">加载失败: ${err.message}</span><button class="btn btn-sm btn-ghost" onclick="renderFriendDetail(document.getElementById('mainContent'))">重试</button></div>`;
    });
}

// ===== Audit logs =====
function renderAudit(container) {
    const title = document.createElement('div');
    title.innerHTML = `<h1 class="section-title">审计日志</h1><p class="section-subtitle">安全事件记录（保留 90 天）</p>`;
    container.appendChild(title);

    const toolbar = document.createElement('div');
    toolbar.className = 'toolbar';
    toolbar.innerHTML = `
        <select class="form-select" id="eventFilter" style="width:auto">
            <option value="">全部事件</option>
            <option value="ACTIVATE">激活</option>
            <option value="REFRESH">刷新令牌</option>
            <option value="REFRESH_REPLAY">重放检测</option>
            <option value="DEVICE_REVOKE">撤销设备</option>
            <option value="FRIEND_DISABLE">禁用朋友</option>
        </select>
        <select class="form-select" id="resultFilter" style="width:auto">
            <option value="">全部结果</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILURE">失败</option>
        </select>
        <select class="form-select" id="timeFilter" style="width:auto">
            <option value="24h">最近 24 小时</option>
            <option value="7d">最近 7 天</option>
            <option value="30d">最近 30 天</option>
        </select>
    `;
    container.appendChild(toolbar);

    const tableWrap = document.createElement('div');
    tableWrap.className = 'table-wrap';
    tableWrap.innerHTML = `<div class="table-scroll"><table><thead><tr><th>时间</th><th>事件</th><th>朋友</th><th>设备</th><th>结果</th><th>错误码</th></tr></thead><tbody><tr><td colspan="6"><div class="loading-skeleton" style="height:200px;margin:16px"></div></td></tr></tbody></table></div>`;
    container.appendChild(tableWrap);

    const eventFilter = toolbar.querySelector('#eventFilter');
    const resultFilter = toolbar.querySelector('#resultFilter');
    const timeFilter = toolbar.querySelector('#timeFilter');

    function loadAndRender() {
        const eFilter = eventFilter.value;
        const rFilter = resultFilter.value;
        const tFilter = timeFilter.value;
        const now = Date.now();
        const cutoff = tFilter === '24h' ? now - 86400000 : tFilter === '7d' ? now - 86400000 * 7 : now - 86400000 * 30;

        API.getAuditLogs().then(logs => {
            let filtered = logs.filter(l => l.createdAt >= cutoff);
            if (eFilter) filtered = filtered.filter(l => l.eventType === eFilter);
            if (rFilter) filtered = filtered.filter(l => l.result === rFilter);

            const tbody = tableWrap.querySelector('tbody');
            if (filtered.length === 0) {
                tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state"><div class="empty-title">无记录</div><div class="empty-desc">当前筛选条件下没有审计日志</div></div></td></tr>`;
                return;
            }
            tbody.innerHTML = filtered.map(l => `
                <tr>
                    <td style="font-family:var(--font-mono);font-size:12px;color:var(--text-dim)">${new Date(l.createdAt).toLocaleString('zh-CN')}</td>
                    <td>${eventLabel(l.eventType)}</td>
                    <td style="font-family:var(--font-mono);font-size:12px">${l.friendId}</td>
                    <td style="font-family:var(--font-mono);font-size:12px">${l.deviceId || '—'}</td>
                    <td>${statusBadge(l.result)}</td>
                    <td style="color:var(--danger);font-size:12px">${l.errorCode || '—'}</td>
                </tr>
            `).join('');
        });
    }

    eventFilter.addEventListener('change', loadAndRender);
    resultFilter.addEventListener('change', loadAndRender);
    timeFilter.addEventListener('change', loadAndRender);
    loadAndRender();
}

// ===== Modals =====
function showCreateFriendModal() {
    const form = document.createElement('form');
    form.innerHTML = `
        <div class="form-group"><label class="form-label">昵称 *</label><input class="form-input" name="nickname" required maxlength="32" placeholder="例如：小明"></div>
        <div class="form-group"><label class="form-label">备注</label><input class="form-input" name="note" maxlength="64" placeholder="可选，如：大学同学"></div>
        <div class="form-row">
            <div class="form-group"><label class="form-label">设备上限</label><input class="form-input" name="maxDevices" type="number" min="1" max="10" value="2"></div>
            <div class="form-group"><label class="form-label">到期时间</label><input class="form-input" name="expiresAt" type="date"></div>
        </div>
        <p class="form-hint">留空到期时间表示长期有效。</p>
    `;

    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary';
    submitBtn.type = 'submit';
    submitBtn.textContent = '创建';

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
        submitBtn.disabled = true;
        submitBtn.textContent = '创建中...';
        const data = {
            nickname: form.nickname.value,
            note: form.note.value,
            maxDevices: parseInt(form.maxDevices.value),
            expiresAt: form.expiresAt.value ? new Date(form.expiresAt.value).getTime() : null
        };
        try {
            const friend = await API.createFriend(data);
            modal.close();
            showToast(`已创建朋友 "${friend.nickname}"`);
            navigate('friend-detail', { id: friend.id });
        } catch (err) {
            submitBtn.disabled = false;
            submitBtn.textContent = '创建';
            showToast('创建失败: ' + err.message, 'error');
        }
    });
}

function showCreateInviteModal(friendId, friendName) {
    const form = document.createElement('form');
    form.innerHTML = `
        <div class="form-group"><label class="form-label">邀请类型</label>
            <select class="form-select" name="kind">
                <option value="ACTIVATION">激活（新朋友）</option>
                <option value="MIGRATION">迁移（现有设备）</option>
            </select>
        </div>
        <div class="form-group"><label class="form-label">有效期</label>
            <select class="form-select" name="expiresIn">
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

    modal.open({
        title: `生成邀请码 · ${friendName}`,
        body: form,
        footer: [
            Object.assign(document.createElement('button'), { className: 'btn btn-ghost', textContent: '取消', type: 'button' }),
            submitBtn
        ]
    });

    modal.footer.querySelector('.btn-ghost').addEventListener('click', () => modal.close());

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        submitBtn.disabled = true;
        submitBtn.textContent = '生成中...';
        const kind = form.kind.value;
        const days = parseInt(form.expiresIn.value);
        try {
            const invite = await API.createInvite(friendId, kind, days);
            showInviteCodeResult(invite.code, invite.expiresAt);
        } catch (err) {
            submitBtn.disabled = false;
            submitBtn.textContent = '生成邀请码';
            showToast('生成失败: ' + err.message, 'error');
        }
    });
}

function showInviteCodeResult(code, expiresAt) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="invite-warning">⚠️ 此邀请码只显示一次，关闭此窗口后无法再次查看。请立即复制并发送给朋友。</div>
        <div class="invite-code-box"><div class="invite-code" id="inviteCodeDisplay">${code}</div></div>
        <p style="font-size:12px;color:var(--text-dim);text-align:center">有效期至 ${new Date(expiresAt).toLocaleDateString('zh-CN')}</p>
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
    copyBtn.addEventListener('click', () => {
        navigator.clipboard.writeText(code).then(() => {
            copyBtn.textContent = '已复制 ✓';
            showToast('邀请码已复制');
            setTimeout(() => { copyBtn.textContent = '复制邀请码'; }, 2000);
        }).catch(() => {
            showToast('复制失败，请手动选择文本复制', 'error');
        });
    });
}

function showRevokeDeviceModal(deviceId, deviceName) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要撤销设备 <strong>${deviceName}</strong> 吗？</div>
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
            renderFriendDetail(document.getElementById('mainContent'));
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认撤销';
            showToast('撤销失败: ' + err.message, 'error');
        }
    });
}

function showDisableFriendModal(friendId, friendName, deviceCount) {
    const body = document.createElement('div');
    body.innerHTML = `
        <div class="confirm-danger-text">确定要禁用朋友 <strong>${friendName}</strong> 吗？</div>
        <div class="confirm-danger-impact">⚠️ 禁用后该朋友下全部 ${deviceCount} 台设备和会话立即失效。需要手动启用才能恢复。</div>
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
            renderFriendDetail(document.getElementById('mainContent'));
        } catch (err) {
            confirmBtn.disabled = false;
            confirmBtn.textContent = '确认禁用';
            showToast('禁用失败: ' + err.message, 'error');
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
