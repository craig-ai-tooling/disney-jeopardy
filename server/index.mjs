// HTTP + WebSocket server: TV UI, phone UI, media, voice clips, APK, and the game rooms.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';
import QRCode from 'qrcode';
import { loadContent, ROOT } from './content.mjs';
import { Store } from './store.mjs';
import { Voice } from './tts.mjs';
import { transcribe, sttEnabled } from './stt.mjs';
import { judge } from './match.mjs';
import { allLines } from './lines.mjs';
import { Rooms } from './game.mjs';

const PORT = Number(process.env.PORT || 8080);
const DATA = path.resolve(process.env.DATA_DIR || path.join(ROOT, '.data'));
const PUBLIC_URL = (process.env.PUBLIC_URL || '').replace(/\/$/, '');
const PUB = path.join(ROOT, 'public');
const CONTENT = path.resolve(process.env.CONTENT_DIR || path.join(ROOT, 'content'));
fs.mkdirSync(DATA, { recursive: true });

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.mp4': 'video/mp4', '.webm': 'video/webm', '.woff2': 'font/woff2', '.apk': 'application/vnd.android.package-archive', '.ico': 'image/x-icon' };

// Opaque media URLs so file names can't hint at answers.
const media = {
  salt: process.env.MEDIA_SALT || crypto.randomBytes(8).toString('hex'),
  map: new Map(),
  url(file) {
    if (!file) return null;
    const abs = path.join(CONTENT, file);
    if (!fs.existsSync(abs)) return null;
    const id = crypto.createHash('sha1').update(this.salt + file).digest('hex').slice(0, 16) + path.extname(file);
    this.map.set(id, abs);
    return `/m/${id}`;
  },
};

let content = loadContent();
const store = new Store(DATA);
const voice = new Voice(DATA);
const rooms = new Rooms({ content, store, voice, media });
setInterval(() => rooms.sweep(), 600e3).unref();
voice.prefetch(allLines().filter((l) => !l.includes('${')).map((l) => [l, 'host']));
console.log(`content: ${content.boards.length} boards, ${content.finals.length} finals; voice ${voice.disabled ? 'off' : 'on'}; stt ${sttEnabled() ? 'on' : 'off'}`);

