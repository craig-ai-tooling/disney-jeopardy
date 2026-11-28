import { $, $$, esc, money, AVATARS, bgFor, Socket, countdown, sfx, thinkMusic } from './common.js';

const app = $('#app');
const tvId = localStorage.djTv || (localStorage.djTv = crypto.randomUUID?.() || String(Math.random()).slice(2));
let pitchIdx = -1;
let S = null, prev = {}, overlay = null, joinBase = location.origin, zoomTimer = null;
const isNative = Boolean(window.DJ_TV || window.DJ_TV_NATIVE) || new URLSearchParams(location.search).has('app');

// ---------- background rotation ----------
let bgSlot = 0, bgName = '';
function setBg(name) {
  if (name === bgName) return; bgName = name;
  const els = $$('.bgimg'); bgSlot ^= 1;
  els[bgSlot].style.backgroundImage = `url(/theme/bg-${name}.webp)`;
  els[bgSlot].classList.add('on'); els[bgSlot ^ 1].classList.remove('on');
}
setBg('castle');

// ---------- narration ----------
const audio = new Audio(); audio.preload = 'auto';
let speaking = null;
function speakNative(text, done) {
  if (window.DJ_TV?.speak) { window.DJ_TV.speak(text, 1.05, 1.0); setTimeout(done, 900 + text.split(/\s+/).length * 380); return; }
  if ('speechSynthesis' in window) {
    const u = new SpeechSynthesisUtterance(text); u.rate = 1.05; u.pitch = 1.05;
    u.onend = done; u.onerror = done; speechSynthesis.cancel(); speechSynthesis.speak(u);
    setTimeout(done, 2500 + text.split(/\s+/).length * 500);
  } else setTimeout(done, 1500 + text.split(/\s+/).length * 350);
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
  let done = () => { if (fired) return; fired = true; if (m.tag === 'cold' && S?.phase === 'coldOpen') return sock.send({ t: 'next' }); if (m.tag?.startsWith('pitch:')) { pitchIdx = Number(m.tag.slice(6)) + 1; render(); if (S?.phase === 'hostDraft' && pitchIdx < 6) setTimeout(() => sock.send({ t: 'pitch', i: pitchIdx }), 400); return; } if (m.tag) sock.send({ t: 'readDone', seq: S?.clue?.id && m.tag !== 'intro' && m.tag !== 'cat' ? m.tag : undefined }); };
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
function focusables() { return $$('[data-nav]').filter((e) => e.offsetParent !== null && !e.disabled); }
function moveFocus(dx, dy) {
  const els = focusables(); if (!els.length) return;
  const cur = document.activeElement?.matches('[data-nav]') ? document.activeElement : null;
  if (!cur) return els[0].focus();
  const a = cur.getBoundingClientRect(); const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bd = Infinity;
  for (const e of els) {
    if (e === cur) continue;
    const b = e.getBoundingClientRect(); const bx = b.left + b.width / 2, by = b.top + b.height / 2;
    const ddx = bx - ax, ddy = by - ay;
    if ((dx && Math.sign(ddx) !== dx) || (dy && Math.sign(ddy) !== dy)) continue;
    if (dx && Math.abs(ddx) < 4) continue; if (dy && Math.abs(ddy) < 4) continue;
    const d = (dx ? Math.abs(ddx) : Math.abs(ddy)) + (dx ? Math.abs(ddy) : Math.abs(ddx)) * 2.5;
    if (d < bd) { bd = d; best = e; }
  }
  best?.focus();
}
document.addEventListener('keydown', (e) => {
  const k = e.key;
  if (k === 'ArrowLeft') moveFocus(-1, 0); else if (k === 'ArrowRight') moveFocus(1, 0);
  else if (k === 'ArrowUp') moveFocus(0, -1); else if (k === 'ArrowDown') moveFocus(0, 1);
  else if (k === 'Escape' || k === 'Backspace' || k === 'GoBack') { if (overlay) { overlay = null; render(); } else return; }
  else if ((k === 'Enter' || k === ' ') && !document.activeElement?.matches('[data-nav]')) { const f = focusables()[0]; if (f) f.click(); else sock.send({ t: 'next' }); }
  else return;
  e.preventDefault();
});
app.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]'); if (!el) return;
  const a = el.dataset.act;
  sfx('pick');
  if (a === 'pick') sock.send({ t: 'pick', clue: el.dataset.id });
  else if (a === 'boards') { overlay = 'boards'; render(); }
  else if (a === 'board') { overlay = null; sock.send({ t: 'board', id: el.dataset.id }); }
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

