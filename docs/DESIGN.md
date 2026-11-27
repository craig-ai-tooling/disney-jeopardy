# Disney Jeopardy — design (working notes)

Inspired by Inside the Magic "Disney Jeopardy" (host Brian Perry). Format observed from transcripts:
- 5 categories x 5 rows: 200 ("cupcake round", easiest), 400, 600, 800, 1000 ("the dreaded 1000 round"). 25 clues + Final = "26 clue game".
- Played row by row (all 200s, then all 400s...) on the show; we support free pick + "row order" option.
- Category mix per episode: 1 film category (e.g. Aladdin, Brave, Sword in the Stone — chosen by last week's Final wheel),
  1 themed category (On the Farm, Firearms, Broadcast journalism, Thanksgiving options, Saying thanks),
  1 media/gimmick category (Hear the Character [audio clip], Zoomed-in Posters, Color Palettes, Song Combos,
  Scores in Reverse, Mickey Through the Decades [video], Morphed Faces, Guess the Dress, Guess the Logo, Alphabetize This, Rhyme Time),
  1 Parks category (Places to Cool Off [photo], Something's Off [photo], Ride Tunes, Roller Coaster Features, Black Friday shopping, 1975 parks),
  1 "Club DJ" fan-picked category.
- One Daily Double (wager up to max(score, 1000)).
- Final Jeopardy: a WHEEL of films is spun; landed film = Final category; everyone wagers; ~30s think music; answer revealed with commentary.
- Host style: playful, puns, commentary after each answer ("The correct answer is ...", fun fact, light teasing).

## Architecture
- server/ Node 20 + ws, single process, in-memory rooms. Serves /tv (TV board), / (phone join), /api, /media, /apk.
- TV: Android TV APK = fullscreen WebView of https://jeopardy.craigcloud.io/tv (autoplay allowed, D-pad -> keys).
- Phones: scan QR -> /join/ROOM -> name+avatar -> buzzer. Answer modes: multiple choice (default), typed (fuzzy match), voice.
- Buzzer fairness: buzzers open after host finishes reading; client measures reaction time from receiving "open";
  server collects buzzes for 250ms after first, lowest reaction wins. Early buzz => 0.5s lockout.
- Content: content/categories/*.json, content/games.json (curated boards), content/finals.json (wheel films).
- Media: Higgsfield images/videos (original AI art, no copyrighted stills), ElevenLabs host VO (pre-generated per clue),
  palettes rendered in CSS, zoom category = CSS zoom-in on image, reveal on answer.
- Deploy: ghcr.io/craig-ai-tooling/disney-jeopardy via CI on runs-on: smc; smc-platform apps/disney-jeopardy (like apps/game-night,
  stateless, no PVC); cloudflared ingress.json rule jeopardy.craigcloud.io; DNS via apply-ingress.sh / Cloudflare MCP.

## Category JSON schema (content/categories/<id>.json)
{
  "id": "aladdin", "title": "Aladdin", "kind": "film|theme|media|parks|fan",
  "intro": "Host line introducing the category (1-2 sentences, playful).",
  "format": "text|picture|video|audio|palette|zoom|quote",
  "clues": [ {
     "value": 200,
     "clue": "Clue text shown on screen and read aloud (Jeopardy style statement, <= 200 chars)",
     "answer": "Canonical answer (short)",
     "accept": ["lowercase alternates accepted for typed answers"],
     "choices": ["correct answer", "3 plausible wrong answers"],   // 4 total, first is correct (shuffled at runtime)
     "reveal": "Host line after reveal: 'The correct answer is X.' + short fun fact / joke (<= 220 chars)",
     "media": {"type":"image|video|audio", "prompt":"Higgsfield prompt for ORIGINAL artwork (no logos, no real characters)", "file":"media/<id>-<value>.jpg"},
     "palette": ["#hex", ...],     // format=palette only: 4-6 colors of a character's outfit
     "quote": "line read by voice actor", // format=quote only
     "dailyDouble": false
  } x5 ]
}