function sendFile(req, res, file, cache = 'public, max-age=300') {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return notFound(res);
    const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
    const range = req.headers.range && /bytes=(\d*)-(\d*)/.exec(req.headers.range);
    const headers = { 'content-type': type, 'cache-control': cache, 'accept-ranges': 'bytes' };
    if (range) {
      const start = range[1] ? Number(range[1]) : 0;
      const end = range[2] ? Math.min(Number(range[2]), st.size - 1) : st.size - 1;
      if (!(start <= end && start < st.size)) { res.writeHead(416, { 'content-range': `bytes */${st.size}` }); return res.end(); }
      res.writeHead(206, { ...headers, 'content-range': `bytes ${start}-${end}/${st.size}`, 'content-length': end - start + 1 });
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    res.writeHead(200, { ...headers, 'content-length': st.size });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).pipe(res);
  });
}
const notFound = (res) => { res.writeHead(404, { 'content-type': 'text/plain' }); res.end('not found'); };
const json = (res, code, body) => { res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(body)); };
const safeJoin = (base, rel) => { const p = path.normalize(path.join(base, rel)); return p.startsWith(base + path.sep) ? p : null; };
const origin = (req) => PUBLIC_URL || `${req.headers['x-forwarded-proto'] || 'http'}://${req.headers['x-forwarded-host'] || req.headers.host}`;

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > limit) { reject(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  try {
    if (p === '/healthz') return json(res, 200, { ok: true, boards: content.boards.length, rooms: rooms.rooms.size });
    if (p === '/' || p === '/tv' || p === '/tv/') return sendFile(req, res, path.join(PUB, 'tv.html'), 'no-cache');
    if (/^\/[A-Za-z]{4}$/.test(p) || p === '/play' || p === '/join') return sendFile(req, res, path.join(PUB, 'play.html'), 'no-cache');
    if (p === '/api/qr') {
      const code = String(url.searchParams.get('code') || '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 4);
      const svg = await QRCode.toString(`${origin(req)}/${code}`, { type: 'svg', margin: 1, errorCorrectionLevel: 'M', color: { dark: '#0b1a4a', light: '#ffffff' } });
      res.writeHead(200, { 'content-type': 'image/svg+xml', 'cache-control': 'no-store' });
      return res.end(svg);
    }
    if (p === '/api/info') return json(res, 200, { joinBase: origin(req), stt: sttEnabled(), voice: !voice.disabled, boards: content.boards.length, finals: content.finals.length });
    if (p === '/api/audio' && req.method === 'POST') {
      const room = rooms.get(url.searchParams.get('room'));
      const pid = url.searchParams.get('pid');
      const player = room?.players.get(pid);
      if (!player) return json(res, 403, { error: 'not in room' });
      if (!sttEnabled()) return json(res, 503, { error: 'stt disabled' });
      const audio = await readBody(req, 4 * 1024 * 1024);
      const q = room.phase === 'finalThink' ? room.final?.chosen : room.clue?.q;
      const hints = q ? [...new Set([q.answer, ...(q.accept || []), ...(q.choices || [])])] : [];
      const { text, ms } = await transcribe(audio, req.headers['content-type'] || 'audio/webm', hints);
      const verdict = q && text ? judge(text, q).verdict : 'wrong';
      console.log(`stt ${ms}ms room=${room.code} "${text}" -> ${verdict}`);
      room.onPlayer(player.ws || { pid }, { t: 'answer', text, spoken: true });
      return json(res, 200, { text, ms });
    }
    if (p.startsWith('/m/')) { const f = media.map.get(p.slice(3)); return f ? sendFile(req, res, f, 'public, max-age=86400') : notFound(res); }
    if (p.startsWith('/voice/')) { const f = safeJoin(voice.dir, p.slice(7)); return f ? sendFile(req, res, f, 'public, max-age=31536000, immutable') : notFound(res); }
    if (p === '/apk' || p === '/disney-jeopardy.apk') {
      const f = [path.join(DATA, 'disney-jeopardy.apk'), path.join(PUB, 'apk', 'disney-jeopardy.apk')].find((x) => fs.existsSync(x));
      if (!f) return notFound(res);
      res.setHeader('content-disposition', 'attachment; filename="disney-jeopardy.apk"');
      return sendFile(req, res, f, 'no-cache');
    }
    const f = safeJoin(PUB, p.slice(1));
    // App code must never go stale on the TV WebView; theme art can cache.
    if (f) return sendFile(req, res, f, /\.(js|css|html|json)$/.test(f) ? 'no-cache' : undefined);
    notFound(res);
  } catch (e) {
    console.error(`${p}: ${e.message}`);
    if (!res.headersSent) json(res, 500, { error: e.message });
  }
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  const role = url.searchParams.get('role');
  let room = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  if (role === 'tv') {
    const tvId = String(url.searchParams.get('tv') || '').slice(0, 64) || crypto.randomUUID();
    room = rooms.forTv(tvId);
    room.attachTv(ws);
  } else {
    room = rooms.get(url.searchParams.get('room'));
    if (!room) { ws.send(JSON.stringify({ t: 'error', code: 'noroom', msg: 'That room code does not exist. Check the TV!' })); return ws.close(); }
  }
  ws.on('message', (data) => {
    let m; try { m = JSON.parse(data); } catch { return; }
    if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
    if (m.t === 'ping') return ws.send(JSON.stringify({ t: 'pong', at: m.at, now: Date.now() }));
    try { role === 'tv' ? room.onTv(m) : room.onPlayer(ws, m); } catch (e) { console.error(`ws ${m.t}: ${e.stack}`); }
  });
  ws.on('close', () => room.detach(ws));
});
setInterval(() => { for (const ws of wss.clients) { if (!ws.isAlive) { ws.terminate(); continue; } ws.isAlive = false; ws.ping(); } }, 20000).unref();

process.on('uncaughtException', (e) => console.error(`uncaught: ${e.stack}`));
process.on('SIGHUP', () => { content = loadContent(); rooms.content = content; console.log('content reloaded'); });
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => { store.save(); setTimeout(() => process.exit(0), 600); });
server.listen(PORT, () => console.log(`disney-jeopardy on :${PORT}`));
