// ElevenLabs host voice with an on-disk cache. Lines are generated the first time they are needed and reused forever.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const KEY = process.env.ELEVENLABS_API_KEY || '';
const MODEL = process.env.ELEVENLABS_MODEL || 'eleven_v3';
export const VOICES = {
  host: process.env.HOST_VOICE_ID || '',
  character: process.env.CHARACTER_VOICE_ID || 'dEc1rkm0ul0kDedEuETS',
};
const SETTINGS = {
  host: { stability: 0.0, similarity_boost: 0.8 },
  character: { stability: 0.0, similarity_boost: 0.75 },
};

export class Voice {
  constructor(dataDir) {
    this.dir = path.join(dataDir, 'voice');
    fs.mkdirSync(this.dir, { recursive: true });
    this.inflight = new Map();
    this.queue = [];
    this.active = 0;
    this.disabled = !KEY || !VOICES.host;
    this.failures = 0;
  }

  key(text, who = 'host') {
    return crypto.createHash('sha1').update(`${VOICES[who]}|${MODEL}|${text}`).digest('hex').slice(0, 20);
  }

  file(id) { return path.join(this.dir, `${id}.mp3`); }

  // Returns a URL if the clip is ready (generating it if needed), or null to fall back to TTS on the TV.
  async get(text, who = 'host') {
    if (!text) return null;
    const id = this.key(text, who);
    if (fs.existsSync(this.file(id))) return `/voice/${id}.mp3`;
    if (this.disabled) return null;
    if (!this.inflight.has(id)) this.inflight.set(id, this.#schedule(() => this.#generate(id, text, who)).finally(() => this.inflight.delete(id)));
    return this.inflight.get(id);
  }

  cached(text, who = 'host') {
    if (!text) return null;
    const id = this.key(text, who);
    return fs.existsSync(this.file(id)) ? `/voice/${id}.mp3` : null;
  }

  prefetch(lines) { for (const [text, who] of lines) this.get(text, who).catch(() => {}); }

  #schedule(fn) {
    return new Promise((resolve, reject) => {
      this.queue.push({ fn, resolve, reject });
      this.#pump();
    });
  }

  #pump() {
    while (this.active < 3 && this.queue.length) {
      const { fn, resolve, reject } = this.queue.shift();
      this.active++;
      fn().then(resolve, reject).finally(() => { this.active--; this.#pump(); });
    }
  }

  async #generate(id, text, who) {
    const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${VOICES[who]}?output_format=mp3_44100_96`, {
      method: 'POST',
      headers: { 'xi-api-key': KEY, 'content-type': 'application/json', accept: 'audio/mpeg' },
      body: JSON.stringify({ text, model_id: MODEL, voice_settings: SETTINGS[who] }),
      signal: AbortSignal.timeout(20000),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      console.error(`tts ${res.status}: ${body.slice(0, 200)}`);
      if (res.status === 401 || /quota|limit/i.test(body)) this.disabled = true;
      return null;
    }
    const buf = Buffer.from(await res.arrayBuffer());
    const tmp = `${this.file(id)}.tmp`;
    fs.writeFileSync(tmp, buf);
    fs.renameSync(tmp, this.file(id));
    return `/voice/${id}.mp3`;
  }
}
