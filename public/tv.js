import { $, $$, esc, money, avatarImg, bgFor, Socket, countdown, sfx, thinkMusic } from './common.js';

const app = $('#app');
const tvId = localStorage.djTv || (localStorage.djTv = crypto.randomUUID?.() || String(Math.random()).slice(2));
let pitchIdx = -1;
let S = null, prev = {}, overlay = null, joinBase = location.origin, zoomTimer = null;
const isNative = Boolean(window.DJ_TV || window.DJ_TV_NATIVE) || new URLSearchParams(location.search).has('app');

// ---------- background rotation ----------
let bgSlot = 0, bgUrl = '';
const themeBg = (b) => `/theme/bg-${bgFor(b)}.webp`;
const artOf = (b) => b?.art || themeBg(b);
function setBg(url) {
  if (url === bgUrl) return; bgUrl = url;
  const els = $$('.bgimg'); bgSlot ^= 1;
  els[bgSlot].style.backgroundImage = `url("${url}")`;
  els[bgSlot].classList.add('on'); els[bgSlot ^ 1].classList.remove('on');
}
setBg(themeBg(null));

// ---------- narration ----------
const audio = new Audio(); audio.preload = 'auto';
let speaking = null;
// Only Chet's real voice clips are played; when a clip is missing the line stays on screen as a subtitle.
function speakNative(text, done) {
  setTimeout(done, 1200 + String(text || '').split(/\s+/).length * 380);
}
// ---------- animated host ----------
// Every mood loop plays continuously (muted) so switching moods is an instant crossfade, not a reload.
const MOODS = ['idle', 'talk', 'celebrate', 'wrong'];
const cam = document.getElementById('hostcam');
let camHost = null, mood = 'idle', moodUntil = 0, moodTimer = null;
function hostMood(m, holdMs) {
  if (holdMs) {
    moodUntil = Date.now() + holdMs; clearTimeout(moodTimer);
    moodTimer = setTimeout(() => { moodUntil = 0; hostMood(speaking ? 'talk' : 'idle'); }, holdMs);
  } else if (Date.now() < moodUntil) return;
  mood = m;
  cam.querySelectorAll('video').forEach((v) => v.classList.toggle('on', v.dataset.mood === mood));
}
function updateCam() {
  const h = S?.host, slot = $('.camslot', app);
  const show = Boolean(h?.anim) && !['lobby', 'hostDraft'].includes(S.phase);
  cam.hidden = !show;
  document.body.classList.toggle('camside', show && !slot);
  if (!show) { cam.querySelectorAll('video').forEach((v) => v.pause()); return; }
  if (camHost !== h.anim) {
    camHost = h.anim;
    cam.querySelectorAll('video').forEach((v) => v.remove());
    for (const m of MOODS) {
      const v = document.createElement('video');
      Object.assign(v, { muted: true, loop: true, autoplay: true, playsInline: true, preload: 'auto', poster: h.img, src: `${h.anim}-${m}.mp4` });
      v.dataset.mood = m; cam.prepend(v);
    }
    cam.querySelector('.hcname').textContent = h.name;
    hostMood(mood);
  }
  cam.querySelectorAll('video').forEach((v) => { if (v.paused) v.play().catch(() => {}); });
  cam.classList.toggle('big', Boolean(slot));
  if (slot) {
    const r = slot.getBoundingClientRect();
    Object.assign(cam.style, { left: `${r.left + r.width / 2}px`, top: `${r.top}px`, height: `${r.height}px` });
  } else cam.removeAttribute('style');
}

function say(m) {
  let fired = false;
  let done = () => { if (fired) return; fired = true; if (m.tag === 'cold' && S?.phase === 'coldOpen') return sock.send({ t: 'next' }); if (m.tag?.startsWith('pitch:')) { pitchIdx = Number(m.tag.slice(6)) + 1; render(); if (S?.phase === 'hostDraft' && pitchIdx < 6) setTimeout(() => sock.send({ t: 'pitch', i: pitchIdx }), 400); return; } if (m.tag) sock.send({ t: 'readDone', seq: m.tag }); };
  speaking = m;
  const ownLine = !m.tag?.startsWith('pitch:');
  if (ownLine) hostMood('talk');
  const end = done;
  done = () => { if (!fired && ownLine && speaking === m) { speaking = null; hostMood('idle'); } end(); };
  if (m.url) {
    audio.src = m.url; audio.onended = done; audio.onerror = () => speakNative(m.text, done);
    audio.play().catch(() => speakNative(m.text, done));
  } else speakNative(m.text, done);
}

// ---------- connection ----------
const sock = new Socket({ role: 'tv', tv: tvId }, (m) => {
  if (m.t === 'state') { prev = S || {}; S = m; render(); }
  else if (m.t === 'say') say(m);
});
fetch('/api/info').then((r) => r.json()).then((i) => { joinBase = i.joinBase; }).catch(() => {});