function scores(hl) {
  if (!S.players.length) return '';
  return `<div class="scores">${S.players.map((p) => `<div class="score ${p.id === S.control ? 'ctrl' : ''} ${p.id === hl ? 'buzzed' : ''} ${p.online ? '' : 'off'}" style="--c:${p.color}">
    <div class="n">${AVATARS[p.avatar] || ''} ${esc(p.name)}</div><div class="s ${p.score < 0 ? 'neg' : ''}">${money(p.score)}</div></div>`).join('')}</div>`;
}

const nextHost = (cur) => { const ids = ['draft', ...(S.hosts || []).map((h) => h.id)]; return ids[(ids.indexOf(cur) + 1) % ids.length]; };

function lobby() {
  const b = S.board; const set = S.settings;
  const modeBtn = (m, label) => `<button data-nav data-act="set" data-v='${JSON.stringify({ mode: m })}' class="${set.mode === m ? '' : 'secondary'}">${label}</button>`;
  const host = joinBase.replace(/^https?:\/\//, '');
  return `<div class="lobby">
    <div class="joinbox">
      <img class="qr" src="/api/qr?code=${S.code}" alt="QR">
      <div class="hint">Scan to join, or go to</div>
      <div class="url">${esc(host)}</div>
      <div class="code">${S.code}</div>
    </div>
    <div style="display:flex;flex-direction:column;min-height:0">
      <div class="row"><img class="logo" src="/theme/logo.webp" alt="Disney Jeopardy"><div class="grow"></div></div>
      ${b ? `<div class="episode"><div class="art" style="background-image:url(${b.art || `/theme/bg-${bgFor(b)}.webp`})"></div>
        <div class="info"><div class="hint">TONIGHT'S EPISODE</div><h2 class="gold">${esc(b.title)}</h2>
        <div class="cats">${b.categories.map((c) => `<span class="chip">${esc(c.title)}</span>`).join('')}</div></div></div>` : '<h2>No boards loaded</h2>'}
      <div class="players">${S.players.map((p) => `<div class="pchip ${p.online ? '' : 'off'}" style="--c:${p.color}"><span class="av">${AVATARS[p.avatar] || '⭐'}</span>${esc(p.name)}
        ${p.id === S.captain ? '<span class="badge">CAPTAIN</span>' : ''}${p.id === set.moderator ? '<span class="badge">MOD</span>' : ''}${p.alwaysMc ? '<span class="badge">ABCD</span>' : ''}</div>`).join('') || '<div class="hint">Waiting for players… scan the code!</div>'}</div>
      <div class="settings">
        <button data-nav data-act="start" ${S.players.length ? '' : 'disabled'}>▶ Start show</button>
        <button data-nav data-act="shuffle" class="secondary">🎲 Different episode</button>
        <button data-nav data-act="boards" class="secondary">📺 Pick episode</button>
      </div>
      <div class="settings" style="margin-top:1vw">
        ${modeBtn('mc', 'Multiple choice')}${modeBtn('typed', 'Type / speak')}${modeBtn('mixed', 'Mixed')}
        <button data-nav data-act="set" data-v='${JSON.stringify({ negatives: !set.negatives })}' class="secondary">Wrong answers: ${set.negatives ? 'lose $' : 'no penalty'}</button>
        <button data-nav data-act="set" data-v='${JSON.stringify({ voice: !set.voice })}' class="secondary">Host voice: ${set.voice ? 'on' : 'off'}</button>
        <button data-nav data-act="set" data-v='${JSON.stringify({ host: nextHost(set.host) })}' class="secondary">Host: ${set.host === 'draft' ? 'vote each game' : esc((S.hosts || []).find((h) => h.id === set.host)?.name || set.host)}</button>
      </div>
      <div class="hint" style="margin:1vw 0 1.5vw">Mixed = type or speak the 200s & 400s, multiple choice from 600 up. Any player can turn on "always multiple choice" or become moderator on their phone.</div>
    </div></div>
    ${overlay === 'boards' ? `<div class="boardlist">${(S.boards || []).map((x) => `<button data-nav data-act="board" data-id="${x.id}" class="${x.played ? 'played' : ''}" style="background-image:url(${x.art || `/theme/bg-${bgFor(x)}.webp`})">${esc(x.title)}${x.played ? ' ✓' : ''}</button>`).join('')}</div>` : ''}`;
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
  return `<div class="center"><div class="titlecard" style="background-image:url(${b.art || `/theme/bg-${bgFor(b)}.webp`})"><h1 class="gold">${esc(b.title)}</h1></div>
    <div class="subtitle">Tonight's theme: <b>${esc(b.theme)}</b></div><button data-nav data-act="next" style="margin-top:1.5vw">Let's see the categories ▶</button></div>${scores()}`;
}

function categories() {
  const c = S.board.categories[S.catIdx];
  return `<div class="center"><div class="hint">CATEGORY ${S.catIdx + 1} OF 5</div>
    <div class="titlecard" style="background-image:url(${c.art || `/theme/bg-${bgFor(S.board)}.webp`})"><h1 class="gold">${esc(c.title)}</h1></div>
    <button data-nav data-act="next" style="margin-top:1.5vw">${S.catIdx < 4 ? 'Next category ▶' : 'To the board! ▶'}</button></div>${scores()}`;
}

function board() {
  const b = S.board;
  const cats = b.categories.map((c) => `<div class="cat" style="background-image:url(${c.art || ''})"><span>${esc(c.title)}</span></div>`).join('');
  let cells = '';
  for (let r = 0; r < 5; r++) for (const c of b.categories) {
    const q = c.clues[r];
    cells += q.used ? '<div class="cell used"></div>' : `<div class="cell" data-nav tabindex="0" data-act="pick" data-id="${q.id}">${q.value}</div>`;
  }
  const ctrl = P(S.control);
  return `<div class="board">${cats}${cells}</div>
    <div class="hint" style="text-align:center">${ctrl ? `<b style="color:${ctrl.color}">${esc(ctrl.name)}</b> picks the next clue (on their phone or with the remote)` : 'Pick a clue'}</div>${scores()}`;
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
      <div class="results">${c.results.map((r) => `<div style="--c:${P(r.pid)?.color}">${pname(r.pid)}: “${esc(r.heard || '—')}” ${r.verdict === 'correct' ? '✅' : r.verdict === 'close' ? '🤔' : '❌'} <button class="secondary" style="font-size:.9vw;padding:.2vw .6vw" data-nav data-act="override" data-pid="${r.pid}" data-c="${r.verdict === 'correct' ? 0 : 1}">${r.verdict === 'correct' ? 'undo' : 'accept'}</button></div>`).join('')}</div>
      ${S.ruling ? `<div class="ruling">${S.ruling.challenge ? 'Challenge! ' : ''}${pname(S.ruling.pid)} said “${esc(S.ruling.heard)}” — ${S.ruling.by === 'moderator' ? `waiting for moderator ${pname(S.ruling.voters[0])}` : `players are voting (${S.ruling.votes}/${S.ruling.voters.length})`}…</div>` : ''}
      ${S.lastRuling && !S.ruling && S.lastRuling.pid ? `<div class="ruling">Ruling: ${S.lastRuling.accept ? 'ACCEPTED ✅' : 'REJECTED ❌'}</div>` : ''}
      <button data-nav data-act="next" style="margin-top:1.2vw">Continue ▶</button>`;
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
      <div class="results">${F.results.map((r) => `<div style="--c:${P(r.pid)?.color}">${pname(r.pid)}: “${esc(r.heard || '—')}” ${r.verdict === 'correct' ? '✅' : r.verdict === 'close' ? '🤔' : '❌'} · wagered ${money(F.wagers[r.pid] || 0)}</div>`).join('')}</div>
      ${S.ruling ? `<div class="ruling">Close call! ${S.ruling.by === 'moderator' ? 'Moderator' : 'Players'} deciding…</div>` : ''}
      <button data-nav data-act="next" style="margin-top:1.2vw">${F.step + 1 < F.order.length ? 'Next player ▶' : F.answerShown ? 'Crown the champion! 👑' : 'Reveal the answer ▶'}</button>
      </div>${scores(cur?.pid)}`;
  }
  return '';
}

