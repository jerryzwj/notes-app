/**
 * 记事本后端 —— Cloudflare Worker
 * 存储：Upstash Redis（REST /pipeline）
 *
 * 数据结构
 *   note:<id>    Hash  { id, title, body, tags, pinned, createdAt, updatedAt, deleted, deletedAt }
 *   notes:index  ZSet  member=<id> score=updatedAt
 *   login:fail:<ip> 计数，用于限制密码爆破
 *   share:<slug>        Hash { noteId, createdAt }，公开只读分享
 *   share:by-note:<id>  String slug，用于取回/取消分享
 *
 * 环境变量（wrangler secret）
 *   UPSTASH_TOKEN    Upstash REST token
 *   AUTH_SECRET      签发登录令牌的密钥
 *   APP_PASSWORD     个人访问密码
 */

const enc = new TextEncoder();
const MAX_BODY = 200_000; // 单条笔记正文上限（字符）
const TOKEN_TTL = 180 * 24 * 60 * 60 * 1000; // 180 天（网页端）
const DEVICE_TOKEN_TTL = 3650 * 24 * 60 * 60 * 1000; // 10 年（安卓壳的免密令牌）
const MAX_LOGIN_FAILS = 10;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const jsonHeaders = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
};

// 安卓壳（https://localhost）调接口是跨域的，需要放行；接口本身靠 Bearer 令牌鉴权，不依赖 cookie
const corsHeaders = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
  'access-control-max-age': '86400',
};

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...jsonHeaders, ...corsHeaders, ...headers } });
}

function fail(status, message) {
  return json({ error: message }, status);
}

/* ---------------------------------------------------------------- 数据库 */