// ---------- D-pad navigation ----------
// The TV re-renders on every state broadcast, so focus is tracked by a stable key per screen
// (data-key, else act/id) and restored after each render; if that element is gone, the
// spatially nearest one wins. While an overlay is open, only its elements are reachable.
let screen = '', lastScreen = '', lastHtml = null, moving = false;
const navMem = {};
const keyOf = (e) => e.dataset.key || [e.dataset.act, e.dataset.id, e.dataset.pid].filter(Boolean).join(':');
function focusables() {
  const root = $('.overlay', app) || document;
  return $$('[data-nav]', root).filter((e) => e.getClientRects().length && !e.disabled && !e.closest('[hidden]'));
}
const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
function nearest(els, rect) {
  const a = center(rect); let best = null, bd = Infinity;
  for (const e of els) { const b = center(e.getBoundingClientRect()); const d = Math.hypot(b.x - a.x, b.y - a.y); if (d < bd) { bd = d; best = e; } }
  return best;
}
const defaultEl = (els) => els.find((e) => e.hasAttribute('data-default')) || els[0];
function reveal(el) {
  const box = el.closest('.ovscroll'); if (!box) return;
  const r = el.getBoundingClientRect(), b = box.getBoundingClientRect(), pad = b.height * 0.08;
  if (r.top < b.top + pad) box.scrollTop -= b.top + pad - r.top;
  else if (r.bottom > b.bottom - pad) box.scrollTop += r.bottom - (b.bottom - pad);
}
function focusEl(el) { if (!el) return; el.focus({ preventScroll: true }); reveal(el); }
function restoreFocus() {
  const els = focusables(); if (!els.length) return;
  const cur = document.activeElement;
  const mem = navMem[screen];
  // Until the viewer moves, follow the screen's default (e.g. Start once the first player joins).
  if (screen === lastScreen && els.includes(cur) && (mem?.user || cur === defaultEl(els))) return reveal(cur);
  if (screen === lastScreen && !mem?.user) return focusEl(defaultEl(els));
  focusEl((mem?.user && (els.find((e) => keyOf(e) === mem.key) || nearest(els, mem.rect))) || defaultEl(els));
}
document.addEventListener('focusin', (e) => {
  const el = e.target.closest?.('[data-nav]'); if (!el || !screen) return;
  navMem[screen] = { key: keyOf(el), rect: el.getBoundingClientRect(), user: navMem[screen]?.user || moving };
  if (el.dataset.art) setBg(el.dataset.art);
});
// Row/column search: among elements in the arrow's direction, take the nearest line (edge gap
// within half the current element's size of the closest one), then the one best aligned with
// the current element, preferring those that overlap it across the direction of travel.
function moveFocus(dx, dy) {
  const els = focusables(); if (!els.length) return;
  const cur = els.includes(document.activeElement) ? document.activeElement : null;
  if (!cur) return restoreFocus();
  const a = cur.getBoundingClientRect(), ca = center(a);
  const cands = [];
  for (const e of els) {
    if (e === cur) continue;
    const b = e.getBoundingClientRect(), cb = center(b);
    if ((dx ? (cb.x - ca.x) * dx : (cb.y - ca.y) * dy) <= 2) continue;
    const gap = Math.max(0, dx > 0 ? b.left - a.right : dx < 0 ? a.left - b.right : dy > 0 ? b.top - a.bottom : a.top - b.bottom);
    const overlap = dx ? Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) : Math.min(a.right, b.right) - Math.max(a.left, b.left);
    cands.push({ e, gap, overlap, off: dx ? Math.abs(cb.y - ca.y) : Math.abs(cb.x - ca.x) });
  }
  // Left/right stay within the current row; up/down may change column.
  if (dx) cands.splice(0, cands.length, ...cands.filter((c) => c.overlap > 0));
  if (!cands.length) return;
  const minGap = Math.min(...cands.map((c) => c.gap)), tol = (dx ? a.width : a.height) / 2;
  const line = cands.filter((c) => c.gap <= minGap + tol);
  line.sort((p, q) => (q.overlap > 0) - (p.overlap > 0) || p.off - q.off);
  moving = true; focusEl(line[0].e); moving = false; sfx('tick');
}
const closeOverlay = () => { overlay = null; setBg(S?.board ? artOf(S.board) : themeBg(null)); render(); };
document.addEventListener('keydown', (e) => {
  const k = e.key;
  if (k === 'ArrowLeft') moveFocus(-1, 0); else if (k === 'ArrowRight') moveFocus(1, 0);
  else if (k === 'ArrowUp') moveFocus(0, -1); else if (k === 'ArrowDown') moveFocus(0, 1);
  else if (k === 'Escape' || k === 'Backspace' || k === 'GoBack') { if (overlay) closeOverlay(); else return; }
  else if (k === 'Enter' || k === ' ') {
    if (e.repeat) { e.preventDefault(); return; }
    const els = focusables(); const cur = els.includes(document.activeElement) ? document.activeElement : null;
    if (cur) cur.click();
    else if (els.length) { restoreFocus(); } else sock.send({ t: 'next' });
  } else return;
  e.preventDefault();
});
app.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]'); if (!el) return;
  const a = el.dataset.act;
  // Activating an element (OK, Tab+Enter, mouse) counts as the viewer choosing it, like an arrow move.
  if (navMem[screen] && keyOf(el) === navMem[screen].key) navMem[screen].user = true;
  sfx('pick');
  if (a === 'pick') sock.send({ t: 'pick', clue: el.dataset.id });
  else if (a === 'boards' || a === 'settings') { delete navMem[`${S.phase}:${a}`]; overlay = a; render(); }
  else if (a === 'close') closeOverlay();
  else if (a === 'board') { sock.send({ t: 'board', id: el.dataset.id }); overlay = null; render(); }
  else if (a === 'set') { const v = JSON.parse(el.dataset.v); sock.send({ t: 'settings', settings: v }); }
  else if (a === 'override') sock.send({ t: 'override', pid: el.dataset.pid, correct: el.dataset.c === '1' });
  else sock.send({ t: a });
});

