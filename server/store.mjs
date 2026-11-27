// Per-TV play history (which clues/finals were already used) so games don't repeat.
import fs from 'node:fs';
import path from 'node:path';

export class Store {
  constructor(dataDir) {
    this.path = path.join(dataDir, 'history.json');
    try { this.data = JSON.parse(fs.readFileSync(this.path, 'utf8')); } catch { this.data = { tvs: {} }; }
    this.timer = null;
  }
  tv(id) {
    const key = String(id || 'anon').slice(0, 64);
    return (this.data.tvs[key] ??= { seen: {}, boards: {}, finals: {}, games: 0 });
  }
  markClue(tvId, clueId) { this.tv(tvId).seen[clueId] = Date.now(); this.save(); }
  markFinal(tvId, film) { this.tv(tvId).finals[film] = Date.now(); this.save(); }
  markBoard(tvId, boardId) { const t = this.tv(tvId); t.boards[boardId] = Date.now(); t.games++; this.save(); }
  reset(tvId) { delete this.data.tvs[String(tvId)]; this.save(); }
  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      try {
        fs.writeFileSync(`${this.path}.tmp`, JSON.stringify(this.data));
        fs.renameSync(`${this.path}.tmp`, this.path);
      } catch (e) { console.error(`history save: ${e.message}`); }
    }, 500);
  }
}
