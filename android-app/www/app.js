/* 记事本 · 前端 */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const KEY = {
    token: 'nn.token',
    cache: 'nn.cache',
    pending: 'nn.pending',
    theme: 'nn.theme',
    last: 'nn.last',
    lock: 'nn.lock',
    backup: 'nn.backupScheduled',
    device: 'nn.device',
  };

  // 安卓壳注入的原生能力；浏览器里是空壳，所有调用都退化成 no-op
  const Native = window.NativeBridge || {
    native: false,
    apiOrigin: '',
    secure: {},
    files: {},
    bio: {},
    share: {},
    backup: {},
  };

  const state = {
    token: '',
    notes: [],
    currentId: null,
    q: '',
    view: 'all',
    tag: '',
    preview: false,
    remoteIds: null,
  };

  let persistTimer = null;
  let flushTimer = null;
  let listFrame = null;
  let toastTimer = null;
  let searchTimer = null;

  /* ------------------------------------------------------------ 小工具 */

  const readJSON = (key, fallback) => {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch {
      return fallback;
    }
  };
  const writeJSON = (key, value) => {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      toast('本地存储空间不足');
    }
  };

  const escapeHTML = (s) =>
    String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');

  function toast(message) {
    const el = $('#toast');
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      el.hidden = true;
    }, 2600);
  }

  const stripMarkdown = (src) =>
    String(src || '')
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/\*\*|~~|`/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/^\s*\d+\.\s+/gm, '')
      .replace(/\s+/g, ' ')
      .trim();

  function formatTime(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const year = d.getFullYear() === now.getFullYear();
    const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    if (sameDay) return `今天 ${hm}`;
    const yesterday = new Date(now.getTime() - 86400000);
    if (d.toDateString() === yesterday.toDateString()) return `昨天 ${hm}`;
    if (year) return `${d.getMonth() + 1}月${d.getDate()}日`;
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  /* -------------------------------------------------------------- 网络 */

  async function api(path, { method = 'GET', body, raw = false, timeout = 25000 } = {}) {
    const headers = {};
    if (body !== undefined) headers['content-type'] = 'application/json';
    if (state.token) headers.authorization = `Bearer ${state.token}`;

    const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ctrl ? setTimeout(() => ctrl.abort(), timeout) : null;

    let res;
    try {
      const url = Native.apiOrigin && path.indexOf('/api/') === 0 ? Native.apiOrigin + path : path;
      res = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctrl ? ctrl.signal : undefined,
      });
    } catch (e) {
      if (e && e.name === 'AbortError') throw new Error('请求超时，请检查网络后重试');
      throw new Error('网络不可用，请检查网络后重试');
    } finally {
      if (timer) clearTimeout(timer);
    }

    if (res.status === 401 && path !== '/api/login') {
      logout(true);
      throw new Error('登录已过期，请重新登录');
    }
    if (!res.ok) {
      let msg = `请求失败 (${res.status})`;
      try {
        const data = await res.json();
        if (data && data.error) msg = data.error;
      } catch {}
      throw new Error(msg);
    }
    return raw ? res : res.json();
  }

  /* ---------------------------------------------------------- 本地缓存 */

  function cacheSave() {
    writeJSON(KEY.cache, { notes: state.notes, ts: Date.now() });
  }

  function cacheLoad() {
    const c = readJSON(KEY.cache, null);
    if (c && Array.isArray(c.notes)) state.notes = c.notes;
  }

  function readPending() {
    const list = readJSON(KEY.pending, []);
    return Array.isArray(list) ? list : [];
  }

  function writePending(list) {
    writeJSON(KEY.pending, list);
  }

  function queueNote(note) {
    const list = readPending();
    const payload = { ...note };
    delete payload._pending;
    const idx = list.findIndex((n) => n.id === note.id);
    if (idx >= 0) list[idx] = payload;
    else list.push(payload);
    writePending(list);
  }

  function unqueue(id, updatedAt = Number.POSITIVE_INFINITY) {
    writePending(readPending().filter((n) => !(n.id === id && (n.updatedAt || 0) <= updatedAt)));
  }

  /* ---------------------------------------------------------------- 同步 */

  function setSync(kind, count = 0) {
    const el = $('#sync');
    if (!el) return;
    const map = {
      idle: '已保存',
      saving: '保存中…',
      pending: `待同步 ${count}`,
      offline: '离线',
    };
    el.dataset.state = kind;
    el.textContent = map[kind] || '';
  }

  function scheduleFlush(delay = 400) {
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flushPending, delay);
  }

  async function flushPending() {
    const queue = readPending();
    if (!queue.length) {
      setSync(navigator.onLine ? 'idle' : 'offline');
      return;
    }
    if (!navigator.onLine || !state.token) {
      setSync(navigator.onLine ? 'pending' : 'offline', queue.length);
      return;
    }
    setSync('saving');
    for (const note of queue) {
      try {
        const res = await api(`/api/notes/${note.id}`, { method: 'PUT', body: note });
        const local = state.notes.find((n) => n.id === note.id);
        if (local && (local.updatedAt || 0) <= (note.updatedAt || 0)) {
          local.updatedAt = res.note.updatedAt;
          local._pending = false;
        }
        unqueue(note.id, note.updatedAt || 0);
      } catch (err) {
        setSync('pending', readPending().length);
        return;
      }
    }
    cacheSave();
    setSync('idle');
    renderList();
    renderEditorMeta();
  }

  /* ---------------------------------------------------------- 笔记操作 */

  const currentNote = () => state.notes.find((n) => n.id === state.currentId);

  function persistCurrentSoon(note) {
    note._pending = true;
    clearTimeout(persistTimer);
    persistTimer = setTimeout(() => {
      queueNote(note);
      cacheSave();
      scheduleFlush(300);
      renderListSoon();
    }, 450);
  }

  function renderListSoon() {
    if (listFrame) return;
    listFrame = requestAnimationFrame(() => {
      listFrame = null;
      renderList();
    });
  }

  function newNote() {
    const now = Date.now();
    const note = {
      id: crypto.randomUUID(),
      title: '',
      body: '',
      tags: [],
      pinned: false,
      createdAt: now,
      updatedAt: now,
      deleted: false,
      deletedAt: 0,
      _pending: true,
    };
    state.notes.push(note);
    state.view = 'all';
    state.tag = '';
    state.q = '';
    $('#search').value = '';
    queueNote(note);
    cacheSave();
    scheduleFlush(200);
    openNote(note.id, { focusTitle: true });
    renderAll();
  }

  async function openNote(id, { focusTitle = false } = {}) {
    const note = state.notes.find((n) => n.id === id);
    if (!note) return;
    state.currentId = id;
    state.bodyMissing = false;
    localStorage.setItem(KEY.last, id);

    const title = $('#noteTitle');
    const tags = $('#noteTags');
    const body = $('#noteBody');
    const preview = $('#preview');
    const btn = $('#modeBtn');
    const bar = $('#formatBar');

    title.value = note.title || '';
    tags.value = (note.tags || []).join(', ');

    updateTrashButton(note);
    $('#pinBtn').classList.toggle('on', Boolean(note.pinned));
    $('#editor').hidden = false;
    $('#paneEmpty').hidden = true;
    $('#app').classList.add('editing');
    renderList();

    if (typeof note.body === 'string') {
      finishOpen(note);
    } else {
      // 先占位，正文异步取
      body.disabled = true;
      body.value = '';
      body.hidden = true;
      preview.hidden = false;
      preview.innerHTML = '<p class="loading-hint">正在读取正文…</p>';
      bar.hidden = true;
      btn.textContent = '编辑';
      btn.classList.add('on');
      $('#noteMeta').textContent = '正在读取…';

      const ok = await ensureBody(note);
      if (state.currentId !== id || !state.notes.includes(note)) return;
      if (!ok) {
        state.bodyMissing = true;
        body.disabled = true;
        preview.innerHTML =
          '<p class="loading-hint">正文没有本地缓存，也没连上服务器。恢复网络后重新打开这条笔记即可。</p>';
        $('#noteMeta').textContent = '正文未加载';
        return;
      }
      finishOpen(note);
    }

    if (focusTitle) setTimeout(() => title.focus(), 30);
  }

  function finishOpen(note) {
    const body = $('#noteBody');
    body.disabled = false;
    // 有正文就默认进预览，空白笔记直接进编辑
    state.preview = Boolean((note.body || '').trim());
    body.value = note.body || '';
    applyMode();
    renderEditorMeta();
  }

  function updateTrashButton(note) {
    const trashBtn = $('#trashBtn');
    const purgeBtn = $('#purgeBtn');
    if (note.deleted) {
      trashBtn.textContent = '恢复';
      trashBtn.classList.add('on');
      purgeBtn.hidden = false;
    } else {
      trashBtn.textContent = '删除';
      trashBtn.classList.remove('on');
      purgeBtn.hidden = true;
    }
  }

  function renderEditorMeta() {
    const note = currentNote();
    if (!note) return;
    const parts = [];
    if (note.createdAt) parts.push(`创建 ${formatTime(note.createdAt)}`);
    if (note.updatedAt) parts.push(`更新 ${formatTime(note.updatedAt)}`);
    if (state.bodyMissing) parts.push('正文未加载');
    else {
      const len = typeof note.body === 'string' ? note.body.replace(/\s/g, '').length : note.length || 0;
      parts.push(`${len} 字`);
    }
    const pending = note._pending || readPending().some((n) => n.id === note.id);
    if (pending) parts.push('未同步');
    $('#noteMeta').textContent = parts.join(' · ');
  }

  function closeEditor() {
    $('#app').classList.remove('editing');
  }

  async function purgeCurrent() {
    const note = currentNote();
    if (!note) return;
    if (!confirm('彻底删除这条笔记？无法恢复。')) return;
    try {
      await api(`/api/notes/${note.id}?purge=1`, { method: 'DELETE' });
      unqueue(note.id);
      state.notes = state.notes.filter((n) => n.id !== note.id);
      state.currentId = null;
      cacheSave();
      closeEditor();
      renderAll();
      toast('已彻底删除');
    } catch (err) {
      toast(err.message);
    }
  }

  /* -------------------------------------------------------------- 渲染 */

  function visibleNotes() {
    const q = state.q.trim().toLowerCase();
    let list = state.notes;

    if (state.view === 'trash') list = list.filter((n) => n.deleted);
    else if (state.view === 'pinned') list = list.filter((n) => !n.deleted && n.pinned);
    else list = list.filter((n) => !n.deleted);

    if (state.tag) list = list.filter((n) => (n.tags || []).includes(state.tag));

    if (q) {
      const terms = q.split(/\s+/).filter(Boolean);
      const remote = state.remoteIds;
      list = list.filter((n) => {
        const hay = `${n.title || ''}\n${typeof n.body === 'string' ? n.body : n.excerpt || ''}\n${(n.tags || []).join(
          ' '
        )}`.toLowerCase();
        if (terms.every((t) => hay.includes(t))) return true;
        // 正文没缓存时，用服务端搜索结果兜底
        return Boolean(remote && remote.has(n.id));
      });
    }

    return [...list].sort((a, b) => {
      if (state.view === 'trash') return (b.deletedAt || 0) - (a.deletedAt || 0);
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      return (b.updatedAt || 0) - (a.updatedAt || 0);
    });
  }

  function renderList() {
    const list = visibleNotes();
    const ul = $('#list');
    const pending = readPending();
    const pendingIds = new Set(pending.map((n) => n.id));

    if (!list.length) {
      ul.innerHTML = '';
      $('#listEmpty').hidden = false;
      $('#listEmpty').querySelector('p').textContent =
        state.q.trim() ? '没有匹配的笔记' : state.view === 'trash' ? '回收站是空的' : '这里还没有笔记';
      $('#listEmpty').querySelector('.hint').textContent =
        state.q.trim() ? '换个关键词试试' : state.view === 'trash' ? '' : '点右上角「新建」开始';
    } else {
      $('#listEmpty').hidden = true;
      ul.innerHTML = list
        .map((n) => {
          const active = n.id === state.currentId ? ' active' : '';
          const title = escapeHTML(n.title || '无标题');
          const raw = typeof n.body === 'string' ? stripMarkdown(n.body) : n.excerpt || '';
          const snippet = escapeHTML(raw.slice(0, 120)) || '（空白）';
          const tags = (n.tags || [])
            .slice(0, 3)
            .map((t) => '#' + escapeHTML(t))
            .join(' ');
          const pin = n.pinned
            ? '<span class="item-pin"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v11M8 14h8l-2 4H10z"/></svg></span>'
            : '';
          const dot = pendingIds.has(n.id) ? '<span class="item-pending" title="待同步"></span>' : '';
          return `<li class="item${active}" data-id="${n.id}">
            <div class="item-top"><span class="item-title">${title}</span>${dot}${pin}</div>
            <div class="item-snippet">${snippet}</div>
            <div class="item-foot"><span>${formatTime(n.deleted ? n.deletedAt : n.updatedAt)}</span><span class="item-tags">${tags}</span></div>
          </li>`;
        })
        .join('');
    }

    $('#countAll').textContent = String(state.notes.filter((n) => !n.deleted).length);
    const trashCount = state.notes.filter((n) => n.deleted).length;
    const tc = $('#countTrash');
    tc.textContent = String(trashCount);
    tc.hidden = trashCount === 0;
    $('#footInfo').textContent = `${state.notes.filter((n) => !n.deleted).length} 条笔记`;
    renderTagbar();
  }

  function renderTagbar() {
    const bar = $('#tagbar');
    const counts = new Map();
    for (const n of state.notes) {
      if (n.deleted) continue;
      for (const t of n.tags || []) counts.set(t, (counts.get(t) || 0) + 1);
    }
    if (!counts.size) {
      bar.innerHTML = '';
      return;
    }
    const tags = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 14);
    bar.innerHTML = tags
      .map(
        ([t, c]) =>
          `<button type="button" class="tag${state.tag === t ? ' active' : ''}" data-tag="${escapeHTML(t)}">${escapeHTML(
            t
          )} <span>${c}</span></button>`
      )
      .join('');
  }

  function renderAll() {
    renderList();
    if (!state.currentId) {
      $('#editor').hidden = true;
      $('#paneEmpty').hidden = false;
      $('#app').classList.remove('editing');
    } else {
      renderEditorMeta();
    }
    document.querySelectorAll('.view').forEach((b) => {
      b.classList.toggle('active', b.dataset.view === state.view);
    });
  }

  /* -------------------------------------------------------------- 编辑器 */

  function markdown(src) {
    // 渲染逻辑放在 markdown.js，主应用与分享页共用同一份实现
    return window.renderMarkdown ? window.renderMarkdown(src) : '';
  }

  // 按 state.preview 把编辑区切到「预览」或「编辑」形态
  function applyMode() {
    const note = currentNote();
    const body = $('#noteBody');
    const preview = $('#preview');
    const btn = $('#modeBtn');
    const bar = $('#formatBar');
    if (state.preview) {
      preview.innerHTML = markdown((note && note.body) || '');
      preview.hidden = false;
      body.hidden = true;
      bar.hidden = true;
      btn.textContent = '编辑';
      btn.classList.add('on');
    } else {
      preview.hidden = true;
      body.hidden = false;
      bar.hidden = false;
      btn.textContent = '预览';
      btn.classList.remove('on');
    }
  }

  function togglePreview() {
    const note = currentNote();
    if (!note) return;
    if (state.bodyMissing) {
      toast('正文还没加载，先恢复网络再编辑');
      return;
    }
    state.preview = !state.preview;
    if (!state.preview) $('#noteBody').value = note.body || '';
    applyMode();
    if (!state.preview) {
      const el = $('#noteBody');
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    }
  }

  // 工具栏「粘贴」：WebView 长按菜单不一定给粘贴项，这里直接读剪贴板插到光标处
  async function pasteFromClipboard() {
    const note = currentNote();
    if (!note) return;
    if (state.bodyMissing) {
      toast('正文还没加载，先恢复网络再编辑');
      return;
    }
    if (state.preview) {
      state.preview = false;
      $('#noteBody').value = note.body || '';
      applyMode();
    }

    let text = '';
    if (Native.clipboard && Native.clipboard.read) {
      text = await Native.clipboard.read();
    } else if (navigator.clipboard && navigator.clipboard.readText) {
      try {
        text = await navigator.clipboard.readText();
      } catch (e) {
        text = '';
      }
    }
    if (!text) {
      toast('读不到剪贴板内容，可以长按正文用系统粘贴，或先复制一次');
      return;
    }

    const el = $('#noteBody');
    el.focus();
    const start = el.selectionStart === null ? el.value.length : el.selectionStart;
    const end = el.selectionEnd === null ? start : el.selectionEnd;
    if (typeof el.setRangeText === 'function') {
      el.setRangeText(text, start, end, 'end');
    } else {
      el.value = el.value.slice(0, start) + text + el.value.slice(end);
    }
    note.body = el.value;
    note.updatedAt = Date.now();
    persistCurrentSoon(note);
    renderEditorMeta();
    toast(`已粘贴 ${text.length} 字`);
  }

  // 工具栏插入 Markdown 片段
  function insertMarkdown(kind) {
    const note = currentNote();
    if (!note) return;
    if (state.bodyMissing) {
      toast('正文还没加载，先恢复网络再编辑');
      return;
    }
    if (state.preview) {
      state.preview = false;
      $('#noteBody').value = note.body || '';
      applyMode();
    }

    const el = $('#noteBody');
    const value = el.value;
    const start = el.selectionStart === null ? value.length : el.selectionStart;
    const end = el.selectionEnd === null ? start : el.selectionEnd;
    const selected = value.slice(start, end);

    const inline = (before, after, placeholder) => {
      const text = selected || placeholder;
      el.value = value.slice(0, start) + before + text + after + value.slice(end);
      el.setSelectionRange(start + before.length, start + before.length + text.length);
    };

    const linePrefix = (prefix) => {
      const lineStart = value.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
      const tail = value.indexOf('\n', Math.max(end, lineStart + 1));
      const lineEnd = tail === -1 ? value.length : tail;
      const block = value.slice(lineStart, lineEnd);
      const changed = block
        .split('\n')
        .map((line) => (line.trim() ? prefix + line : line))
        .join('\n');
      el.value = value.slice(0, lineStart) + changed + value.slice(lineEnd);
      el.setSelectionRange(lineStart + prefix.length, lineStart + changed.length);
    };

    switch (kind) {
      case 'bold':
        inline('**', '**', '加粗文字');
        break;
      case 'italic':
        inline('*', '*', '斜体文字');
        break;
      case 'code':
        inline('`', '`', 'code');
        break;
      case 'link':
        inline('[', '](https://)', selected ? '' : '链接文字');
        break;
      case 'heading':
        linePrefix('## ');
        break;
      case 'list':
        linePrefix('- ');
        break;
      case 'todo':
        linePrefix('- [ ] ');
        break;
      case 'quote':
        linePrefix('> ');
        break;
      case 'rule': {
        const inset = (value.slice(0, end).endsWith('\n') || end === 0 ? '' : '\n') + '---\n';
        el.value = value.slice(0, end) + inset + value.slice(end);
        el.setSelectionRange(end + inset.length, end + inset.length);
        break;
      }
      default:
        return;
    }

    el.focus();
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }


  /* ---------------------------------------------------------------- 登录 */

  function showLogin(message = '') {
    $('#app').hidden = true;
    $('#login').hidden = false;
    const err = $('#loginError');
    err.hidden = !message;
    err.textContent = message;
    setTimeout(() => $('#password').focus(), 50);
  }

  function showApp() {
    $('#login').hidden = true;
    $('#app').hidden = false;
  }

  function logout(silent = false) {
    state.token = '';
    localStorage.removeItem(KEY.token);
    localStorage.removeItem(KEY.device);
    if (Native.secure.remove) Native.secure.remove('token');
    localStorage.removeItem(KEY.cache);
    localStorage.removeItem(KEY.pending);
    state.notes = [];
    state.currentId = null;
    showLogin(silent ? '登录已过期，请重新登录' : '');
  }

  async function login(password) {
    const btn = $('#loginBtn');
    const err = $('#loginError');
    const pwdInput = $('#password');

    if (!password) {
      err.hidden = false;
      err.textContent = '请先输入访问密码';
      pwdInput.focus();
      return;
    }

    err.hidden = true;
    btn.disabled = true;
    const label = btn.textContent;
    btn.textContent = '验证中…';
    try {
      const res = await api('/api/login', {
        method: 'POST',
        body: { password, device: Native.native === true },
        timeout: 20000,
      });
      state.token = res.token;
      localStorage.setItem(KEY.token, res.token);
      localStorage.setItem(KEY.device, res.device ? '1' : '0');
      if (Native.secure.set) Native.secure.set('token', res.token);
      pwdInput.value = '';
      err.hidden = true;
      showApp();
      await refresh();
      if (readPending().length) scheduleFlush(100);
      setSync(navigator.onLine ? 'idle' : 'offline');
    } catch (e) {
      err.hidden = false;
      err.textContent = e.message || '登录失败';
    } finally {
      btn.disabled = false;
      btn.textContent = label;
    }
  }

  async function refresh() {
    // 列表只取元信息（标题/标签/摘要），正文点开时再单独拉
    const res = await api('/api/notes?meta=1');
    const serverNotes = (res.notes || []).map((n) => ({ ...n }));
    for (const n of serverNotes) {
      const known = state.notes.find((x) => x.id === n.id);
      if (known && typeof known.body === 'string' && (known.updatedAt || 0) === (n.updatedAt || 0)) {
        n.body = known.body;
      }
    }
    for (const p of readPending()) {
      const idx = serverNotes.findIndex((n) => n.id === p.id);
      if (idx >= 0) serverNotes[idx] = { ...p, _pending: true };
      else serverNotes.push({ ...p, _pending: true });
    }
    state.notes = serverNotes;
    cacheSave();
    renderAll();
  }

  // 正文按需加载：拿到后写回本地笔记对象（同时进缓存，离线可读）
  async function ensureBody(note) {
    if (typeof note.body === 'string') return true;
    if (!navigator.onLine) return false;
    try {
      const res = await api(`/api/notes/${note.id}`);
      const fresh = res && res.note;
      if (!fresh) return false;
      note.body = typeof fresh.body === 'string' ? fresh.body : '';
      note.title = fresh.title || note.title;
      note.tags = fresh.tags || note.tags || [];
      note.pinned = Boolean(fresh.pinned);
      note.deleted = Boolean(fresh.deleted);
      note.createdAt = fresh.createdAt || note.createdAt;
      note.updatedAt = fresh.updatedAt || note.updatedAt;
      cacheSave();
      return true;
    } catch {
      return false;
    }
  }


  /* ------------------------------------------------------------ 主题等 */

  function applyTheme(mode) {
    const pref = mode === 'auto' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : mode;
    document.documentElement.dataset.theme = pref;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = pref === 'dark' ? '#161618' : '#f6f6f5';
  }

  function toggleTheme() {
    const now = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem(KEY.theme, now);
    applyTheme(now);
  }

  async function exportNotes() {
    try {
      const res = await api('/api/export', { raw: true });
      const filename = `notes-${new Date().toISOString().slice(0, 10)}.json`;
      const text = await res.text();
      if (Native.files.save) {
        const saved = await Native.files.save(filename, text, 'application/json');
        if (saved) {
          toast(typeof saved === 'string' ? `已导出到 ${saved}` : '已导出 JSON');
          return;
        }
      }
      const blob = new Blob([text], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `notes-${new Date().toISOString().slice(0, 10)}.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast('已导出 JSON');
    } catch (err) {
      toast(err.message);
    }
  }

  /* ---------------------------------------------------- 单篇导出（可读版） */

  function safeFileName(name, fallback) {
    const cleaned = (name || '')
      .replace(/[\u0000-\u001f]/g, '')
      .replace(/[\\/:*?"<>|]/g, '-')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 60);
    return cleaned || fallback;
  }

  function buildNoteHtml(note) {
    const title = note.title || '无标题';
    const tags = (note.tags || []).map((t) => `#${t}`).join(' ');
    const when = note.updatedAt ? formatTime(note.updatedAt) : '';
    const info = [tags, when ? `更新 ${when}` : ''].filter(Boolean).join(' · ');
    const content = window.renderMarkdown(note.body || '');

    return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${escapeHTML(title)}</title>
<style>
  :root {
    --bg: #f6f1e6; --panel: #fffdf7; --ink: #2a2620; --ink-2: #57503f;
    --muted: #8c8271; --line: #e2d9c7; --accent: #b0502a; --accent-soft: #f3e2d7;
    --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
    --serif: "Noto Serif CJK SC", "Songti SC", "SimSun", Georgia, serif;
    --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  }
  @media (prefers-color-scheme: dark) {
    :root { --bg: #12100d; --panel: #1b1814; --ink: #ede5d6; --ink-2: #c3b8a4;
      --muted: #8b8272; --line: #2f2a22; --accent: #d9855d; --accent-soft: #2c211a; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--ink); font-family: var(--sans);
    font-size: 16px; line-height: 1.75; -webkit-text-size-adjust: 100%; }
  .page { max-width: 46rem; margin: 0 auto; padding: 3rem 1.5rem 4rem; }
  .sheet { background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
    padding: 2.4rem 2rem 2.8rem; box-shadow: 0 1px 2px rgba(45,36,24,.05), 0 8px 24px rgba(45,36,24,.07); }
  h1.title { font-family: var(--serif); font-size: 1.9rem; line-height: 1.35; font-weight: 600;
    margin: 0 0 .6rem; letter-spacing: .01em; }
  .meta { color: var(--muted); font-size: .85rem; padding-bottom: 1.1rem; margin-bottom: 1.4rem;
    border-bottom: 1px solid var(--line); }
  .content { overflow-wrap: anywhere; }
  .content h1, .content h2, .content h3, .content h4 {
    font-family: var(--serif); line-height: 1.4; margin: 1.6em 0 .6em; font-weight: 600; }
  .content h2 { font-size: 1.25rem; }
  .content h3 { font-size: 1.08rem; }
  .content p { margin: .8em 0; }
  .content ul, .content ol { margin: .6em 0; padding-left: 1.5em; }
  .content li { margin: .3em 0; }
  .content a { color: var(--accent); text-decoration: none; border-bottom: 1px solid var(--accent-soft); }
  .content code { font-family: var(--mono); font-size: .9em; background: var(--accent-soft);
    padding: .12em .35em; border-radius: 4px; }
  .content pre { background: var(--accent-soft); border-radius: 8px; padding: .9em 1em;
    overflow-x: auto; margin: 1em 0; }
  .content pre code { background: none; padding: 0; font-size: .88rem; line-height: 1.6; }
  .content blockquote { margin: 1em 0; padding: .2em 0 .2em 1em; border-left: 3px solid var(--accent-soft);
    color: var(--ink-2); }
  .content hr { border: 0; border-top: 1px solid var(--line); margin: 1.8em 0; }
  .content li.task { list-style: none; margin-left: -1.3em; display: flex; gap: .5em; align-items: baseline; }
  .content .task-box { display: inline-flex; width: 1.05em; height: 1.05em; border: 1px solid var(--muted);
    border-radius: 3px; align-items: center; justify-content: center; font-size: .8em; color: var(--accent); flex: none; }
  .content li.task.done { color: var(--muted); text-decoration: line-through; }
  .content li.task.done .task-box { border-color: var(--accent); background: var(--accent-soft); }
  .foot { color: var(--muted); font-size: .78rem; text-align: center; margin-top: 1.6rem; }
  @media print {
    body { background: #fff; }
    .page { max-width: none; padding: 0; }
    .sheet { border: 0; box-shadow: none; padding: 0; }
    .foot { margin-top: 2rem; }
    a { color: inherit; border: 0; }
  }
</style>
</head>
<body>
  <div class="page">
    <article class="sheet">
      <h1 class="title">${escapeHTML(title)}</h1>
      ${info ? `<div class="meta">${escapeHTML(info)}</div>` : ''}
      <div class="content">${content}</div>
    </article>
    <div class="foot">记事本 · ${escapeHTML(
      new Date().toLocaleString('zh-CN', { hour12: false })
    )}</div>
  </div>
</body>
</html>`;
  }

  async function exportNoteHtml() {
    const note = currentNote();
    if (!note) return;
    if (typeof note.body !== 'string') {
      const ok = await ensureBody(note);
      if (!ok) {
        toast('正文还没加载，连上网络后再导出');
        return;
      }
    }
    try {
      const html = buildNoteHtml(note);
      const filename = `${safeFileName(note.title, `笔记-${new Date().toISOString().slice(0, 10)}`)}.html`;
      if (Native.files.save) {
        const saved = await Native.files.save(filename, html, 'text/html');
        if (saved) {
          toast(typeof saved === 'string' ? `已导出到 ${saved}` : '已导出 HTML');
          return;
        }
      }
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast('已导出 HTML');
    } catch (err) {
      toast(err.message || '导出失败');
    }
  }

  /* ---------------------------------------------------------------- 导入 */

  async function handleImportFile(file) {
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) {
      toast('文件太大，单个备份不应超过 20MB');
      return;
    }
    let data;
    try {
      data = JSON.parse(await file.text());
    } catch {
      toast('不是有效的 JSON 文件');
      return;
    }
    const notes = Array.isArray(data) ? data : data && data.notes;
    if (!Array.isArray(notes) || !notes.length) {
      toast('这个文件里没有笔记');
      return;
    }
    const ok = confirm(
      `将导入 ${notes.length} 条笔记：同一篇按更新时间取新的，比你这里旧的会跳过，不会删除现有笔记。继续？`
    );
    if (!ok) return;

    try {
      toast('正在导入…');
      const res = await api('/api/import', { method: 'POST', body: { notes }, timeout: 60000 });
      toast(`导入完成：写入 ${res.imported} 条，跳过 ${res.skipped} 条`);
      await refresh();
    } catch (err) {
      toast(err.message);
    }
  }

  /* ---------------------------------------------------------------- 分享 */

  function openShareModal(url) {
    $('#shareUrl').value = url;
    $('#shareModal').hidden = false;
  }

  function closeShareModal() {
    $('#shareModal').hidden = true;
  }

  async function shareNote() {
    const note = currentNote();
    if (!note) return;
    try {
      const res = await api(`/api/notes/${note.id}/share`, { method: 'POST' });
      openShareModal(res.url);
    } catch (e) {
      toast(e.message);
    }
  }

  async function stopShare() {
    const note = currentNote();
    if (!note) return;
    try {
      const res = await api(`/api/notes/${note.id}/share`, { method: 'DELETE' });
      closeShareModal();
      toast(res.revoked ? '已取消分享，原链接失效' : '这条笔记本来就没有分享');
    } catch (e) {
      toast(e.message);
    }
  }

  async function copyShare() {
    const el = $('#shareUrl');
    const text = el.value;
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      toast('链接已复制');
      return;
    } catch {
      /* 部分 WebView 不允许直接写剪贴板，退回手动复制 */
    }
    el.removeAttribute('readonly');
    el.select();
    try {
      document.execCommand('copy');
      toast('链接已复制');
    } catch {
      toast('已选中链接，长按或按 Ctrl+C 复制');
    }
    el.setAttribute('readonly', '');
  }

  /* -------------------------------------------------------------- 事件 */

  function bind() {
    $('#loginForm').addEventListener('submit', (e) => {
      e.preventDefault();
      login($('#password').value.trim());
    });

    $('#password').addEventListener('input', () => {
      const err = $('#loginError');
      if (!err.hidden) {
        err.hidden = true;
        err.textContent = '';
      }
    });

    $('#newBtn').addEventListener('click', newNote);
    $('#themeBtn').addEventListener('click', toggleTheme);

    // 顶部「更多」菜单：导入 / 导出 / 退出收在这里，侧栏底部只留笔记数
    const moreBtn = $('#moreBtn');
    const moreMenu = $('#moreMenu');
    const closeMoreMenu = () => {
      if (!moreMenu || moreMenu.hidden) return;
      moreMenu.hidden = true;
      moreBtn.setAttribute('aria-expanded', 'false');
    };
    if (moreBtn && moreMenu) {
      moreBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        const willOpen = moreMenu.hidden;
        moreMenu.hidden = !willOpen;
        moreBtn.setAttribute('aria-expanded', String(willOpen));
      });
      moreMenu.addEventListener('click', (e) => {
        if (e.target.closest('button')) closeMoreMenu();
      });
      document.addEventListener('click', (e) => {
        if (moreMenu.hidden) return;
        if (e.target.closest('#moreMenu') || e.target.closest('#moreBtn')) return;
        closeMoreMenu();
      });
      document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeMoreMenu();
      });
    }

    $('#exportBtn').addEventListener('click', exportNotes);
    $('#importBtn').addEventListener('click', () => $('#importFile').click());
    $('#importFile').addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      handleImportFile(file);
      e.target.value = '';
    });

    $('#shareBtn').addEventListener('click', shareNote);
    $('#exportNoteBtn').addEventListener('click', exportNoteHtml);
    $('#shareCopy').addEventListener('click', copyShare);
    $('#shareStop').addEventListener('click', stopShare);
    $('#shareClose').addEventListener('click', closeShareModal);
    $('#shareModal').addEventListener('click', (e) => {
      if (e.target.id === 'shareModal') closeShareModal();
    });
    $('#logoutBtn').addEventListener('click', () => {
      if (confirm('退出登录？本地缓存会被清除。')) logout();
    });

    $('#views').addEventListener('click', (e) => {
      const btn = e.target.closest('.view');
      if (!btn) return;
      state.view = btn.dataset.view;
      state.currentId = null;
      closeEditor();
      renderAll();
    });

    $('#tagbar').addEventListener('click', (e) => {
      const btn = e.target.closest('.tag');
      if (!btn) return;
      state.tag = state.tag === btn.dataset.tag ? '' : btn.dataset.tag;
      renderList();
    });

    $('#list').addEventListener('click', (e) => {
      const item = e.target.closest('.item');
      if (!item) return;
      openNote(item.dataset.id);
    });

    $('#search').addEventListener('input', (e) => {
      state.q = e.target.value;
      $('#searchClear').hidden = !state.q;
      state.remoteIds = null;
      renderList();

      clearTimeout(searchTimer);
      const q = state.q.trim();
      if (!q || !navigator.onLine) return;
      // 正文只按需加载，全文检索交给服务端（合并到当前结果里）
      searchTimer = setTimeout(async () => {
        try {
          const res = await api(`/api/notes?meta=1&q=${encodeURIComponent(q)}`);
          if (state.q.trim() !== q) return;
          state.remoteIds = new Set((res.notes || []).map((n) => n.id));
          renderList();
        } catch {}
      }, 400);
    });
    $('#searchClear').addEventListener('click', () => {
      state.q = '';
      state.remoteIds = null;
      $('#search').value = '';
      $('#searchClear').hidden = true;
      renderList();
      $('#search').focus();
    });

    const title = $('#noteTitle');
    const tags = $('#noteTags');
    const body = $('#noteBody');

    title.addEventListener('input', () => {
      const n = currentNote();
      if (!n) return;
      n.title = title.value.slice(0, 200);
      n.updatedAt = Date.now();
      persistCurrentSoon(n);
      renderEditorMeta();
    });

    tags.addEventListener('input', () => {
      const n = currentNote();
      if (!n) return;
      n.tags = tags.value
        .split(/[,，、]+/)
        .map((t) => t.trim().replace(/^#/, ''))
        .filter(Boolean)
        .slice(0, 10);
      n.updatedAt = Date.now();
      persistCurrentSoon(n);
    });

    body.addEventListener('input', () => {
      const n = currentNote();
      if (!n) return;
      n.body = body.value;
      n.updatedAt = Date.now();
      persistCurrentSoon(n);
      renderEditorMeta();
    });

    body.addEventListener('blur', () => {
      clearTimeout(persistTimer);
      const n = currentNote();
      if (n && n._pending) {
        queueNote(n);
        cacheSave();
        scheduleFlush(200);
      }
    });

    $('#modeBtn').addEventListener('click', togglePreview);

    $('#formatBar').addEventListener('click', (e) => {
      const btn = e.target.closest('.fmt');
      if (!btn) return;
      if (btn.dataset.act === 'paste') {
        pasteFromClipboard();
        return;
      }
      insertMarkdown(btn.dataset.md);
    });

    // 预览里点正文就直接回到编辑；点链接不打断
    $('#preview').addEventListener('click', (e) => {
      if (e.target.closest('a')) return;
      if (state.preview) togglePreview();
    });

    $('#pinBtn').addEventListener('click', () => {
      const n = currentNote();
      if (!n) return;
      n.pinned = !n.pinned;
      n.updatedAt = Date.now();
      n._pending = true;
      queueNote(n);
      cacheSave();
      scheduleFlush(200);
      $('#pinBtn').classList.toggle('on', n.pinned);
      renderAll();
    });

    $('#trashBtn').addEventListener('click', () => {
      const n = currentNote();
      if (!n) return;
      if (n.deleted) {
        n.deleted = false;
        n.deletedAt = 0;
        n.updatedAt = Date.now();
        n._pending = true;
        queueNote(n);
        cacheSave();
        scheduleFlush(200);
        updateTrashButton(n);
        renderAll();
        toast('已恢复到笔记列表');
      } else {
        n.deleted = true;
        n.deletedAt = Date.now();
        n.updatedAt = Date.now();
        n._pending = true;
        queueNote(n);
        cacheSave();
        scheduleFlush(200);
        state.currentId = null;
        closeEditor();
        renderAll();
        toast('已移入回收站');
      }
    });

    $('#purgeBtn').addEventListener('click', purgeCurrent);
    $('#backBtn').addEventListener('click', closeEditor);

    document.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        flushPending();
      }
      if ((e.metaKey || e.ctrlKey) && document.activeElement === $('#noteBody')) {
        const k = e.key.toLowerCase();
        if (k === 'b') {
          e.preventDefault();
          insertMarkdown('bold');
        } else if (k === 'i') {
          e.preventDefault();
          insertMarkdown('italic');
        }
      }
      if (e.key === 'Escape') {
        if (!$('#shareModal').hidden) closeShareModal();
        else if (document.body.classList.contains('editing')) closeEditor();
      }
    });

    window.addEventListener('online', () => {
      setSync('saving');
      flushPending();
    });
    window.addEventListener('offline', () => setSync('offline'));
    window.addEventListener('beforeunload', () => {
      clearTimeout(persistTimer);
      const n = currentNote();
      if (n && n._pending) {
        queueNote(n);
        cacheSave();
      }
    });

    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (localStorage.getItem(KEY.theme) === 'auto') applyTheme('auto');
    });
  }

  /* ---------------------------------------------------------- 安卓壳增强 */

  function setupNative() {
    if (!Native.native) return;

    const lockBtn = $('#lockBtn');
    if (lockBtn && Native.bio.available) {
      Native.bio
        .available()
        .then((ok) => {
          if (!ok) return;
          const on = localStorage.getItem(KEY.lock) === '1';
          lockBtn.hidden = false;
          lockBtn.textContent = on ? '指纹锁 开' : '指纹锁';
          lockBtn.classList.toggle('on', on);
          lockBtn.onclick = async () => {
            const verified = await Native.bio.authenticate('验证身份', '确认是你本人再改指纹锁');
            if (!verified) {
              toast('已取消');
              return;
            }
            const now = localStorage.getItem(KEY.lock) === '1' ? '0' : '1';
            localStorage.setItem(KEY.lock, now);
            lockBtn.textContent = now === '1' ? '指纹锁 开' : '指纹锁';
            lockBtn.classList.toggle('on', now === '1');
            toast(now === '1' ? '已开启指纹锁，下次启动需要解锁' : '已关闭指纹锁');
          };
        })
        .catch(() => {});
    }

    // 每天 4 点后台备份一次（只在装好后排一次，重启不会重置）
    if (Native.backup.schedule && !localStorage.getItem(KEY.backup)) {
      Native.backup
        .schedule(4)
        .then((res) => {
          if (res) localStorage.setItem(KEY.backup, '1');
        })
        .catch(() => {});
    }
  }

  async function intakeShare() {
    if (!Native.native || !Native.share.take) return;
    const text = await Native.share.take();
    if (!text) return;
    const firstLine = text.trim().split('\n')[0].slice(0, 40);
    try {
      const res = await api('/api/notes', {
        method: 'POST',
        body: { title: firstLine || '分享进来的内容', body: text },
      });
      await refresh();
      if (res && res.note) openNote(res.note.id);
      toast('已从分享创建一条笔记');
    } catch (err) {
      toast('分享内容保存失败：' + err.message);
    }
  }

  /* -------------------------------------------------------------- 启动 */

  async function boot() {
    state.token = localStorage.getItem(KEY.token) || '';

    // 安卓壳：令牌以加密存储为准，localStorage 只是缓存
    if (Native.native && Native.secure.get) {
      const stored = await Native.secure.get('token');
      if (stored) {
        state.token = stored;
        localStorage.setItem(KEY.token, stored);
      } else if (state.token) {
        Native.secure.set('token', state.token);
      }
    }

    applyTheme(localStorage.getItem(KEY.theme) || 'dark');
    cacheLoad();
    renderAll();
    bind();
    setupNative();

    if ('serviceWorker' in navigator && !Native.native) {
      navigator.serviceWorker.register('/sw.js').catch(() => {});
    }

    if (!state.token) {
      showLogin();
      return;
    }

    // 开了指纹锁就先解锁；取消的话退回密码登录
    if (Native.native && localStorage.getItem(KEY.lock) === '1') {
      const ok = Native.bio.authenticate
        ? await Native.bio.authenticate('解锁记事本', '用指纹或锁屏密码继续')
        : false;
      if (!ok) {
        showLogin('指纹解锁已取消，输入密码也能进入');
        return;
      }
    }

    showApp();
    try {
      await api('/api/session');
    } catch {
      showLogin();
      return;
    }

    await flushPending();

    // 安卓壳：把 180 天的网页令牌换成 10 年免密令牌（改网页密码不影响它）
    if (Native.native && localStorage.getItem(KEY.device) !== '1') {
      try {
        const res = await api('/api/token/device', { method: 'POST', timeout: 20000 });
        if (res && res.token) {
          state.token = res.token;
          localStorage.setItem(KEY.token, res.token);
          localStorage.setItem(KEY.device, '1');
          if (Native.secure.set) Native.secure.set('token', res.token);
        }
      } catch (e) {
        // 换不到就继续用现在的令牌，下次启动再试
      }
    }
    try {
      await refresh();
      setSync(navigator.onLine ? 'idle' : 'offline');
    } catch (err) {
      setSync(navigator.onLine ? 'idle' : 'offline');
      toast('离线模式：显示本地缓存');
    }

    await intakeShare();

    // 只在宽屏自动打开上次的笔记；手机上先显示列表
    const wide = window.matchMedia('(min-width: 821px)').matches;
    if (wide) {
      const last = localStorage.getItem(KEY.last);
      if (last && state.notes.some((n) => n.id === last)) openNote(last);
      else {
        const sorted = visibleNotes()[0];
        if (sorted) openNote(sorted.id);
      }
    }
  }

  // 安卓壳的返回键钩子：返回 true 表示网页自己处理了
  window.__nativeBack = () => {
    if (!$('#shareModal').hidden) {
      closeShareModal();
      return true;
    }
    if ($('#app').classList.contains('editing')) {
      closeEditor();
      return true;
    }
    if (state.q) {
      state.q = '';
      $('#search').value = '';
      $('#searchClear').hidden = true;
      renderList();
      return true;
    }
    return false;
  };

  document.addEventListener('DOMContentLoaded', boot);
})();