async function db(env, commands) {
  if (!env.UPSTASH_URL || !env.UPSTASH_TOKEN) throw new HttpError(500, '数据库未配置');
  let res;
  try {
    res = await fetch(env.UPSTASH_URL.replace(/\/+$/, '') + '/pipeline', {
      method: 'POST',
      headers: {
        authorization: `Bearer ${env.UPSTASH_TOKEN}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(commands),
    });
  } catch (e) {
    throw new HttpError(502, '数据库连接失败');
  }
  if (!res.ok) throw new HttpError(502, `数据库返回 ${res.status}`);
  const rows = await res.json();
  const out = [];
  for (const row of rows) {
    if (row && row.error) throw new HttpError(500, `数据库错误: ${row.error}`);
    out.push(row ? row.result : null);
  }
  return out;
}

/* ------------------------------------------------------------------ 鉴权 */

async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let diff = a.length ^ b.length;
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i += 1) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

async function issueToken(env, scope = 'web') {
  const ts = Date.now();
  const sig = await hmacHex(env.AUTH_SECRET, `${scope}:${ts}`);
  return `${ts}.${scope === 'device' ? 'd' : 'w'}.${sig}`;
}

async function verifyToken(env, token) {
  if (!token || !env.AUTH_SECRET) return false;
  const parts = String(token).split('.');

  // 老格式（ts.sig）：只认旧签名，180 天
  if (parts.length === 2) {
    const ts = Number(parts[0]);
    if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > TOKEN_TTL) return false;
    return safeEqual(parts[1], await hmacHex(env.AUTH_SECRET, `v1:${ts}`));
  }

  // 新格式（ts.d|w.sig）：d = 安卓壳的免密令牌，与登录密码无关，只有换 AUTH_SECRET 才会失效
  if (parts.length !== 3) return false;
  const scope = parts[1] === 'd' ? 'device' : 'web';
  const ttl = scope === 'device' ? DEVICE_TOKEN_TTL : TOKEN_TTL;
  const ts = Number(parts[0]);
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > ttl) return false;
  return safeEqual(parts[2], await hmacHex(env.AUTH_SECRET, `${scope}:${ts}`));
}

function bearer(request) {
  const h = request.headers.get('authorization') || '';
  return h.startsWith('Bearer ') ? h.slice(7).trim() : '';
}

async function requireAuth(request, env) {
  if (await verifyToken(env, bearer(request))) return null;
  return fail(401, '未登录或登录已过期');
}

/* ------------------------------------------------------------------ 笔记 */

const emptyNote = (id = '') => ({
  id,
  title: '',
  body: '',
  tags: [],
  pinned: false,
  createdAt: 0,
  updatedAt: 0,
  deleted: false,
  deletedAt: 0,
});

function readNote(id, flat) {
  if (!Array.isArray(flat) || flat.length === 0) return null;
  const map = {};
  for (let i = 0; i < flat.length; i += 2) map[flat[i]] = flat[i + 1];
  let tags = [];
  try {
    tags = JSON.parse(map.tags || '[]');
  } catch {
    tags = [];
  }
  return {
    id: map.id || id,
    title: map.title || '',
    body: map.body || '',
    tags: Array.isArray(tags) ? tags.slice(0, 20) : [],
    pinned: map.pinned === '1',
    createdAt: Number(map.createdAt) || 0,
    updatedAt: Number(map.updatedAt) || 0,
    deleted: map.deleted === '1',
    deletedAt: Number(map.deletedAt) || 0,
  };
}

async function getNote(env, id) {
  const [flat] = await db(env, [['HGETALL', `note:${id}`]]);
  return readNote(id, flat);
}

function cleanTitle(value) {
  return String(value ?? '').replace(/\r/g, '').replace(/\n+/g, ' ').trim().slice(0, 200);
}

function cleanTags(value) {
  const list = Array.isArray(value) ? value : String(value ?? '').split(/[,，#\s]+/);
  const out = [];
  for (const raw of list) {
    const t = String(raw).replace(/^#/, '').trim().slice(0, 24);
    if (t && !out.includes(t)) out.push(t);
    if (out.length >= 10) break;
  }
  return out;
}

async function listNotes(env, { lite = false, q = '' } = {}) {
  const [ids] = await db(env, [['ZREVRANGE', 'notes:index', '0', '-1']]);
  if (!Array.isArray(ids) || ids.length === 0) return [];
  const notes = [];
  for (let i = 0; i < ids.length; i += 50) {
    const chunk = ids.slice(i, i + 50);
    const rows = await db(env, chunk.map((id) => ['HGETALL', `note:${id}`]));
    chunk.forEach((id, idx) => {
      const note = readNote(id, rows[idx]);
      if (note) notes.push(note);
    });
  }

  let out = notes;
  const query = String(q || '').trim().toLowerCase();
  if (query) {
    const terms = query.split(/\s+/).filter(Boolean);
    out = notes.filter((n) => {
      const hay = `${n.title || ''}\n${n.body || ''}\n${(n.tags || []).join(' ')}`.toLowerCase();
      return terms.every((t) => hay.includes(t));
    });
  }

  return lite ? out.map(noteMeta) : out;
}

// 列表只需要元信息：正文换成摘要，避免一次传全部内容
function noteMeta(note) {
  const { body, ...rest } = note;
  return { ...rest, excerpt: excerptOf(body), length: body.length, hasBody: body.length > 0 };
}

function excerptOf(body, len = 100) {
  const text = String(body || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[*_~`>|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return text.slice(0, len);
}

async function saveNote(env, payload) {
  const id = String(payload.id || crypto.randomUUID());
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(id)) throw new HttpError(400, '笔记 ID 非法');

  const existing = await getNote(env, id);
  const now = Date.now();

  let body = typeof payload.body === 'string' ? payload.body : existing?.body ?? '';
  body = body.replace(/\r\n/g, '\n');
  if (body.length > MAX_BODY) throw new HttpError(413, `正文超过 ${MAX_BODY} 字上限`);

  const note = {
    id,
    title: payload.title === undefined ? existing?.title ?? '' : cleanTitle(payload.title),
    body,
    tags: payload.tags === undefined ? existing?.tags ?? [] : cleanTags(payload.tags),
    pinned: payload.pinned === undefined ? existing?.pinned ?? false : Boolean(payload.pinned),
    createdAt: existing?.createdAt || Number(payload.createdAt) || now,
    updatedAt: now,
    deleted: payload.deleted === undefined ? existing?.deleted ?? false : Boolean(payload.deleted),
    deletedAt: 0,
  };
  note.deletedAt = note.deleted ? (existing?.deletedAt || now) : 0;

  await db(env, [
    [
      'HSET',
      `note:${id}`,
      'id', note.id,
      'title', note.title,
      'body', note.body,
      'tags', JSON.stringify(note.tags),
      'pinned', note.pinned ? '1' : '0',
      'createdAt', String(note.createdAt),
      'updatedAt', String(note.updatedAt),
      'deleted', note.deleted ? '1' : '0',
      'deletedAt', String(note.deletedAt),
    ],
    ['ZADD', 'notes:index', String(note.updatedAt), note.id],
  ]);

  return note;
}

async function trashNote(env, id, purge) {
  const note = await getNote(env, id);
  if (!note) throw new HttpError(404, '笔记不存在');
  if (purge) {
    const slug = await getShareSlug(env, id);
    const cmds = [['DEL', `note:${id}`], ['ZREM', 'notes:index', id]];
    if (slug) cmds.push(['DEL', `share:${slug}`], ['DEL', `share:by-note:${id}`]);
    await db(env, cmds);
    return { ok: true, id, purged: true };
  }
  const now = Date.now();
  await db(env, [['HSET', `note:${id}`, 'deleted', '1', 'deletedAt', String(now)]]);
  return { ok: true, id, purged: false };
}

async function restoreNote(env, id) {
  const note = await getNote(env, id);
  if (!note) throw new HttpError(404, '笔记不存在');
  const now = Date.now();
  await db(env, [
    ['HSET', `note:${id}`, 'deleted', '0', 'deletedAt', '0', 'updatedAt', String(now)],
    ['ZADD', 'notes:index', String(now), id],
  ]);
  return { ok: true, id };
}

/* ------------------------------------------------------------ 导入 / 清理 */

const TRASH_TTL_DAYS = 30;
const SWEEP_KEY = 'sys:trashSweep';

function noteFields(note) {
  return [
    'id', note.id,
    'title', note.title,
    'body', note.body,
    'tags', JSON.stringify(note.tags || []),
    'pinned', note.pinned ? '1' : '0',
    'createdAt', String(note.createdAt),
    'updatedAt', String(note.updatedAt),
    'deleted', note.deleted ? '1' : '0',
    'deletedAt', String(note.deletedAt || 0),
  ];
}

// 导入备份：默认按 id 合并，同一篇以 updatedAt 较新的为准
async function importNotes(env, payload) {
  const incoming = Array.isArray(payload && payload.notes) ? payload.notes : [];
  if (!incoming.length) throw new HttpError(400, '文件里没有笔记');
  if (incoming.length > 5000) throw new HttpError(413, '一次最多导入 5000 条笔记');

  const mode = payload.mode === 'overwrite' ? 'overwrite' : 'merge';
  const existing = new Map((await listNotes(env)).map((n) => [n.id, n]));

  const plan = [];
  let skipped = 0;

  for (const raw of incoming) {
    if (!raw || typeof raw !== 'object') {
      skipped += 1;
      continue;
    }
    const id = String(raw.id || '');
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
      skipped += 1;
      continue;
    }
    const body = typeof raw.body === 'string' ? raw.body.replace(/\r\n/g, '\n') : '';
    if (body.length > MAX_BODY) {
      skipped += 1;
      continue;
    }
    const cur = existing.get(id);
    if (cur && mode === 'merge' && Number(raw.updatedAt || 0) <= Number(cur.updatedAt || 0)) {
      skipped += 1;
      continue;
    }
    const updatedAt = Number(raw.updatedAt) || Date.now();
    const deleted = Boolean(raw.deleted);
    plan.push({
      id,
      title: cleanTitle(raw.title || ''),
      body,
      tags: cleanTags(raw.tags || []),
      pinned: Boolean(raw.pinned),
      createdAt: Number(raw.createdAt) || updatedAt,
      updatedAt,
      deleted,
      deletedAt: deleted ? Number(raw.deletedAt) || updatedAt : 0,
    });
  }

  const cmds = [];
  for (const note of plan) {
    cmds.push(['HSET', `note:${note.id}`, ...noteFields(note)]);
    cmds.push(['ZADD', 'notes:index', String(note.updatedAt), note.id]);
  }
  for (let i = 0; i < cmds.length; i += 100) {
    await db(env, cmds.slice(i, i + 100));
  }

  return { imported: plan.length, skipped, mode, total: incoming.length };
}

// 回收站里的笔记超过 30 天自动彻底删除；最多 6 小时跑一次
async function sweepTrash(env) {
  const [last] = await db(env, [['GET', SWEEP_KEY]]);
  const now = Date.now();
  if (last && now - Number(last) < 6 * 3600 * 1000) return 0;
  await db(env, [['SET', SWEEP_KEY, String(now)]]);

  const cutoff = now - TRASH_TTL_DAYS * 86400000;
  const notes = await listNotes(env);
  const old = notes.filter((n) => n.deleted && (n.deletedAt || n.updatedAt) < cutoff);
  for (const n of old) await trashNote(env, n.id, true);
  return old.length;
}

/* -------------------------------------------------------- 分享（只读公开） */

const SHARE_ALPHABET = 'abcdefghijkmnpqrstuvwxyz23456789';

function shareSlug(len = 16) {
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  let out = '';
  for (let i = 0; i < len; i += 1) out += SHARE_ALPHABET[bytes[i] % SHARE_ALPHABET.length];
  return out;
}

async function getShareSlug(env, noteId) {
  const [slug] = await db(env, [['GET', `share:by-note:${noteId}`]]);
  return typeof slug === 'string' && slug ? slug : '';
}

async function createShare(env, noteId, origin) {
  const note = await getNote(env, noteId);
  if (!note) throw new HttpError(404, '笔记不存在');
  if (note.deleted) throw new HttpError(400, '回收站里的笔记不能分享，请先恢复');

  const existing = await getShareSlug(env, noteId);
  if (existing) return { slug: existing, existed: true, url: `${origin}/s/${existing}` };

  const slug = shareSlug();
  const now = Date.now();
  await db(env, [
    ['HSET', `share:${slug}`, 'noteId', noteId, 'createdAt', String(now)],
    ['SET', `share:by-note:${noteId}`, slug],
  ]);
  return { slug, existed: false, url: `${origin}/s/${slug}`, createdAt: now };
}

async function revokeShare(env, noteId) {
  const slug = await getShareSlug(env, noteId);
  if (!slug) return { ok: true, revoked: false };
  await db(env, [['DEL', `share:${slug}`], ['DEL', `share:by-note:${noteId}`]]);
  return { ok: true, revoked: true };
}

async function publicShare(env, slug) {
  const [flat] = await db(env, [['HGETALL', `share:${slug}`]]);
  if (!Array.isArray(flat) || flat.length === 0) throw new HttpError(404, '分享不存在或已取消');

  const map = {};
  for (let i = 0; i < flat.length; i += 2) map[flat[i]] = flat[i + 1];

  const note = await getNote(env, map.noteId);
  if (!note || note.deleted) throw new HttpError(404, '分享不存在或已取消');

  return {
    title: note.title || '无标题',
    body: note.body || '',
    tags: note.tags,
    createdAt: note.createdAt,
    updatedAt: note.updatedAt,
    sharedAt: Number(map.createdAt) || 0,
  };
}

/* ------------------------------------------------------------------ 路由 */

async function handleLogin(request, env) {
  if (!env.APP_PASSWORD || !env.AUTH_SECRET) throw new HttpError(500, '服务端未设置密码');

  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const key = `login:fail:${ip}`;

  let payload;
  try {
    payload = await request.json();
  } catch {
    throw new HttpError(400, '请求格式错误');
  }

  const [count] = await db(env, [['INCR', key], ['EXPIRE', key, '3600']]);
  if (Number(count) > MAX_LOGIN_FAILS) throw new HttpError(429, '尝试次数过多，请一小时后再试');

  if (!safeEqual(String(payload?.password ?? ''), env.APP_PASSWORD)) {
    throw new HttpError(401, '密码不正确');
  }

  await db(env, [['DEL', key]]);
  const device = payload?.device === true;
  return json({
    token: await issueToken(env, device ? 'device' : 'web'),
    expiresIn: device ? DEVICE_TOKEN_TTL : TOKEN_TTL,
    device,
  });
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method.toUpperCase();

  if (path === '/api/ping') return json({ ok: true, service: 'notes', time: Date.now() });

  if (path === '/api/login' && method === 'POST') return handleLogin(request, env);

  // 公开只读分享，不需要登录
  const sharePath = path.match(/^\/api\/share\/([a-z0-9]{6,40})$/);
  if (sharePath && method === 'GET') return json(await publicShare(env, sharePath[1]));

  const denied = await requireAuth(request, env);
  if (denied) return denied;

  if (path === '/api/session') return json({ ok: true });

  // 安卓壳把现有的短令牌换成长期免密令牌，不用重新输密码
  if (path === '/api/token/device' && method === 'POST') {
    return json({ token: await issueToken(env, 'device'), expiresIn: DEVICE_TOKEN_TTL, device: true });
  }

  if (path === '/api/notes' && method === 'GET') {
    const lite = url.searchParams.get('meta') === '1';
    const q = (url.searchParams.get('q') || '').slice(0, 80);
    const swept = await sweepTrash(env).catch(() => 0);
    const notes = await listNotes(env, { lite, q });
    return json({ notes, count: notes.length, swept, serverTime: Date.now() });
  }

  if (path === '/api/notes' && method === 'POST') {
    const note = await saveNote(env, await request.json());
    return json({ note }, 201);
  }

  if (path === '/api/import' && method === 'POST') {
    const result = await importNotes(env, await request.json());
    return json(result);
  }

  const single = path.match(/^\/api\/notes\/([A-Za-z0-9_-]{8,64})(\/(restore|share))?$/);
  if (single) {
    const id = single[1];
    const action = single[3] || '';

    if (action === 'restore' && method === 'POST') return json(await restoreNote(env, id));
    if (action === 'share' && method === 'POST') return json(await createShare(env, id, url.origin));
    if (action === 'share' && method === 'DELETE') return json(await revokeShare(env, id));

    if (!action && method === 'GET') {
      const note = await getNote(env, id);
      if (!note) throw new HttpError(404, '笔记不存在');
      return json({ note });
    }
    if (!action && method === 'PUT') {
      const body = await request.json();
      body.id = id;
      return json({ note: await saveNote(env, body) });
    }
    if (!action && method === 'DELETE') {
      return json(await trashNote(env, id, url.searchParams.get('purge') === '1'));
    }
  }

  if (path === '/api/export' && method === 'GET') {
    const notes = await listNotes(env);
    return new Response(JSON.stringify({ exportedAt: new Date().toISOString(), notes }, null, 2), {
      headers: {
        'content-type': 'application/json; charset=utf-8',
        'content-disposition': `attachment; filename="notes-${new Date().toISOString().slice(0, 10)}.json"`,
        'cache-control': 'no-store',
        ...corsHeaders,
      },
    });
  }

  throw new HttpError(404, '接口不存在');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname.startsWith('/api/')) {
      if (request.method.toUpperCase() === 'OPTIONS') {
        return new Response(null, { status: 204, headers: corsHeaders });
      }
      try {
        return await route(request, env);
      } catch (err) {
        if (err instanceof HttpError) return fail(err.status, err.message);
        console.error('unhandled', err);
        return fail(500, '服务器内部错误');
      }
    }

    // 公开分享页 /s/<slug> 复用静态 share.html，slug 由页面从路径读取
    if (/^\/s\/[a-z0-9]{6,40}/.test(url.pathname)) {
      const res = await env.ASSETS.fetch(new Request(new URL('/share.html', url.origin), { headers: request.headers }));
      const headers = new Headers(res.headers);
      headers.set('x-content-type-options', 'nosniff');
      headers.set('referrer-policy', 'no-referrer');
      headers.set('x-robots-tag', 'noindex, nofollow');
      return new Response(res.body, { status: res.status, headers });
    }

    // 静态资源交给 assets
    const res = await env.ASSETS.fetch(request);
    // 简单的安全响应头
    const headers = new Headers(res.headers);
    headers.set('x-content-type-options', 'nosniff');
    headers.set('referrer-policy', 'same-origin');
    return new Response(res.body, { status: res.status, headers });
  },
};
