'use strict';

/* ------------------------------------------------------------------ *
 * 只读管理台的前端。所有数据都来自 /api/*，没有任何写操作入口。
 * 数据一律用 textContent 渲染：新闻标题、日志内容都来自外部网页。
 * ------------------------------------------------------------------ */

const view = document.getElementById('view');
const newsDialog = document.getElementById('news-dialog');

let cleanups = [];

function onCleanup(fn) {
    cleanups.push(fn);
}

function runCleanups() {
    cleanups.forEach((fn) => {
        try {
            fn();
        } catch (e) {
            /* 忽略清理失败 */
        }
    });
    cleanups = [];
}

function h(tag, props, ...children) {
    const node = document.createElement(tag);
    Object.entries(props || {}).forEach(([key, value]) => {
        if (value === null || value === undefined || value === false) {
            return;
        }
        if (key === 'class') {
            node.className = value;
        } else if (key === 'text') {
            node.textContent = value;
        } else if (key.startsWith('on') && typeof value === 'function') {
            node.addEventListener(key.slice(2).toLowerCase(), value);
        } else {
            node.setAttribute(key, value === true ? '' : value);
        }
    });
    children.flat(Infinity).forEach((child) => {
        if (child === null || child === undefined || child === false) {
            return;
        }
        node.append(child.nodeType ? child : document.createTextNode(String(child)));
    });
    return node;
}

async function api(path, params) {
    const pairs = Object.entries(params || {})
        .filter(([, value]) => value !== null && value !== undefined && value !== '')
        .map(([key, value]) => encodeURIComponent(key) + '=' + encodeURIComponent(value));
    const query = pairs.length ? '?' + pairs.join('&') : '';
    const response = await fetch(path + query, { headers: { Accept: 'application/json' } });
    let data = null;
    try {
        data = await response.json();
    } catch (e) {
        data = null;
    }
    if (!response.ok) {
        throw new Error((data && data.message) || 'HTTP ' + response.status);
    }
    return data;
}

const numberFormat = new Intl.NumberFormat('zh-CN');

function fmtNum(value) {
    return value === null || value === undefined ? '-' : numberFormat.format(value);
}

function fmtTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}

function fmtBytes(value) {
    if (value === null || value === undefined) {
        return '-';
    }
    if (value < 1024) {
        return value + ' B';
    }
    if (value < 1024 * 1024) {
        return (value / 1024).toFixed(1) + ' KB';
    }
    return (value / 1024 / 1024).toFixed(1) + ' MB';
}

function errorBox(error) {
    return h('div', { class: 'error-box' }, '加载失败：' + (error && error.message ? error.message : error));
}

function pageHead(title, subtitle) {
    return h('div', { class: 'page-head' },
        h('div', {}, h('h1', { text: title }), subtitle ? h('p', { text: subtitle }) : null));
}

function statCard(label, value, hint) {
    return h('div', { class: 'stat' },
        h('div', { class: 'label', text: label }),
        h('div', { class: 'value', text: value }),
        hint ? h('div', { class: 'hint', text: hint }) : null);
}

function emptyRow(columns, message) {
    return h('tr', {}, h('td', { colspan: columns }, h('div', { class: 'empty', text: message })));
}

/* ----------------------------- 概览 ----------------------------- */

