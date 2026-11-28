// The cast of rejected-character hosts. Voice IDs can be overridden with HOST_VOICES='{"chet":"..."}'.
const override = (() => { try { return JSON.parse(process.env.HOST_VOICES || '{}'); } catch { return {}; } })();
export const HOSTS = [
  { id: 'chet', name: 'Chet Churro', img: '/theme/hosts/chet-churro.webp', voice: 'cZzlm7itFKvYYE30efU6', pitch: "[purring] Mis amigos... choose Chet. [chuckles] I am crispy, I am sweet, and I am... irresistible." },
  { id: 'rascal', name: 'Rascal Razzmatazz', img: '/theme/hosts/rascal-razzmatazz.webp', voice: 'jttCMsMPZcC7qZdX4NGN', pitch: "[excited] Pick me, pick me! I found this tux in a dumpster and I have NEVER looked better!" },
  { id: 'prince', name: 'Prince Charmless', img: '/theme/hosts/prince-charmless.webp', voice: null, pitch: "[clears throat] Vote for royalty! [croaks] Pardon me. Vote for... mostly royalty." },
  { id: 'chips', name: 'Sir Chipsworth', img: '/theme/hosts/sir-chipsworth.webp', voice: null, pitch: "[clears throat] I promise a tidy, civilized game. [gasps] Is that a crumb? Never mind. Choose me." },
  { id: 'gourd', name: 'Gourdon', img: '/theme/hosts/gourdon.webp', voice: null, pitch: "[laughs] Howdy! Pick ol' Gourdon! [panicking] Quick, before midnight! [laughs] Just kiddin'!" },
  { id: 'moppy', name: 'Moppy Von Mopsalot', img: '/theme/hosts/moppy-von-mopsalot.webp', voice: null, pitch: "[dramatic] Darlings! Give me the spotlight! [sobs] I have waited my whole life... next to a bucket." },
].map((h) => ({ ...h, voice: override[h.id] || h.voice }));
export const hostById = (id) => HOSTS.find((h) => h.id === id) || HOSTS[0];
// Hosts without a saved voice borrow the headliner's so narration never goes silent.
export const voiceFor = (h) => h.voice || HOSTS[0].voice;
