import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const PORT = 18000 + Math.floor(Math.random() * 1000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function client(params, onMsg) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?${new URLSearchParams(params)}`);
  const send = (m) => ws.send(JSON.stringify(m));
  ws.on('message', (d) => onMsg(JSON.parse(d), send));
  return new Promise((r) => ws.on('open', () => r(send)));
}

test('full game: lobby -> clues -> final -> podium', { timeout: 60000 }, async () => {
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'dj-'));
  const srv = spawn(process.execPath, ['server/index.mjs'], { env: { ...process.env, PORT, DATA_DIR: data, TIME_SCALE: '0.01', ELEVENLABS_API_KEY: '', STT_URL: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = ''; srv.stdout.on('data', (d) => { log += d; }); srv.stderr.on('data', (d) => { log += d; });
  try {
    for (let i = 0; i < 50 && !/on :/.test(log); i++) await sleep(100);
    let tv = null; const phases = new Set(); const firstAnswerers = new Set(); let finalEntrants = 0; let tvSend;
    tvSend = await client({ role: 'tv', tv: 'test-tv' }, (m, send) => {
      if (m.t === 'state') { tv = m; phases.add(m.phase); if (m.phase === 'finalThink') finalEntrants = m.final.entrants.length; }
      if (m.t === 'say' && m.tag === 'cold') { phases.add('coldOpen'); setTimeout(() => send({ t: 'next' }), 20); }
      else if (m.t === 'say' && m.tag != null && !String(m.tag).startsWith('pitch:')) setTimeout(() => send({ t: 'readDone', seq: m.tag }), 20);
    });
    for (let i = 0; i < 30 && !tv; i++) await sleep(50);
    assert.match(tv.code, /^[A-Z]{4}$/);
    const states = {}; const sends = {};
    for (const name of ['Ana', 'Ben']) {
      sends[name] = await client({ role: 'player', room: tv.code }, (m, send) => {
        if (m.t === 'state') {
          states[name] = m; const me = m.you; const c = m.clue;
          if (m.phase === 'answering' && c.attempted.length === 1 && !c.dailyDouble) firstAnswerers.add(c.answerer);
          if (m.phase === 'hostDraft' && !m.myVote) send({ t: 'voteHost', id: name === 'Ana' ? 'chet' : 'gourd' });
          if (m.phase === 'board' && m.control === me) send({ t: 'pick', clue: m.board.categories.flatMap((x) => x.clues).find((q) => !q.used).id });
          if (m.phase === 'ddWager' && m.control === me) send({ t: 'wager', amount: 500 });
          // Ben's buzz reaches the server first, but Ana reacted faster on her own phone, so she wins.
          if (m.phase === 'buzzing' && name === 'Ben' && !c.attempted.length) send({ t: 'buzz', rt: 900 });
          if (m.phase === 'buzzing' && name === 'Ana' && !c.attempted.includes(me)) setTimeout(() => send({ t: 'buzz', rt: 0 }), 60);
          if (m.phase === 'answering' && c.answerer === me) send(c.mode === 'mc' || m.answerMode === 'mc' ? { t: 'answer', choice: c.choices.find((x) => !c.eliminated.includes(x)) } : { t: 'answer', text: 'mickey' });
          if (m.phase === 'finalWager' && m.final.entrants.includes(me) && !m.final.wagered.includes(me)) send({ t: 'wager', amount: 0 });
          if (m.phase === 'finalThink' && m.final.entrants.includes(me) && !m.final.answered.includes(me)) send({ t: 'answer', choice: m.final.choices?.[0] || 'x', text: 'x' });
          if (m.ruling && m.ruling.voters.includes(me)) send({ t: 'rule', accept: true });
        }
      });
      sends[name]({ t: 'join', name });
    }
    for (let i = 0; i < 40 && tv.players.length < 2; i++) await sleep(50);
    assert.equal(tv.players.length, 2);
    sends.Ana({ t: 'start' });
    const t0 = Date.now();
    while (tv.phase !== 'podium' && Date.now() - t0 < 50000) {
      if (tv.phase === 'finalReveal' && !tv.ruling) tvSend({ t: 'next' });
      await sleep(30);
    }
    assert.equal(tv.phase, 'podium', `stuck in ${tv.phase}\n${log.slice(-2000)}`);
    for (const p of ['hostDraft', 'coldOpen', 'intro', 'categories', 'board', 'reading', 'buzzing', 'answering', 'reveal', 'finalWheel', 'finalThink', 'finalReveal']) assert.ok(phases.has(p), `never saw ${p}`);
    assert.ok(firstAnswerers.size > 0, 'no buzz-ins recorded');
    assert.deepEqual([...firstAnswerers], [states.Ana.you]);
    assert.equal(finalEntrants, 2, 'everyone plays Final Jeopardy, even at $0 or below');
    assert.ok(!/Error|at .*\.mjs/.test(log), log);
  } finally { srv.kill(); }
});