async function renderOverview(root) {
    const [overview, recentLogs] = await Promise.all([
        api('/api/overview'),
        api('/api/logs', { limit: 6 })
    ]);

    root.replaceChildren(
        pageHead('概览', '数据视角的现状：库里有什么、队列积压多少、日志最近说了什么'),
        h('div', { class: 'note' },
            '当前是 P0 只读版本：只观察数据库与日志。运行中的线程、熔断状态、实时速率属于 P1（RunManager），这里暂不展示。'),
        h('div', { class: 'stat-grid' },
            statCard('新闻总数', fmtNum(overview.news.total), 'MySQL · news'),
            statCard('今日新增', fmtNum(overview.news.today), '按 created_at 统计'),
            statCard('待处理链接', fmtNum(overview.links.pending), 'links_to_be_processed'),
            statCard('已处理链接', fmtNum(overview.links.processed), 'links_already_processed')),
        h('div', { class: 'split' },
            h('section', { class: 'panel' },
                h('h2', { text: '最近日志' }),
                h('div', { class: 'log-list preview' }, recentLogs.items.map(logRow))),
            h('section', { class: 'panel' },
                h('h2', { text: '最近入库' }),
                h('dl', { class: 'kv' },
                    h('dt', { text: '最新时间' }),
                    h('dd', { text: fmtTime(overview.news.lastCreatedAt) }),
                    h('dt', { text: '日志文件' }),
                    h('dd', { class: 'mono', text: overview.logFile }),
                    h('dt', { text: '生成时间' }),
                    h('dd', { text: fmtTime(overview.generatedAt) })))));

    if (recentLogs.items.length === 0) {
        root.querySelector('.log-list').append(h('div', { class: 'empty', text: '还没有日志，先去跑一次爬虫' }));
    }
}

/* ---------------------------- 新闻数据 --------------------------- */

async function renderNews(root) {
    const state = { beforeId: null, keyword: '', loading: false };
    const tbody = h('tbody');
    const more = h('button', { type: 'button' }, '加载更多');
    const summary = h('span', { class: 'muted' });
    const search = h('input', { type: 'search', placeholder: '按标题筛选（like）' });

    async function load(reset) {
        if (state.loading) {
            return;
        }
        state.loading = true;
        more.disabled = true;
        try {
            const params = { limit: 50 };
            if (state.keyword) {
                params.title = state.keyword;
            }
            if (!reset && state.beforeId) {
                params.beforeId = state.beforeId;
            }
            const data = await api('/api/news', params);
            if (reset) {
                tbody.replaceChildren();
            }
            data.items.forEach((item) => tbody.append(newsRow(item)));
            state.beforeId = data.nextBeforeId;
            more.disabled = !data.nextBeforeId;
            summary.textContent = '本次加载 ' + data.items.length + ' 条' + (data.nextBeforeId ? '' : '（已到末尾）');
            if (!tbody.children.length) {
                tbody.append(emptyRow(5, '没有匹配的新闻'));
            }
        } finally {
            state.loading = false;
        }
    }

    search.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            state.keyword = search.value.trim();
            state.beforeId = null;
            load(true);
        }
    });
    more.addEventListener('click', () => load(false));

    root.replaceChildren(
        pageHead('新闻数据', '键集分页，避免大表上的 offset 扫描'),
        h('div', { class: 'toolbar' }, search,
            h('button', { type: 'button', class: 'primary', onclick: () => {
                state.keyword = search.value.trim();
                state.beforeId = null;
                load(true);
            } }, '搜索')),
        h('section', { class: 'panel' },
            h('table', {},
                h('thead', {}, h('tr', {},
                    h('th', { text: 'ID' }),
                    h('th', { text: '标题' }),
                    h('th', { text: '入库时间' }),
                    h('th', { text: '修改时间' }),
                    h('th', { text: '字数' }))),
                tbody),
            h('div', { class: 'toolbar', style: 'margin-top:12px;margin-bottom:0' }, more, summary)));

    await load(true);
}

function newsRow(item) {
    return h('tr', { class: 'clickable', onclick: () => openNews(item.id) },
        h('td', { class: 'mono', text: item.id }),
        h('td', { class: 'title-cell' }, h('div', { class: 'title', text: item.title || '(无标题)' })),
        h('td', { text: fmtTime(item.createdAt) }),
        h('td', { text: fmtTime(item.modifiedAt) }),
        h('td', { text: item.contentLength != null ? item.contentLength : 0 }));
}

async function openNews(id) {
    const news = await api('/api/news/' + id);
    document.getElementById('dialog-title').textContent = news.title || '新闻详情';
    const body = document.getElementById('dialog-body');
    body.replaceChildren(
        h('div', { class: 'meta-row' },
            h('span', { text: 'ID ' + news.id }),
            h('span', { text: '入库 ' + fmtTime(news.createdAt) }),
            h('span', { text: '修改 ' + fmtTime(news.modifiedAt) })),
        h('p', {}, h('a', { class: 'link', href: news.url, target: '_blank', rel: 'noopener noreferrer', text: '打开原文' })),
        h('div', { class: 'news-content', text: news.content || '(正文为空)' }));
    newsDialog.showModal();
}

