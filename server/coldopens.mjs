// Ridiculous cold opens played before each episode. {host} = host name, {p1}/{p2} = random player names, {theme} = board theme.
export const COLD_OPENS = [
  "[excited] Hello, hello, HELLO! It's me, {host}! [laughs] You may remember me from... [pause] absolutely nothing! I was cut from fourteen movies! [excited] But TONIGHT, I am the STAR!",
  "[clears throat] Before we begin, the producers have asked me to remind everyone that I am NOT legally allowed to call myself a Disney character. [whispers] I'm more of a... Disney situation. [excited] LET'S PLAY!",
  "[excited] Welcome to Disney Jeopardy! Tonight's show is brought to you by... [pause] nobody. We have no sponsors. [sighs] I paid for the lights myself. [laughs] Let's GO!",
  "[dramatic] Once upon a time... a brave young host auditioned for a feature film. [pause] They said he was 'too much.' [excited] Well, TOO BAD, because he's HERE NOW!",
  "[excited] {p1}! I've heard SO much about you! [pause] [whispers] Mostly from {p2}. [mischievously] And it was... not great. [laughs] Anyway! Welcome to Disney Jeopardy!",
  "[excited] Look at this crowd! Look at these BEAUTIFUL faces! [pause] [squints] Except {p1}. {p1}, did you just wake up? [laughs] I'm kidding, I'm kidding. Mostly.",
  "[whispers] Okay, okay, are we live? Are we live? [excited] We're LIVE! [gasps] Oh no, I didn't finish my snack. [chewing] Welcome... to Disney... Jeopardy!",
  "[excited] Ladies and gentlemen, boys and girls, and whoever {p1} is supposed to be... welcome to the most magical game show on Earth! [laughs] Legally, the second most magical. Our lawyers are watching.",
  "[sighs] My therapist told me I need to stop hosting game shows in people's living rooms. [pause] [excited] But here we are! Disney Jeopardy, BABY!",
  "[dramatic] They told me I'd never work in this town again. [pause] [excited] And they were RIGHT! So now I work in YOUR town! Welcome to Disney Jeopardy!",
  "[excited] Hi everyone, I'm {host}, and I want to start tonight by saying I have personally memorized every Disney fact ever. [pause] [whispers] That's a lie. The answers are on cards. [laughs] LET'S PLAY!",
  "[excited] Tonight's theme is {theme}! [pause] I didn't pick it. I wanted 'Hosts Who Deserved A Movie.' [sighs] The writers said no. [laughs] Let's play!",
  "[excited] Fun fact: I was originally designed to be a sidekick. [pause] A sidekick... to a lamp. [sighs] The LAMP got the lead. [excited] But tonight, I shine brighter than any lamp! Welcome to Disney Jeopardy!",
  "[mischievously] {p1}, {p2}, I see you both over there. I want a clean game. No biting. No crying. [pause] [whispers] {p2}, I'm mostly talking to you. [excited] Let's PLAY!",
  "[excited] Welcome back to Disney Jeopardy! [pause] Wait. Is this the first episode? [gasps] Then WELCOME, for the FIRST time! [laughs] I've been practicing that line in a mirror for three years.",
  "[whispers] Psst. Before the producers get here... [pause] If you lose tonight, it's not your fault. [mischievously] It's {p1}'s fault. [excited] Okay, they're back! WELCOME TO DISNEY JEOPARDY!",
  "[excited] Please silence your phones! [pause] No wait, DON'T silence your phones, you need them to play! [laughs] Ignore everything I just said. Welcome to Disney Jeopardy!",
  "[dramatic] Somewhere, in a vault deep below the Burbank studios, is a sketch of me with a big red X on it. [pause] [excited] Who's laughing NOW, Burbank?! Welcome to Disney Jeopardy!",
  "[excited] I have been waiting ALL WEEK for this! I got a haircut! I ironed my bow tie! [pause] [whispers] I ironed it while I was wearing it. [sighs] It was a mistake. [excited] LET'S PLAY!",
  "[excited] Tonight we have SPECTACULAR prizes! [pause] [whispers] There are no prizes. [excited] But you'll get BRAGGING RIGHTS, which, in this family, are worth MORE THAN GOLD!",
  "[laughs] Oh, you're all still here? I figured after my last show you'd move. [excited] I'm kidding, I love you! Welcome to Disney Jeopardy!",
  "[excited] Quick warm-up! Everybody say 'Disney!' [pause] [sighs] {p1} didn't say it. I saw that. [laughs] We'll work on it. Welcome to Disney Jeopardy!",
  "[dramatic] In a world... where one host... was rejected from every single movie... [pause] [excited] He found his true calling... ASKING YOU QUESTIONS! Disney Jeopardy starts NOW!",
  "[excited] I've been told I have the energy of a raccoon who found a full churro cart. [pause] [laughs] And THAT, my friends, is a compliment! Welcome to Disney Jeopardy!",
  "[whispers] Fun backstage gossip: the Daily Double is hiding somewhere on this board. [mischievously] I know where it is. [pause] I'm not telling. [excited] Okay, maybe I'll tell {p1}. NO I WON'T! Let's play!",
];

// A bargain-bin variety-show curtain-up the host attempts all by himself (original spoof, not a real song).
export const VARIETY_OPEN = "[excited] Ladies and gentlemen... it's the {host} Variety Hour! [sings] Ba-da-da-da, da-da-da-DAAA! [pause] [whispers] Okay, curtain up... curtain UP! [sighs] We can't afford a curtain. [sings] It's time to light the board up, it's time to buzz real fast, it's time to answer questions that you never knew you'd ask! [excited] And our special celebrity guest tonight is... [pause] also me! [laughs] Ba-da-BUM! WELCOME TO DISNEY JEOPARDY!";

export function coldOpen({ host, players, theme }) {
  const names = players.length ? [...players].sort(() => Math.random() - 0.5) : ['you'];
  const pool = COLD_OPENS.filter((t) => players.length >= 2 || !/\{p2\}/.test(t)).filter((t) => players.length || !/\{p1\}/.test(t));
  const t = Math.random() < 0.15 ? VARIETY_OPEN : pool[Math.floor(Math.random() * pool.length)];
  return t.replaceAll('{host}', host).replaceAll('{p1}', names[0]).replaceAll('{p2}', names[1] || names[0]).replaceAll('{theme}', theme || 'Disney');
}
