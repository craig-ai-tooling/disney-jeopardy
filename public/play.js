import { $, $$, esc, money, AVATARS, avatarImg, Socket, countdown, sfx } from './common.js';

const app = $('#app');
const code = (new URLSearchParams(location.search).get('code') || location.pathname.split('/').filter(Boolean).pop() || '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 4);
const key = `dj:${code}`;
const saved = JSON.parse(localStorage[key] || '{}');
let S = null, me = saved.pid || null, sock = null, draft = '', recording = null, lastPhase = '';
const prefName = localStorage.djName || '';
let avatar = localStorage.djAvatar || Object.keys(AVATARS)[Math.floor(Math.random() * 12)];

const toast = (msg) => { const t = $('#toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('on'), 2200); };
const vibrate = (p) => navigator.vibrate?.(p);
const send = (m) => sock?.send(m);
const P = (id) => S?.players.find((p) => p.id === id);
const mine = () => P(me);

function connect(name) {
  sock = new Socket({ role: 'player', room: code }, (m) => {
    if (m.t === 'open') send({ t: 'join', pid: me, name, avatar });
    else if (m.t === 'joined') { me = m.pid; localStorage[key] = JSON.stringify({ pid: me }); }
    else if (m.t === 'state') { const prevPhase = S?.phase; S = m; onPhase(prevPhase); render(); }
    else if (m.t === 'toast') toast(m.msg);
    else if (m.t === 'error') { toast(m.msg); if (/room|full/i.test(m.msg) && !S) renderJoin(m.msg); }
    else if (m.t === 'closed' && m.code === 4000) app.innerHTML = '<h2>You joined from another tab.</h2>';
  });
}

function renderJoin(err = '') {
  app.innerHTML = `<img class="logo" src="/theme/logo-cut.webp" alt="Disney Jeopardy">
    <div class="card"><div class="muted">Room</div>
      <input id="code" type="text" maxlength="4" value="${esc(code)}" autocapitalize="characters" placeholder="CODE">
      <input id="name" type="text" maxlength="16" placeholder="Your name" value="${esc(prefName)}" autocomplete="nickname">
      <div class="avatars">${Object.keys(AVATARS).map((k) => `<button data-av="${k}" class="${k === avatar ? 'sel' : ''}">${avatarImg(k)}</button>`).join('')}</div>
      <button id="go" style="margin-top:12px">Join the game!</button>
      ${err ? `<div class="muted" style="color:#ffb0b0">${esc(err)}</div>` : ''}</div>`;
  $$('[data-av]').forEach((b) => b.onclick = () => { avatar = b.dataset.av; $$('[data-av]').forEach((x) => x.classList.toggle('sel', x === b)); });
  $('#go').onclick = () => {
    const c = $('#code').value.toUpperCase().replace(/[^A-Z]/g, ''); const n = $('#name').value.trim();
    if (c.length !== 4) return toast('Enter the 4-letter code on the TV');
    if (!n) return toast('Pick a name!');
    localStorage.djName = n; localStorage.djAvatar = avatar;
    if (c !== code) { location.href = `/${c}`; return; }
    sfx('join'); connect(n); app.innerHTML = '<h2>Joining…</h2>';
  };
}

let buzzSeenAt = 0, answerAt = 0, lastResult = null;
const CHOICE_LOCK_MS = 1000;

function onPhase(prev) {
  const ph = S.phase; if (ph === prev) return;
  if (ph === 'buzzing') buzzSeenAt = performance.now();
  if (ph === 'buzzing' && !S.clue?.attempted.includes(me)) vibrate(60);
  if (ph === 'answering' && S.clue?.answerer === me) { vibrate([80, 40, 80]); draft = ''; answerAt = performance.now(); }
  if (ph === 'ddWager' && S.control === me) vibrate([100, 50, 100]);
  if (ph === 'reveal' || ph === 'buzzing') {
    const r = lastOf(S.clue?.results, (x) => x.pid === me);
    if (r && r !== lastResult && (ph === 'reveal' || r.verdict === 'wrong')) { lastResult = r; vibrate(r.verdict === 'correct' ? [40, 40, 40, 40, 220] : r.verdict === 'wrong' ? [350] : [60, 60, 60]); }
  }
  if (ph === 'finalThink' || ph === 'finalWager') draft = '';
}

const lastOf = (arr, fn) => { for (let i = (arr || []).length - 1; i >= 0; i--) if (fn(arr[i])) return arr[i]; };
const ORD = (n) => `${n}${['th', 'st', 'nd', 'rd'][(n % 100 > 10 && n % 100 < 14) || n % 10 > 3 ? 0 : n % 10]}`;
const secs = (ms) => `${(ms / 1000).toFixed(2)}s`;
const auto = () => S.settings.autoAdvance !== false;
// Rank and gap to the player just ahead (or lead over the runner-up).
function standing() {
  const p = mine(); if (!p || S.players.length < 2 || ['lobby', 'hostDraft'].includes(S.phase)) return '';
  const order = [...S.players].sort((a, b) => b.score - a.score);
  const tied = order.filter((x) => x.score === p.score).length > 1;
  const rank = order.findIndex((x) => x.score === p.score) + 1;
  const runnerUp = order.find((x) => x.score < p.score);
  const gap = rank === 1 ? (runnerUp ? `up ${money(p.score - runnerUp.score)}` : '') : `${money(order[rank - 2].score - p.score)} behind ${esc(order[rank - 2].name)}`;
  return `<div class="standing">${tied ? 'Tied ' : ''}${ORD(rank)}${gap ? ` · ${gap}` : ''}</div>`;
}
const top = () => { const p = mine(); return p ? `<div class="top"><div class="me" style="--c:${p.color}">${avatarImg(p.avatar)} ${esc(p.name)}</div><div class="score" data-score="${p.score}">${money(p.score)}</div></div>${standing()}` : ''; };
const tag = (v) => `<span class="tag ${v}">${{ correct: 'RIGHT', close: 'CLOSE', wrong: 'WRONG' }[v] || ''}</span>`;
const MIC = '<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="2" width="8" height="13" rx="4" fill="currentColor"/><path d="M5 11a7 7 0 0 0 14 0M12 18v4M8 22h8" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';

function answerBox(kind) {
  // kind: 'clue' | 'final'
  const c = kind === 'final' ? S.final : S.clue;
  const choices = c.choices || [];
  if (S.answerMode === 'mc' || (kind === 'clue' && c.mode === 'mc')) {
    return `<div class="choices">${choices.map((x) => `<button data-choice="${esc(x)}" class="secondary ${(c.eliminated || []).includes(x) ? 'out' : ''}" ${(c.eliminated || []).includes(x) ? 'disabled' : ''}>${esc(x)}</button>`).join('')}</div>`;
  }
  return `<input id="ans" type="text" maxlength="80" placeholder="Type your answer" value="${esc(draft)}" autocomplete="off" autocorrect="off" spellcheck="false">
    <button id="submit">Submit</button>
    <button id="mic" class="mic">${MIC} Hold to say it</button>
    <div class="muted" style="font-size:13px">Close spellings count. No need for "What is…"</div>`;
}

function bindAnswer() {
  // Choices stay locked briefly after winning the buzz so a leftover buzzer tap can't pick an answer.
  const wait = answerAt + CHOICE_LOCK_MS - performance.now();
  $$('[data-choice]').forEach((b) => {
    b.onclick = () => { sfx('pick'); send({ t: 'answer', choice: b.dataset.choice }); b.disabled = true; };
    if (wait > 0 && !b.disabled) { b.disabled = true; b.classList.add('arming'); setTimeout(() => { b.disabled = false; b.classList.remove('arming'); }, wait); }
  });
  const inp = $('#ans');
  if (inp) {
    inp.oninput = () => { draft = inp.value; };
    inp.onkeydown = (e) => { if (e.key === 'Enter') $('#submit').click(); };
    $('#submit').onclick = () => { if (!inp.value.trim()) return; send({ t: 'answer', text: inp.value.trim() }); app.innerHTML = `${top()}<h2>Locked in!</h2>`; };
    setTimeout(() => inp.focus(), 50);
  }
  const mic = $('#mic'); if (mic) bindMic(mic);
}

// Push-to-talk: record with MediaRecorder, send to the server's Whisper endpoint; fall back to browser speech recognition.
function bindMic(btn) {
  const start = async (e) => {
    e.preventDefault(); if (recording) return;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      const type = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm'].find((t) => window.MediaRecorder?.isTypeSupported?.(t)) || '';
      const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
      const chunks = []; rec.ondataavailable = (ev) => chunks.push(ev.data);
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
        btn.textContent = 'Thinking…';
        try {
          const r = await fetch(`/api/audio?room=${S.code}&pid=${me}`, { method: 'POST', headers: { 'content-type': blob.type }, body: blob });
          if (!r.ok) throw new Error(r.status);
          const { text } = await r.json(); toast(text ? `Heard: “${text}”` : "Didn't catch that");
        } catch { browserSpeech(); }
      };
      recording = rec; rec.start(); btn.classList.add('rec'); btn.innerHTML = '<span class="recdot"></span> Listening… let go to send'; vibrate(30);
      setTimeout(() => recording === rec && stop(), 7000);
    } catch { browserSpeech(); }
  };
  const stop = () => { if (!recording) return; const r = recording; recording = null; btn.classList.remove('rec'); try { r.stop(); } catch {} };
  btn.addEventListener('pointerdown', start); btn.addEventListener('pointerup', stop); btn.addEventListener('pointerleave', stop); btn.addEventListener('pointercancel', stop);
}
function browserSpeech() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return toast('Voice not available here — type it!');
  const r = new SR(); r.lang = 'en-US'; r.maxAlternatives = 3;
  r.onresult = (e) => { const t = e.results[0][0].transcript; toast(`Heard: “${t}”`); const inp = $('#ans'); if (inp) { inp.value = t; draft = t; } send({ t: 'answer', text: t }); };
  r.onerror = () => toast("Didn't catch that — type it!");
  r.start(); toast('Speak now…');
}