/* ---------------------------- 链接队列 --------------------------- */

async function renderLinks(root) {
    const content = h('div');
    const pendingTab = h('button', { type: 'button', class: 'active' }, '待处理');
    const processedTab = h('button', { type: 'button' }, '已处理');

    function selectPending() {
        pendingTab.classList.add('active');
        processedTab.classList.remove('active');
        renderPending(content);
    }

    function selectProcessed() {
        processedTab.classList.add('active');
        pendingTab.classList.remove('active');
        renderProcessed(content);
    }

    pendingTab.addEventListener('click', selectPending);
    processedTab.addEventListener('click', selectProcessed);

    root.replaceChildren(
        pageHead('链接队列', '待处理表是破坏性消费的队列，这里只观察，不提供删除入口'),
        h('div', { class: 'toolbar' }, pendingTab, processedTab),
        content);
    selectPending();
}

async function renderPending(root) {
    root.replaceChildren(h('div', { class: 'empty', text: '加载中…' }));
    try {
        const data = await api('/api/links/pending', { limit: 100 });
        const rows = data.items.map((link) => h('tr', {}, h('td', { class: 'mono', text: link })));
        const duplicates = data.duplicates.length
            ? h('section', { class: 'panel' },
                h('h2', { text: '重复 URL（同一链接被入队多次）' }),
                h('table', {},
                    h('thead', {}, h('tr', {}, h('th', { text: '链接' }), h('th', { text: '出现次数' }))),
                    h('tbody', {}, data.duplicates.map((row) =>
                        h('tr', {},
                            h('td', { class: 'mono', text: row.link }),
                            h('td', { text: row.cnt }))))))
            : null;

        root.replaceChildren(
            h('div', { class: 'stat-grid' },
                statCard('待处理总数', fmtNum(data.total), 'links_to_be_processed'),
                statCard('重复链接种类', fmtNum(data.duplicates.length), '最多显示 20 组')),
            data.duplicates.length ? h('div', { class: 'note' }, '检测到重复入队：Crawler 入队时不去重，重复抓取由 isLinkProcessed 兜底。') : null,
            duplicates,
            h('section', { class: 'panel' },
                h('h2', { text: '最近入队（最多 100 条）' }),
                h('table', {},
                    h('thead', {}, h('tr', {}, h('th', { text: '链接' }))),
                    h('tbody', {}, rows.length ? rows : emptyRow(1, '队列为空')))));
    } catch (error) {
        root.replaceChildren(errorBox(error));
    }
}

async function renderProcessed(root) {
    const search = h('input', { type: 'search', placeholder: '按 URL 搜索（like）' });
    const list = h('div');

    async function load() {
        list.replaceChildren(h('div', { class: 'empty', text: '加载中…' }));
        try {
            const data = await api('/api/links/processed', { limit: 100, q: search.value.trim() });
            const rows = data.items.map((link) => h('tr', {}, h('td', { class: 'mono', text: link })));
            list.replaceChildren(
                h('div', { class: 'stat-grid' },
                    statCard('已处理总数', fmtNum(data.total), 'links_already_processed')),
                h('section', { class: 'panel' },
                    h('h2', { text: '匹配结果（最多 100 条）' }),
                    h('table', {},
                        h('thead', {}, h('tr', {}, h('th', { text: '链接' }))),
                        h('tbody', {}, rows.length ? rows : emptyRow(1, '没有匹配的链接')))));
        } catch (error) {
            list.replaceChildren(errorBox(error));
        }
    }

    search.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            load();
        }
    });

    root.replaceChildren(
        h('div', { class: 'toolbar' }, search,
            h('button', { type: 'button', class: 'primary', onclick: load }, '搜索')),
        list);
    await load();
}

