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

async function api(path, params, options) {
    const pairs = Object.entries(params || {})
        .filter(([, value]) => value !== null && value !== undefined && value !== '')
        .map(([key, value]) => encodeURIComponent(key) + '=' + encodeURIComponent(value));
    const query = pairs.length ? '?' + pairs.join('&') : '';
    const init = Object.assign({}, options || {});
    init.headers = Object.assign({ Accept: 'application/json' }, (options && options.headers) || {});
    const response = await fetch(path + query, init);
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
            'P1：抓取可以在「运行」页启动和停止；概览仍是数据视角，展示库里的既有数据。'),
        overview.run && overview.run.state !== 'IDLE'
            ? h('div', { class: 'note' },
                '当前有一次抓取：' + (RUN_STATE_LABELS[overview.run.state] || overview.run.state)
                + '，已抓 ' + fmtNum(overview.run.attemptedPages) + ' 页。去 ',
                h('a', { class: 'link', href: '#/run', text: '运行页' }), ' 查看详情。')
            : null,
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

const PROCESSED_REASON_LABELS = {
    OK: '已抓取',
    DEAD: '死链',
    SKIPPED: '跳过'
};

function linkRulePanel() {
    const input = h('input', { type: 'search', placeholder: '粘贴一个 URL，看它会不会被抓' });
    const result = h('div', { class: 'muted', style: 'margin-top:8px' });

    async function check() {
        const url = input.value.trim();
        if (!url) {
            result.replaceChildren();
            return;
        }
        try {
            const data = await api('/api/links/check', { url: url });
            result.replaceChildren(
                h('span', { class: 'badge ' + (data.accepted ? 'up' : 'down'),
                    text: data.accepted ? '会抓' : '不会抓' }),
                h('span', { style: 'margin-left:8px', text: data.reason }));
        } catch (error) {
            result.replaceChildren(h('span', { class: 'badge down', text: '检查失败' }),
                h('span', { style: 'margin-left:8px', text: error.message }));
        }
    }

    input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            check();
        }
    });

    return h('section', { class: 'panel' },
        h('h2', { text: '链接规则预览' }),
        h('p', { class: 'muted', style: 'margin-top:-6px', text: '只做字符串判断，不会发起请求。' }),
        h('div', { class: 'toolbar', style: 'margin-bottom:0' }, input,
            h('button', { type: 'button', onclick: check }, '检查')),
        result);
}

async function renderFailed(root) {
    root.replaceChildren(h('div', { class: 'empty', text: '加载中…' }));
    try {
        const data = await api('/api/links/failed', { limit: 100 });

        async function requeue(item) {
            if (!window.confirm('把这个链接放回待处理队列？下一轮抓取会重新尝试。')) {
                return;
            }
            try {
                await api('/api/links/failed/' + item.id + '/requeue', null, { method: 'POST' });
                renderFailed(root);
            } catch (error) {
                window.alert('重新入队失败：' + error.message);
            }
        }

        const rows = data.items.map((item) => h('tr', {},
            h('td', { class: 'mono', text: item.link }),
            h('td', { class: 'mono', text: item.statusCode || '-' }),
            h('td', {}, h('span', { class: 'badge source', text: item.outcome || '-' })),
            h('td', { text: fmtTime(item.createdAt) }),
            h('td', {}, h('button', { type: 'button', onclick: () => requeue(item) }, '重新入队'))));

        root.replaceChildren(
            h('div', { class: 'stat-grid' },
                statCard('失败链接总数', fmtNum(data.total), 'failed_links')),
            h('div', { class: 'note' }, '这些链接在重试耗尽后被记录，不会写进 links_already_processed。'),
            h('section', { class: 'panel' },
                h('h2', { text: '最近失败（最多 100 条）' }),
                h('table', {},
                    h('thead', {}, h('tr', {},
                        h('th', { text: '链接' }),
                        h('th', { text: '状态码' }),
                        h('th', { text: '结果' }),
                        h('th', { text: '时间' }),
                        h('th', { text: '' }))),
                    h('tbody', {}, rows.length ? rows : emptyRow(5, '没有失败记录')))));
    } catch (error) {
        root.replaceChildren(errorBox(error));
    }
}

