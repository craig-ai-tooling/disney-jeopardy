// Fuzzy answer judging for typed and spoken answers.
const STOP = new Set(['the', 'a', 'an', 'of', 'and', 'to', 'in', 'is', 'it', 'what', 'who', 'are', 'was', 'were', 'mr', 'mrs', 'ms', 'dr', 'st', 'saint']);
const NUM = { zero: '0', one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9', ten: '10', eleven: '11', twelve: '12', hundred: '100', thousand: '1000' };

export function normalize(s) {
  return String(s ?? '')
    .toLowerCase()
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/&/g, ' and ')
    .replace(/[''`]/g, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/^(what|who|where|when) (is|are|was|were) /, '')
    .split(' ')
    .map((w) => NUM[w] ?? w)
    .filter((w) => w && !STOP.has(w))
    .join(' ');
}

export function levenshtein(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++)
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}

// Rough phonetic key so "Moana"/"Mowana", "Gaston"/"Gastone" collapse together.
export function phonetic(s) {
  return s.split(' ').map((w) => w
    .replace(/ph/g, 'f').replace(/ck/g, 'k').replace(/c([eiy])/g, 's$1').replace(/c/g, 'k').replace(/q/g, 'k')
    .replace(/x/g, 'ks').replace(/z/g, 's').replace(/wh/g, 'w').replace(/kn/g, 'n').replace(/gh/g, 'g')
    .replace(/(.)\1+/g, '$1').replace(/(?!^)[aeiouyhw]/g, '')).join(' ');
}

function similarity(a, b) {
  if (!a || !b) return 0;
  if (a === b) return 1;
  const lev = 1 - levenshtein(a, b) / Math.max(a.length, b.length);
  const pa = phonetic(a), pb = phonetic(b);
  const phon = pa && pa === pb ? 0.92 : 1 - levenshtein(pa, pb) / Math.max(pa.length, pb.length, 1) - 0.1;
  const ta = new Set(a.split(' ')), tb = new Set(b.split(' '));
  const inter = [...ta].filter((t) => tb.has(t)).length;
  // All the key words of the target are present (e.g. "beast" for "the beast", "simba" in "its simba").
  const contain = inter === tb.size ? 0.9 + 0.05 * (inter / ta.size) : inter / Math.max(ta.size, tb.size) * 0.8;
  return Math.max(lev, phon, contain);
}

// verdict: correct | close | wrong. targets: canonical answer + accept list. wrongs: the distractor choices.
export function judge(input, { answer, accept = [], choices = [] }) {
  const n = normalize(input);
  if (!n) return { verdict: 'wrong', score: 0, heard: input };
  const targets = [answer, ...accept].map(normalize).filter(Boolean);
  const wrongs = choices.filter((c) => c !== answer).map(normalize).filter(Boolean);
  const best = Math.max(...targets.map((t) => similarity(n, t)));
  const bestWrong = wrongs.length ? Math.max(...wrongs.map((t) => similarity(n, t))) : 0;
  if (bestWrong >= 0.95 && bestWrong > best) return { verdict: 'wrong', score: best, heard: input };
  if (best >= 0.86) return { verdict: 'correct', score: best, heard: input };
  if (best >= 0.6 && best > bestWrong) return { verdict: 'close', score: best, heard: input };
  return { verdict: 'wrong', score: best, heard: input };
}