// ---------- tap-to-start (browsers block audio until a gesture) ----------
if (!isNative) {
  const ts = $('#tapstart'); ts.hidden = false;
  const go = () => { if (ts.hidden) return; ts.hidden = true; sfx('join'); audio.play().catch(() => {}); };
  ts.addEventListener('click', go);
  // While the gate is up it owns the remote: OK dismisses it, arrows don't reach the lobby behind it.
  document.addEventListener('keydown', (e) => {
    if (ts.hidden) return;
    e.preventDefault(); e.stopImmediatePropagation();
    if (e.key === 'Enter' || e.key === ' ') go(); else ts.querySelector('button')?.focus();
  }, true);
}

// ---------- rendering ----------
const P = (id) => S.players.find((p) => p.id === id);
const pname = (id) => esc(P(id)?.name || '?');

// Gold logo-style headline: the element's own text is the blue outline, ::after paints the gold.
const okHint = () => (S.settings?.autoAdvance === false ? '<div class="okhint">Press OK to continue</div>' : '');
const BADGE_MARK = { correct: '<path class="mk" d="M11 21l6 6 12-13"/>', wrong: '<path class="mk" d="M14 14l12 12M26 14l-12 12"/>', close: '<text class="q" x="20" y="28" text-anchor="middle">?</text>' };
const badge = (v) => { const k = BADGE_MARK[v] ? v : 'wrong'; return `<svg class="vb vb-${k}" viewBox="0 0 40 40" aria-label="${k}"><circle class="rim" cx="20" cy="20" r="19"/><circle class="face" cx="20" cy="20" r="15.5"/><circle class="ring" cx="20" cy="20" r="13.2"/><path class="glare" d="M9 15a12 12 0 0 1 10-8"/>${BADGE_MARK[k]}</svg>`; };
const gt = (text, cls = '') => `<span class="goldtxt ${cls}" data-t="${esc(text)}">${esc(text)}</span>`;

function scores(hl) {
  if (!S.players.length) return '';
  return `<div class="scores">${S.players.map((p) => `<div data-pid="${p.id}" class="score ${lecternFx[p.id] > Date.now() ? lecternFx[p.id + ':c'] : ''} ${p.id === S.control ? 'ctrl' : ''} ${p.id === hl ? 'buzzed' : ''} ${p.online ? '' : 'off'}" style="--c:${p.color}">
    ${avatarImg(p.avatar)}<div class="sc"><div class="s ${p.score < 0 ? 'neg' : ''}">${money(p.score)}</div><div class="n">${esc(p.name)}</div></div></div>`).join('')}</div>`;
}

const nextHost = (cur) => { const ids = ['draft', ...(S.hosts || []).map((h) => h.id)]; return ids[(ids.indexOf(cur) + 1) % ids.length]; };

const MODES = { mc: 'Multiple choice', typed: 'Type / speak', mixed: 'Mixed' };
const hostLabel = (id) => (id === 'draft' ? 'Vote each game' : (S.hosts || []).find((h) => h.id === id)?.name || id);