function wagerBox(max, label) {
  return `<div class="card"><div class="big">${label}</div><div class="muted">Wager up to ${money(max)}</div>
    <input id="wager" type="number" inputmode="numeric" min="0" max="${max}" value="${Math.min(max, Math.max(0, Math.round(max / 2 / 100) * 100))}">
    <div class="row"><button class="secondary" data-w="0.25">¼</button><button class="secondary" data-w="0.5">½</button><button class="secondary" data-w="1">ALL IN</button></div>
    <button id="lock">Lock it in</button></div>`;
}
function bindWager(max) {
  $$('[data-w]').forEach((b) => b.onclick = () => { $('#wager').value = Math.round(max * Number(b.dataset.w)); });
  $('#lock').onclick = () => { send({ t: 'wager', amount: Number($('#wager').value) }); sfx('pick'); };
}

function lobby() {
  const p = mine(); const captain = S.captain === me; const set = S.settings;
  return `${top()}<img class="logo" src="/theme/logo-cut.webp" alt="">
    <div class="card"><div class="muted">Tonight's episode</div><h2 class="gold" style="margin:4px 0">${esc(S.board?.title || '')}</h2>${S.board?.difficulty ? `<div class="muted">Difficulty: ${esc(S.board.difficulty)}</div>` : ''}
      <div class="muted">${S.players.length} player${S.players.length === 1 ? '' : 's'} in · room ${esc(S.code)}</div></div>
    <div class="card">
      <label class="toggle">Always multiple choice <input type="checkbox" id="mc" ${p?.alwaysMc ? 'checked' : ''}></label>
      <label class="toggle">I'll be the moderator (still playing) <input type="checkbox" id="mod" ${set.moderator === me ? 'checked' : ''}></label>
      <div class="avatars" style="margin-top:6px">${Object.keys(AVATARS).map((k) => `<button data-av="${k}" class="${k === p?.avatar ? 'sel' : ''}">${avatarImg(k)}</button>`).join('')}</div>
    </div>
    ${captain ? `<div class="card"><div class="muted">You're the captain</div>
      <button id="start">Start the show</button><button class="secondary" id="shuffle">Different episode</button>
      <div class="row">${['easy', 'medium', 'hard', 'superfan'].map((d) => `<button class="${S.board?.difficulty === d ? '' : 'secondary'}" data-diff="${d}" style="font-size:13px;padding:6px 4px">🎲 ${d}</button>`).join('')}</div>
      <label class="toggle">Host controls pacing <input type="checkbox" id="pace" ${set.autoAdvance === false ? 'checked' : ''}></label>
      <div class="row">${['mc', 'typed', 'mixed'].map((m) => `<button class="${set.mode === m ? '' : 'secondary'}" data-mode="${m}" style="font-size:15px">${{ mc: 'Multiple choice', typed: 'Type/speak', mixed: 'Mixed' }[m]}</button>`).join('')}</div></div>`
      : '<div class="muted">Waiting for the captain to start…</div>'}`;
}
function bindLobby() {
  $('#mc').onchange = (e) => send({ t: 'prefs', alwaysMc: e.target.checked });
  $('#mod').onchange = (e) => send({ t: 'moderate', on: e.target.checked });
  $$('[data-av]').forEach((b) => b.onclick = () => { localStorage.djAvatar = b.dataset.av; send({ t: 'prefs', avatar: b.dataset.av }); });
  $('#start') && ($('#start').onclick = () => send({ t: 'start' }));
  $('#shuffle') && ($('#shuffle').onclick = () => send({ t: 'shuffle' }));
  $('#pace') && ($('#pace').onchange = (e) => send({ t: 'settings', settings: { autoAdvance: !e.target.checked } }));
  $$('[data-diff]').forEach((b) => b.onclick = () => send({ t: 'shuffle', difficulty: b.dataset.diff }));
  $$('[data-mode]').forEach((b) => b.onclick = () => send({ t: 'settings', settings: { mode: b.dataset.mode } }));
}