/* ------------------------------ 日志 ----------------------------- */

function logRow(entry) {
    return h('div', { class: 'log-row' },
        h('span', { class: 'time', text: entry.time }),
        h('span', {}, h('span', { class: 'badge level-' + entry.level, text: entry.level })),
        h('span', { class: 'thread', text: entry.thread }),
        h('span', { class: 'msg', text: entry.message }));
}

function renderLogs(root) {
    let source = null;
    let auto = false;
    const list = h('div', { class: 'log-list' });
    const levelSelect = h('select', {},
        h('option', { value: '' }, '全部级别'),
        h('option', { value: 'INFO' }, 'INFO'),
        h('option', { value: 'WARN' }, 'WARN'),
        h('option', { value: 'ERROR' }, 'ERROR'));
    const search = h('input', { type: 'search', placeholder: '关键字（消息 / 线程 / 类名）' });
    const autoButton = h('button', { type: 'button' }, '实时刷新：关');
    const status = h('span', { class: 'muted' });

    function matches(entry) {
        const level = levelSelect.value;
        const keyword = search.value.trim().toLowerCase();
        if (level && entry.level !== level) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return (entry.message + ' ' + entry.thread + ' ' + entry.logger).toLowerCase().includes(keyword);
    }

    async function loadHistory() {
        status.textContent = '加载中…';
        try {
            const data = await api('/api/logs', {
                limit: 300,
                level: levelSelect.value || undefined,
                q: search.value.trim() || undefined
            });
            list.replaceChildren();
            data.items.forEach((entry) => list.append(logRow(entry)));
            status.textContent = data.items.length
                ? '最近 ' + data.items.length + ' 条 · ' + fmtBytes(data.sizeBytes)
                : '没有匹配的日志 · ' + fmtBytes(data.sizeBytes);
            if (!data.items.length) {
                list.append(h('div', { class: 'empty', text: '日志文件：' + data.file }));
            }
        } catch (error) {
            list.replaceChildren(errorBox(error));
            status.textContent = '';
        }
    }

    function stopStream() {
        if (source) {
            source.close();
            source = null;
        }
    }

    function startStream() {
        stopStream();
        source = new EventSource('/api/logs/stream');
        source.addEventListener('log', (event) => {
            let entry;
            try {
                entry = JSON.parse(event.data);
            } catch (e) {
                return;
            }
            if (!matches(entry)) {
                return;
            }
            list.append(logRow(entry));
            while (list.children.length > 500) {
                list.firstElementChild.remove();
            }
            list.scrollTop = list.scrollHeight;
        });
    }

    function toggleAuto() {
        auto = !auto;
        autoButton.textContent = '实时刷新：' + (auto ? '开' : '关');
        autoButton.classList.toggle('primary', auto);
        if (auto) {
            startStream();
        } else {
            stopStream();
        }
    }

    levelSelect.addEventListener('change', loadHistory);
    search.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            loadHistory();
        }
    });
    autoButton.addEventListener('click', toggleAuto);
    onCleanup(stopStream);

    root.replaceChildren(
        pageHead('日志', '读 logs/crawler.log，支持按级别和关键字过滤；实时模式走 SSE'),
        h('div', { class: 'toolbar' },
            levelSelect,
            search,
            h('button', { type: 'button', onclick: loadHistory }, '刷新'),
            autoButton),
        h('section', { class: 'panel' }, list,
            h('div', { class: 'toolbar', style: 'margin:12px 0 0' }, status)));
    loadHistory();
}

/* ---------------------------- 环境自检 --------------------------- */

const SOURCE_LABELS = {
    'system property': '系统属性',
    'environment variable': '环境变量',
    'local.properties': 'local.properties',
    'default': '内置默认'
};

function statusBadge(status) {
    return h('span', { class: 'badge ' + (status === 'UP' ? 'up' : 'down'), text: status || '-' });
}