function lobby() {
  const b = S.board; const set = S.settings;
  const host = joinBase.replace(/^https?:\/\//, '');
  const n = S.players.length; const sz = n > 8 ? 4.2 : n > 5 ? 5.2 : 6.4;
  const cast = S.players.map((p) => `<div class="pl ${p.online ? '' : 'off'}" style="--c:${p.color}">${avatarImg(p.avatar)}<div class="pn">${esc(p.name)}</div>
    <div class="tags">${p.id === S.captain ? '<span class="badge">CAPTAIN</span>' : ''}${p.id === set.moderator ? '<span class="badge">MOD</span>' : ''}${p.alwaysMc ? '<span class="badge">ABCD</span>' : ''}</div></div>`).join('')
    + Array.from({ length: Math.max(0, 4 - n) }, () => '<div class="pl empty"><div class="av">?</div><div class="pn">Join in!</div><div class="tags"></div></div>').join('');
  return `<div class="lobby">
    <div class="lb-main">
      <img class="brand" src="/theme/logo-cut.webp" alt="Disney Jeopardy">
      ${b ? `<div class="tonight">Tonight's episode</div><h1 class="eptitle">${gt(b.title)}</h1>
        <div class="cats">${b.categories.map((c) => `<div class="plaque">${esc(c.title)}</div>`).join('')}</div>` : '<h2>No boards loaded</h2>'}
      <div class="cast" style="--sz:${sz}vw">${cast}</div>
      <div class="actions">
        <button data-nav data-key="start" data-act="start" data-default ${n ? '' : 'disabled'}>▶ Start the show</button>
        <button data-nav data-key="boards" data-act="boards" class="secondary">Pick episode</button>
        <button data-nav data-key="shuffle" data-act="shuffle" class="secondary">Surprise me</button>
        <button data-nav data-key="settings" data-act="settings" class="secondary">Settings</button>
      </div>
      <div class="setline">${MODES[set.mode] || esc(set.mode)} answers · Wrong answers ${set.negatives ? 'lose $' : 'cost nothing'} · Host voice ${set.voice ? 'on' : 'off'} · Host: ${esc(hostLabel(set.host))}</div>
    </div>
    <aside class="join">
      <div class="jtitle">Grab your phone!</div>
      <img class="qr" src="/api/qr?code=${S.code}" alt="QR">
      <div class="url">${esc(host)}</div>
      <div class="jlabel">ROOM CODE</div><div class="code">${S.code}</div>
    </aside>
  </div>${overlay === 'boards' ? episodes() : overlay === 'settings' ? settingsPanel() : ''}`;
}

function episodes() {
  const cur = S.board?.id;
  return `<div class="overlay eps"><div class="ovhead"><h1 class="ovtitle">${gt("Pick tonight's episode")}</h1><div class="hint">OK to choose · BACK to close</div></div>
    <div class="ovscroll"><div class="epgrid">${(S.boards || []).map((x) => `<button data-nav data-key="board:${x.id}" data-act="board" data-id="${x.id}" data-art="${esc(artOf(x))}" class="ep ${x.played ? 'played' : ''}" ${x.id === cur ? 'data-default' : ''}>
      <img loading="lazy" src="${esc(artOf(x))}" alt=""><span class="et">${esc(x.title)}</span>${x.id === cur ? '<span class="tag">TONIGHT</span>' : x.played ? '<span class="tag pl">PLAYED</span>' : ''}</button>`).join('')}</div></div></div>`;
}

function settingsPanel() {
  const set = S.settings;
  const opt = (key, v, label, on) => `<button data-nav data-key="${key}" data-act="set" data-v='${JSON.stringify(v)}' class="opt ${on ? 'on' : 'secondary'}" ${on && key.startsWith('mode') ? 'data-default' : ''}>${esc(label)}</button>`;
  const row = (label, opts, note = '') => `<div class="setrow"><div class="lbl">${label}</div><div class="opts">${opts}${note ? `<div class="note">${note}</div>` : ''}</div></div>`;
  return `<div class="overlay settings"><div class="setpanel"><h1 class="ovtitle">${gt('Game settings')}</h1>
    ${row('Answers', Object.entries(MODES).map(([m, l]) => opt(`mode:${m}`, { mode: m }, l, set.mode === m)).join(''), 'Mixed = type or speak the 200s & 400s, multiple choice from 600 up.')}
    ${row('Wrong answers', opt('neg:1', { negatives: true }, 'Lose $', set.negatives) + opt('neg:0', { negatives: false }, 'No penalty', !set.negatives))}
    ${row('Host controls pacing', opt('pace:1', { autoAdvance: false }, 'On', set.autoAdvance === false) + opt('pace:0', { autoAdvance: true }, 'Off', set.autoAdvance !== false), 'On = the game waits for OK after each answer.')}
    ${row('Host voice', opt('voice:1', { voice: true }, 'On', set.voice) + opt('voice:0', { voice: false }, 'Off', !set.voice))}
    ${row('Host', ['draft', ...(S.hosts || []).map((h) => h.id)].map((id) => opt(`host:${id}`, { host: id }, hostLabel(id), set.host === id)).join(''))}
    <div class="note" style="margin-top:1vw">Players can turn on "always multiple choice" or become moderator on their phones.</div>
    <button data-nav data-key="close" data-act="close" style="margin-top:1.4vw">Done</button></div></div>`;
}

function hostDraft() {
  const speakingIdx = pitchIdx >= 0 && pitchIdx < 6 && speaking?.tag?.startsWith('pitch:') ? Number(speaking.tag.slice(6)) : -1;
  return `<div class="center"><h1 class="gold" style="font-size:3.6vw;margin:0">PICK TONIGHT'S HOST!</h1><div class="subtitle" style="margin:.4vw 0 1vw">Vote on your phones · ${[...(S.hosts || [])].reduce((a, h) => a + h.votes, 0)}/${S.players.filter((p) => p.online).length} votes</div>
    <div class="hostrow">${(S.hosts || []).map((h, i) => `<div class="hostcard ${i === speakingIdx ? 'talking' : ''}"><img src="${h.img}" alt=""><div class="hn">${esc(h.name)}</div><div class="hv">${'★'.repeat(h.votes)}</div></div>`).join('')}</div>
    <button data-nav data-act="draftDone" style="margin-top:1.2vw">Done voting ▶</button></div><div class="timer" id="timer" style="position:fixed"></div>`;
}

function coldOpenView() {
  const pic = S.host.anim ? '<div class="hostwrap camslot"></div>' : `<div class="hostwrap"><img class="hostimg" src="${S.host.img}" alt=""></div>`;
  return `<div class="center">${pic}
    <h1 class="gold" style="font-size:3.4vw;margin:1vw 0 0">${esc(S.host.name)}</h1><div class="subtitle" style="max-width:70vw">${esc(speaking?.text || '')}</div>
    <button data-nav data-act="next" style="margin-top:1.5vw">Skip ▶</button></div>`;
}

function intro() {
  const b = S.board;
  return `<div class="center"><div class="titlecard" style="background-image:url(${b.art || `/theme/bg-${bgFor(b)}.webp`})"><h1>${gt(b.title)}</h1></div>
    <div class="subtitle">Tonight's theme: <b>${esc(b.theme)}</b></div><button data-nav data-act="next" style="margin-top:1.5vw">Let's see the categories ▶</button></div>${scores()}`;
}

function categories() {
  const c = S.board.categories[S.catIdx];
  return `<div class="center"><div class="hint">CATEGORY ${S.catIdx + 1} OF 5</div>
    <div class="titlecard" style="background-image:url(${c.art || `/theme/bg-${bgFor(S.board)}.webp`})"><h1>${gt(c.title)}</h1></div>
    <button data-nav data-act="next" style="margin-top:1.5vw">${S.catIdx < 4 ? 'Next category ▶' : 'To the board! ▶'}</button></div>${scores()}`;
}

function board() {
  const b = S.board;
  const cats = b.categories.map((c) => `<div class="cat"><div class="cart" style="background-image:url(${c.art || ''})"></div><span>${esc(c.title)}</span></div>`).join('');
  let cells = '';
  for (let r = 0; r < 5; r++) for (const c of b.categories) {
    const q = c.clues[r];
    cells += q.used ? '<div class="cell used"></div>' : `<button class="cell" data-nav data-key="pick:${q.id}" data-act="pick" data-id="${q.id}"><span>$${q.value}</span></button>`;
  }
  const ctrl = P(S.control);
  return `<div class="wall"><div class="board">${cats}${cells}</div></div>
    <div class="hint pickhint">${ctrl ? `<b style="color:${ctrl.color}">${esc(ctrl.name)}</b> picks the next clue (on their phone or with the remote)` : 'Pick a clue'}</div>${scores()}`;
}

function clueMedia(c) {
  if (c.palette) return `<div class="palette">${c.palette.map((h, i) => `<div style="background:${esc(h)};animation-delay:${i * 0.15}s"></div>`).join('')}</div>`;
  if (c.emoji) return `<div class="emoji">${esc(c.emoji)}</div>`;
  if (c.quote) return `<div class="quote">“${esc(c.quote)}”</div>`;
  if (!c.media) return '';
  if (c.media.type === 'video') return `<div class="media"><video src="${c.media.url}" autoplay playsinline ${S.phase === 'reveal' ? 'controls' : ''}></video></div>`;
  if (c.format === 'zoom' && S.phase !== 'reveal') return `<div class="media zoombox"><img id="zoomimg" src="${c.media.url}" alt=""></div>`;
  return `<div class="media"><img src="${c.media.url}" alt=""></div>`;
}

function clue() {
  const c = S.clue; const ph = S.phase;
  const showChoices = c.mode === 'mc' || (ph === 'reveal' && c.results.some((r) => r.mode === 'mc'));
  const answerer = c.answerer && ph === 'answering' ? P(c.answerer) : null;
  let status = '';
  if (ph === 'reading') status = c.dailyDouble ? `<span>DAILY DOUBLE for ${pname(c.ddPlayer)} — wager ${money(c.wager)}</span>` : '<span>Get ready…</span>';
  else if (ph === 'buzzing') status = '<span class="lights">BUZZERS OPEN!</span>';
  else if (answerer) status = `<span style="color:${answerer.color}">${esc(answerer.name)}${c.buzzMs != null && !c.dailyDouble ? ` buzzed in (${(c.buzzMs / 1000).toFixed(2)}s)` : ''} — ${c.mode === 'mc' ? 'pick one!' : 'type or say it!'}</span>`;
  let reveal = '';
  if (ph === 'reveal') {
    reveal = `<div class="answer">${esc(c.answer)}</div><div class="reveal">${esc(c.reveal)}</div>
      <div class="results">${c.results.map((r) => `<div style="--c:${P(r.pid)?.color}">${pname(r.pid)}: “${esc(r.heard || '—')}” ${badge(r.verdict)} <button class="secondary" style="font-size:.9vw;padding:.2vw .6vw" data-nav data-act="override" data-pid="${r.pid}" data-c="${r.verdict === 'correct' ? 0 : 1}">${r.verdict === 'correct' ? 'undo' : 'accept'}</button></div>`).join('')}</div>
      ${S.ruling ? `<div class="ruling">${S.ruling.challenge ? 'Challenge! ' : ''}${pname(S.ruling.pid)} said “${esc(S.ruling.heard)}” — ${S.ruling.by === 'moderator' ? `waiting for moderator ${pname(S.ruling.voters[0])}` : `players are voting (${S.ruling.votes}/${S.ruling.voters.length})`}…</div>` : ''}
      ${S.lastRuling && !S.ruling && S.lastRuling.pid ? `<div class="ruling">Ruling: ${badge(S.lastRuling.accept ? 'correct' : 'wrong')} ${S.lastRuling.accept ? 'ACCEPTED' : 'REJECTED'}</div>` : ''}
      <button data-nav data-act="next" data-default style="margin-top:1.2vw">Continue ▶</button>${okHint()}`;
  }
  return `<div class="clue ${c.media || c.palette ? 'withmedia' : ''}">
    <div class="meta"><span>${esc(c.category)}</span><span>${c.dailyDouble ? `Daily Double · ${money(c.wager ?? 0)}` : money(c.value)}</span></div>
    ${clueMedia(c)}
    ${ph === 'reveal' ? '' : `<div class="text">${esc(c.text)}</div>`}
    ${showChoices && ph !== 'reveal' ? `<div class="choices">${c.choices.map((x) => `<div class="${c.eliminated.includes(x) ? 'out' : ''}">${esc(x)}</div>`).join('')}</div>` : ''}
    ${reveal}
    <div class="status">${status}</div><div class="timer" id="timer"></div>
  </div>${scores(answerer?.id)}`;
}

function ddWager() {
  return `<div class="dd"><h1 class="gold">DAILY DOUBLE!</h1><div class="subtitle" style="font-size:2.6vw;text-shadow:0 .2vw .5vw #000"><b>${pname(S.control)}</b> is making a wager…</div></div>`;
}

function wheel(F) {
  const n = F.wheel.length; const seg = 360 / n;
  const colors = ['#e53935', '#fb8c00', '#fdd835', '#43a047', '#1e88e5', '#8e24aa'];
  const grad = F.wheel.map((_, i) => `${colors[i % colors.length]} ${i * seg}deg ${(i + 1) * seg}deg`).join(',');
  const idx = F.wheel.indexOf(F.film);
  const target = 360 * 8 - (idx * seg + seg / 2);
  return `<div class="wheelwrap"><div class="pointer">▼</div><div class="wheel" id="wheel" data-target="${target}" style="background:conic-gradient(${grad})">
    ${F.wheel.map((f, i) => `<span style="transform:rotate(${i * seg + seg / 2 - 90}deg)">${esc(f)}</span>`).join('')}</div><div class="hub"></div></div>`;
}

function final() {
  const F = S.final; const ph = S.phase;
  if (ph === 'finalWheel') return `<div class="center"><h1 class="gold" style="font-size:4vw;margin:0 0 1vw">FINAL JEOPARDY</h1>${wheel(F)}</div>`;
  if (ph === 'finalWager') return `<div class="center"><h1 class="gold" style="font-size:3.4vw;margin:0">FINAL JEOPARDY</h1><div class="titlecard" style="width:50vw;display:flex;align-items:center;justify-content:center;background:radial-gradient(#1c48d8,#071865)"><h1 class="gold" style="background:none">${esc(F.film)}</h1></div>
    <div class="subtitle">Make your wagers on your phones! (${F.wagered.length}/${F.entrants.length})</div><div class="timer" id="timer" style="position:fixed"></div></div>${scores()}`;
  if (ph === 'finalClue' || ph === 'finalThink') return `<div class="clue"><div class="meta"><span>Final Jeopardy · ${esc(F.film)}</span><span>${F.answered.length}/${F.entrants.length} answered</span></div>
    <div class="text">${esc(F.clue)}</div><div class="status">${ph === 'finalThink' ? '<span class="lights">Answer on your phones!</span>' : ''}</div><div class="timer" id="timer"></div></div>${scores()}`;
  if (ph === 'finalReveal') {
    const cur = F.results[F.results.length - 1];
    return `<div class="clue"><div class="meta"><span>Final Jeopardy · ${esc(F.film)}</span><span></span></div>
      <div class="text" style="font-size:2vw;opacity:.85">${esc(F.clue)}</div>
      ${F.answerShown ? `<div class="answer">${esc(F.answer)}</div><div class="reveal">${esc(F.reveal || '')}</div>` : ''}
      <div class="results">${F.results.map((r) => `<div style="--c:${P(r.pid)?.color}">${pname(r.pid)}: “${esc(r.heard || '—')}” ${badge(r.verdict)} · wagered ${money(F.wagers[r.pid] || 0)}</div>`).join('')}</div>
      ${S.ruling ? `<div class="ruling">Close call! ${S.ruling.by === 'moderator' ? 'Moderator' : 'Players'} deciding…</div>` : ''}
      <button data-nav data-act="next" style="margin-top:1.2vw">${F.step + 1 < F.order.length ? 'Next player ▶' : F.answerShown ? 'Crown the champion! ★' : 'Reveal the answer ▶'}</button>${okHint()}
      </div>${scores(cur?.pid)}`;
  }
  return '';
}

function podium() {
  const ranked = [...S.players].sort((a, b) => b.score - a.score);
  const order = [ranked[1], ranked[0], ranked[2]].filter(Boolean);
  const rest = ranked.slice(3);
  return `<div class="finale"><h1 class="winner">${gt(`${ranked[0]?.name || ''} wins!`)}</h1>
    <div class="champsub">Tonight's champion · ${money(ranked[0]?.score || 0)}</div>
    <div class="podium">${order.map((p) => { const r = ranked.indexOf(p) + 1; return `<div class="p r${r}" style="--c:${p.color}"><div class="pa">${avatarImg(p.avatar, 'pav')}</div>
      <div class="pname">${esc(p.name)}</div><div class="pscore">${money(p.score)}</div><div class="stand"><span>${r}</span></div></div>`; }).join('')}</div>
    ${rest.length ? `<div class="rest">${rest.map((p, i) => `<div class="rp">${i + 4}. ${avatarImg(p.avatar)} ${esc(p.name)} · ${money(p.score)}</div>`).join('')}</div>` : ''}
    <button data-nav data-key="newGame" data-act="newGame" data-default>Play another episode ▶</button></div>`;
}

// Canvas confetti: corner cannons with gravity + drag, flipping paper, glitter, a few Mickey heads, firework shells.
const fx = (() => {
  const cv = document.createElement('canvas'); cv.id = 'fx'; document.body.appendChild(cv);
  const g = cv.getContext('2d'); const A = 16 / 9; let P = [], S2 = [], raf = 0, last = 0;
  const COL = ['#ffd34d', '#ffb300', '#4fc3ff', '#2457f5', '#ff6fae', '#e53935', '#7dff8a', '#c08cff', '#ffffff'];
  const SPK = ['#ffe27a', '#ff8ad8', '#7fd8ff', '#ffffff', '#ff6a3d'];
  const DRAG = [3, 1.7, 2.6];
  const rnd = Math.random;
  const add = (x, y, vx, vy, wait, k) => P.push({ x, y, vx, vy, t: -wait, k, rot: rnd() * 6.3, vr: (rnd() - .5) * 15, fl: rnd() * 6.3, vf: 5 + rnd() * 9, ph: rnd() * 6.3,
    s: k === 1 ? .007 + rnd() * .005 : k === 2 ? .02 + rnd() * .008 : .012 + rnd() * .009, c: k === 1 ? (rnd() < .7 ? 0 : 8) : k === 2 ? 0 : (rnd() * COL.length) | 0, l: rnd() < .3 ? .6 : rnd() < .6 ? 1 : 1.4 });
  const kind = () => { const r = rnd(); return r < .75 ? 0 : r < .96 ? 1 : 2; };
  function loop(now) {
    const dt = Math.min(.05, (now - last) / 1000 || .016); last = now;
    if (cv.width !== innerWidth || cv.height !== innerHeight) { cv.width = innerWidth; cv.height = innerHeight; }
    const W = cv.width, H = cv.height, u = W / A;
    g.clearRect(0, 0, W, H);
    for (const p of P) {
      p.t += dt; if (p.t < 0) continue;
      const d = DRAG[p.k]; p.vy += dt; p.vx -= p.vx * d * dt; p.vy -= p.vy * d * dt;
      const fall = Math.max(0, Math.min(1, p.vy / .3));
      p.x += p.vx * dt + Math.sin(p.ph + p.t * 2.6) * .16 * dt * fall; p.y += p.vy * dt; p.rot += p.vr * dt * (.4 + .6 * fall); p.fl += p.vf * dt;
      const s = p.s * H * p.l, f = Math.cos(p.fl);
      g.setTransform(f, 0, 0, 1, p.x * u, p.y * H); g.rotate(p.rot);
      g.globalAlpha = p.l < 1 ? .7 : 1; g.fillStyle = COL[p.c];
      if (p.k === 0) { g.fillRect(-s / 2, -s * .32, s, s * .64); g.fillStyle = `rgba(${f > 0 ? '255,255,255' : '0,0,0'},${.35 * (1 - Math.abs(f))})`; g.fillRect(-s / 2, -s * .32, s, s * .64); }
      else if (p.k === 1) { g.globalAlpha = .4 + .6 * Math.abs(Math.sin(p.t * 9 + p.ph)); g.fillRect(-s * .6, -s * .1, s * 1.2, s * .2); g.fillRect(-s * .1, -s * .6, s * .2, s * 1.2); }
      else { g.beginPath(); g.arc(0, 0, s * .4, 0, 6.3); g.arc(-s * .4, -s * .4, s * .26, 0, 6.3); g.arc(s * .4, -s * .4, s * .26, 0, 6.3); g.fill(); }
    }
    g.setTransform(1, 0, 0, 1, 0, 0); g.globalAlpha = 1; g.globalCompositeOperation = 'lighter'; g.lineCap = 'round';
    for (const q of S2) {
      q.t += dt; q.vy += .32 * dt; q.vx -= q.vx * 1.5 * dt; q.vy -= q.vy * 1.5 * dt; q.x += q.vx * dt; q.y += q.vy * dt;
      const a = (1 - q.t / q.m) ** 2; g.strokeStyle = q.c; g.globalAlpha = Math.max(0, a); g.lineWidth = H * .0032;
      g.beginPath(); g.moveTo(q.x * u, q.y * H); g.lineTo((q.x - q.vx * .09) * u, (q.y - q.vy * .09) * H); g.stroke();
    }
    g.globalCompositeOperation = 'source-over'; g.globalAlpha = 1;
    P = P.filter((p) => !(p.y > 1.08 && p.vy > 0) && p.t < 11); S2 = S2.filter((q) => q.t < q.m);
    raf = P.length || S2.length ? requestAnimationFrame(loop) : (g.clearRect(0, 0, W, H), 0);
  }
  const start = () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(loop); } };
  return {
    cannons(n, power = 1) {
      for (let i = 0; i < n; i++) { const left = i % 2 === 0, a = (60 + (rnd() - .5) * 22) * Math.PI / 180, sp = (2.4 + rnd() * 1.3) * power;
        add(left ? -.01 : A + .01, 1.01, Math.cos(a) * sp * (left ? 1 : -1), -Math.sin(a) * sp, rnd() * .35, kind()); }
      start();
    },
    firework(cx, cy) {
      const c = SPK[(rnd() * SPK.length) | 0];
      for (let i = 0; i < 80; i++) { const a = i * 6.283 / 80, sp = .5 + rnd() * .28; S2.push({ x: cx * A, y: cy, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, t: 0, m: 1.1 + rnd() * .7, c: rnd() < .8 ? c : '#fff' }); }
      start();
    },
  };
})();
let podiumGen = 0;
function podiumFx() {
  const gen = ++podiumGen; fx.cannons(260, 1.1);
  for (let i = 0; i < 12; i++) setTimeout(() => { if (gen === podiumGen && S.phase === 'podium') fx.firework(.15 + Math.random() * .7, .12 + Math.random() * .3); }, 350 + i * 650);
  setTimeout(() => { if (gen === podiumGen && S.phase === 'podium') fx.cannons(160); }, 3800);
}
const lecternFx = {};
function hitLectern(pid, cls, lost) {
  lecternFx[pid] = Date.now() + 900; lecternFx[pid + ':c'] = cls;
  const el = $(`.score[data-pid="${pid}"]`); if (el) { el.classList.remove(cls); void el.offsetWidth; el.classList.add(cls); }
  if (lost > 0 && el) { const r = el.getBoundingClientRect(); const d = document.createElement('div'); d.className = 'lostamt'; d.textContent = `–${money(lost)}`;
    d.style.left = `${r.left + r.width / 2}px`; d.style.top = `${r.top}px`; document.body.appendChild(d); setTimeout(() => d.remove(), 1500); }
}
function verdictBanner(r, answer, amount) {
  const p = P(r.pid); if (!p) return;
  $('.vbanner')?.remove();
  const b = document.createElement('div'); b.className = 'vbanner';
  b.innerHTML = `<div class="rays"></div><div class="ribbon">${avatarImg(p.avatar, 'bav')}${gt(p.name)}</div>
    ${answer ? `<div class="banswer">${gt(answer)}</div>` : ''}${S.clue?.buzzOrder?.[0]?.pid === r.pid ? `<div class="bfast">Fastest finger · ${(S.clue.buzzOrder[0].rt / 1000).toFixed(2)}s</div>` : ''}${amount > 0 ? `<div class="bchip">+${money(amount)}</div>` : ''}`;
  document.body.appendChild(b); setTimeout(() => b.remove(), 3200);
  setTimeout(() => hitLectern(r.pid, 'gain'), 1500);
}
function flash(kind) { const f = $('#flash'); f.className = ''; void f.offsetWidth; f.className = kind; }