function boardPick() {
  const b = S.board;
  const rows = [0, 1, 2, 3, 4].map((r) => b.categories.map((c) => { const q = c.clues[r]; return `<button data-pick="${q.id}" ${q.used ? 'disabled' : ''}>${q.used ? '' : q.value}</button>`; }).join('')).join('');
  return `${top()}<h2 class="gold">Your pick!</h2><div class="grid">${b.categories.map((c) => `<div class="h">${esc(c.title)}</div>`).join('')}${rows}</div>`;
}

function rulingBox() {
  const r = S.ruling; if (!r || !r.voters.includes(me) || r.pid === me) return '';
  const rv = S.phase === 'finalReveal' ? S.final : S.clue;
  return `<div class="card"><div class="muted">${r.by === 'moderator' ? 'Moderator call' : 'Vote!'}${r.challenge ? ' · challenge' : ''}</div>
    <div class="big">${esc(P(r.pid)?.name)} said “${esc(r.heard)}”</div>
    <div class="muted">Answer: <b>${esc(rv?.answer || '')}</b></div>
    <div class="row"><button id="acc">Accept</button><button class="secondary" id="rej">Reject</button></div></div>`;
}
function bindRuling() {
  $('#acc') && ($('#acc').onclick = () => { send({ t: 'rule', accept: true }); app.querySelector('.card:last-child').innerHTML = '<div class="big">Vote cast!</div>'; });
  $('#rej') && ($('#rej').onclick = () => { send({ t: 'rule', accept: false }); app.querySelector('.card:last-child').innerHTML = '<div class="big">Vote cast!</div>'; });
}

