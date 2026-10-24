const fs = require('fs');

async function test() {
  const url = "https://hosting-media.riverside.com/media/podcasts/25c060e8-e598-4782-920d-b96b89a1ffb0/episodes/259b2d69-8d5d-4a0e-bf23-3d1a09604af3/transcripts.txt";
  const text = await (await fetch(url)).text();
  console.log('Total characters:', text.length);
  
  const paragraphs = text.split(/\r?\n\r?\n+/).map(p => p.trim()).filter(Boolean);
  console.log('Total paragraphs:', paragraphs.length);
  
  const durationSec = 43 * 60 + 1; // 00:43:01 from feed
  const words = paragraphs.map(p => p.split(/\s+/).filter(Boolean).length);
  const totalWords = words.reduce((a, b) => a + b, 0);
  console.log('Total words:', totalWords);
  
  let currentWord = 0;
  const cues = [];
  for (let i = 0; i < paragraphs.length; i++) {
    const p = paragraphs[i];
    const pWords = words[i];
    const startSec = (currentWord / totalWords) * durationSec;
    currentWord += pWords;
    const endSec = (currentWord / totalWords) * durationSec;
    cues.push({
      startMs: Math.round(startSec * 1000),
      endMs: Math.round(endSec * 1000),
      text: p
    });
  }
  
  console.log('First 5 cues:');
  cues.slice(0, 5).forEach((c, idx) => {
    console.log(`[${formatTime(c.startMs)} -> ${formatTime(c.endMs)}] ${c.text.substring(0, 60)}...`);
  });
  
  console.log('Last 2 cues:');
  cues.slice(-2).forEach((c, idx) => {
    console.log(`[${formatTime(c.startMs)} -> ${formatTime(c.endMs)}] ${c.text.substring(0, 60)}...`);
  });
}

function formatTime(ms) {
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  const sec = s % 60;
  return `${m}:${sec.toString().padStart(2, '0')}`;
}

test();