// React once a verdict settles: a new result, or a 'close' one decided later by a ruling.
function hostReact() {
  const settled = (list = [], before = []) => list.find((r, i) => ['correct', 'wrong'].includes(r.verdict) && r.verdict !== before[i]?.verdict);
  const r = settled(S.clue?.results, prev.clue?.id === S.clue?.id ? prev.clue?.results : [])
    || settled(S.final?.results, prev.final?.results);
  if (!r) return;
  hostMood(r.verdict === 'correct' ? 'celebrate' : 'wrong', 4000);
  const inFinal = !S.clue?.results?.includes(r);
  // Show the real score change: overrides and penalty settings make it differ from the clue value.
  const delta = (P(r.pid)?.score ?? 0) - (prev.players?.find((x) => x.id === r.pid)?.score ?? 0);
  if (r.verdict === 'correct') verdictBanner(r, inFinal ? r.heard : S.clue?.answer || r.heard, Math.max(delta, 0));
  else {
    document.body.classList.remove('glitching'); void document.body.offsetWidth; document.body.classList.add('glitching');
    setTimeout(() => document.body.classList.remove('glitching'), 800);
    setTimeout(() => hitLectern(r.pid, 'shake', Math.max(-delta, 0)), 0);
  }
}
function effects() {
  const ph = S.phase, pp = prev.phase;
  if (S.players.length > (prev.players?.length || 0) && pp) sfx('join');
  if (ph !== pp) {
    if (ph === 'buzzing') sfx('open');
    if (ph === 'ddWager') sfx('dd');
    if (ph === 'finalThink') thinkMusic(34);
    if (ph === 'podium') { sfx('fanfare'); podiumFx(); hostMood('celebrate', 8000); }
    if (ph === 'hostDraft') { pitchIdx = 0; sock.send({ t: 'pitch', i: 0 }); }
    if (ph === 'finalWheel') setTimeout(() => { const w = $('#wheel'); if (w) w.style.transform = `rotate(${w.dataset.target}deg)`; }, 300);
  }
  if (ph === 'answering' && pp !== 'answering') sfx('buzz');
  const nr = S.clue?.results.length || 0, pr = prev.clue?.id === S.clue?.id ? prev.clue?.results.length || 0 : 0;
  if (nr > pr) { const last = S.clue.results[nr - 1]; if (last.verdict === 'correct') { sfx('correct'); flash('correct'); if (S.clue.dailyDouble) fx.cannons(160, .9); } else if (last.verdict === 'wrong') { sfx('wrong'); flash('wrong'); } }
  if (S.final?.results.length > (prev.final?.results.length || 0)) { const r = S.final.results.at(-1); sfx(r.verdict === 'correct' ? 'correct' : 'wrong'); }
  hostReact();
  // Zoom clues start tight on a random spot and slowly pull back.
  clearInterval(zoomTimer);
  const zi = $('#zoomimg');
  if (zi) {
    const ox = 20 + Math.random() * 60, oy = 20 + Math.random() * 60; let z = 5;
    zi.style.transformOrigin = `${ox}% ${oy}%`; zi.style.transform = `scale(${z})`;
    zoomTimer = setInterval(() => { z = Math.max(1, z - 0.25); zi.style.transform = `scale(${z})`; if (z <= 1) clearInterval(zoomTimer); }, 1000);
  }
}

