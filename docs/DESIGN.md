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

## Content: themed boards (content/boards/<board-id>.json)
Craig: "Notice the theme of the entirety of the boards and then the categories. That's going to be really important, so it's not just random scoring."
Every board is an EPISODE with one theme (e.g. Thanksgiving, Black Friday, Halloween); all 5 categories tie to that theme, with the show's mix:
1 film-ish category, 1-2 themed trivia categories, 1 media/gimmick category, 1 Parks category, 1 "fan" wildcard. Values 200 ("cupcake", easy for kids) .. 1000 (true Disney nerd).
Replayability: ~40 boards (~1000 clues) + ~64 Final films. Server tracks seen clues per TV and can remix unseen categories by tag.
Real Inside the Magic transcripts are in docs/research/*.txt (style + example clue reference; do not copy whole boards verbatim).

{
  "id": "thanksgiving-feast",            // kebab-case, globally unique, = file name
  "title": "Thanksgiving Feast",          // episode title shown on TV
  "theme": "Thanksgiving",                // short theme label
  "tags": ["holiday","food"],            // used for remix
  "intro": "Host opening line for the episode (1-3 sentences, playful, TTS friendly).",
  "art": {"prompt": "16:9 title-card scene evoking the theme, ORIGINAL art, no text/logos/characters", "file": "media/<board-id>.jpg"},
  "categories": [ <category> x5 ]         // exactly one clue on the whole board has dailyDouble:true (value 600-1000)
}

<category> = {
  "id": "pass-the-gravy",                 // kebab-case, unique within the board (global id = board/category)
  "title": "Pass the Gravy", "kind": "film|theme|media|parks|fan",
  "intro": "Host line introducing the category (1-2 sentences, playful).",
  "art": {"prompt": "16:9 category card art, ORIGINAL, no text/logos/characters", "file": "media/<board-id>--<category-id>.jpg"},
  "format": "text|picture|zoom|video|palette|quote|emoji",
  "clues": [ {
    "value": 200,
    "clue": "Clue shown on screen and read aloud (<= 200 chars, TTS friendly)",
    "answer": "Canonical short answer",
    "accept": ["lowercase alternates for typed answers"],
    "choices": ["correct answer (== answer)", "3 plausible wrong answers of the same type"],
    "reveal": "Host line after reveal: 'The correct answer is X.' + short fun fact / joke (<= 220 chars)",
    "media": {"type":"image|video", "prompt":"prompt for ORIGINAL AI artwork (no logos/real characters/text)", "file":"media/<board-id>--<category-id>-<value>.jpg|.mp4"},  // optional
    "palette": ["#hex", ...],             // format=palette only: 4-6 outfit colours
    "quote": "short iconic line (<=15 words) read by a voice actor",  // format=quote only
    "dailyDouble": false
  } x5 values 200,400,600,800,1000 ]
}

## Finals (content/finals/<part>.json): array of {"film","category","clue","answer","accept","choices","reveal"} - one per wheel film.
