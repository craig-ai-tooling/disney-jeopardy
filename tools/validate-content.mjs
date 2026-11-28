#!/usr/bin/env node
// Validates content/boards/*.json and content/finals/*.json. Usage: node tools/validate-content.mjs [files...]
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const VALUES = [200, 400, 600, 800, 1000];
const KINDS = ['film', 'theme', 'media', 'parks', 'fan'];
const FORMATS = ['text', 'picture', 'zoom', 'video', 'palette', 'quote', 'emoji'];
const errors = [];
const err = (f, m) => errors.push(`${f}: ${m}`);
const kebab = (s) => typeof s === 'string' && /^[a-z0-9]+(-[a-z0-9]+)*$/.test(s);
const str = (s, max) => typeof s === 'string' && s.trim().length > 0 && (!max || s.length <= max);

function checkAnswerBlock(f, where, c) {
  if (!str(c.clue, 260)) err(f, `${where}: clue missing or > 260 chars`);
  if (!str(c.answer, 80)) err(f, `${where}: answer missing`);
  if (!Array.isArray(c.accept) || !c.accept.length || c.accept.some((a) => !str(a) || a !== a.toLowerCase()))
    err(f, `${where}: accept must be non-empty lowercase strings`);
  if (!Array.isArray(c.choices) || c.choices.length !== 4) err(f, `${where}: choices must have 4 entries`);
  else {
    if (c.choices[0] !== c.answer) err(f, `${where}: choices[0] must equal answer`);
    if (new Set(c.choices.map((x) => String(x).toLowerCase())).size !== 4) err(f, `${where}: choices not unique`);
  }
  if (!str(c.reveal, 300)) err(f, `${where}: reveal missing or > 300 chars`);
}

const seenBoards = new Set();
function checkBoard(f, b) {
  if (!kebab(b.id)) err(f, 'id must be kebab-case');
  if (path.basename(f, '.json') !== b.id) err(f, 'file name must equal id');
  if (seenBoards.has(b.id)) err(f, 'duplicate board id');
  seenBoards.add(b.id);
  for (const k of ['title', 'theme', 'intro']) if (!str(b[k])) err(f, `${k} missing`);
  if (!Array.isArray(b.tags) || !b.tags.length) err(f, 'tags missing');
  const checkArt = (w, art, want) => {
    if (art === undefined) return;
    if (!str(art.prompt)) err(f, `${w}: art.prompt missing`);
    if (art.file !== want) err(f, `${w}: art.file should be ${want}`);
  };
  checkArt('board', b.art, `media/${b.id}.jpg`);
  if (!Array.isArray(b.categories) || b.categories.length !== 5) return err(f, 'need exactly 5 categories');
  let dd = 0;
  const catIds = new Set();
  for (const cat of b.categories) {
    const w = `category ${cat.id}`;
    if (!kebab(cat.id) || catIds.has(cat.id)) err(f, `${w}: bad or duplicate id`);
    catIds.add(cat.id);
    if (!str(cat.title, 40)) err(f, `${w}: title missing or > 40 chars`);
    if (!KINDS.includes(cat.kind)) err(f, `${w}: kind must be one of ${KINDS}`);
    if (!FORMATS.includes(cat.format)) err(f, `${w}: format must be one of ${FORMATS}`);
    if (!str(cat.intro)) err(f, `${w}: intro missing`);
    checkArt(w, cat.art, `media/${b.id}--${cat.id}.jpg`);
    if (!Array.isArray(cat.clues) || cat.clues.length !== 5) { err(f, `${w}: need 5 clues`); continue; }
    cat.clues.forEach((c, i) => {
      const cw = `${w} ${c.value}`;
      if (c.value !== VALUES[i]) err(f, `${cw}: values must be ${VALUES}`);
      checkAnswerBlock(f, cw, c);
      if (c.dailyDouble) { dd++; if (c.value < 600) err(f, `${cw}: daily double must be 600+`); }
      if (c.media) {
        const ext = c.media.type === 'video' ? 'mp4' : 'jpg';
        if (!['image', 'video'].includes(c.media.type)) err(f, `${cw}: media.type must be image|video`);
        if (!str(c.media.prompt)) err(f, `${cw}: media.prompt missing`);
        const want = `media/${b.id}--${cat.id}-${c.value}.${ext}`;
        if (c.media.file !== want) err(f, `${cw}: media.file should be ${want}`);
      }
      if (['picture', 'zoom', 'video'].includes(cat.format) && !c.media) err(f, `${cw}: ${cat.format} needs media`);
      if (cat.format === 'palette' && !(Array.isArray(c.palette) && c.palette.length >= 3 && c.palette.every((h) => /^#[0-9a-fA-F]{6}$/.test(h))))
        err(f, `${cw}: palette needs 3-6 #rrggbb colours`);
      if (cat.format === 'quote' && !str(c.quote, 140)) err(f, `${cw}: quote missing`);
    });
  }
  if (dd !== 1) err(f, `board must have exactly 1 dailyDouble (has ${dd})`);
}

const seenFilms = new Set();
function checkFinals(f, arr) {
  if (!Array.isArray(arr)) return err(f, 'finals file must be an array');
  arr.forEach((x, i) => {
    const w = `final ${i} (${x.film})`;
    if (!str(x.film) || !str(x.category)) err(f, `${w}: film/category missing`);
    if (seenFilms.has(x.film)) err(f, `${w}: duplicate film`);
    seenFilms.add(x.film);
    checkAnswerBlock(f, w, x);
  });
}

const dir = (d) => (fs.existsSync(d) ? fs.readdirSync(d).filter((n) => n.endsWith('.json')).map((n) => path.join(d, n)) : []);
const files = process.argv.slice(2).length ? process.argv.slice(2).map((p) => path.resolve(p))
  : [...dir(path.join(root, 'content/boards')), ...dir(path.join(root, 'content/finals'))];
let boards = 0, clues = 0, finals = 0;
for (const f of files) {
  let data;
  try { data = JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) { err(f, `invalid JSON: ${e.message}`); continue; }
  if (f.includes(`${path.sep}finals${path.sep}`)) { checkFinals(f, data); finals += data.length || 0; }
  else { checkBoard(f, data); boards++; clues += (data.categories || []).reduce((n, c) => n + (c.clues?.length || 0), 0); }
}
if (errors.length) { console.error(errors.join('\n')); console.error(`\n${errors.length} error(s)`); process.exit(1); }
console.log(`ok: ${boards} boards, ${clues} clues, ${finals} finals`);