async function renderLinks(root) {
    const content = h('div');
    const tabs = [
        ['pending', '待处理'],
        ['processed', '已处理'],
        ['failed', '失败链接']
    ].map(([name, label]) => {
        const button = h('button', { type: 'button' }, label);
        button.addEventListener('click', () => select(name));
        return { name, button };
    });

    function select(name) {
        tabs.forEach((tab) => tab.button.classList.toggle('active', tab.name === name));
        if (name === 'processed') {
            renderProcessed(content);
        } else if (name === 'failed') {
            renderFailed(content);
        } else {
            renderPending(content);
        }
    }

    root.replaceChildren(
        pageHead('链接队列', '待处理表是破坏性消费的队列，这里只观察，不提供删除入口'),
        linkRulePanel(),
        h('div', { class: 'toolbar' }, tabs.map((tab) => tab.button)),
        content);
    select('pending');
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
            const rows = data.items.map((item) => h('tr', {},
                h('td', { class: 'mono', text: item.link }),
                h('td', {}, h('span', { class: 'badge source',
                    text: PROCESSED_REASON_LABELS[item.reason] || item.reason || '-' }))));
            list.replaceChildren(
                h('div', { class: 'stat-grid' },
                    statCard('已处理总数', fmtNum(data.total), 'links_already_processed')),
                h('section', { class: 'panel' },
                    h('h2', { text: '匹配结果（最多 100 条）' }),
                    h('table', {},
                        h('thead', {}, h('tr', {},
                            h('th', { text: '链接' }),
                            h('th', { text: '处理结果' }))),
                        h('tbody', {}, rows.length ? rows : emptyRow(2, '没有匹配的链接')))));
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

/* ---------------------------- 搜索 ------------------------------- */

async function renderSearch(root) {
    const input = h('input', { type: 'search', placeholder: '按标题搜索（Elasticsearch match）' });
    const results = h('div');

    async function run(page) {
        const keyword = input.value.trim();
        if (!keyword) {
            results.replaceChildren(h('div', { class: 'empty', text: '输入关键字后回车' }));
            return;
        }
        results.replaceChildren(h('div', { class: 'empty', text: '搜索中…' }));
        try {
            const data = await api('/api/search', { q: keyword, page: page, size: 20 });
            renderHits(keyword, data);
        } catch (error) {
            results.replaceChildren(errorBox(error));
        }
    }

    function renderHits(keyword, data) {
        const totalPages = Math.max(1, Math.ceil(data.total / data.size));
        const hits = data.items.map((hit) => h('article', { class: 'search-hit' },
            h('h3', {}, h('a', { class: 'link', href: hit.url, target: '_blank', rel: 'noopener noreferrer',
                text: hit.title || '(无标题)' })),
            h('div', { class: 'mono muted', text: hit.url }),
            hit.content ? h('p', { class: 'snippet', text: hit.content }) : null,
            h('div', { class: 'muted', text: 'score ' + Number(hit.score).toFixed(2) })));

        results.replaceChildren(
            h('div', { class: 'toolbar', style: 'margin-bottom:12px' },
                h('span', { class: 'muted',
                    text: '「' + keyword + '」共 ' + fmtNum(data.total) + ' 条，第 ' + data.page
                        + ' / ' + totalPages + ' 页' }),
                h('span', { class: 'spacer' }),
                h('button', { type: 'button', disabled: data.page <= 1, onclick: () => run(data.page - 1) }, '上一页'),
                h('button', { type: 'button', disabled: data.page >= totalPages, onclick: () => run(data.page + 1) }, '下一页')),
            hits.length
                ? h('div', { class: 'search-list' }, hits)
                : h('div', { class: 'empty', text: '没有匹配的新闻' }));
    }

    input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            run(1);
        }
    });

    root.replaceChildren(
        pageHead('搜索', '对 Elasticsearch 的 news 索引做 title match 查询；ES 不可用时会明确提示'),
        h('div', { class: 'toolbar' }, input,
            h('button', { type: 'button', class: 'primary', onclick: () => run(1) }, '搜索')),
        h('div', { class: 'note' }, '正文在写入 ES 前被裁剪到 10 个字符，这里只作为摘要展示；完整正文请看「新闻数据」。'),
        results);
    results.replaceChildren(h('div', { class: 'empty', text: '输入关键字后回车' }));
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