function podium() {
  const ranked = [...S.players].sort((a, b) => b.score - a.score);
  const order = [ranked[1], ranked[0], ranked[2]].filter(Boolean);
  const h = { 0: '22vw', 1: '15vw', 2: '10vw' };
  return `<div class="center"><img class="logo" src="/theme/logo.webp" alt=""><h1 class="gold" style="font-size:4vw;margin:.5vw 0">${esc(ranked[0]?.name || '')} WINS!</h1>
    <div class="podium">${order.map((p) => { const r = ranked.indexOf(p); return `<div class="p"><div style="font-size:4vw">${AVATARS[p.avatar] || ''}</div><div style="font-weight:900;font-size:1.8vw;color:${p.color}">${esc(p.name)}</div><div class="gold" style="font-size:2.2vw">${money(p.score)}</div><div class="stand" style="height:${h[r]}">${r + 1}</div></div>`; }).join('')}</div>
    <div class="row" style="margin-top:2vw"><button data-nav data-act="newGame">Play another episode ▶</button></div></div>`;
}

function confetti(n = 120) {
  const colors = ['#ffd34d', '#4fc3ff', '#ff6fae', '#7dff8a', '#c08cff', '#fff'];
  for (let i = 0; i < n; i++) {
    const d = document.createElement('div'); d.className = 'confetti';
    d.style.left = `${Math.random() * 100}vw`; d.style.background = colors[i % colors.length];
    d.style.animationDuration = `${2.5 + Math.random() * 2.5}s`; d.style.animationDelay = `${Math.random() * 1.2}s`;
    document.body.appendChild(d); setTimeout(() => d.remove(), 6500);
  }
}
function flash(kind) { const f = $('#flash'); f.className = ''; void f.offsetWidth; f.className = kind; }

