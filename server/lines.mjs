// Fixed host lines (pre-generated once, then cached). Keep them short and boisterous.
export const LINES = {
  welcome: [
    "[excited] Welcome, welcome, WELCOME to Disney Jeopardy! [laughs] Grab your phones, scan that code... and let's get MAGICAL!",
  ],
  dailyDouble: [
    "[gasps] Ding ding ding! It's a DAILY DOUBLE! [mischievously] Now... how much of your hard-earned treasure are you willing to risk?",
    "Whoa whoa whoa! You found the Daily Double! Time to make a wager, my friend!",
    "[excited] Stop the presses! It's a Daily Double! [laughs] Bibbidi-bobbidi-BET!",
  ],
  timeUp: [
    "[sighs] Time's up! Not a single buzz? I'm shocked. [dramatic] SHOCKED, I tell you.",
    "[whispers] Crickets... [chuckles] Even Jiminy is disappointed.",
    "Nobody? Not one brave soul? Alright, alright, let's see it.",
  ],
  allWrong: [
    "[laughs] Oof! Everybody struck out on that one.",
    "Yikes! That one got all of you. Let's take a look.",
  ],
  finalIntro: [
    "[excited] It's time for... FINAL JEOPARDY! [whispers] Everybody look at the wheel... [shouts] because we are about to SPIN for our final category!",
  ],
  finalWager: [
    "There's our final category! Lock in your wagers on your phones. Bet big or go home!",
  ],
  finalThink: [
    "[whispers] Thirty seconds on the clock... think hard... and good luck.",
  ],
  finalReveal: [
    "Pencils down! Let's see how everybody did, starting from the bottom of the leaderboard.",
  ],
  podium: [
    "[excited] And THAT'S the game! Give it up for our Disney Jeopardy champion! [laughs] See ya real soon!",
  ],
  boardDone: [
    "That clears the board! Let's see where everybody stands.",
  ],
};

export const pick = (k) => LINES[k][Math.floor(Math.random() * LINES[k].length)];
export const allLines = () => Object.values(LINES).flat();

// Strip ElevenLabs v3 audio tags like [laughs] for on-screen text and fallback TTS.
export const plain = (t) => String(t ?? '').replace(/\[[^\]]{1,40}\]\s*/g, '').replace(/\s{2,}/g, ' ').trim();