// Who buzzed in and how fast, from the server's arbitration order.
function buzzLine() {
  const o = S.clue?.buzzOrder || []; const i = o.findIndex((b) => b.pid === me);
  if (i === 0) return `<div class="buzzinfo">Fastest finger! ${o.length > 1 ? `${secs(o[1].rt - o[0].rt)} ahead of ${esc(P(o[1].pid)?.name)}` : `${secs(o[0].rt)} reaction`}</div>`;
  if (i > 0) return `<div class="buzzinfo">You buzzed ${ORD(i + 1)}, ${secs(o[i].rt - o[0].rt)} behind ${esc(P(o[0].pid)?.name)}</div>`;
  return S.clue?.attempted.includes(me) ? '' : '<div class="muted">Stay ready: if they miss, buzzers reopen.</div>';
}

function revealView() {
  const c = S.clue; const r = lastOf(c.results, (x) => x.pid === me);
  const win = c.results.find((x) => x.verdict === 'correct');
  const mood = r ? r.verdict === 'correct' ? 'win' : r.verdict === 'close' ? 'close' : 'lose' : '';
  document.body.classList.remove('win', 'lose', 'close', 'done'); if (mood) document.body.classList.add(mood);
  const head = r ? `<div class="hero ${mood}"><div class="burst"></div><div class="word">${{ win: 'CORRECT!', close: 'SO CLOSE…', lose: 'NOT QUITE' }[mood]}</div>
      <div class="delta">${r.verdict === 'close' ? 'Judges deciding' : r.verdict === 'wrong' && !S.settings.negatives ? 'No penalty' : `${r.verdict === 'correct' ? '+' : '−'}<span data-count="${r.amount}">$0</span>`}</div></div>`
    : win ? `<div class="who">${avatarImg(P(win.pid)?.avatar)}<h2>${esc(P(win.pid)?.name)} got it! +${money(win.amount)}</h2></div>` : '<h2>Nobody got it</h2>';
  const others = c.results.filter((x) => x !== r);
  return `${top()}${head}
    ${r && r.heard ? `<div class="said">You said “${esc(r.heard)}”</div>` : ''}
    <div class="muted">The answer</div><div class="answer">${esc(c.answer)}</div>
    ${others.length ? `<div class="card list">${others.map((x) => `<div class="li">${avatarImg(P(x.pid)?.avatar)}<span>${esc(P(x.pid)?.name)}: “${esc(x.heard || '—')}”</span>${tag(x.verdict)}</div>`).join('')}</div>` : ''}
    ${rulingBox()}${r && r.verdict !== 'correct' && !S.ruling && S.settings.moderator !== me ? '<button class="secondary" id="challenge">I was right! (challenge)</button>' : ''}
    ${(S.captain === me || S.settings.moderator === me) && !S.ruling && !auto() ? '<button id="next">Continue ▶</button>' : ''}`;
}