/* ------------------------------ 运行 ----------------------------- */

const RUN_STATE_LABELS = {
    IDLE: '空闲',
    STARTING: '启动中',
    RUNNING: '运行中',
    STOPPING: '停止中',
    FINISHED: '已结束',
    FAILED: '失败'
};

const STOP_REASON_LABELS = {
    COMPLETED: '正常完成',
    BUDGET_EXHAUSTED: '页数用尽',
    CIRCUIT_TRIPPED: '熔断',
    INTERRUPTED: '手动停止',
    FAILED: '异常失败'
};

const PHASE_LABELS = {
    fetching: '抓取中',
    waiting: '退避等待',
    idle: '空闲'
};

const CONFIG_FIELD_MAP = {
    'crawler.threads': 'threads',
    'crawler.ratePerSecond': 'requestsPerSecond',
    'crawler.maxPages': 'maxPages',
    'crawler.maxRetries': 'maxRetries',
    'crawler.retryBackoffMillis': 'retryBackoffMillis',
    'crawler.circuitBreakerThreshold': 'circuitBreakerThreshold'
};

const RUN_FORM_FIELDS = [
    ['threads', '线程数', 1, 8, 1],
    ['requestsPerSecond', '速率 (req/s)', 0.1, 5, 0.1],
    ['maxPages', '页数上限', 1, 100000, 1],
    ['maxRetries', '重试次数', 0, 20, 1],
    ['retryBackoffMillis', '退避基数 (ms)', 0, 600000, 100],
    ['circuitBreakerThreshold', '熔断阈值', 1, 100, 1]
];

