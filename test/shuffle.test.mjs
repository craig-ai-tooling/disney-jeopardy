import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Rooms } from '../server/game.mjs';

const board = (id, difficulty) => ({ id, title: id, theme: id, difficulty, categories: [{ id: 'c', title: 'c', clues: [200, 400, 600, 800, 1000].map((value) => ({ id: `${id}/c/${value}`, value })) }] });

function room(boards) {
  const tvs = {};
  const store = { tv: (id) => (tvs[id] ??= { seen: {}, boards: {}, finals: {}, games: 0 }), markBoard: (id, b) => { store.tv(id).boards[b] = Date.now(); } };
  const content = { boards, finals: [], byId: new Map(boards.map((b) => [b.id, b])) };
  const rooms = new Rooms({ content, store, voice: { prefetch() {} }, media: { url: (f) => f || null } });
  return { r: rooms.forTv('tv-1'), store };
}

test('shuffle with a difficulty stays within that level and avoids repeats', () => {
  const boards = [board('e1', 'easy'), board('e2', 'easy'), board('e3', 'easy'), board('h1', 'hard'), board('h2', 'hard'), board('s1', 'superfan')];
  const { r, store } = room(boards);
  const seen = new Set();
  for (let i = 0; i < 3; i++) {
    r.onTv({ t: 'shuffle', difficulty: 'easy' });
    assert.equal(r.board.difficulty, 'easy');
    seen.add(r.board.id);
    store.markBoard('tv-1', r.board.id);
  }
  assert.equal(seen.size, 3, 'played boards are not repeated until the level is exhausted');
  for (let i = 0; i < 10; i++) { r.onTv({ t: 'shuffle', difficulty: 'hard' }); assert.equal(r.board.difficulty, 'hard'); }
  r.onTv({ t: 'shuffle', difficulty: 'superfan' });
  r.onTv({ t: 'shuffle', difficulty: 'superfan' });
  assert.equal(r.board.id, 's1', 'a single-board level keeps that board');
  r.onTv({ t: 'shuffle', difficulty: 'bogus' });
  assert.ok(boards.includes(r.board));
  const list = r.boardList();
  assert.deepEqual(list.map((b) => b.difficulty), boards.map((b) => b.difficulty));
  assert.equal(r.view('tv').board.difficulty, r.board.difficulty);
});