function render() {
  if (!S) return;
  // Lets the TV app's Back button exit (press twice) whenever no overlay is open.
  window.DJ_TV_NATIVE?.setTopLevel?.(!overlay);
  if (S.phase === 'podium') setBg(themeBg(S.board));
  else if (overlay !== 'boards' && S.board) setBg(artOf(S.board));
  const keepZoom = $('#zoomimg') && S.phase !== 'reveal' && prev.clue?.id === S.clue?.id;
  const ph = S.phase;
  if (overlay && ph !== 'lobby') overlay = null;
  lastScreen = screen; screen = overlay ? `${ph}:${overlay}` : ph;
  const scroll = $('.ovscroll', app)?.scrollTop;
  const html = ph === 'lobby' ? lobby() : ph === 'hostDraft' ? hostDraft() : ph === 'coldOpen' ? coldOpenView() : ph === 'intro' ? intro() : ph === 'categories' ? categories() : ph === 'board' ? board()
    : ph === 'ddWager' ? ddWager() : ['reading', 'buzzing', 'answering', 'reveal'].includes(ph) ? clue()
      : ph.startsWith('final') ? final() : ph === 'podium' ? podium() : '';
  if (keepZoom) {
    // Don't restart the zoom animation on every state update: patch only the status, choices and scores.
    const tmp = document.createElement('div'); tmp.innerHTML = html;
    for (const sel of ['.status', '.choices', '.scores']) { const a = $(sel, app), b = $(sel, tmp); if (a && b) a.replaceWith(b); }
    lastHtml = null;
  } else {
    // Identical markup is left alone so focus, scroll and playing media are untouched.
    if (html !== lastHtml) { app.innerHTML = html; lastHtml = html; const box = $('.ovscroll', app); if (box && scroll != null && screen === lastScreen) box.scrollTop = scroll; }
    effects();
  }
  updateCam();
  countdown($('#timer'), S.deadline);
  restoreFocus();
  if (keepZoom) effectsLight();
}
function effectsLight() {
  const ph = S.phase, pp = prev.phase;
  if (ph === 'answering' && pp !== 'answering') sfx('buzz');
  if (ph === 'buzzing' && pp !== 'buzzing') sfx('open');
  const nr = S.clue?.results.length || 0, pr = prev.clue?.results.length || 0;
  if (nr > pr) { const last = S.clue.results[nr - 1]; if (last.verdict === 'wrong') { sfx('wrong'); flash('wrong'); } }
  hostReact();
}