function fmtDuration(millis) {
    const totalSeconds = Math.max(0, Math.round((millis || 0) / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
        return hours + ' 小时 ' + minutes + ' 分';
    }
    if (minutes > 0) {
        return minutes + ' 分 ' + seconds + ' 秒';
    }
    return seconds + ' 秒';
}

function stateBadge(state) {
    return h('span', { class: 'badge state-' + state, text: RUN_STATE_LABELS[state] || state });
}

function progressBar(value, max, modifier) {
    const percent = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
    return h('div', { class: 'progress ' + (modifier || '') }, h('span', { style: 'width: ' + percent + '%' }));
}

function runStatusPanel(snapshot, onStop) {
    const config = snapshot.config || {};
    const maxPages = config.maxPages || 0;
    const attempted = snapshot.attemptedPages || 0;
    const blocks = snapshot.consecutiveBlocks || 0;
    const threshold = config.circuitBreakerThreshold || 0;
    const active = ['STARTING', 'RUNNING', 'STOPPING'].includes(snapshot.state);

    return h('section', { class: 'panel' },
        h('h2', { text: '当前运行' }),
        h('div', { class: 'run-status' },
            stateBadge(snapshot.state),
            snapshot.runId ? h('span', { class: 'muted mono', text: 'run #' + snapshot.runId }) : null,
            h('span', { class: 'muted',
                text: snapshot.startedAt ? '开始于 ' + fmtTime(snapshot.startedAt) : '还没有运行记录' }),
            snapshot.startedAt ? h('span', { class: 'muted', text: '已用 ' + fmtDuration(snapshot.elapsedMillis) }) : null,
            snapshot.stopReason
                ? h('span', { class: 'badge source', text: STOP_REASON_LABELS[snapshot.stopReason] || snapshot.stopReason })
                : null,
            active
                ? h('button', { type: 'button', class: 'danger', disabled: snapshot.state === 'STOPPING',
                    onclick: onStop }, snapshot.state === 'STOPPING' ? '停止中…' : '停止')
                : null),
        snapshot.error ? h('div', { class: 'error-box', text: snapshot.error }) : null,
        h('div', { class: 'stat-grid', style: 'margin-top:14px' },
            statCard('已抓取页', fmtNum(attempted), maxPages ? '上限 ' + fmtNum(maxPages) : ''),
            statCard('入库新闻', fmtNum(snapshot.storedNews), '本次运行'),
            statCard('丢弃链接', fmtNum(snapshot.droppedLinks), '重试耗尽'),
            statCard('剩余额度', fmtNum(snapshot.remainingPages), '页')),
        h('div', { class: 'progress-row' },
            h('span', { class: 'muted', text: '页数进度' }),
            h('span', { class: 'muted', text: fmtNum(attempted) + ' / ' + fmtNum(maxPages) })),
        progressBar(attempted, maxPages),
        h('div', { class: 'progress-row' },
            h('span', { class: 'muted', text: '熔断进度（连续被拦）' }),
            h('span', { class: 'muted', text: blocks + ' / ' + threshold })),
        progressBar(blocks, threshold, threshold > 0 && blocks / threshold >= 0.8 ? 'bad'
            : (threshold > 0 && blocks / threshold >= 0.5 ? 'warn' : '')),
        h('div', { class: 'muted', style: 'margin-top:12px',
            text: '速率 ' + (config.requestsPerSecond != null ? config.requestsPerSecond : '-') + ' req/s · '
                + (config.threads != null ? config.threads : '-') + ' 线程 · 重试 '
                + (config.maxRetries != null ? config.maxRetries : '-') + ' 次' }));
}

function threadsPanel(threads) {
    const rows = (threads || []).map((thread) => h('tr', {},
        h('td', { class: 'mono', text: thread.name }),
        h('td', {}, h('span', { class: 'badge phase-' + thread.phase, text: PHASE_LABELS[thread.phase] || thread.phase })),
        h('td', { class: 'mono', text: thread.attempt ? '#' + thread.attempt : '-' }),
        h('td', { class: 'mono link-cell', text: thread.link || '-' })));

    return h('section', { class: 'panel' },
        h('h2', { text: '工作线程' }),
        h('table', {},
            h('thead', {}, h('tr', {},
                h('th', { text: '线程' }),
                h('th', { text: '状态' }),
                h('th', { text: '尝试' }),
                h('th', { text: '当前链接' }))),
            h('tbody', {}, rows.length ? rows : emptyRow(4, '当前没有活跃线程'))));
}

function runForm(defaults) {
    const inputs = {};
    const labels = RUN_FORM_FIELDS.map(([name, label, min, max, step]) => {
        const input = h('input', { type: 'number', name: name, min: min, max: max, step: step,
            value: defaults[name] != null ? defaults[name] : '' });
        inputs[name] = input;
        return h('label', {}, h('span', { text: label }), input);
    });
    const error = h('div', { class: 'error-box', hidden: true });

    const form = h('form', { class: 'run-form' }, labels,
        h('div', { class: 'actions' },
            h('button', { type: 'submit', class: 'primary' }, '开始抓取'),
            h('button', { type: 'button', onclick: () => {
                RUN_FORM_FIELDS.forEach(([name]) => {
                    if (defaults[name] != null) {
                        inputs[name].value = defaults[name];
                    }
                });
            } }, '恢复默认')),
        error);

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const payload = {};
        RUN_FORM_FIELDS.forEach(([name]) => {
            payload[name] = Number(inputs[name].value);
        });
        const summary = RUN_FORM_FIELDS.map(([name, label]) => label + '=' + inputs[name].value).join('，');
        if (!window.confirm('确认开始抓取？\n\n' + summary
            + '\n\n会真实访问新浪并写入数据库，受全局限速与熔断保护。')) {
            return;
        }
        try {
            await api('/api/runs', null, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        } catch (err) {
            error.textContent = '启动失败：' + err.message;
            error.hidden = false;
        }
    });
    return form;
}

