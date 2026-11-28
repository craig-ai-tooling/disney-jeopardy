// Shared client helpers for the TV and phone apps.
export const AVATARS = { castle: '🏰', star: '⭐', crown: '👑', rocket: '🚀', shell: '🐚', apple: '🍎', lamp: '🪔', slipper: '👠', rose: '🌹', wand: '🪄', balloon: '🎈', teacup: '🫖' };
export const $ = (s, el = document) => el.querySelector(s);
export const $$ = (s, el = document) => [...el.querySelectorAll(s)];
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
export const money = (n) => (n < 0 ? '-$' : '$') + Math.abs(n).toLocaleString();

// Theme background per board, picked from the board's theme/tags.
const BG = [
  ['beach', /beach|summer|surf|island|pool|sun|vacation|tiki|hawai/],
  ['villains', /villain|halloween|spooky|haunt|dark|evil|scary|monster/],
  ['winter', /winter|christmas|holiday|snow|frozen|ice|new year/],
  ['lagoon', /jungle|tropic|lagoon|animal|safari|wild|nature|adventure/],
  ['space', /space|star|galaxy|future|robot|sci|pixar|tomorrow/],
  ['ocean', /ocean|sea|under|mermaid|water|fish|pirate|sail/],
  ['autumn', /autumn|fall|thanksgiving|harvest|food|feast|cook|snack/],
  ['mainstreet', /main street|parade|retro|classic|vintage|music|broadway|park/],
];
export function bgFor(board) {
  if (!board) return 'castle';
  const s = `${board.theme} ${board.title} ${(board.tags || []).join(' ')}`.toLowerCase();
  return BG.find(([, re]) => re.test(s))?.[0] || 'castle';
}

export class Socket {
  constructor(params, onMsg) {
    this.params = params; this.onMsg = onMsg; this.queue = []; this.delay = 500;
    this.connect();
  }
  connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    this.ws = new WebSocket(`${proto}://${location.host}/ws?${new URLSearchParams(this.params)}`);
    this.ws.onopen = () => { this.delay = 500; this.onMsg({ t: 'open' }); for (const m of this.queue.splice(0)) this.send(m); };
    this.ws.onmessage = (e) => { try { this.onMsg(JSON.parse(e.data)); } catch (err) { console.error(err); } };
    this.ws.onclose = (e) => {
      this.onMsg({ t: 'closed', code: e.code });
      if (e.code === 4000) return;
      setTimeout(() => this.connect(), this.delay); this.delay = Math.min(this.delay * 2, 5000);
    };
  }
  send(m) { if (this.ws.readyState === 1) this.ws.send(JSON.stringify(m)); else this.queue.push(m); }
}

// Countdown bar driven by the server's remaining-ms value.
export function countdown(el, ms) {
  if (!el) return;
  el.style.transition = 'none'; el.style.transform = 'scaleX(1)';
  if (!ms) { el.style.transform = 'scaleX(0)'; return; }
  requestAnimationFrame(() => requestAnimationFrame(() => { el.style.transition = `transform ${ms}ms linear`; el.style.transform = 'scaleX(0)'; }));
}

// Tiny synthesized sound effects (no files needed).
let ctx;
export function sfx(kind) {
  try {
    ctx ??= new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') ctx.resume();
    const t = ctx.currentTime;
    const tone = (f, start, dur, type = 'sine', vol = 0.18, slide) => {
      const o = ctx.createOscillator(); const g = ctx.createGain();
      o.type = type; o.frequency.setValueAtTime(f, t + start);
      if (slide) o.frequency.exponentialRampToValueAtTime(slide, t + start + dur);
      g.gain.setValueAtTime(0.0001, t + start); g.gain.exponentialRampToValueAtTime(vol, t + start + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + start + dur);
      o.connect(g).connect(ctx.destination); o.start(t + start); o.stop(t + start + dur + 0.05);
    };
    const S = {
      buzz: () => { tone(880, 0, 0.12, 'square', 0.12); tone(1320, 0.1, 0.18, 'square', 0.1); },
      correct: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, i * 0.09, 0.35, 'triangle', 0.2)),
      wrong: () => { tone(220, 0, 0.35, 'sawtooth', 0.12, 150); tone(160, 0.25, 0.4, 'sawtooth', 0.12, 110); },
      open: () => tone(1200, 0, 0.08, 'sine', 0.12),
      pick: () => { tone(660, 0, 0.08, 'triangle', 0.15); tone(990, 0.06, 0.12, 'triangle', 0.15); },
      dd: () => [392, 523, 659, 784, 1047, 1319].forEach((f, i) => tone(f, i * 0.07, 0.5, 'triangle', 0.16)),
      timeup: () => { tone(440, 0, 0.25, 'square', 0.1); tone(330, 0.25, 0.4, 'square', 0.1); },
      tick: () => tone(1800, 0, 0.03, 'square', 0.05),
      join: () => { tone(784, 0, 0.1, 'sine', 0.15); tone(1175, 0.08, 0.2, 'sine', 0.15); },
      fanfare: () => [523, 523, 523, 659, 784, 659, 784, 1047].forEach((f, i) => tone(f, i * 0.13, 0.25, 'triangle', 0.18)),
    };
    S[kind]?.();
  } catch {}
}

// Think music for Final Jeopardy: a looping, original music-box melody.
export function thinkMusic(seconds = 30) {
  try {
    ctx ??= new (window.AudioContext || window.webkitAudioContext)();
    const notes = [659, 784, 880, 784, 659, 523, 587, 659, 587, 523, 440, 494, 523, 587, 659, 0];
    const step = 0.25; const t0 = ctx.currentTime + 0.05;
    const total = Math.floor(seconds / step);
    for (let i = 0; i < total; i++) {
      const f = notes[i % notes.length]; if (!f) continue;
      const o = ctx.createOscillator(); const g = ctx.createGain();
      o.type = 'triangle'; o.frequency.value = f * (Math.floor(i / 32) % 2 ? 1.0595 : 1);
      g.gain.setValueAtTime(0.0001, t0 + i * step); g.gain.exponentialRampToValueAtTime(0.08, t0 + i * step + 0.01); g.gain.exponentialRampToValueAtTime(0.0001, t0 + i * step + 0.22);
      o.connect(g).connect(ctx.destination); o.start(t0 + i * step); o.stop(t0 + i * step + 0.25);
    }
  } catch {}
}