// Animate the reveal delta, and roll the header score from its last value.
let shownScore = null;
function countUp() {
  $$('[data-count]').forEach((el) => { const to = Number(el.dataset.count); const t0 = performance.now(); const f = (t) => { const k = Math.min(1, (t - t0) / 700); el.textContent = money(Math.round(to * (1 - (1 - k) ** 3))); if (k < 1) requestAnimationFrame(f); }; requestAnimationFrame(f); });
  const sc = $('.top .score'); if (!sc) return;
  const to = Number(sc.dataset.score); const from = shownScore ?? to; shownScore = to;
  if (from === to) return;
  sc.classList.add(to > from ? 'up' : 'down'); const t0 = performance.now();
  const f = (t) => { const k = Math.min(1, (t - t0) / 900); sc.textContent = money(Math.round(from + (to - from) * (1 - (1 - k) ** 3))); if (k < 1) requestAnimationFrame(f); else sc.classList.remove('up', 'down'); };
  requestAnimationFrame(f);
}

function render() {
  if (!S || !me) return;
  const ph = S.phase; const c = S.clue; const F = S.final;
  const focusInp = document.activeElement?.id === 'ans';
  if (focusInp && ph === lastPhase && (ph === 'answering' || ph === 'finalThink')) { countdown($('.timer'), S.deadline); return; }
  lastPhase = ph;
  let h = '';
  if (ph === 'lobby') h = lobby();
  else if (ph === 'hostDraft') h = `${top()}<h2 class="gold">Vote for tonight's host!</h2><div class="hostpick">${S.hosts.map((x) => `<button data-host="${x.id}" class="${S.myVote === x.id ? '' : 'secondary'}"><img src="${x.img}" alt=""><span>${esc(x.name)}</span></button>`).join('')}</div>`;
  else if (ph === 'coldOpen' || ph === 'intro' || ph === 'categories') h = `${top()}<img class="logo" src="/theme/logo.webp" alt=""><h2>${ph === 'categories' ? `Category ${S.catIdx + 1}: ${esc(S.board.categories[S.catIdx].title)}` : esc(S.board.title)}</h2><div class="muted">Eyes on the TV!</div>${S.captain === me ? '<button class="secondary" id="next">Skip ▶</button>' : ''}`;
  else if (ph === 'board') h = S.control === me ? boardPick() : `${top()}<h2>${esc(P(S.control)?.name)} is picking…</h2><div class="muted">Get your thumb ready.</div>`;
  else if (ph === 'ddWager') h = S.control === me ? `${top()}<h2 class="gold">DAILY DOUBLE!</h2>${wagerBox(Math.max(mine().score, 1000), 'How much do you wager?')}` : `${top()}<h2 class="gold">Daily Double!</h2><div class="muted">${esc(P(S.control)?.name)} is wagering…</div>`;
  else if (ph === 'reading' || ph === 'buzzing') {
    if (c.dailyDouble) h = `${top()}<h2>Daily Double</h2><div class="muted">${c.ddPlayer === me ? 'Listen up — it\'s all yours.' : `${esc(P(c.ddPlayer)?.name)} is answering.`}</div>`;
    else {
      const out = c.attempted.includes(me);
      const mineRes = lastOf(c.results, (x) => x.pid === me);
      const why = out ? (mineRes ? `You said “${esc(mineRes.heard || '…')}”. Others can still steal it.` : 'You already took your shot.') : ph === 'reading' ? 'Wait for the lights. Buzz early and you\'re locked out for a moment!' : 'Buzzers are open!';
      h = `${top()}<div class="clueinfo"><span>${esc(c.category)}</span><b>${money(c.value)}</b></div>${c.text && !c.media ? `<div class="cluetext">${esc(c.text)}</div>` : ''}<button class="buzzer ${ph === 'buzzing' && !out ? 'open' : 'wait'}" id="buzz">${out ? 'LOCKED OUT' : ph === 'buzzing' ? 'BUZZ!' : 'wait…'}</button><div class="muted">${why}</div>`;
    }
  } else if (ph === 'answering') {
    h = c.answerer === me ? `${top()}<div class="clueinfo"><span>${esc(c.category)}</span><b>${money(c.dailyDouble ? c.wager : c.value)}</b></div><h2 class="gold">You're up!</h2>${buzzLine()}<div class="timer"></div>${answerBox('clue')}`
      : `${top()}<div class="who">${avatarImg(P(c.answerer)?.avatar)}<h2>${esc(P(c.answerer)?.name)} is answering…</h2></div>${buzzLine()}`;
  } else if (ph === 'reveal') {
    h = revealView();
  } else if (ph === 'finalWheel') h = `${top()}<h2 class="gold">FINAL JEOPARDY</h2><div class="muted">Watch the wheel!</div>`;
  else if (ph === 'finalWager') h = F.entrants.includes(me) ? (F.wagered.includes(me) ? (mine().score <= 0 ? `${top()}<h2 class="gold">${esc(F.film)}</h2><h2>Playing for pride!</h2><div class="muted">No wager this time, but you still get to answer.</div>` : `${top()}<h2>Wager locked in</h2>`) : `${top()}<h2 class="gold">${esc(F.film)}</h2>${wagerBox(Math.max(mine().score, 0), 'Final wager')}`) : `${top()}<h2>Sitting this one out</h2><div class="muted">You need more than $0 to play Final Jeopardy.</div>`;
  else if (ph === 'finalClue' || ph === 'finalThink') h = F.entrants.includes(me) && !F.answered.includes(me) && ph === 'finalThink' ? `${top()}<h2 class="gold">${esc(F.film)}</h2><div class="timer"></div>${answerBox('final')}` : `${top()}<h2>${F.answered.includes(me) ? 'Answer locked in' : 'Read the clue on the TV…'}</h2>`;
  else if (ph === 'finalReveal') h = `${top()}<h2>Final Jeopardy reveal…</h2>${rulingBox()}${S.captain === me && !S.ruling && !auto() ? '<button id="next">Next ▶</button>' : ''}`;
  else if (ph === 'podium') {
    const order = [...S.players].sort((a, b) => b.score - a.score);
    const rank = order.findIndex((p) => p.score === mine().score) + 1;
    document.body.classList.add(rank === 1 ? 'win' : 'done');
    h = `${top()}<div class="medal r${Math.min(rank, 4)}"><span>${ORD(rank)}</span></div><h2 class="gold">${rank === 1 ? 'CHAMPION!' : `You finished ${ORD(rank)}!`}</h2>
      <div class="card list">${order.map((p, i) => `<div class="li ${p.id === me ? 'me' : ''}"><b>${i + 1}</b>${avatarImg(p.avatar)}<span>${esc(p.name)}</span><em>${money(p.score)}</em></div>`).join('')}</div>
      ${S.captain === me ? '<button id="again">Play another episode</button>' : ''}`;
  }
  if (ph !== 'reveal' && ph !== 'podium') document.body.classList.remove('win', 'lose', 'close', 'done');
  app.innerHTML = h;
  countdown($('.timer'), S.deadline);
  countUp();
  if (ph === 'lobby') bindLobby();
  $$('[data-pick]').forEach((b) => b.onclick = () => { sfx('pick'); send({ t: 'pick', clue: b.dataset.pick }); });
  $('#buzz') && $('#buzz').addEventListener('pointerdown', (e) => { e.preventDefault(); vibrate(40); send({ t: 'buzz', rt: buzzSeenAt ? Math.round(performance.now() - buzzSeenAt) : undefined }); });
  $$('[data-host]').forEach((b) => b.onclick = () => { sfx('pick'); vibrate(20); send({ t: 'voteHost', id: b.dataset.host }); });
  $('#next') && ($('#next').onclick = () => send({ t: 'next' }));
  $('#again') && ($('#again').onclick = () => send({ t: 'newGame' }));
  $('#challenge') && ($('#challenge').onclick = () => send({ t: 'challenge' }));
  if (ph === 'ddWager' && S.control === me) bindWager(Math.max(mine().score, 1000));
  if (ph === 'finalWager' && $('#lock')) bindWager(Math.max(mine().score, 0));
  if ($('#ans') || $('[data-choice]')) bindAnswer();
  bindRuling();
}

if (code.length === 4 && prefName && saved.pid) { connect(prefName); app.innerHTML = '<h2>Reconnecting…</h2>'; } else renderJoin();