function runHistoryPanel(history) {
    if (!history.available) {
        return h('section', { class: 'panel' },
            h('h2', { text: '运行历史' }),
            h('div', { class: 'note', text: history.message || '运行历史不可用' }));
    }
    const rows = (history.items || []).map((run) => h('tr', {},
        h('td', { class: 'mono', text: '#' + run.id }),
        h('td', { text: fmtTime(run.startedAt) }),
        h('td', { text: run.finishedAt ? fmtTime(run.finishedAt) : '未正常结束' }),
        h('td', { text: fmtNum(run.attemptedPages) + ' / ' + fmtNum(run.maxPages) }),
        h('td', { text: fmtNum(run.newsStored) }),
        h('td', { text: fmtNum(run.droppedLinks) }),
        h('td', {}, h('span', { class: 'badge source',
            text: STOP_REASON_LABELS[run.stopReason] || run.stopReason || '-' }))));

    return h('section', { class: 'panel' },
        h('h2', { text: '运行历史' }),
        h('table', {},
            h('thead', {}, h('tr', {},
                h('th', { text: 'ID' }),
                h('th', { text: '开始' }),
                h('th', { text: '结束' }),
                h('th', { text: '页数' }),
                h('th', { text: '入库' }),
                h('th', { text: '丢弃' }),
                h('th', { text: '结束原因' }))),
            h('tbody', {}, rows.length ? rows : emptyRow(7, '还没有运行记录'))));
}

function defaultsFromConfig(items) {
    const defaults = {};
    (items || []).forEach((row) => {
        const field = CONFIG_FIELD_MAP[row.key];
        if (field) {
            defaults[field] = row.value;
        }
    });
    return defaults;
}

async function renderRun(root) {
    const [current, configData, history] = await Promise.all([
        api('/api/runs/current'),
        api('/api/config/effective'),
        api('/api/runs', { limit: 20 }).catch((error) => ({ available: false, items: [], message: error.message }))
    ]);
    const defaults = defaultsFromConfig(configData.items);

    const statusSlot = h('div');
    const threadsSlot = h('div');
    const formSlot = h('div');
    const historySlot = h('div');
    let lastActive = null;

    async function stopRun(snapshot) {
        if (!window.confirm('停止当前抓取？已抓到的新闻会保留，未处理的链接会留在队列里。')) {
            return;
        }
        try {
            await api('/api/runs/' + (snapshot.runId || 0) + '/stop', null, { method: 'POST' });
        } catch (error) {
            window.alert('停止失败：' + error.message);
        }
    }

    function apply(snapshot) {
        statusSlot.replaceChildren(runStatusPanel(snapshot, () => stopRun(snapshot)));
        threadsSlot.replaceChildren(threadsPanel(snapshot.threads));
        const active = ['STARTING', 'RUNNING', 'STOPPING'].includes(snapshot.state);
        if (active !== lastActive) {
            lastActive = active;
            formSlot.replaceChildren(active
                ? h('section', { class: 'panel' },
                    h('h2', { text: '开始新的抓取' }),
                    h('p', { class: 'muted', text: '当前有任务在跑，结束后可以再开一次。' }))
                : h('section', { class: 'panel' },
                    h('h2', { text: '开始新的抓取' }),
                    h('p', { class: 'muted', style: 'margin-top:-6px',
                        text: '默认值取自 CrawlConfig 的生效配置。界面硬上限：8 线程、5 req/s。' }),
                    runForm(defaults)));
        }
    }

    root.replaceChildren(
        pageHead('运行控制', '在本进程内启动/停止抓取，实时指标通过 SSE 每秒刷新'),
        h('div', { class: 'note' },
            '启动会真实访问新浪并写入数据库。抓取受全局限速与熔断保护，界面不提供「无限制」选项。'),
        statusSlot,
        threadsSlot,
        formSlot,
        historySlot);

    apply(current);
    historySlot.replaceChildren(runHistoryPanel(history));

    const source = new EventSource('/api/runs/stream');
    source.addEventListener('tick', (event) => {
        try {
            apply(JSON.parse(event.data));
        } catch (e) {
            /* 忽略单帧解析失败 */
        }
    });
    onCleanup(() => source.close());
}

/* ------------------------------ 路由 ----------------------------- */

const routes = {
    overview: renderOverview,
    run: renderRun,
    news: renderNews,
    search: renderSearch,
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