function healthCard(name, status, rows) {
    return h('div', { class: 'health-card' },
        h('div', { class: 'head' }, h('span', { class: 'name', text: name }), statusBadge(status)),
        h('dl', { class: 'kv' }, rows
            .filter((row) => row[1] !== null && row[1] !== undefined && row[1] !== '')
            .map((row) => [h('dt', { text: row[0] }), h('dd', { text: String(row[1]) })])));
}

async function renderSettings(root) {
    const health = await api('/api/health');
    const configRows = health.config.map((row) => h('tr', {},
        h('td', { class: 'mono', text: row.key }),
        h('td', { class: 'mono', text: row.value }),
        h('td', {}, h('span', { class: 'badge source', text: SOURCE_LABELS[row.source] || row.source }))));

    root.replaceChildren(
        pageHead('环境自检', '连接状态、schema 版本、证书，以及每个参数真正的来源'),
        h('div', { class: 'note' },
            '配置优先级：系统属性 > 环境变量 > local.properties > 内置默认。改完不生效时，先看「来源」这一列。'),
        h('section', { class: 'panel' },
            h('h2', { text: '依赖状态' }),
            h('div', { class: 'health-grid' },
                healthCard('MySQL', health.database.status, [
                    ['库名', health.database.name],
                    ['版本', health.database.version],
                    ['Schema', health.database.flywayVersion ? 'V' + health.database.flywayVersion : null],
                    ['延迟', health.database.latencyMs != null ? health.database.latencyMs + ' ms' : null],
                    ['错误', health.database.message]
                ]),
                healthCard('Elasticsearch', health.elasticsearch.status, [
                    ['集群', health.elasticsearch.clusterName],
                    ['版本', health.elasticsearch.version],
                    ['文档数', health.elasticsearch.newsDocuments != null ? fmtNum(health.elasticsearch.newsDocuments) : null],
                    ['错误', health.elasticsearch.message]
                ]),
                healthCard('CA 证书', health.certificate.status, [
                    ['路径', health.certificate.path],
                    ['大小', health.certificate.sizeBytes != null ? fmtBytes(health.certificate.sizeBytes) : null],
                    ['说明', health.certificate.message]
                ]),
                healthCard('日志文件', health.logs.lastModified ? 'UP' : 'DOWN', [
                    ['路径', health.logs.file],
                    ['大小', fmtBytes(health.logs.sizeBytes)],
                    ['最后写入', health.logs.lastModified ? fmtTime(health.logs.lastModified) : null]
                ]))),
        h('section', { class: 'panel' },
            h('h2', { text: 'crawler.* 生效配置' }),
            h('table', {},
                h('thead', {}, h('tr', {},
                    h('th', { text: '参数' }),
                    h('th', { text: '生效值' }),
                    h('th', { text: '来源' }))),
                h('tbody', {}, configRows))),
        h('p', { class: 'muted', text: '检查时间：' + fmtTime(health.checkedAt) + '（结果缓存 5 秒，密码类配置不会下发到浏览器）' }));
}

/* ------------------------------ 路由 ----------------------------- */

const routes = {
    overview: renderOverview,
    news: renderNews,
    links: renderLinks,
    logs: renderLogs,
    settings: renderSettings
};

function currentRoute() {
    const raw = location.hash.replace(/^#\/?/, '').split('?')[0];
    return routes[raw] ? raw : 'overview';
}

async function render() {
    const name = currentRoute();
    runCleanups();
    document.querySelectorAll('.sidenav a').forEach((link) => {
        link.classList.toggle('active', link.dataset.route === name);
    });
    view.replaceChildren(h('div', { class: 'empty', text: '加载中…' }));
    try {
        await routes[name](view);
    } catch (error) {
        view.replaceChildren(errorBox(error));
    }
}

document.getElementById('dialog-close').addEventListener('click', () => newsDialog.close());
newsDialog.addEventListener('click', (event) => {
    if (event.target === newsDialog) {
        newsDialog.close();
    }
});

window.addEventListener('hashchange', render);
setInterval(() => {
    document.getElementById('clock').textContent = new Date().toLocaleString('zh-CN', { hour12: false });
}, 1000);
document.getElementById('clock').textContent = new Date().toLocaleString('zh-CN', { hour12: false });

render();