// React once a verdict settles: a new result, or a 'close' one decided later by a ruling.
function hostReact() {
  const settled = (list = [], before = []) => list.find((r, i) => ['correct', 'wrong'].includes(r.verdict) && r.verdict !== before[i]?.verdict);
  const r = settled(S.clue?.results, prev.clue?.id === S.clue?.id ? prev.clue?.results : [])
    || settled(S.final?.results, prev.final?.results);
  if (r) hostMood(r.verdict === 'correct' ? 'celebrate' : 'wrong', 4000);
}
function effects() {
  const ph = S.phase, pp = prev.phase;
  if (S.players.length > (prev.players?.length || 0) && pp) sfx('join');
  if (ph !== pp) {
    if (ph === 'buzzing') sfx('open');
    if (ph === 'ddWager') sfx('dd');
    if (ph === 'finalThink') thinkMusic(34);
    if (ph === 'podium') { sfx('fanfare'); confetti(200); hostMood('celebrate', 8000); }
    if (ph === 'hostDraft') { pitchIdx = 0; sock.send({ t: 'pitch', i: 0 }); }
    if (ph === 'finalWheel') setTimeout(() => { const w = $('#wheel'); if (w) w.style.transform = `rotate(${w.dataset.target}deg)`; }, 300);
  }
  if (ph === 'answering' && pp !== 'answering') sfx('buzz');
  const nr = S.clue?.results.length || 0, pr = prev.clue?.id === S.clue?.id ? prev.clue?.results.length || 0 : 0;
  if (nr > pr) { const last = S.clue.results[nr - 1]; if (last.verdict === 'correct') { sfx('correct'); flash('correct'); if (S.clue.dailyDouble) confetti(80); } else if (last.verdict === 'wrong') { sfx('wrong'); flash('wrong'); } }
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
  if (S.board) setBg(bgFor(S.board));
  const keepZoom = $('#zoomimg') && S.phase !== 'reveal' && prev.clue?.id === S.clue?.id;
  const focusedId = document.activeElement?.dataset?.id || document.activeElement?.dataset?.act;
  const ph = S.phase;
  const html = ph === 'lobby' ? lobby() : ph === 'hostDraft' ? hostDraft() : ph === 'coldOpen' ? coldOpenView() : ph === 'intro' ? intro() : ph === 'categories' ? categories() : ph === 'board' ? board()
    : ph === 'ddWager' ? ddWager() : ['reading', 'buzzing', 'answering', 'reveal'].includes(ph) ? clue()
      : ph.startsWith('final') ? final() : ph === 'podium' ? podium() : '';
  if (keepZoom) {
    // Don't restart the zoom animation on every state update: patch only the status, choices and scores.
    const tmp = document.createElement('div'); tmp.innerHTML = html;
    for (const sel of ['.status', '.choices', '.scores']) { const a = $(sel, app), b = $(sel, tmp); if (a && b) a.replaceWith(b); }
  } else { app.innerHTML = html; effects(); }
  updateCam();
  countdown($('#timer'), S.deadline);
  const els = focusables();
  const again = els.find((e) => (e.dataset.id || e.dataset.act) === focusedId);
  (again || els[0])?.focus({ preventScroll: true });
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
