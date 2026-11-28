// Speech-to-text for spoken answers via an OpenAI-compatible Whisper server (speaches / faster-whisper).
const URL_ = process.env.WHISPER_URL || '';
const MODEL = process.env.WHISPER_MODEL || 'Systran/faster-whisper-small.en';

export const sttEnabled = () => Boolean(URL_);

export async function transcribe(audio, mime, hints = []) {
  if (!URL_) throw new Error('stt disabled');
  const form = new FormData();
  const ext = mime.includes('mp4') ? 'mp4' : mime.includes('ogg') ? 'ogg' : mime.includes('wav') ? 'wav' : 'webm';
  form.append('file', new Blob([audio], { type: mime }), `answer.${ext}`);
  form.append('model', MODEL);
  form.append('language', 'en');
  form.append('response_format', 'json');
  form.append('temperature', '0');
  // Biasing prompt: the vocabulary this clue's answer is likely drawn from.
  if (hints.length) form.append('prompt', `Disney trivia answer. Possible words: ${hints.join(', ')}.`);
  const t0 = Date.now();
  const res = await fetch(`${URL_.replace(/\/$/, '')}/v1/audio/transcriptions`, { method: 'POST', body: form, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error(`whisper ${res.status}`);
  const { text } = await res.json();
  return { text: String(text || '').trim(), ms: Date.now() - t0 };
}
