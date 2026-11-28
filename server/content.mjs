import fs from 'node:fs';
import path from 'node:path';

export const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const CONTENT = path.resolve(process.env.CONTENT_DIR || path.join(ROOT, 'content'));

function readDir(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => {
    try { return JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')); } catch (e) { console.error(`bad content ${f}: ${e.message}`); return null; }
  }).filter(Boolean);
}

export function loadContent() {
  const boards = readDir(path.join(CONTENT, 'boards'));
  const finals = readDir(path.join(CONTENT, 'finals')).flat();
  for (const b of boards) for (const c of b.categories) {
    c.boardId = b.id;
    c.key = `${b.id}/${c.id}`;
    for (const clue of c.clues) {
      clue.id = `${c.key}/${clue.value}`;
      if (clue.media?.file && !fs.existsSync(path.join(CONTENT, clue.media.file))) clue.media.missing = true;
    }
  }
  for (const f of finals) f.id = `final/${f.film}`;
  return { boards, finals, byId: new Map(boards.map((b) => [b.id, b])) };
}

export const MEDIA_DIR = path.join(CONTENT, 'media');
