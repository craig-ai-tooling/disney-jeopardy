// Fixed host lines (pre-generated once, then cached). Keep them short and boisterous.
export const LINES = {
  welcome: [
    "Welcome, welcome, WELCOME to Disney Jeopardy! Grab your phones, scan that code, and let's get magical!",
  ],
  dailyDouble: [
    "Ding ding ding! It's a DAILY DOUBLE! How much of your hard-earned treasure are you willing to risk?",
    "Whoa whoa whoa! You found the Daily Double! Time to make a wager, my friend!",
    "Stop the presses! It's a Daily Double! Bibbidi-bobbidi-BET!",
  ],
  timeUp: [
    "Time's up! Not a single buzz? I'm shocked. SHOCKED, I tell you.",
    "Crickets! Even Jiminy is disappointed.",
    "Nobody? Not one brave soul? Alright, alright, let's see it.",
  ],
  allWrong: [
    "Oof! Everybody struck out on that one.",
    "Yikes! That one got all of you. Let's take a look.",
  ],
  finalIntro: [
    "It's time for FINAL JEOPARDY! Everybody look at the wheel, because we are about to spin for our final category!",
  ],
  finalWager: [
    "There's our final category! Lock in your wagers on your phones. Bet big or go home!",
  ],
  finalThink: [
    "Thirty seconds on the clock. Think hard, and good luck!",
  ],
  finalReveal: [
    "Pencils down! Let's see how everybody did, starting from the bottom of the leaderboard.",
  ],
  podium: [
    "And that's the game! Give it up for our Disney Jeopardy champion! See ya real soon!",
  ],
  boardDone: [
    "That clears the board! Let's see where everybody stands.",
  ],
};

export const pick = (k) => LINES[k][Math.floor(Math.random() * LINES[k].length)];
export const allLines = () => Object.values(LINES).flat();
