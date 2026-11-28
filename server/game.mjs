// Room state machine. The server is authoritative for buzzing, scoring and timing; the TV renders and narrates.
import crypto from 'node:crypto';
import { judge } from './match.mjs';
import { pick as line, plain } from './lines.mjs';
import { coldOpen } from './coldopens.mjs';
import { HOSTS, hostById, voiceFor } from './hosts.mjs';

const VALUES = [200, 400, 600, 800, 1000];
const COLORS = ['#ffcf3f', '#4fc3ff', '#ff6fae', '#7dff8a', '#c08cff', '#ff8a3d', '#3dffe0', '#ff4f4f', '#b6ff3d', '#ffffff', '#ffa8e0', '#8ab4ff'];
const AVATARS = ['castle', 'star', 'crown', 'rocket', 'shell', 'apple', 'lamp', 'slipper', 'rose', 'wand', 'balloon', 'teacup'];
const CODE_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZ';
const SCALE = Number(process.env.TIME_SCALE || 1);
const HYPE = ['[excited]', '[playfully]', '[dramatically]'];
const hype = (t) => (!t || /^\s*\[/.test(t) ? t : `${HYPE[t.length % HYPE.length]} ${t}`);
const BUZZ_WINDOW = 250;
const T = { buzz: 9000, answer: 15000, answerTyped: 22000, ddWager: 25000, finalWager: 35000, finalThink: 35000, ruling: 20000, reveal: 6000, earlyLock: 300, draft: 30000 };

const shuffle = (a) => { a = [...a]; for (let i = a.length - 1; i > 0; i--) { const j = crypto.randomInt(i + 1); [a[i], a[j]] = [a[j], a[i]]; } return a; };
const now = () => Date.now();
const id = (n = 9) => crypto.randomBytes(n).toString('base64url');

export class Rooms {
  constructor({ content, store, voice, media }) {
    Object.assign(this, { content, store, voice, media });
    this.rooms = new Map();
  }
  newCode() {
    for (;;) {
      const c = Array.from({ length: 4 }, () => CODE_CHARS[crypto.randomInt(CODE_CHARS.length)]).join('');
      if (!this.rooms.has(c)) return c;
    }
  }
  forTv(tvId) {
    for (const r of this.rooms.values()) if (r.tvId === tvId) return r;
    const r = new Room(this, this.newCode(), tvId);
    this.rooms.set(r.code, r);
    return r;
  }
  get(code) { return this.rooms.get(String(code || '').toUpperCase()); }
  sweep() {
    for (const [c, r] of this.rooms) if (!r.tv && ![...r.players.values()].some((p) => p.ws) && now() - r.touched > 6 * 3600e3) { r.clearTimer(); this.rooms.delete(c); }
  }
}

export class Room {
  constructor(rooms, code, tvId) {
    this.rooms = rooms;
    this.code = code;
    this.tvId = tvId;
    this.tv = null;
    this.players = new Map();
    this.settings = { mode: 'mixed', moderator: null, negatives: true, buzzMs: T.buzz, voice: true, host: 'draft' };
    this.host = HOSTS[0];
    this.hostVotes = new Map();
    this.phase = 'lobby';
    this.touched = now();
    this.timer = null;
    this.deadline = 0;
    this.board = null;
    this.chooseBoard();
  }

  // ---------- content selection ----------
  chooseBoard(boardId) {
    const { content, store } = this.rooms;
    const hist = store.tv(this.tvId);
    if (boardId && content.byId.has(boardId)) this.board = content.byId.get(boardId);
    else {
      const pool = content.boards.filter((b) => b.id !== this.board?.id);
      const scored = pool.map((b) => ({ b, played: hist.boards[b.id] || 0, seen: b.categories.flatMap((c) => c.clues).filter((c) => hist.seen[c.id]).length }));
      scored.sort((x, y) => (x.played - y.played) || (x.seen - y.seen) || (Math.random() - 0.5));
      this.board = scored[0]?.b || content.boards[0] || null;
    }
    this.used = new Set();
    this.broadcast();
  }

  prefetchBoard() {
    const b = this.board;
    if (!b || !this.settings.voice) return;
    const v = this.rooms.voice;
    const who = voiceFor(this.host);
    const lines = [[b.intro, who]];
    for (const c of b.categories) {
      lines.push([c.intro, who]);
      for (const q of c.clues) lines.push([hype(q.clue), who], [q.reveal, who]);
    }
    v.prefetch(lines);
  }

  boardList() {
    const hist = this.rooms.store.tv(this.tvId);
    return this.rooms.content.boards.map((b) => ({ id: b.id, title: b.title, theme: b.theme, played: Boolean(hist.boards[b.id]), art: this.rooms.media.url(b.art?.file) }));
  }

  // ---------- connections ----------
  attachTv(ws) {
    if (this.tv && this.tv !== ws) try { this.tv.close(4000, 'replaced'); } catch {}
    this.tv = ws;
    this.touched = now();
    this.sendTo(ws, this.view('tv'));
  }
  detach(ws) {
    if (this.tv === ws) this.tv = null;
    for (const p of this.players.values()) if (p.ws === ws) { p.ws = null; p.lastSeen = now(); }
    this.broadcast();
  }

  join(ws, { pid, name, avatar }) {
    this.touched = now();
    let p = pid && this.players.get(pid);
    if (!p) {
      if (this.players.size >= 12) return this.sendTo(ws, { t: 'error', msg: 'Room is full (12 players).' });
      const used = new Set([...this.players.values()].map((x) => x.color));
      p = { id: id(), name: '', score: 0, color: COLORS.find((c) => !used.has(c)) || COLORS[0], avatar: AVATARS[this.players.size % AVATARS.length], alwaysMc: false, joined: now() };
      this.players.set(p.id, p);
      this.say(`${plain(name) || 'A new player'} has entered the game!`, false);
    }
    p.name = String(name || p.name || 'Player').replace(/\s+/g, ' ').trim().slice(0, 16) || 'Player';
    if (AVATARS.includes(avatar)) p.avatar = avatar;
    if (p.ws && p.ws !== ws) try { p.ws.close(4000, 'replaced'); } catch {}
    p.ws = ws;
    ws.pid = p.id;
    this.sendTo(ws, { t: 'joined', pid: p.id, code: this.code });
    this.broadcast();
  }

  captain() { return [...this.players.values()].sort((a, b) => a.joined - b.joined)[0]?.id || null; }

  // ---------- messages ----------
  onTv(m) {
    this.touched = now();
    switch (m.t) {
      case 'start': return this.start();
      case 'next': return this.advance();
      case 'pick': return this.pick(m.clue, null);
      case 'readDone': return this.readDone(m.seq);
      case 'board': return this.phase === 'lobby' && this.chooseBoard(m.id);
      case 'shuffle': return this.phase === 'lobby' && this.chooseBoard();
      case 'settings': return this.applySettings(m.settings);
      case 'kick': this.players.delete(m.pid); return this.broadcast();
      case 'newGame': return this.reset();
      case 'draftDone': return this.finishDraft();
      case 'pitch': { const h = HOSTS[m.i]; if (h && this.phase === 'hostDraft') this.say(h.pitch, false, `pitch:${m.i}`, voiceFor(h)); return this.broadcast(); }
      case 'resetHistory': this.rooms.store.reset(this.tvId); return this.chooseBoard();
      case 'override': return this.override(m.pid, m.correct);
    }
  }

  onPlayer(ws, m) {
    if (m.t === 'join') return this.join(ws, m);
    const p = this.players.get(ws.pid);
    if (!p) return this.sendTo(ws, { t: 'error', msg: 'Join first.' });
    this.touched = now();
    switch (m.t) {
      case 'buzz': return this.buzz(p, m);
      case 'answer': return this.answer(p, m);
      case 'pick': return this.pick(m.clue, p);
      case 'wager': return this.wager(p, m.amount);
      case 'rule': return this.rule(p, m.accept);
      case 'challenge': return this.challenge(p);
      case 'prefs': if (typeof m.alwaysMc === 'boolean') p.alwaysMc = m.alwaysMc; if (AVATARS.includes(m.avatar)) p.avatar = m.avatar; return this.broadcast();
      case 'start': return p.id === this.captain() && this.start();
      case 'next': return (p.id === this.captain() || p.id === this.settings.moderator) && this.advance();
      case 'settings': return p.id === this.captain() && this.applySettings(m.settings);
      case 'shuffle': return p.id === this.captain() && this.phase === 'lobby' && this.chooseBoard();
      case 'board': return p.id === this.captain() && this.phase === 'lobby' && this.chooseBoard(m.id);
      case 'voteHost': return this.voteHost(p, m.id);
      case 'moderate': this.settings.moderator = m.on ? p.id : (this.settings.moderator === p.id ? null : this.settings.moderator); return this.broadcast();
    }
  }

  applySettings(s = {}) {
    if (['mc', 'typed', 'mixed'].includes(s.mode)) this.settings.mode = s.mode;
    if (typeof s.negatives === 'boolean') this.settings.negatives = s.negatives;
    if (typeof s.voice === 'boolean') this.settings.voice = s.voice;
    if (s.host === 'draft' || HOSTS.some((h) => h.id === s.host)) this.settings.host = s.host;
    if ('moderator' in s) this.settings.moderator = this.players.has(s.moderator) ? s.moderator : null;
    if ([6000, 9000, 12000, 15000].includes(s.buzzMs)) this.settings.buzzMs = s.buzzMs;
    this.broadcast();
  }

  reset() {
    this.clearTimer();
    for (const p of this.players.values()) p.score = 0;
    this.phase = 'lobby';
    this.clue = null; this.final = null; this.dd = null; this.control = null;
    this.chooseBoard();
  }

  // ---------- flow ----------
  start() {
    if (this.phase !== 'lobby' || !this.board || !this.players.size) return;
    this.rooms.store.markBoard(this.tvId, this.board.id);
    this.used = new Set();
    this.control = shuffle([...this.players.keys()])[0];
    if (this.settings.host === 'draft') {
      this.phase = 'hostDraft';
      this.hostVotes = new Map();
      this.draftIdx = 0;
      this.setTimer(T.draft, () => this.finishDraft());
      return this.broadcast();
    }
    this.host = hostById(this.settings.host);
    this.coldOpen();
  }

  voteHost(p, hid) {
    if (this.phase !== 'hostDraft' || !HOSTS.some((h) => h.id === hid)) return;
    this.hostVotes.set(p.id, hid);
    if ([...this.players.values()].filter((x) => x.ws).every((x) => this.hostVotes.has(x.id))) return this.finishDraft();
    this.broadcast();
  }

  finishDraft() {
    if (this.phase !== 'hostDraft') return;
    this.clearTimer();
    const tally = new Map();
    for (const h of this.hostVotes.values()) tally.set(h, (tally.get(h) || 0) + 1);
    const top = Math.max(0, ...tally.values());
    const tied = top ? [...tally].filter(([, n]) => n === top).map(([h]) => h) : HOSTS.map((h) => h.id);
    this.host = hostById(shuffle(tied)[0]);
    this.coldOpen();
  }

  coldOpen() {
    this.phase = 'coldOpen';
    this.prefetchBoard();
    this.say(coldOpen({ host: this.host.name, players: [...this.players.values()].map((p) => p.name), theme: this.board.theme }), true, 'cold');
    this.broadcast();
  }

  advance() {
    if (this.phase === 'coldOpen') { this.phase = 'intro'; this.say(`${this.board.intro}`, true, 'intro'); return this.broadcast(); }
    if (this.phase === 'intro') { this.phase = 'categories'; this.catIdx = 0; this.say(this.board.categories[0].intro, true, 'cat:0'); return this.broadcast(); }
    if (this.phase === 'categories') {
      this.catIdx++;
      if (this.catIdx < 5) { this.say(this.board.categories[this.catIdx].intro, true, `cat:${this.catIdx}`); return this.broadcast(); }
      return this.toBoard();
    }
    if (this.phase === 'reveal') return this.afterReveal();
    if (this.phase === 'finalReveal') return this.finalStep();
    if (this.phase === 'podium') return this.reset();
  }

  toBoard() {
    this.clearTimer();
    this.clue = null; this.dd = null;
    if (this.used.size >= 25) return this.startFinal();
    this.phase = 'board';
    this.broadcast();
  }

  findClue(cid) {
    for (const c of this.board?.categories || []) for (const q of c.clues) if (q.id === cid) return { cat: c, q };
    return null;
  }

  pick(cid, p) {
    if (this.phase !== 'board') return;
    if (p && p.id !== this.control && this.players.get(this.control)?.ws) return this.sendTo(p.ws, { t: 'toast', msg: `${this.players.get(this.control).name} picks the next clue.` });
    const f = this.findClue(cid);
    if (!f || this.used.has(cid)) return;
    this.used.add(cid);
    this.rooms.store.markClue(this.tvId, cid);
    const choices = shuffle(f.q.choices);
    this.clue = { ...f, choices, attempted: new Set(), eliminated: new Set(), buzzOpen: false, lockouts: new Map(), seq: id(4), answerer: null, results: [] };
    if (f.q.dailyDouble && this.players.has(this.control)) {
      this.phase = 'ddWager';
      const cp = this.players.get(this.control);
      this.dd = { pid: cp.id, max: Math.max(cp.score, 1000), wager: null };
      this.say(line('dailyDouble'), true);
      this.setTimer(T.ddWager, () => this.wager(cp, Math.min(this.dd.max, Math.max(5, Math.floor(cp.score / 4) || 200))));
    } else {
      this.phase = 'reading';
      this.say(hype(f.q.clue), true, this.clue.seq);
      this.setTimer(this.readMs(f.q.clue), () => this.readDone(this.clue?.seq));
    }
    this.broadcast();
  }

  readMs(text) { return 2500 + plain(text).split(/\s+/).length * 420 + (this.clue?.q.media?.type === 'video' ? 4000 : 0); }

  wager(p, amount) {
    if (this.phase === 'ddWager' && this.dd?.pid === p.id && this.dd.wager == null) {
      this.dd.wager = Math.max(5, Math.min(this.dd.max, Math.round(Number(amount) || 0)));
      this.clearTimer();
      this.phase = 'reading';
      this.say(hype(this.clue.q.clue), true, this.clue.seq);
      this.setTimer(this.readMs(this.clue.q.clue), () => this.readDone(this.clue?.seq));
      return this.broadcast();
    }
    if (this.phase === 'finalWager' && this.final.entrants.has(p.id) && !this.final.wagers.has(p.id)) {
      this.final.wagers.set(p.id, Math.max(0, Math.min(Math.max(p.score, 0), Math.round(Number(amount) || 0))));
      if ([...this.final.entrants].every((x) => this.final.wagers.has(x))) this.finalClue();
      else this.broadcast();
    }
  }

  readDone(seq) {
    if ((this.phase === 'intro' && seq === 'intro') || (this.phase === 'categories' && seq === `cat:${this.catIdx}`)) return this.setTimer(900, () => this.advance());
    if (this.phase === 'reveal' && !this.ruling && this.clue && seq === `rev-${this.clue.seq}`) return this.setTimer(2500, () => this.afterReveal());
    if (this.phase !== 'reading' || !this.clue || (seq && seq !== this.clue.seq)) return;
    this.clearTimer();
    if (this.dd) return this.startAnswer(this.players.get(this.dd.pid));
    this.openBuzzers();
  }

  openBuzzers() {
    this.phase = 'buzzing';
    this.clue.buzzOpen = true;
    this.clue.openedAt = now();
    this.setTimer(this.settings.buzzMs, () => this.noBuzz());
    this.broadcast();
  }

  buzz(p, m) {
    const c = this.clue;
    if (!c || c.attempted.has(p.id)) return;
    if (this.phase === 'reading') { c.lockouts.set(p.id, now() + T.earlyLock); return this.sendTo(p.ws, { t: 'toast', msg: 'Too early! Wait for the lights.' }); }
    if (this.phase !== 'buzzing' || (c.lockouts.get(p.id) || 0) > now()) return;
    const elapsed = now() - c.openedAt;
    const rt = Number.isFinite(m?.rt) ? Math.max(0, Math.min(m.rt, elapsed + 50)) : elapsed;
    c.buzzes ||= new Map();
    if (!c.buzzes.has(p.id)) c.buzzes.set(p.id, rt);
    if (c.buzzWindow) return;
    c.buzzWindow = setTimeout(() => {
      c.buzzWindow = null;
      const entries = [...c.buzzes].filter(([id]) => this.players.has(id));
      c.buzzes = new Map();
      if (this.phase !== 'buzzing' || this.clue !== c) return;
      if (!entries.length) return this.noBuzz();
      const [id, best] = entries.sort((a, b) => a[1] - b[1])[0];
      this.clearTimer();
      c.buzzOpen = false;
      c.buzzMs = best;
      this.startAnswer(this.players.get(id));
    }, BUZZ_WINDOW);
  }

  answerMode(p) {
    if (p.alwaysMc || this.settings.mode === 'mc') return 'mc';
    if (this.settings.mode === 'mixed') return (this.clue?.q.value || 0) >= 600 ? 'mc' : 'typed';
    return 'typed';
  }

  startAnswer(p) {
    const c = this.clue;
    c.answerer = p.id;
    c.attempted.add(p.id);
    c.mode = this.answerMode(p);
    this.phase = 'answering';
    this.setTimer(c.mode === 'mc' ? T.answer : T.answerTyped, () => this.answer(p, { timeout: true }));
    this.broadcast();
  }

  answer(p, m) {
    const c = this.clue;
    if (this.phase === 'finalThink') return this.finalAnswer(p, m);
    if (this.phase !== 'answering' || !c || c.answerer !== p.id) return;
    this.clearTimer();
    const q = c.q;
    let verdict, heard = '';
    if (m.timeout) verdict = 'wrong';
    else if (c.mode === 'mc' || m.choice != null) {
      heard = String(m.choice ?? '');
      verdict = heard === q.answer ? 'correct' : 'wrong';
      if (verdict === 'wrong') c.eliminated.add(heard);
    } else {
      heard = String(m.text || '').slice(0, 120);
      ({ verdict } = judge(heard, q));
    }
    const amount = this.dd ? this.dd.wager : q.value;
    const res = { pid: p.id, heard, verdict, amount, mode: c.mode, spoken: Boolean(m.spoken) };
    c.results.push(res);
    if (verdict === 'correct') {
      p.score += amount;
      this.control = p.id;
      return this.reveal(null, p);
    }
    if (verdict === 'close') {
      // Unsure: show the answer first, then let a moderator (who is not the answerer) or the other players decide.
      return this.reveal(res);
    }
    if (this.settings.negatives) p.score -= amount;
    this.say(m.timeout ? `${p.name}, time's up!` : this.oops(p), false);
    if (this.dd || !this.canRebuzz()) return this.reveal();
    this.openBuzzers();
  }

  canRebuzz() { return [...this.players.values()].some((x) => x.ws && !this.clue.attempted.has(x.id)) && this.clue.eliminated.size < 3; }

  noBuzz() {
    if (this.phase !== 'buzzing' || this.clue?.buzzWindow) return;
    this.say(this.clue.attempted.size ? line('allWrong') : line('timeUp'), false);
    this.reveal();
  }

  reveal(pending = null, winner = null) {
    this.clearTimer();
    this.phase = 'reveal';
    const c = this.clue;
    c.revealedAt = now();
    const text = winner ? `${this.cheer(winner, c.q.answer)} ${String(c.q.reveal || '').replace(/^\s*(\[[^\]]*\]\s*)?the correct answer is[^!.?]*[!.?]\s*/i, '')}` : c.q.reveal;
    this.say(text, true, `rev-${c.seq}`);
    if (pending) this.openRuling(pending);
    else this.setTimer(4000 + plain(text).split(/\s+/).length * 450, () => this.afterReveal());
    this.broadcast();
  }

  // ---------- rulings (moderator / vote) ----------
  openRuling(res) {
    const mod = this.settings.moderator;
    const voters = mod && mod !== res.pid && this.players.get(mod)?.ws ? [mod] : [...this.players.values()].filter((x) => x.ws && x.id !== res.pid).map((x) => x.id);
    this.ruling = { res, voters: new Set(voters), votes: new Map(), by: voters.length === 1 && voters[0] === mod ? 'moderator' : 'vote' };
    if (!voters.length) return this.closeRuling(true);
    this.setTimer(T.ruling, () => this.closeRuling());
  }

  rule(p, accept) {
    const r = this.ruling;
    if (!r || !r.voters.has(p.id)) return;
    r.votes.set(p.id, Boolean(accept));
    if (r.by === 'moderator' || r.votes.size >= r.voters.size) this.closeRuling();
    else this.broadcast();
  }

  closeRuling(force) {
    const r = this.ruling;
    if (!r) return;
    this.clearTimer();
    const yes = [...r.votes.values()].filter(Boolean).length;
    const no = r.votes.size - yes;
    const accept = force ?? (r.votes.size ? yes >= no : r.res.verdict === 'close');
    const p = this.players.get(r.res.pid);
    if (p) {
      if (r.challenge) {
        if (accept) { p.score += r.res.amount * (this.settings.negatives ? 2 : 1); r.res.verdict = 'correct'; this.control = p.id; }
      } else if (accept) { p.score += r.res.amount; r.res.verdict = 'correct'; this.control = p.id; } else { if (this.settings.negatives) p.score -= r.res.amount; r.res.verdict = 'wrong'; }
      this.say(accept ? `The judges say... YES! Points to ${p.name}!` : `The judges say... no! So sorry, ${p.name}.`, false);
    }
    r.done = { accept, yes, no };
    this.lastRuling = r;
    this.ruling = null;
    if (r.final) return this.finalStep(true);
    this.setTimer(T.reveal, () => this.afterReveal());
    this.broadcast();
  }

  challenge(p) {
    if (this.phase !== 'reveal' || this.ruling || !this.clue) return;
    const res = [...this.clue.results].reverse().find((x) => x.pid === p.id && x.verdict === 'wrong' && x.mode === 'typed' && x.heard);
    if (!res || res.challenged) return;
    res.challenged = true;
    this.openRuling(res);
    if (this.ruling) this.ruling.challenge = true;
    this.broadcast();
  }

  override(pid, correct) {
    const p = this.players.get(pid);
    const res = this.clue?.results.findLast?.((x) => x.pid === pid);
    if (!p || !res || this.phase !== 'reveal') return;
    if (correct && res.verdict !== 'correct') { p.score += res.amount * (this.settings.negatives ? 2 : 1); res.verdict = 'correct'; }
    else if (!correct && res.verdict === 'correct') { p.score -= res.amount * (this.settings.negatives ? 2 : 1); res.verdict = 'wrong'; }
    this.broadcast();
  }

  afterReveal() {
    if (this.phase !== 'reveal' || this.ruling) return;
    if (this.used.size >= 25) { this.say(line('boardDone'), false); return this.startFinal(); }
    this.toBoard();
  }

  cheer(p, a) {
    const s = [`[excited] ¡Sí! ${a}! Way to go, ${p.name}!`, `[laughs] ${a}! Look at ${p.name} go!`, `[excited] ${a}! That is CORRECT, ${p.name}!`, `[gasps] Ohhh, ${a}! ${p.name}, you genius!`, `Ding ding ding! ${a}! ${p.name} gets it!`];
    return s[crypto.randomInt(s.length)];
  }
  oops(p) {
    const s = [`[sighs] Oh no, ${p.name}...`, `Ooh, not quite, ${p.name}!`, `[chuckles] Nice try, ${p.name}!`, `Nope! Sorry, ${p.name}!`];
    return s[crypto.randomInt(s.length)];
  }

  // ---------- final jeopardy ----------
  startFinal() {
    this.clearTimer();
    const hist = this.rooms.store.tv(this.tvId);
    const finals = this.rooms.content.finals;
    if (!finals.length) return this.podium();
    const fresh = finals.filter((f) => !hist.finals[f.film]);
    const pool = fresh.length >= 6 ? fresh : finals;
    const wheel = shuffle(pool).slice(0, Math.min(12, pool.length));
    const chosen = wheel[crypto.randomInt(wheel.length)];
    this.rooms.store.markFinal(this.tvId, chosen.film);
    const entrants = [...this.players.keys()];
    const broke = [...this.players.values()].filter((p) => p.score <= 0).map((p) => [p.id, 0]);
    this.final = { wheel: wheel.map((f) => f.film), chosen, entrants: new Set(entrants), wagers: new Map(broke), answers: new Map(), order: [], step: -1, choices: shuffle(chosen.choices) };
    this.phase = 'finalWheel';
    this.say(line('finalIntro'), true);
    this.rooms.voice.prefetch([[hype(chosen.clue), voiceFor(this.host)], [chosen.reveal, voiceFor(this.host)]]);
    this.setTimer(12000, () => this.finalWagerPhase());
    this.broadcast();
  }

  finalWagerPhase() {
    if (this.phase !== 'finalWheel') return;
    this.phase = 'finalWager';
    this.say(`The category is... ${this.final.chosen.film}! ${plain(line('finalWager'))}`, true);
    if ([...this.final.entrants].every((x) => this.final.wagers.has(x))) return this.finalClue();
    this.setTimer(T.finalWager, () => {
      for (const pid of this.final.entrants) if (!this.final.wagers.has(pid)) this.final.wagers.set(pid, 0);
      this.finalClue();
    });
    this.broadcast();
  }

  finalClue() {
    this.clearTimer();
    this.phase = 'finalClue';
    this.say(hype(this.final.chosen.clue), true, 'final');
    this.setTimer(this.readMs(this.final.chosen.clue), () => this.finalThink());
    this.broadcast();
  }

  finalThink() {
    if (this.phase !== 'finalClue') return;
    this.phase = 'finalThink';
    this.say(line('finalThink'), false);
    this.setTimer(T.finalThink, () => this.finalRevealPhase());
    this.broadcast();
  }

  finalAnswer(p, m) {
    if (!this.final.entrants.has(p.id) || this.final.answers.has(p.id)) return;
    const f = this.final.chosen;
    const mode = this.answerMode(p) === 'mc' || m.choice != null ? 'mc' : 'typed';
    const heard = String(m.choice ?? m.text ?? '').slice(0, 120);
    const verdict = mode === 'mc' ? (heard === f.answer ? 'correct' : 'wrong') : judge(heard, f).verdict;
    this.final.answers.set(p.id, { pid: p.id, heard, verdict, mode, amount: this.final.wagers.get(p.id) || 0 });
    if ([...this.final.entrants].every((x) => this.final.answers.has(x))) this.finalRevealPhase();
    else this.broadcast();
  }

  finalRevealPhase() {
    if (this.phase !== 'finalThink' && this.phase !== 'finalClue') return;
    this.clearTimer();
    for (const pid of this.final.entrants) if (!this.final.answers.has(pid)) this.final.answers.set(pid, { pid, heard: '', verdict: 'wrong', mode: 'typed', amount: this.final.wagers.get(pid) || 0 });
    this.final.order = [...this.final.entrants].sort((a, b) => (this.players.get(a)?.score || 0) - (this.players.get(b)?.score || 0));
    this.phase = 'finalReveal';
    this.say(line('finalReveal'), true);
    this.broadcast();
  }

  finalStep(fromRuling) {
    const F = this.final;
    if (!fromRuling) {
      if (this.ruling) return;
      F.step++;
      if (F.step >= F.order.length) {
        if (!F.answerShown) { F.answerShown = true; this.say(F.chosen.reveal, true, 'final-rev'); return this.broadcast(); }
        return this.podium();
      }
      const a = F.answers.get(F.order[F.step]);
      const p = this.players.get(a.pid);
      if (a.verdict === 'close') {
        this.openRuling(a);
        if (this.ruling) { this.ruling.final = true; F.current = a; return this.broadcast(); }
      }
      if (p) p.score += a.verdict === 'correct' ? a.amount : -a.amount;
      this.say(`${p?.name} wrote... ${a.heard || 'nothing'}! ${a.verdict === 'correct' ? 'CORRECT!' : 'Ohh, no.'} They wagered ${a.amount}.`, false);
    } else {
      // closeRuling already applied the wager for an accepted close answer; a rejected one still loses the wager (negatives or not).
      const a = F.current;
      const p = this.players.get(a.pid);
      if (a.verdict === 'wrong' && p && !this.settings.negatives) p.score -= a.amount;
    }
    this.broadcast();
  }

  podium() {
    this.clearTimer();
    this.phase = 'podium';
    this.say(line('podium'), true);
    this.broadcast();
  }

  // ---------- timers / output ----------
  setTimer(ms, fn) { this.clearTimer(); ms = Math.max(15, ms * SCALE); this.deadline = now() + ms; this.timer = setTimeout(() => { this.timer = null; this.deadline = 0; fn(); }, ms); }
  clearTimer() { clearTimeout(this.timer); this.timer = null; this.deadline = 0; }

  // Narration goes to the TV only. `wait` means the TV should report readDone when it finishes speaking.
  say(text, wait, tag, voiceId = voiceFor(this.host)) {
    if (!text || !this.tv) return;
    const v = this.rooms.voice;
    const msg = { t: 'say', text: plain(text), tag: tag || null };
    const send = (url) => this.sendTo(this.tv, { ...msg, url: this.settings.voice ? url : null });
    const ready = this.settings.voice ? v.cached(text, voiceId) : null;
    if (ready || !this.settings.voice || v.disabled) return send(ready);
    // Wait for a fresh clip; if ElevenLabs never delivers, the TV shows the line as a subtitle only.
    Promise.race([v.get(text, voiceId), new Promise((r) => setTimeout(() => r(null), wait ? 9000 : 5000))]).then(send, () => send(null));
  }

  publicClue() {
    const c = this.clue;
    if (!c) return null;
    const revealed = this.phase === 'reveal';
    return {
      id: c.q.id, value: c.q.value, category: c.cat.title, catId: c.cat.id, text: plain(c.q.clue), format: c.cat.format || c.q.format || 'text',
      media: c.q.media && !c.q.media.missing ? { type: c.q.media.type, url: this.rooms.media.url(c.q.media.file) } : null,
      palette: c.q.palette || null, quote: c.q.quote || null, emoji: c.q.emoji || null,
      dailyDouble: Boolean(this.dd), wager: this.dd?.wager ?? null, ddPlayer: this.dd?.pid || null,
      choices: c.choices, eliminated: [...c.eliminated], attempted: [...c.attempted], answerer: c.answerer, mode: c.mode || null,
      buzzOpen: c.buzzOpen, buzzMs: c.buzzMs ?? null,
      answer: revealed ? c.q.answer : null, reveal: revealed ? plain(c.q.reveal) : null,
      results: c.results.map((r) => ({ ...r })),
    };
  }

  view(role, pid) {
    const players = [...this.players.values()].map((p) => ({ id: p.id, name: p.name, score: p.score, color: p.color, avatar: p.avatar, online: Boolean(p.ws), alwaysMc: p.alwaysMc }));
    const b = this.board;
    const media = this.rooms.media;
    const s = {
      t: 'state', role, host: { id: this.host.id, name: this.host.name, img: this.host.img, anim: this.host.anim || null }, hosts: this.phase === 'hostDraft' || this.phase === 'lobby' ? HOSTS.map((h) => ({ id: h.id, name: h.name, img: h.img, votes: [...this.hostVotes.values()].filter((x) => x === h.id).length })) : null, myVote: undefined, code: this.code, phase: this.phase, settings: this.settings, captain: this.captain(), control: this.control || null,
      deadline: this.deadline ? this.deadline - now() : 0, players,
      board: b && {
        id: b.id, title: b.title, theme: b.theme, art: media.url(b.art?.file),
        categories: b.categories.map((c) => ({ id: c.id, title: c.title, art: media.url(c.art?.file), clues: VALUES.map((v) => { const q = c.clues.find((x) => x.value === v); return { id: q.id, value: v, used: this.used.has(q.id) }; }) })),
      },
      catIdx: this.phase === 'categories' ? this.catIdx : null,
      clue: this.publicClue(),
      ruling: this.ruling ? { pid: this.ruling.res.pid, heard: this.ruling.res.heard, by: this.ruling.by, voters: [...this.ruling.voters], votes: this.ruling.votes.size, challenge: Boolean(this.ruling.challenge) } : null,
      lastRuling: this.lastRuling?.done ? { pid: this.lastRuling.res.pid, ...this.lastRuling.done } : null,
      final: this.final && this.publicFinal(),
      stats: { boards: this.rooms.content.boards.length },
    };
    if (role === 'tv') s.boards = this.boardList();
    if (role === 'player') {
      const p = this.players.get(pid);
      s.you = pid;
      s.myVote = this.hostVotes.get(pid) || null;
      s.answerMode = p ? this.answerMode(p) : 'mc';
      if (this.phase === 'lobby' && pid === this.captain()) s.boards = this.boardList();
    }
    return s;
  }

  publicFinal() {
    const F = this.final;
    const showClue = ['finalClue', 'finalThink', 'finalReveal'].includes(this.phase);
    const revealed = this.phase === 'finalReveal' || this.phase === 'podium';
    const shown = F.order.slice(0, F.step + 1);
    return {
      wheel: F.wheel, film: this.phase === 'finalWheel' ? F.chosen.film : F.chosen.film, category: F.chosen.category,
      clue: showClue ? plain(F.chosen.clue) : null, choices: showClue ? F.choices : null,
      entrants: [...F.entrants], wagered: [...F.wagers.keys()], answered: [...F.answers.keys()],
      order: F.order, step: F.step, answerShown: Boolean(F.answerShown),
      answer: F.answerShown ? F.chosen.answer : null, reveal: F.answerShown ? plain(F.chosen.reveal) : null,
      results: revealed ? shown.map((pid) => F.answers.get(pid)) : [],
      wagers: revealed ? Object.fromEntries(shown.map((pid) => [pid, F.wagers.get(pid) || 0])) : {},
    };
  }

  sendTo(ws, msg) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(msg)); }

  broadcast() {
    clearTimeout(this.bcast);
    this.bcast = setTimeout(() => {
      if (this.tv) this.sendTo(this.tv, this.view('tv'));
      for (const p of this.players.values()) if (p.ws) this.sendTo(p.ws, this.view('player', p.id));
    }, 15);
  }
}
